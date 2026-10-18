package wal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import com.google.gson.Gson;

/**
 * Write-ahead log: um arquivo em que SÓ SE ACRESCENTAM linhas, uma por evento.
 *
 * Formato de cada linha:   {@code <crc32 em hexa> <json do evento>\n}
 *
 * Garantias:
 *  - adicionar() só retorna depois de a linha estar no disco (fsync). Por isso o
 *    chamador pode registrar o evento ANTES de aplicá-lo e de responder ao
 *    cliente: se o servidor cair depois, o fato não se perde;
 *  - o CRC32 detecta linhas corrompidas ou gravadas pela metade;
 *  - se a queda ocorrer no meio de uma gravação, a ponta final incompleta é
 *    descartada na recuperação (ela nunca foi confirmada a ninguém) e o arquivo
 *    é truncado, para que a próxima linha não se cole nela;
 *  - uma linha ruim NO MEIO do arquivo não é ignorada: é corrupção de verdade e
 *    a recuperação falha com exceção, em vez de reconstruir um estado errado.
 *
 * Uso: chame recuperar() uma vez, ao iniciar, e depois adicionar() a cada fato.
 */
public class Wal implements AutoCloseable {
    private final Gson gson = new Gson();
    private final Path caminho;
    private final boolean sincronizar;
    private FileChannel canal;
    private long posicao;     // próximo byte a escrever (= tamanho válido do arquivo)
    private long ultimoSeq;

    public Wal(String caminhoArquivo) {
        this(caminhoArquivo, true);
    }

    /**
     * @param sincronizar se false, não chama fsync (muito mais rápido, mas NÃO
     *                    sobrevive a queda do sistema operacional; use apenas
     *                    para medir o custo do fsync em experimentos).
     */
    public Wal(String caminhoArquivo, boolean sincronizar) {
        this.caminho = Paths.get(caminhoArquivo);
        this.sincronizar = sincronizar;
    }

    /**
     * Lê o log inteiro, na ordem, e devolve os eventos válidos. Deixa o arquivo
     * pronto para receber novas linhas. Deve ser chamado antes de adicionar().
     */
    public synchronized List<Evento> recuperar() {
        try {
            Path pasta = caminho.toAbsolutePath().getParent();
            if (pasta != null) {
                Files.createDirectories(pasta);
            }
            canal = FileChannel.open(caminho, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);

            byte[] bytes = Files.readAllBytes(caminho);
            List<Evento> eventos = new ArrayList<>();
            int inicio = 0;
            int validoAte = 0;
            int numeroLinha = 0;

            while (inicio < bytes.length) {
                int fim = inicio;
                while (fim < bytes.length && bytes[fim] != '\n') {
                    fim++;
                }
                if (fim == bytes.length) {
                    // última linha sem '\n': gravação interrompida pela queda
                    System.out.println("[WAL] Descartando linha final incompleta (" + (fim - inicio) + " bytes).");
                    break;
                }
                numeroLinha++;
                Evento evento = interpretar(bytes, inicio, fim);
                if (evento == null) {
                    boolean ultimaLinha = (fim + 1 == bytes.length);
                    if (ultimaLinha) {
                        System.out.println("[WAL] Descartando última linha inválida (linha " + numeroLinha + ").");
                        break;
                    }
                    throw new IllegalStateException("WAL corrompido na linha " + numeroLinha
                            + " de " + caminho + ": a recuperação foi interrompida para não reconstruir estado errado.");
                }
                eventos.add(evento);
                ultimoSeq = Math.max(ultimoSeq, evento.seq);
                inicio = fim + 1;
                validoAte = inicio;
            }

            // Corta qualquer sobra inválida do final, para a próxima linha não se colar nela.
            canal.truncate(validoAte);
            posicao = validoAte;
            if (sincronizar) {
                canal.force(false);
            }
            return eventos;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao recuperar o WAL " + caminho, e);
        }
    }

    /** Devolve o evento da linha ou null se a linha for inválida (CRC ou JSON ruins). */
    private Evento interpretar(byte[] bytes, int inicio, int fim) {
        int espaco = -1;
        for (int i = inicio; i < fim; i++) {
            if (bytes[i] == ' ') {
                espaco = i;
                break;
            }
        }
        if (espaco < 0) {
            return null;
        }
        try {
            long crcLido = Long.parseLong(new String(bytes, inicio, espaco - inicio, StandardCharsets.US_ASCII), 16);
            CRC32 crc = new CRC32();
            crc.update(bytes, espaco + 1, fim - (espaco + 1));
            if (crc.getValue() != crcLido) {
                return null;
            }
            String json = new String(bytes, espaco + 1, fim - (espaco + 1), StandardCharsets.UTF_8);
            Evento evento = gson.fromJson(json, Evento.class);
            return (evento == null || evento.tipo == null) ? null : evento;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Registra um evento. Só retorna quando ele está no disco. Se falhar,
     * lança exceção: quem chamou NÃO deve aplicar a mudança nem responder sucesso.
     */
    public synchronized void adicionar(Evento evento) {
        if (canal == null) {
            throw new IllegalStateException("Chame recuperar() antes de adicionar().");
        }
        try {
            evento.seq = ultimoSeq + 1;
            byte[] json = gson.toJson(evento).getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(json, 0, json.length);
            byte[] prefixo = (Long.toHexString(crc.getValue()) + " ").getBytes(StandardCharsets.US_ASCII);

            ByteBuffer linha = ByteBuffer.allocate(prefixo.length + json.length + 1);
            linha.put(prefixo).put(json).put((byte) '\n').flip();

            long onde = posicao;
            while (linha.hasRemaining()) {
                onde += canal.write(linha, onde);
            }
            if (sincronizar) {
                canal.force(false); // só depois disto o fato é considerado durável
            }
            posicao = onde;
            ultimoSeq = evento.seq;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gravar no WAL " + caminho, e);
        }
    }

    public synchronized long getUltimoSeq() {
        return ultimoSeq;
    }

    @Override
    public synchronized void close() {
        try {
            if (canal != null) {
                canal.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
