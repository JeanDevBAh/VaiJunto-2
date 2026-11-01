package api;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import controller.GegenciarGeral;
import model.Cidades;
import model.Trecho;

/**
 * API REST que um servidor oferece aos DEMAIS servidores do consórcio.
 */
public class ServidorApi {
    public static final String PREFIXO = "/api/v1";

    private final String empresaId;
    private final String consorcioToken;
    private final GegenciarGeral gerenciador;
    private final HttpServer servidor;
    private final Gson gson = new Gson();

    public ServidorApi(int porta, String empresaId, GegenciarGeral gerenciador) throws IOException {
        this(porta, empresaId, System.getenv().getOrDefault("CONSORCIO_TOKEN", "vaijunto-local-token"), gerenciador);
    }

    public ServidorApi(int porta, String empresaId, String consorcioToken, GegenciarGeral gerenciador) throws IOException {
        this.empresaId = empresaId;
        this.consorcioToken = consorcioToken == null || consorcioToken.isBlank() ? "vaijunto-local-token" : consorcioToken;
        this.gerenciador = gerenciador;
        this.servidor = HttpServer.create(new InetSocketAddress(porta), 0);
        this.servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.servidor.createContext(PREFIXO + "/saude", this::tratarSaude);
        this.servidor.createContext(PREFIXO + "/trechos", this::tratarTrechos);
        this.servidor.createContext(PREFIXO + "/reservas", this::tratarReservas);
        this.servidor.createContext(PREFIXO + "/reservas/cancelar", this::tratarCancelarReserva);
        this.servidor.createContext(PREFIXO + "/reservas/estado", this::tratarEstadoReserva);
        this.servidor.createContext(PREFIXO + "/tx", this::tratarTx);
        this.servidor.createContext("/", ex -> responder(ex, 404, erro("Caminho desconhecido")));
    }

    public void iniciar() { servidor.start(); }
    public void parar() { servidor.stop(0); }
    public int getPorta() { return servidor.getAddress().getPort(); }

    private void tratarSaude(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            responder(ex, 405, erro("Use GET"));
            return;
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("empresaId", empresaId);
        corpo.put("status", "OK");
        responder(ex, 200, corpo);
    }

    private void tratarTrechos(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            responder(ex, 405, erro("Use GET"));
            return;
        }
        String data = parametro(ex.getRequestURI().getRawQuery(), "data");
        if (data == null || data.isBlank()) {
            responder(ex, 400, erro("Parâmetro obrigatório: data"));
            return;
        }
        List<TrechoRef> trechos = new ArrayList<>();
        for (Trecho t : gerenciador.getTrechosData(data)) {
            trechos.add(TrechoRef.de(t));
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("empresaId", empresaId);
        corpo.put("data", data);
        corpo.put("trechos", trechos);
        responder(ex, 200, corpo);
    }

