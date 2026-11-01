package wal;

/** Tipos de fato que podem ser registrados no log. */
public enum TipoEvento {
    USUARIO_CADASTRADO,
    USUARIO_TIPO_ALTERADO,
    CARONA_CRIADA,
    CARONA_CANCELADA,
    RESERVA_CRIADA,
    RESERVA_CANCELADA,
    RESERVA_DISTRIBUIDA_INICIADA,
    RESERVA_DISTRIBUIDA_CONFIRMADA,
    RESERVA_DISTRIBUIDA_CANCELADA,

    // --- transação distribuída de itinerário (2PC leve) ---
    TX_INICIADA,
    TX_PREPARADA,
    TX_COMMIT,
    TX_ABORT,
    TX_COMMIT_CONFIRMADO,
    TX_ABORT_CONFIRMADO,
    TX_RETRY_SCHEDULED
}
