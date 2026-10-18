package client;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;
import java.util.Objects;

import model.TipoUser;
import network.DTORequest;
import network.DTOResponse;
import network.JsonUtil;

/**
 * Cliente TCP utilizado pela aplicação JavaFX para acessar o servidor.
 *
 * Encapsula a criação do socket, os streams de entrada e saída e o envio de
 * requisições JSON.
 *
 * Disponibiliza métodos de alto nível para todas as operações do protocolo,
 * como login, cadastro, oferta de carona, busca de viagens e reservas.
 *
 * A conexão possui timeout de leitura e é mantida aberta durante a sessão do
 * usuário, pois o servidor associa o resultado da última busca à conexão.
 */
public final class VaiJuntoClient implements Closeable {
    private static final String SERVER_HOST_ENV = "VAIJUNTO_SERVER_HOST";
    private static final String SERVER_PORT_ENV = "VAIJUNTO_SERVER_PORT";
    public static final String DEFAULT_HOST = obterHostPadrao();
    public static final int DEFAULT_PORT = obterPortaPadrao();
    private static final int DEFAULT_TIMEOUT = 10_000;

    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    public VaiJuntoClient() throws IOException{
        this(DEFAULT_HOST,DEFAULT_PORT,DEFAULT_TIMEOUT);
    }

    public VaiJuntoClient(String host, int port) throws IOException {
        this(host, port, DEFAULT_TIMEOUT);
    }

    public VaiJuntoClient(String host, int port, int timeoutMillis) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("O endereço do servidor não pode ser vazio.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("A porta deve estar entre 1 e 65535.");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("O timeout deve ser positivo.");
        }
        socket = new Socket(host, port);
        socket.setSoTimeout(timeoutMillis);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);
    }

    private static String obterHostPadrao() {
        String host = System.getenv(SERVER_HOST_ENV);
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    private static int obterPortaPadrao() {
        String porta = System.getenv(SERVER_PORT_ENV);
        if (porta == null || porta.isBlank()) {
            return 8080;
        }
        try {
            return Integer.parseInt(porta);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "A variável " + SERVER_PORT_ENV + " deve conter uma porta numérica.",
                    exception);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized DTOResponse enviar(DTORequest request) throws IOException {
        Objects.requireNonNull(request, "A requisição não pode ser nula.");
        writer.println(JsonUtil.paraJson(request));
        if (writer.checkError()) {
            throw new IOException("Não foi possível enviar a requisição ao servidor.");
        }
        String resposta = reader.readLine();
        if (resposta == null) {
            throw new IOException("O servidor encerrou a conexão sem responder.");
        }
        try {
            return JsonUtil.paraObject(resposta, DTOResponse.class);
        } catch (RuntimeException exception) {
            throw new IOException("Resposta inválida recebida do servidor.", exception);
        }
    }

    /**===================================
     * Teste de cominicação com o servidor
     * ===================================
     */
    public DTOResponse<?> ping() throws IOException {
        return enviar(new DTORequest("PING"));
    }

    /**===================================
     * Cliente Geral
     * ===================================
     */
    public DTOResponse<?> cadastro(String login, String senha, TipoUser tipoUser) throws IOException{
        DTORequest request = new DTORequest("CADASTRAR");
        request.setLogin(Objects.requireNonNull(login));
        request.setSenha(Objects.requireNonNull(senha));
        request.setTipoUser(Objects.requireNonNull(tipoUser));
        return enviar(request);
    }

    public DTOResponse<?> login(String login, String senha) throws IOException{
        DTORequest request = new DTORequest("LOGIN");
        request.setLogin(Objects.requireNonNull(login));
        request.setSenha(Objects.requireNonNull(senha));
        return enviar(request);
    }

    public DTOResponse<?> logout(String token) throws IOException{
        DTORequest request = new DTORequest("LOGOUT");
        request.setToken(Objects.requireNonNull(token));
        return enviar(request);
    }

    public DTOResponse<?> mudarTipo(String token, TipoUser tipoUser) throws IOException{
        DTORequest request = new DTORequest("MUDAR_TIPO_USUARIO");
        request.setToken(Objects.requireNonNull(token));
        request.setTipoUser(tipoUser);
        return enviar(request);
    }


    /**===================================
     * Cliente Motorista
     * ===================================
     */
    public DTOResponse<?> oferecerCarona(String token, String data, String hora,
                List<String> rota, List<Double> precos, Integer vagas) throws IOException{
        DTORequest request = new DTORequest("OFERECER_CARONA");
        request.setRota(List.copyOf(rota));
        request.setData(Objects.requireNonNull(data));
        request.setPrecos(List.copyOf(precos));
        request.setHora(Objects.requireNonNull(hora));
        request.setToken(Objects.requireNonNull(token));
        request.setAtivaOuNao(true);
        request.setVagas(Objects.requireNonNull(vagas));
        return enviar(request);
    }
    public DTOResponse<?> minhasCaronas(String token) throws IOException{
        DTORequest request = new DTORequest("MINHAS_CARONAS");
        request.setToken(Objects.requireNonNull(token));
        return enviar(request);
    }
    public DTOResponse<?> consultaPassageiros(String token, String idCarona) throws IOException{
        DTORequest request = new DTORequest("CONSULTA_PASSAGEIROS");
        request.setToken(Objects.requireNonNull(token));
        request.setIdCarona(Objects.requireNonNull(idCarona));
        return enviar(request);
    }
    public DTOResponse<?> cancelaCarona(String token, String idCarona) throws IOException{
        DTORequest request = new DTORequest("CANCELAR_CARONA");
        request.setToken(Objects.requireNonNull(token));
        request.setIdCarona(Objects.requireNonNull(idCarona));
        return enviar(request);
    }


    /**===================================
     * Cliente Passageiro
     * ===================================
     */
    public DTOResponse<?> buscarViagens(String origem, String destino, String data) throws IOException {
        DTORequest request = new DTORequest("BUSCAR_VIAGENS");
        request.setOrigem(Objects.requireNonNull(origem, "Origem obrigatória seu animal"));
        request.setDestino(Objects.requireNonNull(destino, "Destino obrigatório"));
        request.setData(Objects.requireNonNull(data, "informe da data"));
        return enviar(request);
    }
    public DTOResponse<?> reservar(Integer indiceItinerario, String token) throws IOException{
        DTORequest request = new DTORequest("RESERVAR");
        request.setIndiceItinerario(Objects.requireNonNull(indiceItinerario));
        request.setToken(Objects.requireNonNull(token));
        return enviar(request);
    }
    public DTOResponse<?> cancelaReserva(String token, String idReserva) throws IOException{
        DTORequest request = new DTORequest("CANCELAR_RESERVA");
        request.setIdReserva(Objects.requireNonNull(idReserva));
        request.setToken(Objects.requireNonNull(token));
        return enviar(request);
    }
    public DTOResponse<?> minhasReservas(String token) throws IOException {
        DTORequest request = new DTORequest("MINHAS_RESERVAS");
        request.setToken(Objects.requireNonNull(token));
        return enviar(request);
    }
    
    @Override
    public void close() throws IOException {
        socket.close();}

}
