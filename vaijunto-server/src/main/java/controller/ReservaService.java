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
    private final Map<String, TxHold> holds = new ConcurrentHashMap<>();

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

    private static final class TxHold {
        final String txId;
        final String login;
        final List<Trecho> trechos;
        final long expiraEm;
        final long criadoEm;

        private TxHold(String txId, String login, List<Trecho> trechos, long expiraEm) {
            this.txId = txId;
            this.login = login;
            this.trechos = new ArrayList<>(trechos);
            this.criadoEm = System.currentTimeMillis();
            this.expiraEm = expiraEm;
        }
    }

    // ---------- replay (NÃO grava no log) ----------

    /** Recria a reserva e volta a ocupar um lugar em cada trecho dela. */
    public void aplicarCriacao(Evento e, CaronaService caronaService, UserService userService) {
        List<Trecho> trechos = new ArrayList<>();
        for (Evento.TrechoRef ref : e.trechos) {
            Trecho t = caronaService.buscarTrecho(
                    ref.caronaId, Cidades.valueOf(ref.origem), Cidades.valueOf(ref.destino));
            if (t == null) {
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
            reserva.cancelar();
        }
    }

    public List<Itinerario> buscarViagens(Cidades origem, Cidades destino, String data){
        return grafo.buscarItinerarios(origem, destino, data);
    }

    public String efetuarReserva(Usuario passageiro, Itinerario viagem){
        List<Trecho> trechos = viagem.getTrechos();
        synchronized (this) {
            for (Trecho trecho : trechos) {
                if (trecho.getLugaresDisponiveis() <= 0) {
                    return null;
                }
            }

            Reserva reserva = new Reserva(passageiro.getId(), passageiro.getLogin(), trechos);
            if (wal != null) {
                wal.adicionar(Evento.reservaCriada(reserva));
            }

            for (Trecho trecho : trechos) {
                trecho.reservarLugar(passageiro);
            }

            reservas.put(reserva.getId(), reserva);
            return reserva.getId();
        }
    }

    public boolean prepararTx(String txId, String login, List<Trecho> trechos, long ttlMillis) {
        if (txId == null || txId.isBlank() || trechos == null || trechos.isEmpty()) {
            return false;
        }
        synchronized (this) {
            if (holds.containsKey(txId)) {
                return true;
            }
            for (Trecho trecho : trechos) {
                if (trecho == null || trecho.getLugaresDisponiveis() <= 0) {
                    return false;
                }
            }
            for (Trecho trecho : trechos) {
                trecho.setLugaresDisponiveis(trecho.getLugaresDisponiveis() - 1);
            }
            holds.put(txId, new TxHold(txId, login, trechos, System.currentTimeMillis() + Math.max(1L, ttlMillis)));
            return true;
        }
    }

    public TxHold confirmarTx(String txId) {
        if (txId == null || txId.isBlank()) {
            return null;
        }
        synchronized (this) {
            TxHold hold = holds.remove(txId);
            if (hold == null) {
                return null;
            }
            Reserva reserva = new Reserva(hold.login, hold.login, hold.trechos);
            reservas.put(reserva.getId(), reserva);
            return hold;
        }
    }

    public boolean abortarTx(String txId) {
        if (txId == null || txId.isBlank()) {
            return false;
        }
        synchronized (this) {
            TxHold hold = holds.remove(txId);
            if (hold == null) {
                return true;
            }
            for (Trecho trecho : hold.trechos) {
                if (trecho != null) {
                    trecho.setLugaresDisponiveis(trecho.getLugaresDisponiveis() + 1);
                }
            }
            return true;
        }
    }

    public boolean existeReservaLocal(String idReserva) {
        if (idReserva == null || idReserva.isBlank()) {
            return false;
        }
        return reservas.values().stream().anyMatch(r -> r.getId().equals(idReserva));
    }

    public List<Reserva> listarReservas() {
        return new ArrayList<>(reservas.values());
    }

    public boolean expirarHold(String txId) {
        synchronized (this) {
            TxHold hold = holds.get(txId);
            if (hold == null || System.currentTimeMillis() < hold.expiraEm) {
                return false;
            }
            holds.remove(txId);
            for (Trecho trecho : hold.trechos) {
                if (trecho != null) {
                    trecho.setLugaresDisponiveis(trecho.getLugaresDisponiveis() + 1);
                }
            }
            return true;
        }
    }

    public List<String> listarTxsExpiradas() {
        List<String> expiradas = new ArrayList<>();
        long agora = System.currentTimeMillis();
        for (Map.Entry<String, TxHold> entrada : holds.entrySet()) {
            if (agora >= entrada.getValue().expiraEm) {
                expiradas.add(entrada.getKey());
            }
        }
        return expiradas;
    }

    public String getTxEstado(String txId) {
        if (txId == null || txId.isBlank()) {
            return "DESCONHECIDA";
        }
        if (holds.containsKey(txId)) {
            return "PREPARADA";
        }
        return "DESCONHECIDA";
    }

    public boolean cancelarReserva(String idReserva, String login){
        Reserva reserva = reservas.get(idReserva);
        if (reserva == null) {
            return false;
        }
        synchronized (reserva) {
            if (reserva.isAtiva() && reserva.getLoginPassageiro().equals(login)) {
                if (wal != null) {
                    wal.adicionar(Evento.reservaCancelada(idReserva));
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