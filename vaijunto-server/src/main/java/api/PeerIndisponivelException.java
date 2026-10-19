package api;

/**
 * Outro servidor do consórcio não respondeu (caiu, está lento ou devolveu erro).
 *
 * Não é um erro fatal: quem chama deve seguir em frente sem aquela empresa.
 */
public class PeerIndisponivelException extends RuntimeException {
    public PeerIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }

    public PeerIndisponivelException(String mensagem) {
        super(mensagem);
    }
}