    private void tratarReservas(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            responder(ex, 405, erro("Use POST"));
            return;
        }
        try {
            JsonObject body = gson.fromJson(leitura(ex), JsonObject.class);
            String login = body.has("passageiroLogin") ? body.get("passageiroLogin").getAsString() : null;
            JsonArray trechos = body.getAsJsonArray("trechos");
            if (login == null || login.isBlank() || trechos == null || trechos.size() == 0) {
                responder(ex, 400, erro("passageiroLogin e trechos são obrigatórios."));
                return;
            }
            List<TrechoRef> refs = new ArrayList<>();
            for (int i = 0; i < trechos.size(); i++) {
                refs.add(gson.fromJson(trechos.get(i), TrechoRef.class));
            }
            String idReserva = gerenciador.reservarTrechosRemotos(login, refs);
            responder(ex, 200, Map.of("sucesso", true, "idReserva", idReserva, "mensagem", "Reserva confirmada no peer."));
        } catch (JsonParseException | IllegalArgumentException e) {
            responder(ex, 400, erro("Payload inválido: " + e.getMessage()));
        } catch (RuntimeException e) {
            responder(ex, 409, erro(e.getMessage()));
        }
    }

    private void tratarCancelarReserva(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            responder(ex, 405, erro("Use POST"));
            return;
        }
        try {
            JsonObject body = gson.fromJson(leitura(ex), JsonObject.class);
            String login = body.has("passageiroLogin") ? body.get("passageiroLogin").getAsString() : null;
            String idReserva = body.has("idReserva") ? body.get("idReserva").getAsString() : null;
            if (login == null || login.isBlank() || idReserva == null || idReserva.isBlank()) {
                responder(ex, 400, erro("passageiroLogin e idReserva são obrigatórios."));
                return;
            }
            boolean sucesso = gerenciador.cancelarReservaRemota(login, idReserva);
            responder(ex, 200, Map.of("sucesso", sucesso, "mensagem", sucesso ? "Reserva cancelada no peer." : "Reserva inexistente ou não pertence a este usuário."));
        } catch (JsonParseException | IllegalArgumentException e) {
            responder(ex, 400, erro("Payload inválido: " + e.getMessage()));
        }
    }

    private void tratarEstadoReserva(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            responder(ex, 405, erro("Use GET"));
            return;
        }
        String idReserva = parametro(ex.getRequestURI().getRawQuery(), "idReserva");
        String login = parametro(ex.getRequestURI().getRawQuery(), "login");
        if (idReserva == null || idReserva.isBlank()) {
            responder(ex, 400, erro("Parâmetro obrigatório: idReserva"));
            return;
        }
        String status = gerenciador.getStatusReservaRemota(login, idReserva);
        responder(ex, 200, Map.of("status", status, "idReserva", idReserva));
    }

    private void tratarTx(HttpExchange ex) throws IOException {
        if (!autorizado(ex)) {
            responder(ex, 401, erro("Token inválido ou ausente."));
            return;
        }

        String path = ex.getRequestURI().getPath();
        String prefixo = PREFIXO + "/tx/";
        if (!path.startsWith(prefixo)) {
            responder(ex, 404, erro("Rota de transação inválida."));
            return;
        }

        String restante = path.substring(prefixo.length());
        String txId = restante.contains("/") ? restante.substring(0, restante.indexOf('/')) : restante;
        if (txId == null || txId.isBlank()) {
            responder(ex, 400, erro("txId obrigatório."));
            return;
        }

        String subrota = restante.startsWith(txId + "/") ? restante.substring(txId.length() + 1) : "";
        if ("GET".equals(ex.getRequestMethod()) && (subrota.isEmpty() || "estado".equals(subrota))) {
            responder(ex, 200, Map.of("txId", txId, "estado", gerenciador.getStatusTxLocal(txId), "empresaId", empresaId));
            return;
        }

        if (!"POST".equals(ex.getRequestMethod())) {
            responder(ex, 405, erro("Use POST"));
            return;
        }

        try {
            JsonObject body = gson.fromJson(leitura(ex), JsonObject.class);
            String login = body != null && body.has("login") ? body.get("login").getAsString() : null;
            long ttlMs = body != null && body.has("ttlMs") ? body.get("ttlMs").getAsLong() : 10_000L;

            if ("prepare".equals(subrota)) {
                JsonArray trechos = body == null || !body.has("trechos") ? null : body.getAsJsonArray("trechos");
                if (login == null || login.isBlank() || trechos == null || trechos.size() == 0) {
                    responder(ex, 400, erro("login e trechos são obrigatórios para prepare."));
                    return;
                }
                List<Trecho> trechosLocal = new ArrayList<>();
                for (int i = 0; i < trechos.size(); i++) {
                    JsonObject trechoJson = trechos.get(i).getAsJsonObject();
                    Trecho trecho = gerenciador.getTrechoPorReferencia(
                            trechoJson.get("caronaId").getAsString(),
                            Cidades.fromString(trechoJson.get("origem").getAsString()),
                            Cidades.fromString(trechoJson.get("destino").getAsString()));
                    if (trecho == null) {
                        responder(ex, 409, erro("Trecho não encontrado no participante: " + trechoJson.get("caronaId").getAsString()));
                        return;
                    }
                    trechosLocal.add(trecho);
                }
                boolean ok = gerenciador.prepararTxLocal(txId, login, trechosLocal, ttlMs);
                responder(ex, ok ? 200 : 409, Map.of("txId", txId, "sucesso", ok, "estado", ok ? "PREPARADA" : "ABORT", "empresaId", empresaId));
                return;
            }

            if ("commit".equals(subrota)) {
                boolean ok = gerenciador.confirmarTxLocal(txId);
                responder(ex, ok ? 200 : 409, Map.of("txId", txId, "sucesso", ok, "estado", ok ? "COMMIT" : "DESCONHECIDA", "empresaId", empresaId));
                return;
            }

            if ("abort".equals(subrota)) {
                boolean ok = gerenciador.abortarTxLocal(txId, "ABORT_PEER");
                responder(ex, ok ? 200 : 409, Map.of("txId", txId, "sucesso", ok, "estado", ok ? "ABORT" : "DESCONHECIDA", "empresaId", empresaId));
                return;
            }

            responder(ex, 404, erro("Rota de transação inválida: " + subrota));
        } catch (IllegalArgumentException | JsonParseException e) {
            responder(ex, 400, erro("Payload inválido: " + e.getMessage()));
        }
    }

    private boolean autorizado(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst(ClienteConsorcio.HEADER_TOKEN);
        return token != null && token.equals(consorcioToken);
    }

    private static String leitura(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> erro(String mensagem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("erro", mensagem);
        return m;
    }

    private static String parametro(String queryBruta, String nome) {
        if (queryBruta == null) {
            return null;
        }
        for (String par : queryBruta.split("&")) {
            String[] kv = par.split("=", 2);
            if (kv.length == 2 && kv[0].equals(nome)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void responder(HttpExchange ex, int codigo, Object corpo) throws IOException {
        byte[] bytes = gson.toJson(corpo).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
