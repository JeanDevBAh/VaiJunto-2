package api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Verifica periodicamente (GET /saude) se os outros servidores estão no ar e
 * registra no console cada MUDANÇA de estado (ONLINE <-> OFFLINE).
 *
 * É a base da detecção de falhas por timeout exigida pelo enunciado.
 */
public class MonitorPeers {
    private final Map<String, String> peers;           // id -> url base
    private final ClienteConsorcio cliente;
    private final long intervaloMs;
    private final Map<String, Boolean> estado = new ConcurrentHashMap<>();
    private ScheduledExecutorService agendador;

    public MonitorPeers(Map<String, String> peers, ClienteConsorcio cliente, long intervaloMs) {
        this.peers = peers;
        this.cliente = cliente;
        this.intervaloMs = intervaloMs;
    }

    public void iniciar() {
        if (peers.isEmpty()) {
            return;
        }
        agendador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-peers");
            t.setDaemon(true);     // não impede o servidor de encerrar
            return t;
        });
        agendador.scheduleWithFixedDelay(this::verificarTodos, 0, intervaloMs, TimeUnit.MILLISECONDS);
    }

    public void parar() {
        if (agendador != null) {
            agendador.shutdownNow();
        }
    }

    /** Último estado conhecido. Antes da primeira verificação, devolve false. */
    public boolean estaOnline(String peerId) {
        return Boolean.TRUE.equals(estado.get(peerId));
    }

    private void verificarTodos() {
        for (Map.Entry<String, String> peer : peers.entrySet()) {
            boolean online;
            String motivo = "";
            try {
                cliente.saude(peer.getValue());
                online = true;
            } catch (RuntimeException e) {
                online = false;
                motivo = " (" + e.getMessage() + ")";
            }
            Boolean anterior = estado.put(peer.getKey(), online);
            if (anterior == null || anterior != online) {
                System.out.println("[PEERS] " + peer.getKey() + " " + (online ? "ONLINE" : "OFFLINE") + motivo);
            }
        }
    }
}
