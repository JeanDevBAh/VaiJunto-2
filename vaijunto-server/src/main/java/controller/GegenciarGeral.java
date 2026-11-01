package controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import api.ClienteConsorcio;
import api.TrechoRef;
import config.Config;
import model.Carona;
import model.Cidades;
import model.Grafo;
import model.Itinerario;
import model.Reserva;
import model.TipoUser;
import model.Trecho;
import model.Usuario;
import wal.Evento;
import wal.Wal;
/**
 * Fachada central dos serviços de negócio do servidor.
 *
 * Reúne e coordena UserService, CaronaService e ReservaService, oferecendo
 * uma interface única para o ClientHandler.
 *
 * Também realiza a validação inicial das sessões antes de permitir operações
 * protegidas, como oferecer caronas, cancelar caronas ou criar reservas.
 *
 * Essa classe reduz o acoplamento entre a camada de comunicação e os serviços
 * específicos da aplicação.
 */
public class GegenciarGeral {
    private final CaronaService caronaService;
    private final ReservaService reservaService;
    private final UserService userService;
    private final Grafo grafo;
    private final String empresaId;
    private final Wal wal;
    private final Map<String, String> peers;
    private final ClienteConsorcio clienteConsorcio;
    private final Map<String, ReservaDistribuidaPendente> reservasDistribuidasPendentes = new ConcurrentHashMap<>();

    /** Sem log: tudo em memória (usado pelos testes de concorrência). */
    public GegenciarGeral(){
        this((Wal) null, "LOCAL", Collections.emptyMap());
    }

    /** Usado pelo servidor: abre o log no caminho informado e usa a identidade da Config. */
    public GegenciarGeral(String caminhoWal, Config config) {
        this(new Wal(caminhoWal), config.empresaId, config.peers);
    }

    /**
     * Com log. Primeiro REPRODUZ todos os eventos já gravados (sem regravá-los)
     * e só depois o servidor fica pronto para novas operações.
     */
    public GegenciarGeral(Wal wal, String empresaId){
        this(wal, empresaId, Collections.emptyMap());
    }

    public GegenciarGeral(Wal wal, String empresaId, Map<String, String> peers) {
        this.grafo = new Grafo();
        this.empresaId = empresaId;
        this.wal = wal;
        this.userService = new UserService(wal);
        this.caronaService = new CaronaService(this.grafo, wal, empresaId);
        this.reservaService = new ReservaService(this.grafo, wal);
        this.peers = peers == null ? Collections.emptyMap() : new HashMap<>(peers);
        this.clienteConsorcio = new ClienteConsorcio();

        if (wal != null) {
            List<Evento> eventos = wal.recuperar();
            for (Evento e : eventos) {
                aplicarEvento(e);
            }
            reconciliarReservasPendentes();
            System.out.println("[RECUPERACAO] " + eventos.size() + " eventos reproduzidos do log.");
        }
    }


    /** Replay: despacha cada evento para o serviço dono dele (sem gravar no log). */
    private void aplicarEvento(Evento e) {
        switch (e.tipo) {
            case USUARIO_CADASTRADO:    userService.aplicarCadastro(e); break;
            case USUARIO_TIPO_ALTERADO: userService.aplicarMudancaTipo(e); break;
            case CARONA_CRIADA:         caronaService.aplicarCriacao(e); break;
            case CARONA_CANCELADA:      caronaService.aplicarCancelamento(e); break;
            case RESERVA_CRIADA:        reservaService.aplicarCriacao(e, caronaService, userService); break;
            case RESERVA_CANCELADA:     reservaService.aplicarCancelamento(e); break;
            case RESERVA_DISTRIBUIDA_INICIADA:
                registrarReservaPendente(e.idLocal, e.login, extrairMapaRemotas(e));
                break;
            case RESERVA_DISTRIBUIDA_CONFIRMADA:
                confirmarReservaPendente(e.idLocal); break;
            case RESERVA_DISTRIBUIDA_CANCELADA:
                cancelarReservaPendente(e.idLocal); break;
        }
    }
    // ==========================================
    // FLUXOS DE USUÁRIO (Encaminha para o UserService)
    // ==========================================
    public boolean cadastrarUsuario(String login, String senha, TipoUser tipo) {
        return userService.cadastrarUsuario(login, senha, tipo);
    }

