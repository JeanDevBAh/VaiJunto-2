package com.vaijunto.ui;

import client.VaiJuntoClient;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import model.TipoUser;
import network.DTOResponse;
import sessao.SessaoUsuario;
import util.ClienteTask;
import java.util.Map;

/**
 * Tela inicial de autenticação e cadastro do usuário.
 *
 * Permite informar login e senha para realizar login ou preencher também o
 * tipo de usuário para efetuar um cadastro.
 *
 * As chamadas ao servidor são executadas de forma assíncrona por meio de
 * ClienteTask. Em caso de login bem-sucedido, cria uma SessaoUsuario e
 * direciona o usuário ao dashboard correspondente.
 */
public class LoginView extends VBox {

    private final NavegacaoManager navegacao;
    private final TextField campoLogin = new TextField();
    private final PasswordField campoSenha = new PasswordField();
    private final ComboBox<TipoUser> comboTipo = new ComboBox<>();
    private final Label labelStatus = new Label();

    public LoginView(NavegacaoManager navegacao) {
        this.navegacao = navegacao;
        montarLayout();
    }

    private void montarLayout() {
        setSpacing(12);
        setPadding(new Insets(24));
        setAlignment(Pos.CENTER);

        Label titulo = new Label("VaiJunto");
        titulo.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        campoLogin.setPromptText("Login");
        campoSenha.setPromptText("Senha");

        comboTipo.getItems().addAll(TipoUser.values());
        comboTipo.setPromptText("Tipo de usuário (só para cadastro)");

        Button btnEntrar = new Button("Entrar");
        btnEntrar.setDefaultButton(true);
        btnEntrar.setOnAction(e -> fazerLogin());

        Button btnCadastrar = new Button("Cadastrar");
        btnCadastrar.setOnAction(e -> fazerCadastro());

        labelStatus.setWrapText(true);
        labelStatus.setStyle("-fx-text-fill: #b00020;");

        getChildren().addAll(titulo, campoLogin, campoSenha, comboTipo, btnEntrar, btnCadastrar, labelStatus);
    }

    private void fazerLogin() {
        String login = campoLogin.getText();
        String senha = campoSenha.getText();

        if (login == null || login.isBlank() || senha == null || senha.isBlank()) {
            labelStatus.setText("Preencha login e senha.");
            return;
        }

        labelStatus.setText("Conectando...");

        ClienteTask.executar(
                () -> {
                    // A MESMA instância de VaijuntoClient precisa sobreviver
                    // além deste método -- por isso ela é criada aqui e
                    // repassada pra SessaoUsuario, não fechada no final.
                    VaiJuntoClient client = new VaiJuntoClient();
                    DTOResponse<?> resposta = client.login(login, senha);
                    return new Object[]{client, resposta};
                },
                resultado -> {
                    VaiJuntoClient client = (VaiJuntoClient) resultado[0];
                    DTOResponse<?> resposta = (DTOResponse<?>) resultado[1];

                    if (!resposta.isSucesso()) {
                        labelStatus.setText(resposta.getMensagem());
                        return;
                    }

                    Map<?, ?> dados = (Map<?, ?>) resposta.getDados();
                    String token = (String) dados.get("token");
                    TipoUser tipoAssumido = TipoUser.valueOf((String) dados.get("tipoUser"));

                    SessaoUsuario sessao = new SessaoUsuario(token, login, tipoAssumido, client);
                    RoteadorDashboard.mostrarDashboardCorreto(sessao, navegacao);
                },
                erro -> labelStatus.setText("Erro de conexão: " + erro.getMessage())
        );
    }

    private void fazerCadastro() {
        String login = campoLogin.getText();
        String senha = campoSenha.getText();
        TipoUser tipo = comboTipo.getValue();

        if (login == null || login.isBlank() || senha == null || senha.isBlank() || tipo == null) {
            labelStatus.setText("Preencha login, senha e tipo de usuário para cadastrar.");
            return;
        }

        labelStatus.setText("Cadastrando...");

    ClienteTask.<DTOResponse<?>>executar(
                () -> {
                    try (VaiJuntoClient client = new VaiJuntoClient()) {
                        return client.cadastro(login, senha, tipo);
                    }
                },
                resposta -> {
                    Alert alerta = new Alert(resposta.isSucesso() ? AlertType.INFORMATION : AlertType.ERROR);
                    alerta.setHeaderText(null);
                    alerta.setContentText(resposta.getMensagem());
                    alerta.showAndWait();
                    if (resposta.isSucesso()) {
                        labelStatus.setText("Cadastro feito. Pode fazer login agora.");
                    }
                },
                erro -> labelStatus.setText("Erro de conexão: " + erro.getMessage())
        );
    }
}