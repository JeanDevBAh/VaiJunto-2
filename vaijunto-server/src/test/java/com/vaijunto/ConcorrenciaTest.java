package com.vaijunto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import model.TipoUser;
import network.DTORequest;
import network.DTOResponse;
import network.JsonUtil;
import server.ClientHandler;

public class ConcorrenciaTest {
    private ServidorDeTeste servidor;

    @Before
    public void iniciarServidor() throws IOException {
        servidor = new ServidorDeTeste();
        servidor.iniciar();
    }

    @After
    public void pararServidor() throws IOException {
        servidor.close();
    }

    @Test
    public void deveCadastrarUsuariosDiferentesSimultaneamente() throws Exception {
        int quantidade = 100;
        ExecutorService executor = Executors.newFixedThreadPool(quantidade);
        CountDownLatch inicio = new CountDownLatch(1);
        List<Future<DTOResponse<?>>> tarefas = new ArrayList<>();

        try {
            for (int i = 0; i < quantidade; i++) {
                final int indice = i;
                tarefas.add(executor.submit(() -> {
                    try (ClienteDeTeste client = novoCliente()) {
                        inicio.await();
                        return client.cadastro("concorrente-" + indice, "senha", TipoUser.PASSAGEIRO);
                    }
                }));
            }

            inicio.countDown();
            int sucessos = 0;
            for (Future<DTOResponse<?>> tarefa : tarefas) {
                if (tarefa.get(10, TimeUnit.SECONDS).isSucesso()) {
                    sucessos++;
                }
            }
            assertEquals(quantidade, sucessos);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void deveAceitarSomenteUmCadastroDoMesmoLogin() throws Exception {
        int quantidade = 20;
        ExecutorService executor = Executors.newFixedThreadPool(quantidade);
        CountDownLatch inicio = new CountDownLatch(1);
        List<Future<DTOResponse<?>>> tarefas = new ArrayList<>();

        try {
            for (int i = 0; i < quantidade; i++) {
                tarefas.add(executor.submit(() -> {
                    try (ClienteDeTeste client = novoCliente()) {
                        inicio.await();
                        return client.cadastro("login-compartilhado", "senha", TipoUser.PASSAGEIRO);
                    }
                }));
            }

            inicio.countDown();
            int sucessos = 0;
            for (Future<DTOResponse<?>> tarefa : tarefas) {
                if (tarefa.get(10, TimeUnit.SECONDS).isSucesso()) {
                    sucessos++;
                }
            }
            assertEquals(1, sucessos);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void reservasSimultaneasNaoPodemExcederAsVagas() throws Exception {
        int vagas = 3;
        int quantidadePassageiros = 12;
        String data = "2099-12-31";

        try (ClienteDeTeste motorista = novoCliente()) {
            assertTrue(motorista.cadastro("motorista-concorrente", "senha", TipoUser.MOTORISTA).isSucesso());
            String tokenMotorista = fazerLogin(motorista, "motorista-concorrente");
            DTOResponse<?> oferta = motorista.oferecerCarona(
                    tokenMotorista,
                    data,
                    "10:00",
                    List.of("VALENTINE", "RHODES"),
                    List.of(25.0),
                    vagas);
            assertTrue(oferta.getMensagem(), oferta.isSucesso());
        }

        ExecutorService executor = Executors.newFixedThreadPool(quantidadePassageiros);
        CountDownLatch buscasConcluidas = new CountDownLatch(quantidadePassageiros);
        CountDownLatch iniciarReservas = new CountDownLatch(1);
        List<Future<Boolean>> tarefas = new ArrayList<>();

        try {
            for (int i = 0; i < quantidadePassageiros; i++) {
                final int indice = i;
                tarefas.add(executor.submit(() -> {
                            try (ClienteDeTeste passageiro = novoCliente()) {
                        String login = "passageiro-concorrente-" + indice;
                        assertTrue(passageiro.cadastro(login, "senha", TipoUser.PASSAGEIRO).isSucesso());
                        String token = fazerLogin(passageiro, login);
                        DTOResponse<?> busca = passageiro.buscarViagens("VALENTINE", "RHODES", data);
                        assertTrue(busca.getMensagem(), busca.isSucesso());
                        assertNotNull(busca.getDados());
                        buscasConcluidas.countDown();
                        iniciarReservas.await();
                        return passageiro.reservar(0, token).isSucesso();
                    }
                }));
            }

            assertTrue("Nem todos os clientes concluíram a busca",
                    buscasConcluidas.await(15, TimeUnit.SECONDS));
            iniciarReservas.countDown();

            int reservasConfirmadas = 0;
            for (Future<Boolean> tarefa : tarefas) {
                if (tarefa.get(15, TimeUnit.SECONDS)) {
                    reservasConfirmadas++;
                }
            }
            assertEquals(vagas, reservasConfirmadas);
        } finally {
            iniciarReservas.countDown();
            executor.shutdownNow();
        }
    }

    private ClienteDeTeste novoCliente() throws IOException {
        return new ClienteDeTeste("127.0.0.1", servidor.getPorta());
    }

    private static String fazerLogin(ClienteDeTeste client, String login) throws IOException {
        DTOResponse<?> resposta = client.login(login, "senha");
        assertTrue(resposta.getMensagem(), resposta.isSucesso());
        assertTrue(resposta.getDados() instanceof Map<?, ?>);
        Map<?, ?> dados = (Map<?, ?>) resposta.getDados();
        assertNotNull(dados.get("token"));
        return String.valueOf(dados.get("token"));
    }

    private static final class ClienteDeTeste implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final PrintWriter writer;

        private ClienteDeTeste(String host, int porta) throws IOException {
            socket = new Socket(host, porta);
            socket.setSoTimeout(10_000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);
        }

        private DTOResponse<?> cadastro(String login, String senha, TipoUser tipo) throws IOException {
            DTORequest request = request("CADASTRAR");
            request.setLogin(login);
            request.setSenha(senha);
            request.setTipoUser(tipo);
            return enviar(request);
        }

        private DTOResponse<?> login(String login, String senha) throws IOException {
            DTORequest request = request("LOGIN");
            request.setLogin(login);
            request.setSenha(senha);
            return enviar(request);
        }

        private DTOResponse<?> oferecerCarona(String token, String data, String hora,
                List<String> rota, List<Double> precos, int vagas) throws IOException {
            DTORequest request = request("OFERECER_CARONA");
            request.setToken(token);
            request.setData(data);
            request.setHora(hora);
            request.setRota(rota);
            request.setPrecos(precos);
            request.setVagas(vagas);
            request.setAtivaOuNao(true);
            return enviar(request);
        }

        private DTOResponse<?> buscarViagens(String origem, String destino, String data)
                throws IOException {
            DTORequest request = request("BUSCAR_VIAGENS");
            request.setOrigem(origem);
            request.setDestino(destino);
            request.setData(data);
            return enviar(request);
        }

        private DTOResponse<?> reservar(int indice, String token) throws IOException {
            DTORequest request = request("RESERVAR");
            request.setIndiceItinerario(indice);
            request.setToken(token);
            return enviar(request);
        }

        private DTOResponse<?> enviar(DTORequest request) throws IOException {
            writer.println(JsonUtil.paraJson(request));
            String resposta = reader.readLine();
            if (resposta == null) {
                throw new IOException("Servidor encerrou a conexão.");
            }
            return JsonUtil.paraObject(resposta, DTOResponse.class);
        }

        private static DTORequest request(String acao) {
            DTORequest request = new DTORequest();
            request.setAcao(acao);
            return request;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class ServidorDeTeste implements AutoCloseable {
        private final ServerSocket socket;
        private final controller.GegenciarGeral gerenciador = new controller.GegenciarGeral();
        private final ExecutorService clientes = Executors.newCachedThreadPool();
        private volatile boolean executando;
        private Thread aceitador;

        private ServidorDeTeste() throws IOException {
            socket = new ServerSocket(0);
        }

        private int getPorta() {
            return socket.getLocalPort();
        }

        private void iniciar() {
            executando = true;
            aceitador = new Thread(() -> {
                while (executando) {
                    try {
                        Socket cliente = socket.accept();
                        clientes.submit(new ClientHandler(cliente, gerenciador));
                    } catch (IOException erro) {
                        if (executando) {
                            throw new RuntimeException("Falha no servidor de teste", erro);
                        }
                    }
                }
            }, "servidor-de-teste-aceitador");
            aceitador.setDaemon(true);
            aceitador.start();
        }

        @Override
        public void close() throws IOException {
            executando = false;
            socket.close();
            clientes.shutdownNow();
        }
    }
}
