package api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Faz chamadas REST aos OUTROS servidores do consórcio.
 *
 * Toda chamada tem timeout. Se o outro servidor não responder a tempo, estiver
 * fora do ar ou devolver um erro, lança PeerIndisponivelException: a queda de
 * um servidor nunca pode ser tratada como erro fatal pelos demais.
 */
public class ClienteConsorcio {
    public static final Duration TIMEOUT_PADRAO = Duration.ofSeconds(2);

    private final HttpClient http;
    private final Duration timeout;
    private final Gson gson = new Gson();

    public ClienteConsorcio() {
        this(TIMEOUT_PADRAO);
    }

    public ClienteConsorcio(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Devolve o empresaId do outro servidor, ou lança PeerIndisponivelException. */
    public String saude(String urlBase) {
        JsonObject json = gson.fromJson(get(urlBase + ServidorApi.PREFIXO + "/saude"), JsonObject.class);
        return json.get("empresaId").getAsString();
    }

    /** Trechos do outro servidor, na data, que ainda têm vaga. */
    public List<TrechoRef> listarTrechos(String urlBase, String data) {
        String url = urlBase + ServidorApi.PREFIXO + "/trechos?data="
                + URLEncoder.encode(data, StandardCharsets.UTF_8);
        JsonObject json = gson.fromJson(get(url), JsonObject.class);
        List<TrechoRef> lista = new ArrayList<>();
        json.getAsJsonArray("trechos").forEach(e -> lista.add(gson.fromJson(e, TrechoRef.class)));
        return lista;
    }

    /** Confirma uma reserva em um peer para um passageiro do consórcio. */
    public String reservarTrechos(String urlBase, String loginPassageiro, List<TrechoRef> trechos) {
        if (trechos == null || trechos.isEmpty()) {
            throw new PeerIndisponivelException("Lista de trechos vazia para reserva distribuída.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passageiroLogin", loginPassageiro);
        payload.put("trechos", trechos);
        String corpo = gson.toJson(payload);
        String url = urlBase + ServidorApi.PREFIXO + "/reservas";
        JsonObject json = gson.fromJson(post(url, corpo), JsonObject.class);
        if (!json.get("sucesso").getAsBoolean()) {
            throw new PeerIndisponivelException(json.has("mensagem")
                    ? json.get("mensagem").getAsString()
                    : "Reserva distribuída recusada pelo peer");
        }
        return json.get("idReserva").getAsString();
    }

    /** Cancela uma reserva distribuída em um peer. */
    public boolean cancelarReserva(String urlBase, String loginPassageiro, String idReserva) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passageiroLogin", loginPassageiro);
        payload.put("idReserva", idReserva);
        String corpo = gson.toJson(payload);
        String url = urlBase + ServidorApi.PREFIXO + "/reservas/cancelar";
        JsonObject json = gson.fromJson(post(url, corpo), JsonObject.class);
        return json.has("sucesso") && json.get("sucesso").getAsBoolean();
    }

    /** Consulta o estado de uma reserva remota. */
    public String consultarStatusReserva(String urlBase, String idReserva, String loginPassageiro) {
        String url = urlBase + ServidorApi.PREFIXO + "/reservas/estado?idReserva="
                + URLEncoder.encode(idReserva, StandardCharsets.UTF_8)
                + "&login=" + URLEncoder.encode(loginPassageiro, StandardCharsets.UTF_8);
        JsonObject json = gson.fromJson(get(url), JsonObject.class);
        if (json.has("status")) {
            return json.get("status").getAsString();
        }
        return "INEXISTENTE";
    }

    private String post(String url, String corpo) {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() != 200) {
                throw new PeerIndisponivelException("HTTP " + resposta.statusCode() + " em " + url);
            }
            return resposta.body();
        } catch (IOException e) {
            throw new PeerIndisponivelException("Sem resposta de " + url + " (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerIndisponivelException("Interrompido ao chamar " + url, e);
        }
    }

    private String get(String url) {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() != 200) {
                throw new PeerIndisponivelException("HTTP " + resposta.statusCode() + " em " + url);
            }
            return resposta.body();
        } catch (IOException e) {
            // cobre conexão recusada, servidor fora do ar e timeout
            throw new PeerIndisponivelException("Sem resposta de " + url + " (" + e.getClass().getSimpleName() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerIndisponivelException("Interrompido ao chamar " + url, e);
        }
    }
}
