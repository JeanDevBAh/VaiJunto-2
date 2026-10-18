package controller;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import model.Cidades;
import model.Grafo;
import model.Itinerario;
import model.Reserva;
import model.Trecho;
import model.TipoUser;
import model.Usuario;
import wal.Evento;
import wal.Wal;
/**
 * Responsável pela busca de itinerários e pelo gerenciamento das reservas.
 *
 * Consulta o Grafo para localizar caminhos entre cidades e cria reservas
 * associadas aos trechos escolhidos pelo passageiro.
 *
 * A confirmação da reserva é protegida por sincronização e ocorre em duas
 * etapas: primeiro todos os trechos são validados e, somente se houver vaga
 * em todos eles, as alterações são efetivadas.
 *
 * Também realiza cancelamento e listagem das reservas de um passageiro.
 */
public class ReservaService {
    private final Grafo grafo;
    private final Map<String, Reserva> reservas;

    // Null = sem log
    private final Wal wal;

    public ReservaService(Grafo grafo){
        this(grafo, null);
    }

    public ReservaService(Grafo grafo, Wal wal){
        this.grafo = grafo;
        this.reservas = new ConcurrentHashMap<>();
        this.wal = wal;
    }

    // ---------- replay (NÃO grava no log) ----------

    /** Recria a reserva e volta a ocupar um lugar em cada trecho dela. */
    public void aplicarCriacao(Evento e, CaronaService caronaService, UserService userService) {
        List<Trecho> trechos = new ArrayList<>();
        for (Evento.TrechoRef ref : e.trechos) {
            Trecho t = caronaService.buscarTrecho(
                    ref.caronaId, Cidades.valueOf(ref.origem), Cidades.valueOf(ref.destino));
            if (t == null) {
                // Só acontece se a carona foi cancelada ANTES da reserva ser registrada (corrida rara).
                System.out.println("[RECUPERACAO] Trecho " + ref.origem + "->" + ref.destino
                        + " da carona " + ref.caronaId + " não existe (reserva " + e.id + ").");
                continue;
            }
            trechos.add(t);
        }
        if (trechos.isEmpty()) {
            return;
        }
        Usuario passageiro = userService.buscarPorLogin(e.login);
        if (passageiro == null) {
            passageiro = new Usuario(e.login, null, e.passageiroId, TipoUser.PASSAGEIRO);
        }
        Reserva reserva = new Reserva(e.id, e.passageiroId, e.login, trechos);
        reservas.put(reserva.getId(), reserva);
        for (Trecho t : trechos) {
            t.reservarLugar(passageiro);
        }
    }

    public void aplicarCancelamento(Evento e) {
        Reserva reserva = reservas.get(e.id);
        if (reserva != null) {
            reserva.cancelar(); // libera os lugares
        }
    }
    

    public List<Itinerario> buscarViagens(Cidades origem, Cidades destino, String data){
        return grafo.buscarItinerarios(origem, destino, data);
    }

    public String efetuarReserva(Usuario passageiro, Itinerario viagem){
    List<Trecho> trechos = viagem.getTrechos();
    
    // Sincroniza o processo de reserva para evitar que outra thread roube a vaga
    // entre a checagem e a confirmação.
    synchronized (this) {
        // 1. Fase de Validação: Checa todos os trechos primeiro
        for(Trecho trecho : trechos){
            if (trecho.getLugaresDisponiveis() <= 0) {
                return null; // Falha atômica: rejeita antes de alterar qualquer vaga
            }
        }
        
        // 2. Registra no log ANTES de ocupar as vagas. Se falhar, nenhuma vaga foi tocada.
        Reserva reserva = new Reserva(passageiro.getId(), passageiro.getLogin(), trechos);
        if (wal != null) {
            wal.adicionar(Evento.reservaCriada(reserva));
        }

        // 3. Fase de Confirmação: Efetiva a reserva com segurança
        for(Trecho trecho : trechos){
            trecho.reservarLugar(passageiro);
        }
        
        reservas.put(reserva.getId(), reserva);
        return reserva.getId();
    }
}

    public boolean cancelarReserva(String idReserva, String login){
        Reserva reserva = reservas.get(idReserva);
        if (reserva == null) {
            return false;
        }
        // synchronized na reserva: dois cancelamentos simultâneos não registram o evento duas vezes
        synchronized (reserva) {
            if (reserva.isAtiva() && reserva.getLoginPassageiro().equals(login)) {
                if (wal != null) {
                    wal.adicionar(Evento.reservaCancelada(idReserva)); // registrar ANTES de aplicar
                }
                reserva.cancelar();
                return true;
            }
        }
        return false;
    }

    public List<Reserva> listarReservasPorPassageiro(String login) {
        return reservas.values().stream()
                .filter(reserva -> reserva.getLoginPassageiro().equals(login))
                .toList();
    }
}