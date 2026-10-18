package com.vaijunto.ui;

import model.TipoUser;
import sessao.SessaoUsuario;

/**
 * Decide qual dashboard deve ser exibido de acordo com o tipo do usuário.
 *
 * Usuários do tipo MOTORISTA são direcionados para MotoristaView.
 * Usuários do tipo PASSAGEIRO são direcionados para PassageiroView.
 *
 * A regra foi centralizada nessa classe para evitar duplicação da lógica de
 * navegação após o login ou após a troca de tipo de usuário.
 */
public final class RoteadorDashboard {

    private RoteadorDashboard() {}

    public static void mostrarDashboardCorreto(SessaoUsuario sessao, NavegacaoManager navegacao) {
        if (sessao.getTipoUser() == TipoUser.MOTORISTA) {
            navegacao.mostrar(new MotoristaView(sessao, navegacao));
        } else {
            navegacao.mostrar(new PassageiroView(sessao, navegacao));
        }
    }
}