    public String fazerLogin(String login, String senha) {
        return userService.fazerLogin(login, senha);
    }

    public boolean fazerLogout(String token) {
        return userService.logout(token);
    }

    public boolean mudarTipoUsuario(String token, TipoUser tipoUser){
        return userService.mudaTipoUser(token, tipoUser);
    }

    public Usuario validarSessao(String token) {
        return userService.validarSessao(token);
    }

    // ==========================================
    // FLUXOS DE MOTORISTA (Encaminha para o CaronaService)
    // ==========================================
    public String oferecerCarona(String token, List<Cidades> rota, String data, String hora, 
                                int vagasTotais, boolean ativaOuNao, List<Double> precos) {
        // Opcional: Validar se o token pertence a um usuário válido antes de criar
        Usuario user = userService.validarSessao(token);
        if (user == null) {
            throw new SecurityException("Usuário não autenticado.");
        }

        return caronaService.criarCarona(rota, data, hora, vagasTotais, ativaOuNao, precos,  user.getLogin());
    }
    public List<Carona> listaCaronas(String token){
        Usuario motorista = userService.validarSessao(token);
        if (motorista == null) {
            throw new SecurityException("Usuário não autenticado.");
        }
        return caronaService.buscaCaronasPorMotorista(motorista.getLogin());
    }
    public boolean cancelarCarona(String token, String id){
        Usuario motorista = userService.validarSessao(token);
        if (motorista == null) {
            throw new SecurityException("Usuário não autenticado.");
        }
        return caronaService.cancelarCarona(id, motorista.getLogin());
    }

    public Map<String, List<String>> consultarPassageiros(String token, String idCarona){
        Usuario motorista = userService.validarSessao(token);
        if (motorista == null) {
            throw new SecurityException("Usuário não autenticado.");
        }
        return caronaService.consultarPassageirosDaCarona(idCarona, motorista.getLogin());
    }


    // ==========================================
    // FLUXOS DE PASSAGEIRO (Encaminha para o ReservaService)
    // ==========================================
    public List<Itinerario> buscarViagens(Cidades origem, Cidades destino, String data) {
        List<Trecho> trechos = new ArrayList<>(caronaService.listarTrechosPorData(data));
        for (Map.Entry<String, String> peer : peers.entrySet()) {
            try {
                for (TrechoRef ref : clienteConsorcio.listarTrechos(peer.getValue(), data)) {
                    if (ref == null) {
                        continue;
                    }
                    Cidades origemRef = Cidades.fromString(ref.origem);
                    Cidades destinoRef = Cidades.fromString(ref.destino);
                    trechos.add(new Trecho(ref.caronaId, ref.empresaId, ref.data, origemRef, destinoRef, ref.vagas, ref.preco, peer.getKey()));
                }
            } catch (RuntimeException e) {
                System.out.println("[CONSORCIO] Peer " + peer.getKey() + " indisponível: " + e.getMessage());
            }
        }
        return buscarItinerariosComTrechos(origem, destino, data, trechos);
    }

    private List<Itinerario> buscarItinerariosComTrechos(Cidades origem, Cidades destino, String data, List<Trecho> trechos) {
        if (origem == null || destino == null || data == null || origem == destino) {
            return Collections.emptyList();
        }

        Map<Cidades, List<Trecho>> adjacencias = new HashMap<>();
        for (Cidades cidade : Cidades.values()) {
            adjacencias.put(cidade, new ArrayList<>());
        }

        for (Trecho trecho : trechos) {
            if (trecho == null || trecho.getData() == null || !trecho.getData().equals(data)) {
                continue;
            }
            if (trecho.getCidadeOrigem() == null || trecho.getCidadeDestino() == null) {
                continue;
            }
            if (trecho.getLugaresDisponiveis() <= 0 || !trecho.getCidadeDestino().isDisponivel()) {
                continue;
            }
            adjacencias.computeIfAbsent(trecho.getCidadeOrigem(), k -> new ArrayList<>()).add(trecho);
        }

        List<Itinerario> resultado = new ArrayList<>();
        List<Trecho> caminhoAtual = new ArrayList<>();
        Set<Cidades> visitadas = new HashSet<>();
        visitadas.add(origem);
        dfsBusca(origem, destino, data, adjacencias, caminhoAtual, visitadas, resultado);
        return resultado;
    }

