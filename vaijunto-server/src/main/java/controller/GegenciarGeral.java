package controller;

import java.util.List;
import java.util.Map;

import config.Config;
import model.Carona;
import model.Cidades;
import model.Grafo;
import model.Itinerario;
import model.Reserva;
import model.TipoUser;
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
    
    /** Sem log: tudo em memória (usado pelos testes de concorrência). */
    public GegenciarGeral(){
        this((Wal) null);
    }

    /** Abre (ou cria) o log no caminho informado e recupera o estado a partir dele. */
    public GegenciarGeral(String caminhoWal){
        this(new Wal(caminhoWal));
    }

    public GegenciarGeral(String caminhoWal, Config config) {
        this(new Wal(caminhoWal), config.empresaId);
    }

    /**
     * Com log. Primeiro REPRODUZ todos os eventos já gravados (sem regravá-los)
     * e só depois o servidor fica pronto para novas operações.
     */
    public GegenciarGeral(Wal wal, String empresaId){
        this.grafo = new Grafo();
        this.userService = new UserService(wal);
        this.caronaService = new CaronaService(this.grafo, wal, empresaId);
        this.reservaService = new ReservaService(this.grafo, wal);

        if (wal != null) {
            List<Evento> eventos = wal.recuperar();
            for (Evento e : eventos) {
                aplicarEvento(e);
            }
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
        return reservaService.buscarViagens(origem, destino, data);
    }

    public String reservarViagem(String token, Itinerario itinerario) {
        Usuario passageiro = userService.validarSessao(token);
        if (passageiro == null) {
            throw new SecurityException("Usuário não autenticado.");
        }
        
        return reservaService.efetuarReserva(passageiro, itinerario);
    }

    public boolean cancelarReserva(String token, String idReserva) {
        Usuario passageiro = userService.validarSessao(token);
        if (passageiro == null) {
            return false;
        }
        return reservaService.cancelarReserva(idReserva, passageiro.getLogin());
    }

    public List<Reserva> listarReservas(String token) {
        Usuario passageiro = userService.validarSessao(token);
        if (passageiro == null) {
            throw new SecurityException("Usuário não autenticado.");
        }
        return reservaService.listarReservasPorPassageiro(passageiro.getLogin());
    }

   

}