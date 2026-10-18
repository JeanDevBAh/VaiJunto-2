package network;
/**
 * Representa uma resposta recebida do servidor.
 *
 * Contém o status da operação, uma mensagem para o usuário e os dados
 * retornados pelo servidor.
 *
 * A aplicação utiliza esse objeto para decidir se deve atualizar a interface
 * ou exibir uma mensagem de erro.
 */
public class DTOResponse<T> {
    
    private boolean sucesso;
    private String mensagem;
    private T dados; // Pode guardar uma lista de itinerários, uma reserva, etc.

    // Construtor para respostas simples (sucesso/erro + texto)
    public DTOResponse(boolean sucesso, String mensagem) {
        this.sucesso = sucesso;
        this.mensagem = mensagem;
        this.dados = null;
    }

    // Construtor para respostas com dados complexos
    public DTOResponse(boolean sucesso, String mensagem, T dados) {
        this.sucesso = sucesso;
        this.mensagem = mensagem;
        this.dados = dados;
    }

    // Getters e Setters
    public boolean isSucesso() {
        return sucesso;
    }

    public void setSucesso(boolean sucesso) {
        this.sucesso = sucesso;
    }

    public String getMensagem() {
        return mensagem;
    }

    public void setMensagem(String mensagem) {
        this.mensagem = mensagem;
    }

    public T getDados() {
        return dados;
    }

    public void setDados(T dados) {
        this.dados = dados;
    }
}