    private void dfsBusca(Cidades atual, Cidades destino, String data,
                          Map<Cidades, List<Trecho>> adjacencias,
                          List<Trecho> caminhoAtual,
                          Set<Cidades> visitadas,
                          List<Itinerario> resultado) {
        if (atual == destino) {
            resultado.add(new Itinerario(new ArrayList<>(caminhoAtual)));
            return;
        }

        for (Trecho trecho : adjacencias.getOrDefault(atual, Collections.emptyList())) {
            Cidades proxima = trecho.getCidadeDestino();
            if (!trecho.getData().equals(data) || !proxima.isDisponivel() || visitadas.contains(proxima)) {
                continue;
            }
            visitadas.add(proxima);
            caminhoAtual.add(trecho);
            dfsBusca(proxima, destino, data, adjacencias, caminhoAtual, visitadas, resultado);
            caminhoAtual.remove(caminhoAtual.size() - 1);
            visitadas.remove(proxima);
        }
    }

    public String reservarViagem(String token, Itinerario itinerario) {
        Usuario passageiro = userService.validarSessao(token);
        if (passageiro == null) {
            throw new SecurityException("Usuário não autenticado.");
        }
        if (itinerario == null || itinerario.getTrechos().isEmpty()) {
            return null;
        }

        Map<String, List<Trecho>> trechosPorEmpresa = new HashMap<>();
        List<Trecho> trechosLocais = new ArrayList<>();
        for (Trecho trecho : itinerario.getTrechos()) {
            String empresa = trecho.getEmpresaId();
            if (empresa != null && !empresa.equals(empresaId)) {
                trechosPorEmpresa.computeIfAbsent(empresa, k -> new ArrayList<>()).add(trecho);
            } else {
                trechosLocais.add(trecho);
            }
        }

        List<String> idsReservas = new ArrayList<>();
        Map<String, String> idsRemotosReservados = new LinkedHashMap<>();
        String idLocal = null;

        try {
            for (Map.Entry<String, List<Trecho>> entrada : trechosPorEmpresa.entrySet()) {
                String empresaPeer = entrada.getKey();
                String urlBase = peers.get(empresaPeer);
                if (urlBase == null) {
                    return null;
                }
                List<TrechoRef> refs = entrada.getValue().stream().map(TrechoRef::de).toList();
                String idRemoto = clienteConsorcio.reservarTrechos(urlBase, passageiro.getLogin(), refs);
                idsRemotosReservados.put(empresaPeer, idRemoto);
            }

            if (!trechosLocais.isEmpty()) {
                idLocal = reservaService.efetuarReserva(passageiro, new Itinerario(trechosLocais));
                if (idLocal == null) {
                    rollbackReservasRemotas(idsRemotosReservados, passageiro.getLogin());
                    return null;
                }
                idsReservas.add("LOCAL:" + idLocal);
            }

            if (!idsRemotosReservados.isEmpty()) {
                String idTransacao = idLocal == null ? UUID.randomUUID().toString() : idLocal;
                registrarReservaPendente(idTransacao, passageiro.getLogin(), idsRemotosReservados);
                if (wal != null) {
                    wal.adicionar(Evento.reservaDistribuidaIniciada(idTransacao, passageiro.getLogin(),
                            idsRemotosReservados.entrySet().stream()
                                .map(e -> new Evento.ReservaDistribuidaRef(e.getKey(), e.getValue()))
                                .toList()));
                }
            }

            for (Map.Entry<String, String> idRemoto : idsRemotosReservados.entrySet()) {
                idsReservas.add("REMOTE:" + idRemoto.getKey() + ":" + idRemoto.getValue());
            }
            if (!idsRemotosReservados.isEmpty() && wal != null) {
                wal.adicionar(Evento.reservaDistribuidaConfirmada(idLocal == null ? String.join("|", idsReservas) : idLocal,
                        idsRemotosReservados.entrySet().stream()
                            .map(e -> new Evento.ReservaDistribuidaRef(e.getKey(), e.getValue()))
                            .toList()));
            }
            return String.join("|", idsReservas);
        } catch (RuntimeException e) {
            rollbackReservasRemotas(idsRemotosReservados, passageiro.getLogin());
            if (wal != null) {
                wal.adicionar(Evento.reservaDistribuidaCancelada(
                        idLocal == null ? UUID.randomUUID().toString() : idLocal,
                        idsRemotosReservados.entrySet().stream()
                            .map(entry -> new Evento.ReservaDistribuidaRef(entry.getKey(), entry.getValue()))
                            .toList()));
            }
            if (!trechosLocais.isEmpty() && !idsReservas.isEmpty()) {
                for (String id : idsReservas) {
                    if (id.startsWith("LOCAL:")) {
                        reservaService.cancelarReserva(id.substring("LOCAL:".length()), passageiro.getLogin());
                    }
                }
            }
            return null;
        }
    }

