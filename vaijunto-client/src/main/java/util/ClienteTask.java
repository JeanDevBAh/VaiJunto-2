package util;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javafx.concurrent.Task;

/**
 * Executa operações de rede em uma thread separada da thread principal do
 * JavaFX.
 *
 * Como as operações de socket podem bloquear enquanto aguardam uma resposta,
 * essa classe evita que a interface fique congelada durante login, busca,
 * cadastro, reserva ou qualquer outra chamada ao servidor.
 *
 * Os callbacks de sucesso e falha são executados pela infraestrutura de
 * eventos do JavaFX, permitindo atualizar os componentes visuais com segurança.
 */
public final class ClienteTask {

    private ClienteTask() {}

    public static <T> void executar(Callable<T> chamada,
                                     Consumer<T> aoTerminar,
                                     Consumer<Throwable> aoFalhar) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return chamada.call();
            }
        };
        task.setOnSucceeded(e -> aoTerminar.accept(task.getValue()));
        task.setOnFailed(e -> aoFalhar.accept(task.getException()));

        Thread thread = new Thread(task, "vaijunto-client-call");
        thread.setDaemon(true);
        thread.start();
    }
}