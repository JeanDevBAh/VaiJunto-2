package simulador;

import client.VaiJuntoClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import model.Cidades;
import model.TipoUser;
import network.DTOResponse;

/**
 * Simulador de passageiros para avaliar a carga e o comportamento do sistema
 * em múltiplos clientes concorrentes.
 */
public final class SimuladorPassageiros {
    private static final List<Cidades> CIDADES_DISPONIVEIS = Arrays.stream(Cidades.values())
            .filter(Cidades::isDisponivel)
            .toList();
    private static final List<Rota> ROTAS_DE_EXEMPLO = List.of(
            new Rota(Cidades.VALENTINE, Cidades.RHODES),
            new Rota(Cidades.STRAWBERRY, Cidades.BLACKWATER),
            new Rota(Cidades.SAINT_DENIS, Cidades.ANESBURG));

    private SimuladorPassageiros() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        List<Servidor> servidores = Servidor.parse(config.servidores);

        if (servidores.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nenhum servidor informado. Use --servidores=host:porta,host:porta,...");
        }

        semearCaronas(servidores);

        System.out.printf(Locale.forLanguageTag("pt-BR"),
                "Iniciando simulação com %d usuários, %d iterações e %d servidores.%n",
                config.usuarios,
                config.iteracoes,
                servidores.size());

