package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;
import config.Config;
import controller.GegenciarGeral;
/**
 * Classe responsável por iniciar o servidor TCP do VaiJunto.
 *
 * Abre um ServerSocket na porta 8080 e aguarda continuamente novas conexões
 * de clientes. Para cada cliente conectado, cria um ClientHandler executado
 * em uma nova thread, permitindo que vários motoristas e passageiros sejam
 * atendidos simultaneamente.
 *
 * O objeto GegenciarGeral é compartilhado entre os handlers e mantém o estado
 * central das sessões, caronas, trechos e reservas.
 */
public class ServidorTCP {
    
    // Porta onde o servidor vai escutar as conexões 
    private static final int PORTA = 8080;
    

    public static void main(String[] args) {
    Config config = Config.doAmbiente();            // falha aqui se EMPRESA_ID faltar
    System.out.println("[SERVER] " + config);

    String arquivoWal = Paths.get(config.dataDir, "wal.log").toString();
    GegenciarGeral gerenciador = new GegenciarGeral(arquivoWal, config); 

        // Abre o ServerSocket na porta definida
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("[SERVER] Servidor rodando com sucesso e escutando na porta " + PORTA + ".");

            // Laço infinito para manter o servidor sempre aberto aceitando novos clientes
            while (true) {
                // O accept() pausa a execução e aguarda até que um cliente se conecte
                Socket socketCliente = serverSocket.accept();
                System.out.println("[SERVER] Novo passageiro/motorista conectado: " + socketCliente.getRemoteSocketAddress());

                // Cria uma nova thread (ClientHandler) para cuidar exclusivamente deste cliente
                ClientHandler handler = new ClientHandler(socketCliente, gerenciador);
                new Thread(handler).start();
            }

        } catch (IOException e) {
            System.out.println("[SERVER] Erro crítico no Servidor TCP: " + e.getMessage());
        }
    }
}