package controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import api.ClienteConsorcio;
import api.MonitorPeers;
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
    private MonitorPeers monitorPeers;
    private final Map<String, ReservaDistribuidaPendente> reservasDistribuidasPendentes = new ConcurrentHashMap<>();
    private final Map<String, String> transacoesLocais = new ConcurrentHashMap<>();
    private final Map<String, List<String>> transacoesParticipantes = new ConcurrentHashMap<>();
    private final Map<String, RetryTx> filaRetentativas = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executorRetentativas = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tx-retry");
        t.setDaemon(true);
        return t;
    });

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
            reconciliarTransacoesPendentes();
            System.out.println("[RECUPERACAO] " + eventos.size() + " eventos reproduzidos do log.");
        }
        executorRetentativas.scheduleWithFixedDelay(this::processarRetentativasPendentes, 2, 2, TimeUnit.SECONDS);
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
            case TX_INICIADA:
                registrarParticipantesTx(e.txId, e.participantes);
                registrarEstadoTransacao(e.txId, "INICIADA");
                break;
            case TX_PREPARADA:
                registrarEstadoTransacao(e.txId, "PREPARADA");
                break;
            case TX_COMMIT:
                registrarEstadoTransacao(e.txId, "COMMIT");
                break;
            case TX_ABORT:
                registrarEstadoTransacao(e.txId, "ABORT"); break;
            case TX_COMMIT_CONFIRMADO:
                registrarEstadoTransacao(e.txId, "COMMIT_CONFIRMADO"); break;
            case TX_ABORT_CONFIRMADO:
                registrarEstadoTransacao(e.txId, "ABORT_CONFIRMADO"); break;
            case TX_RETRY_SCHEDULED:
                registrarEstadoTransacao(e.txId, "RETRY"); break;
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
    public void setMonitorPeers(MonitorPeers monitorPeers) {
        this.monitorPeers = monitorPeers;
    }

    public List<Itinerario> buscarViagens(Cidades origem, Cidades destino, String data) {
        List<Trecho> trechos = new ArrayList<>(caronaService.listarTrechosPorData(data));
        List<CompletableFuture<List<Trecho>>> tarefas = new ArrayList<>();

        for (Map.Entry<String, String> peer : peers.entrySet()) {
            final String empresa = peer.getKey();
            final String urlBase = peer.getValue();
            if (monitorPeers != null && !monitorPeers.estaOnline(empresa)) {
                continue;
            }
            tarefas.add(CompletableFuture.supplyAsync(() -> {
                List<Trecho> trechosRemotos = new ArrayList<>();
                try {
                    for (TrechoRef ref : clienteConsorcio.listarTrechos(urlBase, data)) {
                        if (ref == null) {
                            continue;
                        }
                        Cidades origemRef = Cidades.fromString(ref.origem);
                        Cidades destinoRef = Cidades.fromString(ref.destino);
                        trechosRemotos.add(new Trecho(ref.caronaId, ref.empresaId, ref.data, origemRef, destinoRef, ref.vagas, ref.preco, empresa));
                    }
                    return trechosRemotos;
                } catch (RuntimeException e) {
                    System.out.println("[CONSORCIO] Peer " + empresa + " indisponível: " + e.getMessage());
                    return Collections.emptyList();
                }
            }));
        }

        for (CompletableFuture<List<Trecho>> tarefa : tarefas) {
            trechos.addAll(tarefa.join());
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

        List<Trecho> trechosOrdenados = new ArrayList<>(itinerario.getTrechos());
        trechosOrdenados.sort(Comparator.comparing(Trecho::getTrechoId));

        Map<String, List<Trecho>> trechosPorEmpresa = new LinkedHashMap<>();
        for (Trecho trecho : trechosOrdenados) {
            String empresa = trecho.getEmpresaId();
            if (empresa == null || empresa.equals(empresaId)) {
                empresa = empresaId;
            }
            trechosPorEmpresa.computeIfAbsent(empresa, k -> new ArrayList<>()).add(trecho);
        }

        String txId = UUID.randomUUID().toString();
        List<String> participantes = new ArrayList<>(trechosPorEmpresa.keySet());
        List<String> trechosTx = trechosOrdenados.stream().map(Trecho::getTrechoId).toList();
        if (wal != null) {
            wal.adicionar(Evento.txIniciada(txId, passageiro.getLogin(), participantes, trechosTx));
        }
        registrarEstadoTransacao(txId, "INICIADA");

        Map<String, Boolean> preparadas = new LinkedHashMap<>();
        boolean falhou = false;
        String motivo = "sem vaga";

        for (Map.Entry<String, List<Trecho>> entrada : trechosPorEmpresa.entrySet()) {
            String empresaPeer = entrada.getKey();
            List<Trecho> trechosEmpresa = entrada.getValue();
            boolean ok;
            if (empresaPeer.equals(empresaId)) {
                ok = reservaService.prepararTx(txId, passageiro.getLogin(), trechosEmpresa, 10_000L);
            } else {
                String urlBase = peers.get(empresaPeer);
                if (urlBase == null) {
                    falhou = true;
                    motivo = "peer ausente: " + empresaPeer;
                    break;
                }
                List<TrechoRef> refs = trechosEmpresa.stream().map(TrechoRef::de).toList();
                ok = clienteConsorcio.prepararTx(urlBase, txId, passageiro.getLogin(), refs, 10_000L);
            }
            preparadas.put(empresaPeer, ok);
            if (!ok) {
                falhou = true;
                motivo = "prepare recusado por " + empresaPeer;
                break;
            }
        }

        if (falhou) {
            if (wal != null) {
                wal.adicionar(Evento.txAbort(txId, motivo));
            }
            registrarEstadoTransacao(txId, "ABORT");
            for (Map.Entry<String, Boolean> entrada : preparadas.entrySet()) {
                if (!Boolean.TRUE.equals(entrada.getValue())) {
                    continue;
                }
                String empresaPeer = entrada.getKey();
                if (empresaPeer.equals(empresaId)) {
                    reservaService.abortarTx(txId);
                } else {
                    String urlBase = peers.get(empresaPeer);
                    if (urlBase != null) {
                        boolean abortou = clienteConsorcio.abortarTx(urlBase, txId);
                        if (!abortou) {
                            agendarRetryTx(txId, "ABORT", new ArrayList<>(trechosPorEmpresa.keySet()));
                        }
                    }
                }
            }
            return null;
        }

        if (wal != null) {
            wal.adicionar(Evento.txCommit(txId));
        }
        registrarEstadoTransacao(txId, "COMMIT");

        for (Map.Entry<String, List<Trecho>> entrada : trechosPorEmpresa.entrySet()) {
            String empresaPeer = entrada.getKey();
            if (empresaPeer.equals(empresaId)) {
                if (reservaService.confirmarTx(txId) == null) {
                    agendarRetryTx(txId, "COMMIT", new ArrayList<>(trechosPorEmpresa.keySet()));
                }
                continue;
            }
            String urlBase = peers.get(empresaPeer);
            if (urlBase == null) {
                continue;
            }
            boolean confirmado = clienteConsorcio.confirmarTx(urlBase, txId);
            if (!confirmado) {
                agendarRetryTx(txId, "COMMIT", new ArrayList<>(trechosPorEmpresa.keySet()));
                registrarEstadoTransacao(txId, "RETRY");
            }
        }

        return txId;
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

    public boolean iniciarTx(String txId, String login, List<String> participantes, List<Trecho> trechos) {
        if (txId == null || txId.isBlank()) {
            return false;
        }
        registrarEstadoTransacao(txId, "INICIADA");
        if (wal != null) {
            wal.adicionar(Evento.txIniciada(txId, login, participantes, trechos == null ? new ArrayList<>()
                    : trechos.stream().map(Trecho::getTrechoId).toList()));
        }
        return true;
    }

    public boolean prepararTxLocal(String txId, String login, List<Trecho> trechos, long ttlMillis) {
        if (txId == null || txId.isBlank() || trechos == null || trechos.isEmpty()) {
            return false;
        }
        String estadoAtual = getStatusTxLocal(txId);
        if ("PREPARADA".equalsIgnoreCase(estadoAtual) || "COMMIT".equalsIgnoreCase(estadoAtual) || "ABORT".equalsIgnoreCase(estadoAtual)) {
            return true;
        }
        if (wal != null) {
            wal.adicionar(Evento.txPreparada(txId, empresaId,
                    trechos.stream().map(Trecho::getTrechoId).toList(), System.currentTimeMillis() + ttlMillis));
        }
        registrarEstadoTransacao(txId, "PREPARADA");
        boolean ok = reservaService.prepararTx(txId, login, trechos, ttlMillis);
        if (!ok) {
            registrarEstadoTransacao(txId, "ABORT");
            if (wal != null) {
                wal.adicionar(Evento.txAbort(txId, "PREPARE_REJEITADO_LOCAL"));
            }
        }
        return ok;
    }

    public boolean confirmarTxLocal(String txId) {
        if (txId == null || txId.isBlank()) {
            return false;
        }
        String estadoAtual = getStatusTxLocal(txId);
        if ("COMMIT".equalsIgnoreCase(estadoAtual) || "COMMIT_CONFIRMADO".equalsIgnoreCase(estadoAtual)) {
            return true;
        }
        if (wal != null) {
            wal.adicionar(Evento.txCommit(txId));
        }
        registrarEstadoTransacao(txId, "COMMIT");
        if (reservaService.confirmarTx(txId) == null) {
            return false;
        }
        return true;
    }

    public boolean abortarTxLocal(String txId, String motivo) {
        if (txId == null || txId.isBlank()) {
            return false;
        }
        String estadoAtual = getStatusTxLocal(txId);
        if ("ABORT".equalsIgnoreCase(estadoAtual) || "ABORT_CONFIRMADO".equalsIgnoreCase(estadoAtual)) {
            return true;
        }
        if (wal != null) {
            wal.adicionar(Evento.txAbort(txId, motivo == null ? "ABORT_LOCAL" : motivo));
        }
        registrarEstadoTransacao(txId, "ABORT");
        boolean ok = reservaService.abortarTx(txId);
        return ok;
    }

    public String getStatusTxLocal(String txId) {
        if (txId == null || txId.isBlank()) {
            return "DESCONHECIDA";
        }
        return transacoesLocais.getOrDefault(txId, reservaService.getTxEstado(txId));
    }

    public void registrarEstadoTransacao(String txId, String estado) {
        if (txId == null || txId.isBlank() || estado == null || estado.isBlank()) {
            return;
        }
        transacoesLocais.put(txId, estado);
    }

    public void registrarParticipantesTx(String txId, List<String> participantes) {
        if (txId == null || txId.isBlank()) {
            return;
        }
        if (participantes == null || participantes.isEmpty()) {
            transacoesParticipantes.remove(txId);
            return;
        }
        transacoesParticipantes.put(txId, new ArrayList<>(participantes));
    }

    private static final class RetryTx {
        private final String txId;
        private final String decisao;
        private final List<String> participantes;
        private final long proximaTentativaEm;
        private final int tentativas;

        private RetryTx(String txId, String decisao, List<String> participantes, long proximaTentativaEm, int tentativas) {
            this.txId = txId;
            this.decisao = decisao;
            this.participantes = participantes == null ? Collections.emptyList() : new ArrayList<>(participantes);
            this.proximaTentativaEm = proximaTentativaEm;
            this.tentativas = tentativas;
        }
    }

    private void agendarRetryTx(String txId, String decisao, List<String> participantes) {
        if (txId == null || txId.isBlank()) {
            return;
        }
        List<String> participantesValidos = participantes == null ? transacoesParticipantes.getOrDefault(txId, Collections.emptyList()) : participantes;
        if (participantesValidos == null || participantesValidos.isEmpty()) {
            return;
        }
        RetryTx retry = new RetryTx(txId, decisao, participantesValidos, System.currentTimeMillis() + 5_000L, 0);
        filaRetentativas.put(txId, retry);
        registrarEstadoTransacao(txId, "RETRY");
        if (wal != null) {
            wal.adicionar(Evento.txRetryScheduled(txId, retry.proximaTentativaEm, decisao));
        }
    }

    private boolean enviarDecisaoTx(String txId, String decisao) {
        if (txId == null || txId.isBlank()) {
            return false;
        }
        List<String> participantes = transacoesParticipantes.getOrDefault(txId, Collections.emptyList());
        if (participantes.isEmpty()) {
            return true;
        }
        boolean ok = true;
        for (String empresa : participantes) {
            if (empresa == null || empresa.isBlank()) {
                continue;
            }
            if (empresaId.equals(empresa)) {
                ok = "COMMIT".equalsIgnoreCase(decisao)
                        ? (confirmarTxLocal(txId) || "COMMIT".equalsIgnoreCase(getStatusTxLocal(txId)))
                        : (abortarTxLocal(txId, "RECUPERACAO") || "ABORT".equalsIgnoreCase(getStatusTxLocal(txId)));
            } else {
                String urlBase = peers.get(empresa);
                if (urlBase == null) {
                    continue;
                }
                if ("COMMIT".equalsIgnoreCase(decisao)) {
                    ok = clienteConsorcio.confirmarTx(urlBase, txId) && ok;
                } else {
                    ok = clienteConsorcio.abortarTx(urlBase, txId) && ok;
                }
            }
            if (!ok) {
                break;
            }
        }
        if (ok && "COMMIT".equalsIgnoreCase(decisao)) {
            registrarEstadoTransacao(txId, "COMMIT");
        } else if (ok) {
            registrarEstadoTransacao(txId, "ABORT");
        }
        return ok;
    }

    private void processarRetentativasPendentes() {
        long agora = System.currentTimeMillis();
        for (Map.Entry<String, RetryTx> entrada : new ArrayList<>(filaRetentativas.entrySet())) {
            RetryTx retry = entrada.getValue();
            if (retry == null || retry.proximaTentativaEm > agora) {
                continue;
            }
            boolean sucesso = enviarDecisaoTx(retry.txId, retry.decisao);
            if (sucesso) {
                filaRetentativas.remove(retry.txId);
                continue;
            }
            long novaTentativa = agora + Math.min(30_000L, 5_000L * (retry.tentativas + 1));
            filaRetentativas.put(retry.txId, new RetryTx(retry.txId, retry.decisao, retry.participantes, novaTentativa, retry.tentativas + 1));
            if (wal != null) {
                wal.adicionar(Evento.txRetryScheduled(retry.txId, novaTentativa, retry.decisao));
            }
        }

        for (String txId : reservaService.listarTxsExpiradas()) {
            String estado = getStatusTxLocal(txId);
            if ("PREPARADA".equalsIgnoreCase(estado)) {
                registrarEstadoTransacao(txId, "ABORT");
                if (wal != null) {
                    wal.adicionar(Evento.txAbort(txId, "TTL_EXPIRADO"));
                }
                boolean liberou = reservaService.abortarTx(txId);
                if (!liberou) {
                    reservaService.expirarHold(txId);
                }
                enviarDecisaoTx(txId, "ABORT");
            }
        }
    }

    private void reconciliarTransacoesPendentes() {
        for (Map.Entry<String, String> entrada : new HashMap<>(transacoesLocais).entrySet()) {
            String txId = entrada.getKey();
            String estado = entrada.getValue();
            List<String> participantes = transacoesParticipantes.getOrDefault(txId, Collections.emptyList());
            if ("INICIADA".equalsIgnoreCase(estado)) {
                if (wal != null) {
                    wal.adicionar(Evento.txAbort(txId, "RECUPERACAO_INICIADA_SEM_DECISAO"));
                }
                registrarEstadoTransacao(txId, "ABORT");
                enviarDecisaoTx(txId, "ABORT");
            } else if ("COMMIT".equalsIgnoreCase(estado) && !participantes.isEmpty()) {
                agendarRetryTx(txId, "COMMIT", participantes);
            }
        }
    }

    private void reconciliarReservasPendentes() {
        for (Map.Entry<String, ReservaDistribuidaPendente> entrada : reservasDistribuidasPendentes.entrySet()) {
            String idLocal = entrada.getKey();
            ReservaDistribuidaPendente reserva = entrada.getValue();
            boolean localExiste = reservaService.existeReservaLocal(idLocal);
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
        boolean localExiste = reservaService.existeReservaLocal(idReserva);
        if (localExiste) {
            return "ATIVA";
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
            if (urlBase == null || idReserva == null || idReserva.isBlank()) {
                continue;
            }
            try {
                clienteConsorcio.cancelarReserva(urlBase, loginPassageiro, idReserva);
            } catch (RuntimeException e) {
                System.out.println("[CONSORCIO] rollback remoto falhou para " + empresaPeer + ": " + e.getMessage());
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

    public Trecho getTrechoPorReferencia(String caronaId, Cidades origem, Cidades destino) {
        if (caronaId == null || caronaId.isBlank()) {
            return null;
        }
        return caronaService.buscarTrecho(caronaId, origem, destino);
    }

    //=================================================
    //API
    //=================================================    
    public List<Trecho> getTrechosData(String data){
        return caronaService.listarTrechosPorData(data);
    }

}