        long inicio = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(config.usuarios, 16));
        List<Future<ResultadoUsuario>> tarefas = new ArrayList<>();

        for (int indice = 0; indice < config.usuarios; indice++) {
            final int id = indice;
            tarefas.add(pool.submit(() -> executarUsuario(id, config, servidores)));
        }

        int totalReservas = 0;
        int usuariosComSucesso = 0;
        int usuariosFalharam = 0;

        for (Future<ResultadoUsuario> tarefa : tarefas) {
            try {
                ResultadoUsuario resultado = tarefa.get();
                totalReservas += resultado.reservasConfirmadas;
                if (resultado.reservasConfirmadas > 0 || resultado.buscasSemErro > 0) {
                    usuariosComSucesso++;
                } else {
                    usuariosFalharam++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                usuariosFalharam++;
            } catch (ExecutionException e) {
                usuariosFalharam++;
                System.err.println("Erro ao executar usuário: " + e.getCause());
            }
        }

        pool.shutdown();
        long tempoMs = (System.nanoTime() - inicio) / 1_000_000;

        System.out.printf(Locale.forLanguageTag("pt-BR"),
                "Simulação concluída em %d ms.%n", tempoMs);
        System.out.printf(Locale.forLanguageTag("pt-BR"),
                "Reservas confirmadas: %d | usuários com sucesso: %d | usuários com falha: %d%n",
                totalReservas,
                usuariosComSucesso,
                usuariosFalharam);
    }

    private static void semearCaronas(List<Servidor> servidores) {
        for (Servidor servidor : servidores) {
            try (VaiJuntoClient cliente = new VaiJuntoClient(servidor.host(), servidor.port(), 6_000)) {
                String login = "motorista_seed_" + servidor.port();
                String senha = "senha123";
                cliente.cadastro(login, senha, TipoUser.MOTORISTA);

                DTOResponse<?> loginResp = cliente.login(login, senha);
                String token = extrairToken(loginResp.getDados());
                if (token == null || token.isBlank()) {
                    continue;
                }

                for (Rota rota : ROTAS_DE_EXEMPLO) {
                    String data = LocalDate.now().plusDays(1).toString();
                    DTOResponse<?> oferta = cliente.oferecerCarona(
                            token,
                            data,
                            "08:00",
                            List.of(rota.origem().name(), rota.destino().name()),
                            List.of(25.0),
                            2);

                    if (oferta != null && oferta.isSucesso()) {
                        System.out.printf("[seed] carona criada em %s:%d (%s -> %s)%n",
                                servidor.host(), servidor.port(), rota.origem(), rota.destino());
                    }
                }
            } catch (Exception exception) {
                System.err.printf("[seed] falha em %s:%d: %s%n", servidor.host(), servidor.port(), exception.getMessage());
            }
        }
    }

    private static ResultadoUsuario executarUsuario(int indice, Config config, List<Servidor> servidores) {
        var random = ThreadLocalRandom.current();
        String login = "sim_" + indice + "_" + random.nextInt(1_000_000);
        String senha = "senha123";
        Servidor servidor = servidores.get(random.nextInt(servidores.size()));

        int buscasSemErro = 0;
        int reservasConfirmadas = 0;

        try (VaiJuntoClient cliente = new VaiJuntoClient(servidor.host(), servidor.port(), 6_000)) {
            DTOResponse<?> cadastro = cliente.cadastro(login, senha, TipoUser.PASSAGEIRO);
            if (!cadastro.isSucesso() && cadastro.getMensagem() != null
                    && !cadastro.getMensagem().toLowerCase(Locale.ROOT).contains("já existe")) {
                System.out.printf("[usuario %s] cadastro falhou: %s%n", login, cadastro.getMensagem());
            }

            DTOResponse<?> loginResp = cliente.login(login, senha);
            if (!loginResp.isSucesso()) {
                return new ResultadoUsuario(buscasSemErro, reservasConfirmadas);
            }

            String token = extrairToken(loginResp.getDados());
            if (token == null || token.isBlank()) {
                return new ResultadoUsuario(buscasSemErro, reservasConfirmadas);
            }

            for (int i = 0; i < config.iteracoes; i++) {
                Rota rota = ROTAS_DE_EXEMPLO.get(random.nextInt(ROTAS_DE_EXEMPLO.size()));
                String data = LocalDate.now().plusDays(random.nextInt(0, 4)).toString();
                DTOResponse<?> busca = cliente.buscarViagens(rota.origem().name(), rota.destino().name(), data);
                if (!busca.isSucesso()) {
                    continue;
                }
                buscasSemErro++;

                Object dadosBusca = busca.getDados();
                if (!(dadosBusca instanceof List<?> itinerarios) || itinerarios.isEmpty()) {
                    continue;
                }

                int indiceEscolhido = random.nextInt(itinerarios.size());
                DTOResponse<?> reserva = cliente.reservar(indiceEscolhido, token);
                if (reserva != null && reserva.isSucesso()) {
                    reservasConfirmadas++;
                }
            }

            DTOResponse<?> consultas = cliente.minhasReservas(token);
            if (consultas != null && !consultas.isSucesso()) {
                System.out.printf("[usuario %s] consulta final falhou: %s%n", login, consultas.getMensagem());
            }
        } catch (Exception exception) {
            System.err.printf("[usuario %s] erro: %s%n", login, exception.getMessage());
        }

        return new ResultadoUsuario(buscasSemErro, reservasConfirmadas);
    }

    @SuppressWarnings("unchecked")
    private static String extrairToken(Object dadosLogin) {
        if (dadosLogin instanceof Map<?, ?> mapa && mapa.containsKey("token")) {
            Object token = mapa.get("token");
            return token == null ? null : String.valueOf(token);
        }
        if (dadosLogin instanceof String token) {
            return token;
        }
        return null;
    }

    private record Config(int usuarios, int iteracoes, String servidores) {
        static Config parse(String[] args) {
            int usuarios = Integer.parseInt(System.getProperty("sim.usuarios", "20"));
            int iteracoes = Integer.parseInt(System.getProperty("sim.iteracoes", "5"));
            String servidores = System.getProperty("sim.servidores",
                    "127.0.0.1:8080,127.0.0.1:8081,127.0.0.1:8082");

            for (String arg : args) {
                if (arg.startsWith("--usuarios=")) {
                    usuarios = Integer.parseInt(arg.substring("--usuarios=".length()));
                } else if (arg.startsWith("--iteracoes=")) {
                    iteracoes = Integer.parseInt(arg.substring("--iteracoes=".length()));
                } else if (arg.startsWith("--servidores=")) {
                    servidores = arg.substring("--servidores=".length());
                }
            }

            if (usuarios <= 0) {
                throw new IllegalArgumentException("O número de usuários deve ser positivo.");
            }
            if (iteracoes <= 0) {
                throw new IllegalArgumentException("O número de iterações deve ser positivo.");
            }
            return new Config(usuarios, iteracoes, servidores);
        }
    }

    private record Servidor(String host, int port) {
        static List<Servidor> parse(String servidores) {
            String[] partes = servidores.split(",");
            List<Servidor> resultado = new ArrayList<>();
            for (String parte : partes) {
                String valor = parte.trim();
                if (valor.isEmpty()) {
                    continue;
                }
                String[] campos = valor.split(":");
                if (campos.length != 2) {
                    throw new IllegalArgumentException("Servidor inválido: " + valor + ". Use host:porta.");
                }
                String host = campos[0].trim();
                int porta = Integer.parseInt(campos[1].trim());
                resultado.add(new Servidor(host, porta));
            }
            return resultado;
        }
    }

    private record Rota(Cidades origem, Cidades destino) {
    }

    private record ResultadoUsuario(int buscasSemErro, int reservasConfirmadas) {
    }
}
