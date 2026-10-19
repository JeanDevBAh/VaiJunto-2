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
import java.util.List;

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
