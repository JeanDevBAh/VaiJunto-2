package com.vaijunto.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Centraliza a navegação entre as telas da aplicação JavaFX.
 *
 * É responsável por controlar a Stage principal e substituir a raiz da Scene
 * quando uma nova tela precisa ser apresentada.
 *
 * Dessa forma, as telas não precisam manipular diretamente a janela principal.
 */
public class NavegacaoManager {
    private final Stage stage;

    public NavegacaoManager(Stage stage) {
        this.stage = stage;
    }

    public void mostrar(Parent raiz) {
        Scene cenaAtual = stage.getScene();
        if (cenaAtual == null) {
            stage.setScene(new Scene(raiz, 480, 360));
        } else {
            cenaAtual.setRoot(raiz);
        }
        stage.show();
    }
}