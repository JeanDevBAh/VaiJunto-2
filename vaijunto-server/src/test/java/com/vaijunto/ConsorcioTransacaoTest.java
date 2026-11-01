package com.vaijunto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import api.ClienteConsorcio;
import api.MonitorPeers;
import api.ServidorApi;
import controller.GegenciarGeral;
import model.Cidades;
import model.Itinerario;
import model.TipoUser;
import model.Trecho;
import wal.Wal;

public class ConsorcioTransacaoTest {
    private static final String TOKEN = "vaijunto-local-token";
    private final List<ServidorNode> nodes = new ArrayList<>();

    @Before
    public void iniciarConsorcio() throws Exception {
        nodes.clear();
        Map<String, String> peersA = new LinkedHashMap<>();
        Map<String, String> peersB = new LinkedHashMap<>();
        Map<String, String> peersC = new LinkedHashMap<>();

        ServidorNode a = novoNo("EMPRESA_A", peersA);
        ServidorNode b = novoNo("EMPRESA_B", peersB);
        ServidorNode c = novoNo("EMPRESA_C", peersC);

        peersA.put("EMPRESA_B", b.baseUrl);
        peersA.put("EMPRESA_C", c.baseUrl);
        peersB.put("EMPRESA_A", a.baseUrl);
        peersB.put("EMPRESA_C", c.baseUrl);
        peersC.put("EMPRESA_A", a.baseUrl);
        peersC.put("EMPRESA_B", b.baseUrl);

        a.geral = new GegenciarGeral(new Wal(a.walPath), "EMPRESA_A", peersA);
        b.geral = new GegenciarGeral(new Wal(b.walPath), "EMPRESA_B", peersB);
        c.geral = new GegenciarGeral(new Wal(c.walPath), "EMPRESA_C", peersC);

        a.api = new ServidorApi(a.portaHttp, "EMPRESA_A", TOKEN, a.geral);
        b.api = new ServidorApi(b.portaHttp, "EMPRESA_B", TOKEN, b.geral);
        c.api = new ServidorApi(c.portaHttp, "EMPRESA_C", TOKEN, c.geral);

        a.api.iniciar();
        b.api.iniciar();
        c.api.iniciar();

        a.geral.setMonitorPeers(null);
        b.geral.setMonitorPeers(null);
        c.geral.setMonitorPeers(null);

        nodes.add(a);
        nodes.add(b);
        nodes.add(c);
    }

    @After
    public void fecharConsorcio() {
        for (ServidorNode node : nodes) {
            if (node.monitor != null) {
                node.monitor.parar();
            }
            if (node.api != null) {
                node.api.parar();
            }
        }
    }

    @Test
    public void deveConfirmarApenasUmUltimoAssentoEmConcorrencia() throws Exception {
        ServidorNode empresaA = nodes.get(0);
        ServidorNode empresaB = nodes.get(1);

        assertTrue(empresaB.geral.cadastrarUsuario("motorista-ultimo-assento", "senha", TipoUser.MOTORISTA));
        String tokenMotorista = empresaB.geral.fazerLogin("motorista-ultimo-assento", "senha");
        String idCarona = empresaB.geral.oferecerCarona(
                tokenMotorista,
                List.of(Cidades.VALENTINE, Cidades.RHODES),
                "2099-12-31",
                "10:00",
                1,
                true,
                List.of(25.0));
        assertNotNull(idCarona);

        List<Itinerario> itinerarios = empresaA.geral.buscarViagens(Cidades.VALENTINE, Cidades.RHODES, "2099-12-31");
        assertFalse("Deveria encontrar a rota da empresa B", itinerarios.isEmpty());

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch inicio = new CountDownLatch(1);
        List<Future<String>> tarefas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int indice = i;
            tarefas.add(executor.submit(() -> {
                String login = "passageiro-ultimo-" + indice;
                empresaA.geral.cadastrarUsuario(login, "senha", TipoUser.PASSAGEIRO);
                String token = empresaA.geral.fazerLogin(login, "senha");
                inicio.await();
                return empresaA.geral.reservarViagem(token, itinerarios.get(0));
            }));
        }

        inicio.countDown();
        int confirmados = 0;
        for (Future<String> tarefa : tarefas) {
            String resultado = tarefa.get(20, TimeUnit.SECONDS);
            if (resultado != null && !resultado.isBlank()) {
                confirmados++;
            }
        }
        executor.shutdownNow();