    public boolean cancelarReserva(String token, String idReserva) {
        Usuario passageiro = userService.validarSessao(token);
        if (passageiro == null) {
            return false;
        }
        if (idReserva == null || idReserva.isBlank()) {
            return false;
        }

        boolean ok = true;
        for (String item : idReserva.split("\\|")) {
            if (item.startsWith("LOCAL:")) {
                ok = reservaService.cancelarReserva(item.substring("LOCAL:".length()), passageiro.getLogin()) && ok;
            } else if (item.startsWith("REMOTE:")) {
                String[] partes = item.substring("REMOTE:".length()).split(":", 2);
                if (partes.length != 2) {
                    ok = false;
                    continue;
                }
                String empresaPeer = partes[0];
                String idRemota = partes[1];
                String urlBase = peers.get(empresaPeer);
                if (urlBase == null) {
                    ok = false;
                    continue;
                }
                ok = clienteConsorcio.cancelarReserva(urlBase, passageiro.getLogin(), idRemota) && ok;
            }
        }
        return ok;
    }

    public List<Reserva> listarReservas(String token) {
        Usuario passageiro = userService.validarSessao(token);
        if (passageiro == null) {
            throw new SecurityException("Usuário não autenticado.");
        }
        return reservaService.listarReservasPorPassageiro(passageiro.getLogin());
    }

    public String reservarTrechosRemotos(String loginPassageiro, List<TrechoRef> trechos) {
        if (loginPassageiro == null || loginPassageiro.isBlank() || trechos == null || trechos.isEmpty()) {
            throw new IllegalArgumentException("loginPassageiro e lista de trechos são obrigatórios.");
        }
        Usuario passageiro = userService.buscarPorLogin(loginPassageiro);
        if (passageiro == null) {
            passageiro = new Usuario(loginPassageiro, null, loginPassageiro, TipoUser.PASSAGEIRO);
        }
        List<Trecho> trechosLocais = new ArrayList<>();
        for (TrechoRef ref : trechos) {
            if (ref == null) {
                continue;
            }
            Cidades origem = Cidades.fromString(ref.origem);
            Cidades destino = Cidades.fromString(ref.destino);
            Trecho trecho = caronaService.buscarTrecho(ref.caronaId, origem, destino);
            if (trecho == null) {
                throw new IllegalArgumentException("Trecho remoto não encontrado para o peer: " + ref.caronaId);
            }
            trechosLocais.add(trecho);
        }
        String idReserva = reservaService.efetuarReserva(passageiro, new Itinerario(trechosLocais));
        if (idReserva == null) {
            throw new IllegalStateException("Um ou mais trechos remotos ficaram indisponíveis no peer.");
        }
        return idReserva;
    }

    public boolean cancelarReservaRemota(String loginPassageiro, String idReserva) {
        if (loginPassageiro == null || loginPassageiro.isBlank() || idReserva == null || idReserva.isBlank()) {
            return false;
        }
        Usuario passageiro = userService.buscarPorLogin(loginPassageiro);
        if (passageiro == null) {
            passageiro = new Usuario(loginPassageiro, null, loginPassageiro, TipoUser.PASSAGEIRO);
        }
        return reservaService.cancelarReserva(idReserva, passageiro.getLogin());
    }

