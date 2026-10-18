package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonSyntaxException;

import controller.GegenciarGeral;
import model.Carona;
import model.Cidades;
import model.Itinerario;
import model.TipoUser;
import network.DTORequest;
import network.DTOResponse;
import network.JsonUtil;


/**
 * Processa a comunicação entre um cliente e o servidor.
 *
 * Cada instância controla uma conexão TCP específica, lendo requisições JSON
 * linha a linha e enviando uma resposta para cada requisição recebida.
 *
 * A classe também interpreta o campo "acao" do DTORequest e encaminha cada
 * operação para o serviço correspondente, realizando validações de entrada,
 * autenticação por token e tratamento de erros.
 *
 * A variável ultimabusca mantém os itinerários retornados pela última busca
 * realizada naquela conexão, permitindo que o cliente reserve um itinerário
 * utilizando seu índice.
 */
public class ClientHandler implements Runnable {
    
    private Socket socketCliente;
    private final GegenciarGeral gegenciador;
    private List<Itinerario> ultimabusca;

    public ClientHandler(Socket socketCliente, GegenciarGeral gerenciador) {
        this.socketCliente = socketCliente;
        this.gegenciador = gerenciador;
    }

    @Override
    public void run() {
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(socketCliente.getInputStream()));
            PrintWriter writer = new PrintWriter(socketCliente.getOutputStream(), true);
        ) {
            String mensagemRecebida;

            // Fica escutando enquanto o cliente estiver mandando mensagens na mesma conexão
            while ((mensagemRecebida = reader.readLine()) != null) {
                
                // 1. Transforma o JSON recebido da rede em um DTORequest Java
                DTORequest request;
                try {
                    request = JsonUtil.paraObject(mensagemRecebida, DTORequest.class);
                } catch (JsonSyntaxException | NumberFormatException e) {
                    writer.println(JsonUtil.paraJson(
                            new DTOResponse<>(false, "JSON inválido: " + e.getMessage())));
                    continue;
                }
                
                // 2. Processa a requisição (Note o <?> aqui)
                DTOResponse<?> response;
                try {
                    response = processarRequisicao(request);
                } catch (java.io.UncheckedIOException e) {
                    // Não conseguiu gravar no log: a operação NÃO foi aplicada. Avisa o cliente e mantém a conexão.
                    System.out.println("[SERVER] Falha ao gravar no WAL: " + e.getMessage());
                    response = new DTOResponse<>(false, "Falha interna ao gravar dados no servidor.");
                }

                // 3. Converte a resposta em JSON e envia de volta pelo Socket
                String jsonResposta = JsonUtil.paraJson(response);
                writer.println(jsonResposta);
            }

        } catch (IOException e) {
            System.out.println("Erro na conexão com o cliente: " + e.getMessage());
        } finally {
            try {
                socketCliente.close();
                System.out.println("Conexão encerrada com o cliente.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private DTOResponse<?> processarRequisicao(DTORequest request) {
        if (request == null || request.getAcao() == null) {
            return new DTOResponse<>(false, "Requisição inválida ou sem ação definida.");
        }

        switch (request.getAcao().trim().toUpperCase()) {
            case "PING":
                return new DTOResponse<>(true, "Pong! Servidor VaiJunto online.");
            case "BUSCAR_VIAGENS":
                return tratarBuscarViagens(request);
            case "CADASTRAR":
                return tratarCadastro(request);
            case "LOGIN":
                return tratarLogin(request);
            case "LOGOUT":
                return tratarLogout(request);
            case "MUDAR_TIPO_USUARIO":
                return tratarMudanca(request);
            case "OFERECER_CARONA":
                return tratarOfertaCarona(request);
            case "MINHAS_CARONAS":
                return tratarMinhasCaronas(request);
            case "CONSULTA_PASSAGEIROS":
                return tratarConsultaPassageiros(request);
            case "CANCELAR_CARONA":
                return tratarCancelarCarona(request);
            case "RESERVAR":
                return tratarReservar(request);
            case "CANCELAR_RESERVA":
                return tratarCancelarReserva(request);
            case "MINHAS_RESERVAS":
                return tratarMinhasReservas(request);
            default:
                return new DTOResponse<>(false, "Ação desconhecida: " + request.getAcao());
        }
    }

    private DTOResponse<?> tratarLogout(DTORequest request) {
        if (request.getToken() == null) {
            return new DTOResponse<>(false, "Token é obrigatório.");
        }
        boolean sucesso = gegenciador.fazerLogout(request.getToken());
        return new DTOResponse<>(sucesso, sucesso ? "Logout efetuado." : "Sessão inválida ou já encerrada.");
    }


    private DTOResponse<?> tratarCadastro(DTORequest request){
        if (isBlank(request.getLogin()) || isBlank(request.getSenha()) || request.getTipoUser() == null) {
            return new DTOResponse<>(false, "Dados inválidos: Login, senha e tipo de usuário são obrigatórios.");
        }

        TipoUser tipoUser;
        try {
            tipoUser = TipoUser.valueOf(request.getTipoUser().toString().toUpperCase());
        } catch(IllegalArgumentException e) {
            return new DTOResponse<>(false, "Tipo de usuário inválido. Use MOTORISTA ou PASSAGEIRO.");       
        }
        
        boolean sucesso = gegenciador.cadastrarUsuario(request.getLogin(), request.getSenha(), request.getTipoUser());
        if (sucesso) {
            return new DTOResponse<>(true, "Usuário cadastrado com sucesso.");
        }
        return new DTOResponse<>(false, "Erro: Login já existente.");    
    }

    private DTOResponse<?> tratarCancelarCarona(DTORequest request) {
        if (isBlank(request.getToken()) || isBlank(request.getIdCarona())) {
            return new DTOResponse<>(false, "Token e idCarona são obrigatórios.");
        }
        try {
            boolean sucesso = gegenciador.cancelarCarona(request.getToken(), request.getIdCarona());
            return new DTOResponse<>(sucesso, sucesso
                    ? "Carona cancelada com sucesso."
                    : "Carona não encontrada, já cancelada ou não pertence a este motorista.");
        } catch (SecurityException e) {
            return new DTOResponse<>(false, "Usuário não autenticado.");
        }
    }

    private DTOResponse<?> tratarMinhasCaronas(DTORequest request) {
        if (isBlank(request.getToken())) {
            return new DTOResponse<>(false, "Token é obrigatório.");
        }
        try {
            List<Carona> caronas = gegenciador.listaCaronas(request.getToken());
            return new DTOResponse<>(true, "Caronas encontradas", caronas);
        } catch (SecurityException e) {
            return new DTOResponse<>(false, "Usuário não autenticado.");
        }
    }

    private DTOResponse<?> tratarMudanca(DTORequest request) {
        if (isBlank(request.getToken()) || request.getTipoUser() == null) {
            return new DTOResponse<>(false, "Token e tipoUser são obrigatórios.");
        }
        TipoUser tipo;
        try {
            tipo = TipoUser.valueOf(request.getTipoUser().toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return new DTOResponse<>(false, "tipoUser inválido. Use MOTORISTA ou PASSAGEIRO.");
        }
        boolean sucesso = gegenciador.mudarTipoUsuario(request.getToken(), tipo);
        return new DTOResponse<>(sucesso, sucesso ? "Tipo de usuário atualizado." : "Sessão inválida.");
    }

    private DTOResponse<?> tratarLogin(DTORequest request){
        if (isBlank(request.getLogin()) || isBlank(request.getSenha())) {
            return new DTOResponse<>(false, "Login e senha são obrigatórios.");
        }
        String token = gegenciador.fazerLogin(request.getLogin(), request.getSenha());
        if (token == null) {
            return new DTOResponse<>(false, "Login ou senha inválidos.");
        }
        model.Usuario usuario = gegenciador.validarSessao(token);
        Map<String, Object> dados = new java.util.HashMap<>();
        dados.put("token", token);
        dados.put("tipoUser", usuario.getTipoUser().name());
        return new DTOResponse<>(true, "Login efetuado com sucesso.", dados);
    }

    private DTOResponse<?> tratarBuscarViagens(DTORequest request){
        if (isBlank(request.getOrigem()) || isBlank(request.getDestino())
                || !isValidDate(request.getData())) {
            return new DTOResponse<>(false, "Entradas inválidas: Origem, destino e data são obrigatórios.");
        }
        
        try {
            Cidades origem = Cidades.fromString(request.getOrigem());
            Cidades destino = Cidades.fromString(request.getDestino());
            
            List<Itinerario> itinerarios = gegenciador.buscarViagens(origem, destino, request.getData());

            this.ultimabusca = itinerarios;
            return new DTOResponse<>(true, "Itinerários encontrados", itinerarios);
            
        } catch (IllegalArgumentException e) {
            return new DTOResponse<>(false, "Erro na busca: " + e.getMessage());
        }
    }
    private DTOResponse<?> tratarConsultaPassageiros(DTORequest request) {
        if (request.getToken() == null || request.getIdCarona() == null) {
            return new DTOResponse<>(false, "Token e idCarona são obrigatórios.");
        }
        try {
            // Corrigido para "gegenciador" para bater com a variável do seu ClientHandler
            Map<String, List<String>> passageiros =
                    gegenciador.consultarPassageiros(request.getToken(), request.getIdCarona());
            
            if (passageiros == null) {
                return new DTOResponse<>(false, "Carona não encontrada ou não pertence a este motorista.");
            }
            return new DTOResponse<>(true, "Passageiros confirmados por trecho.", passageiros);
        } catch (SecurityException e) {
            return new DTOResponse<>(false, "Usuário não autenticado.");
        }
    }

    private DTOResponse<?> tratarOfertaCarona(DTORequest request) {
        if (isBlank(request.getToken()) || request.getRota() == null || request.getRota().size() < 2
                || request.getPrecos() == null || !isValidDate(request.getData())
                || !isValidTime(request.getHora())
                || request.getVagas() == null || request.isAtivaOuNao() == null) {
            return new DTOResponse<>(false, "Dados incompletos para oferecer carona.");
        }
 
        List<Cidades> rota = new ArrayList<>();
        try {
            for (String nomeCidade : request.getRota()) {
                rota.add(Cidades.fromString(nomeCidade)); 
            }
        } catch (IllegalArgumentException e) {
            return new DTOResponse<>(false, "Erro na rota: " + e.getMessage());
        }
 
        try {
            String idCarona = gegenciador.oferecerCarona(
                    request.getToken(),
                    rota,
                    request.getData(),
                    request.getHora(),
                    request.getVagas(),
                    request.isAtivaOuNao(),
                    request.getPrecos()
            );
            return new DTOResponse<>(true, "Carona publicada com sucesso.", idCarona);
        } catch (SecurityException e) {
            return new DTOResponse<>(false, "Usuário não autenticado.");
        } catch (IllegalArgumentException e) {
            return new DTOResponse<>(false, "Dados da carona inválidos: " + e.getMessage());
        }
    }

    private DTOResponse<?> tratarCancelarReserva(DTORequest request) {
        if (isBlank(request.getToken()) || isBlank(request.getIdReserva())) {
            return new DTOResponse<>(false, "Token e idReserva são obrigatórios.");
        }
 
        boolean sucesso = gegenciador.cancelarReserva(request.getToken(), request.getIdReserva());
        return new DTOResponse<>(sucesso, sucesso ? "Reserva cancelada com sucesso."
                : "Não foi possível cancelar (reserva inexistente, já cancelada, de outro usuário ou sessão inválida).");
    }

    private DTOResponse<?> tratarMinhasReservas(DTORequest request) {
        if (isBlank(request.getToken())) {
            return new DTOResponse<>(false, "Token é obrigatório.");
        }
        try {
            return new DTOResponse<>(true, "Reservas encontradas",
                    gegenciador.listarReservas(request.getToken()));
        } catch (SecurityException e) {
            return new DTOResponse<>(false, "Usuário não autenticado.");
        }
    }

    private DTOResponse<?> tratarReservar(DTORequest request) {
        if (isBlank(request.getToken()) || request.getIndiceItinerario() == null) {
            return new DTOResponse<>(false, "Token e indiceItinerario são obrigatórios.");
        }
        if (ultimabusca == null) {
            return new DTOResponse<>(false, "Nenhuma busca de viagens foi feita nesta sessão ainda.");
        }
 
        int indice = request.getIndiceItinerario();
        if (indice < 0 || indice >= ultimabusca.size()) {
            return new DTOResponse<>(false, "Índice de itinerário inválido. Refaça a busca.");
        }
 
        Itinerario itinerario = ultimabusca.get(indice);
 
        try {
            String idReserva = gegenciador.reservarViagem(request.getToken(), itinerario);
            if (idReserva == null) {
                return new DTOResponse<>(false, "Um ou mais trechos ficaram indisponíveis. Refaça a busca.");
            }
            return new DTOResponse<>(true, "Reserva confirmada com sucesso.", idReserva);
        } catch (SecurityException e) {
            return new DTOResponse<>(false, "Usuário não autenticado.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isValidDate(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isValidTime(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            LocalTime.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}