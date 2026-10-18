package model;
/**
 * Enumeração que identifica os tipos de usuário existentes no sistema.
 *
 * MOTORISTA representa o usuário que publica caronas.
 * PASSAGEIRO representa o usuário que pesquisa e reserva viagens.
 *
 * O tipo pode ser alterado pelo usuário por meio da operação
 * MUDAR_TIPO_USUARIO.
 */
public enum TipoUser {
    MOTORISTA,
    PASSAGEIRO;

}