    private Map<String, String> extrairMapaRemotas(Evento e) {
        Map<String, String> mapa = new LinkedHashMap<>();
        if (e.reservasRemotas == null) {
            return mapa;
        }
        for (Evento.ReservaDistribuidaRef ref : e.reservasRemotas) {
            if (ref != null && ref.empresaId != null && ref.idRemota != null) {
                mapa.put(ref.empresaId, ref.idRemota);
            }
        }
        return mapa;
    }

    private void registrarReservaPendente(String idLocal, String login, Map<String, String> idsRemotosPorEmpresa) {
        if (idLocal == null || idsRemotosPorEmpresa == null || idsRemotosPorEmpresa.isEmpty()) {
            return;
        }
        reservasDistribuidasPendentes.put(idLocal, new ReservaDistribuidaPendente(login, idsRemotosPorEmpresa));
    }

    private void confirmarReservaPendente(String idLocal) {
        if (idLocal == null) {
            return;
        }
        reservasDistribuidasPendentes.remove(idLocal);
    }

    private void cancelarReservaPendente(String idLocal) {
        if (idLocal == null) {
            return;
        }
        reservasDistribuidasPendentes.remove(idLocal);
    }

    private void reconciliarReservasPendentes() {
        for (Map.Entry<String, ReservaDistribuidaPendente> entrada : reservasDistribuidasPendentes.entrySet()) {
            String idLocal = entrada.getKey();
            ReservaDistribuidaPendente reserva = entrada.getValue();
            boolean localExiste = reservaService.listarReservasPorPassageiro(reserva.login)
                    .stream().anyMatch(r -> r.getId().equals(idLocal));
            if (localExiste) {
                confirmarReservaPendente(idLocal);
                continue;
            }
            boolean algumaAtiva = false;
            for (Map.Entry<String, String> remote : reserva.idsRemotosPorEmpresa.entrySet()) {
                String urlBase = peers.get(remote.getKey());
                if (urlBase == null) {
                    continue;
                }
                try {
                    String status = clienteConsorcio.consultarStatusReserva(urlBase, remote.getValue(), reserva.login);
                    if ("ATIVA".equalsIgnoreCase(status) || "EXISTE".equalsIgnoreCase(status)) {
                        algumaAtiva = true;
                    }
                } catch (RuntimeException e) {
                    // peer indisponível; a recuperação continua para o próximo peer
                }
            }
            if (!algumaAtiva) {
                rollbackReservasRemotas(reserva.idsRemotosPorEmpresa, reserva.login);
                cancelarReservaPendente(idLocal);
            }
        }
    }

    public String getStatusReservaRemota(String loginPassageiro, String idReserva) {
        if (idReserva == null || idReserva.isBlank()) {
            return "INEXISTENTE";
        }
        for (Map.Entry<String, String> peer : peers.entrySet()) {
            try {
                String status = clienteConsorcio.consultarStatusReserva(peer.getValue(), idReserva, loginPassageiro == null ? "" : loginPassageiro);
                if (!"INEXISTENTE".equalsIgnoreCase(status)) {
                    return status;
                }
            } catch (RuntimeException ignored) {
                // ignora peers indisponíveis e continua a busca
            }
        }
        return "INEXISTENTE";
    }

    private void rollbackReservasRemotas(Map<String, String> idsRemotosReservados, String loginPassageiro) {
        if (idsRemotosReservados == null || idsRemotosReservados.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> remoto : idsRemotosReservados.entrySet()) {
            String empresaPeer = remoto.getKey();
            String idReserva = remoto.getValue();
            String urlBase = peers.get(empresaPeer);
            if (urlBase != null && idReserva != null && !idReserva.isBlank()) {
                clienteConsorcio.cancelarReserva(urlBase, loginPassageiro, idReserva);
            }
        }
    }

    private static final class ReservaDistribuidaPendente {
        private final String login;
        private final Map<String, String> idsRemotosPorEmpresa;

        private ReservaDistribuidaPendente(String login, Map<String, String> idsRemotosPorEmpresa) {
            this.login = login;
            this.idsRemotosPorEmpresa = new LinkedHashMap<>(idsRemotosPorEmpresa);
        }
    }

    //=================================================
    //API
    //=================================================    
    public List<Trecho> getTrechosData(String data){
        return caronaService.listarTrechosPorData(data);
    }

}