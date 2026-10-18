package wal;

/** Tipos de fato que podem ser registrados no log. */
public enum TipoEvento {
    USUARIO_CADASTRADO,
    USUARIO_TIPO_ALTERADO,
    CARONA_CRIADA,
    CARONA_CANCELADA,
    RESERVA_CRIADA,
    RESERVA_CANCELADA
}
