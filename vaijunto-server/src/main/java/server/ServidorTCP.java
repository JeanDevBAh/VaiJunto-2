package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;

import api.ClienteConsorcio;
import api.MonitorPeers;
import api.ServidorApi;
import config.Config;
import controller.GegenciarGeral;

/**
 * Classe responsável por iniciar o servidor TCP do VaiJunto.
 *
 * Abre um ServerSocket na porta TCP definida em Config e aguarda continuamente novas conexões
 * de clientes. Para cada cliente conectado, cria um ClientHandler executado
 * em uma nova thread, permitindo que vários motoristas e passageiros sejam
 * atendidos simultaneamente.
 *
 * O objeto GegenciarGeral é compartilhado entre os handlers e mantém o estado
 * central das sessões, caronas, trechos e reservas.
 */
public class ServidorTCP {

    public static void main(String[] args) {
        Config config = Config.doAmbiente();
        System.out.println("[SERVER] " + config);

        String arquivoWal = Paths.get(config.dataDir, "wal.log").toString();
        GegenciarGeral gerenciador = new GegenciarGeral(arquivoWal, config);

        final ServidorApi[] apiHolder = new ServidorApi[1];
        final MonitorPeers[] monitorHolder = new MonitorPeers[1];

        try {
            apiHolder[0] = new ServidorApi(config.portaHttp, config.empresaId, config.consorcioToken, gerenciador);
            apiHolder[0].iniciar();
            System.out.println("[SERVER] API REST ativa na porta HTTP " + config.portaHttp + ".");

            monitorHolder[0] = new MonitorPeers(config.peers, new ClienteConsorcio(config.consorcioToken), 3_000L);
            monitorHolder[0].iniciar();
            gerenciador.setMonitorPeers(monitorHolder[0]);
            System.out.println("[SERVER] Monitor de peers iniciado para " + config.peers.size() + " empresa(s). ");
        } catch (IOException e) {
            System.out.println("[SERVER] Falha ao iniciar serviços distribuídos: " + e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (monitorHolder[0] != null) {
                monitorHolder[0].parar();
            }
            if (apiHolder[0] != null) {
                apiHolder[0].parar();
            }
            System.out.println("[SERVER] Serviços de rede do VaiJunto encerrados.");
        }));

        // Abre o ServerSocket na porta TCP desta empresa (vem da Config)
        try (ServerSocket serverSocket = new ServerSocket(config.portaTcp)) {
            System.out.println("[SERVER] Empresa " + config.empresaId
                    + " escutando clientes na porta TCP " + config.portaTcp + ".");

            while (true) {
                Socket socketCliente = serverSocket.accept();
                System.out.println("[SERVER] Novo passageiro/motorista conectado: " + socketCliente.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(socketCliente, gerenciador);
                new Thread(handler).start();
            }

        } catch (IOException e) {
            System.out.println("[SERVER] Erro crítico no Servidor TCP: " + e.getMessage());
        }
    }
}