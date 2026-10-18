package sessao;

import client.VaiJuntoClient;
import model.TipoUser;
import java.io.IOException;
/**
 * Mantém o estado do usuário autenticado no cliente.
 *
 * Armazena token, login, tipo de usuário e a instância do VaiJuntoClient
 * associada à conexão TCP atual.
 *
 * A mesma conexão é mantida durante toda a sessão porque o servidor guarda
 * informações dependentes da conexão, como a última busca de itinerários.
 *
 * Também permite atualizar o tipo de usuário depois que o servidor confirma
 * a operação MUDAR_TIPO_USUARIO.
 */
public class SessaoUsuario {
    private final String token;
    private final String login;
    private final VaiJuntoClient client;
    private TipoUser tipoUser; 

    public SessaoUsuario(String token, String login, TipoUser tipoUser, VaiJuntoClient client) {
        this.token = token;
        this.login = login;
        this.tipoUser = tipoUser;
        this.client = client;
    }

    public String getToken() {
        return token;
    }

    public String getLogin() {
        return login;
    }

    public TipoUser getTipoUser() {
        return tipoUser;
    }

    public VaiJuntoClient getClient() {
        return client;
    }

    /**
     * Só deve ser chamado DEPOIS que o servidor confirmar a troca (resposta
     * sucesso=true de MUDAR_TIPO_USUARIO). Este método não sincroniza nada
     * sozinho -- só evita que a UI fique com um tipoUser desatualizado em
     * relação ao que o servidor já aplicou.
     */
    public void atualizarTipoUsuario(TipoUser novoTipo) {
        this.tipoUser = novoTipo;
    }

    public void fechar() throws IOException {
        client.close();
    }
}