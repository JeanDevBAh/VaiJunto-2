package network;
/**
 * Objeto de transferência utilizado nas respostas enviadas pelo servidor.
 *
 * Toda resposta possui um indicador de sucesso, uma mensagem descritiva e
 * um campo de dados opcional. O campo dados pode conter uma lista de caronas,
 * itinerários, reservas, um identificador ou informações de autenticação.
 *
 * O uso de generics permite que a resposta transporte diferentes tipos de
 * conteúdo sem criar uma classe específica para cada operação.
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