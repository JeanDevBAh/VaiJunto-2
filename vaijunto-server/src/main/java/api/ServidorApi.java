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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import controller.GegenciarGeral;
import model.Trecho;

/**
 * API REST que um servidor oferece aos DEMAIS servidores do consórcio.
 * (Os clientes motorista/passageiro continuam usando o TCP de sempre.)
 *
 * Endpoints (prefixo /api/v1):
 *   GET /saude                 -> 200 {"empresaId":"...","status":"OK"}
 *   GET /trechos?data=VALOR    -> 200 {"empresaId":"...","data":"...","trechos":[...]}
 *                                 400 se faltar o parâmetro data
 *
 * Códigos gerais: 404 caminho desconhecido, 405 método diferente de GET.
 *
 * Usa o servidor HTTP que já vem no JDK (sem biblioteca extra), com uma
 * thread virtual por requisição.
 */
public class ServidorApi {
    public static final String PREFIXO = "/api/v1";

    private final String empresaId;
    private final GegenciarGeral gerenciador;
    private final HttpServer servidor;
    private final Gson gson = new Gson();

    /** @param porta 0 escolhe uma porta livre (útil em testes; veja getPorta()) */
    public ServidorApi(int porta, String empresaId, GegenciarGeral gerenciador) throws IOException {
        this.empresaId = empresaId;
        this.gerenciador = gerenciador;
        this.servidor = HttpServer.create(new InetSocketAddress(porta), 0); // escuta em todas as interfaces
        this.servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.servidor.createContext(PREFIXO + "/saude", this::tratarSaude);
        this.servidor.createContext(PREFIXO + "/trechos", this::tratarTrechos);
        this.servidor.createContext("/", ex -> responder(ex, 404, erro("Caminho desconhecido")));
    }

    public void iniciar() {
        servidor.start();
    }

    public void parar() {
        servidor.stop(0);
    }

    public int getPorta() {
        return servidor.getAddress().getPort();
    }

    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------

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