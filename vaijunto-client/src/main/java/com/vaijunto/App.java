package com.vaijunto;

import com.vaijunto.ui.LoginView;
import com.vaijunto.ui.NavegacaoManager;
import javafx.application.Application;
import javafx.stage.Stage;
/**
 * Classe principal da aplicação JavaFX.
 *
 * Inicializa a interface gráfica, configura o título da janela e cria o
 * NavegacaoManager responsável por controlar as telas da aplicação.
 *
 * A tela inicial apresentada é a tela de login.
 */
public class App extends Application {
    @Override
    public void start(Stage stage) {
        stage.setTitle("VaiJunto");

        NavegacaoManager navegacao = new NavegacaoManager(stage);
        navegacao.mostrar(new LoginView(navegacao));
    }

    public static void main(String[] args) {
        launch(args);
    }
}