        assertEquals("Deveria haver exatamente 1 assento confirmado para a última vaga.", 1, confirmados);
    }

    @Test
    public void deveSerIdempotenteParaPrepareCommitAbort() throws Exception {
        ServidorNode empresaA = nodes.get(0);

        assertTrue(empresaA.geral.cadastrarUsuario("motorista-idempotente", "senha", TipoUser.MOTORISTA));
        String tokenMotorista = empresaA.geral.fazerLogin("motorista-idempotente", "senha");
        String idCarona = empresaA.geral.oferecerCarona(
                tokenMotorista,
                List.of(Cidades.VALENTINE, Cidades.RHODES),
                "2099-12-31",
                "11:00",
                2,
                true,
                List.of(18.0));
        assertNotNull(idCarona);

        List<Itinerario> itinerarios = empresaA.geral.buscarViagens(Cidades.VALENTINE, Cidades.RHODES, "2099-12-31");
        assertFalse(itinerarios.isEmpty());

        String login = "passageiro-idempotente";
        assertTrue(empresaA.geral.cadastrarUsuario(login, "senha", TipoUser.PASSAGEIRO));
        String token = empresaA.geral.fazerLogin(login, "senha");
        String txId = empresaA.geral.reservarViagem(token, itinerarios.get(0));
        assertNotNull(txId);

        String estadoDepois = empresaA.geral.getStatusTxLocal(txId);
        assertTrue("Estado inesperado: " + estadoDepois,
                "COMMIT".equalsIgnoreCase(estadoDepois) || "RETRY".equalsIgnoreCase(estadoDepois));

        boolean okPrepare = empresaA.geral.prepararTxLocal(txId, login, itinerarios.get(0).getTrechos(), 5_000L);
        boolean okCommit = empresaA.geral.confirmarTxLocal(txId);
        boolean okAbort = empresaA.geral.abortarTxLocal(txId, "idempotencia");

        assertTrue(okPrepare);
        assertTrue(okCommit);
        assertTrue(okAbort);
    }

    @Test
    public void deveReconstituirEstadoDaTxAoReabrirOWal() throws Exception {
        Path tmp = Files.createTempDirectory("vaijunto-recovery-");
        String walPath = tmp.resolve("wal.log").toString();

        GegenciarGeral empresa = new GegenciarGeral(new wal.Wal(walPath), "EMPRESA_A", new LinkedHashMap<>());
        assertTrue(empresa.cadastrarUsuario("motorista-recovery", "senha", TipoUser.MOTORISTA));
        String tokenMotorista = empresa.fazerLogin("motorista-recovery", "senha");
        String caronaId = empresa.oferecerCarona(
                tokenMotorista,
                List.of(Cidades.VALENTINE, Cidades.RHODES),
                "2099-12-31",
                "12:00",
                2,
                true,
                List.of(30.0));
        assertNotNull(caronaId);

        assertTrue(empresa.cadastrarUsuario("passageiro-recovery", "senha", TipoUser.PASSAGEIRO));
        String tokenPassageiro = empresa.fazerLogin("passageiro-recovery", "senha");
        List<Itinerario> itinerarios = empresa.buscarViagens(Cidades.VALENTINE, Cidades.RHODES, "2099-12-31");
        assertFalse(itinerarios.isEmpty());

        String txId = empresa.reservarViagem(tokenPassageiro, itinerarios.get(0));
        assertNotNull(txId);

        GegenciarGeral reiniciada = new GegenciarGeral(new wal.Wal(walPath), "EMPRESA_A", new LinkedHashMap<>());
        String estado = reiniciada.getStatusTxLocal(txId);
        assertTrue("Taxa não recuperada do WAL: " + estado,
                "COMMIT".equalsIgnoreCase(estado) || "RETRY".equalsIgnoreCase(estado) || "INICIADA".equalsIgnoreCase(estado));
    }

    private ServidorNode novoNo(String empresaId, Map<String, String> peers) throws IOException {
        int portaHttp = acharPortaLivre();
        Path dataDir = Files.createTempDirectory("vaijunto-test-" + empresaId.toLowerCase());
        String walPath = dataDir.resolve("wal.log").toString();
        return new ServidorNode(empresaId, portaHttp, walPath, peers, null, null, null, null);
    }

    private int acharPortaLivre() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class ServidorNode {
        final String empresaId;
        final int portaHttp;
        final String baseUrl;
        final String walPath;
        final Map<String, String> peers;
        GegenciarGeral geral;
        ServidorApi api;
        MonitorPeers monitor;

        ServidorNode(String empresaId, int portaHttp, String walPath, Map<String, String> peers,
                     GegenciarGeral geral, ServidorApi api, MonitorPeers monitor, String baseUrl) {
            this.empresaId = empresaId;
            this.portaHttp = portaHttp;
            this.walPath = walPath;
            this.peers = peers;
            this.geral = geral;
            this.api = api;
            this.monitor = monitor;
            this.baseUrl = "http://127.0.0.1:" + portaHttp;
        }
    }
}
