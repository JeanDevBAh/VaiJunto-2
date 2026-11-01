package wal;

import java.util.ArrayList;
import java.util.List;

import model.Carona;
import model.Cidades;
import model.Reserva;
import model.Trecho;
import model.Usuario;

/**
 * Um fato registrado no log (uma linha do wal.log).
 *
 * É uma classe "achatada": todos os eventos usam o mesmo formato, com o campo
 * {@code tipo} dizendo quais dos outros campos estão preenchidos. Isso evita a
 * necessidade de adaptadores de polimorfismo do Gson. Campos nulos não são
 * gravados.
 *
 * IMPORTANTE: tudo que é gerado aleatoriamente (UUIDs) ou depende do relógio
 * vai DENTRO do evento. Assim o replay é determinístico: reaplicar o log
 * sempre reconstrói exatamente o mesmo estado.
 */
public class Evento {
    public long seq;            // número de ordem, atribuído pelo Wal ao gravar
    public TipoEvento tipo;

    // --- usuário ---
    public String login;
    public String senha;
    public String usuarioId;
    public String tipoUser;

    // --- carona / reserva ---
    public String id;           // id da carona ou da reserva
    public String empresaId;    // empresa dona da carona (CARONA_CRIADA)
    public String motorista;
    public List<String> rota;   // nomes das constantes de Cidades
    public String data;
    public String hora;
    public int vagas;
    public boolean ativa;
    public List<Double> precos;

    // --- reserva ---
    public String passageiroId;
    public List<TrechoRef> trechos;

    // --- reserva distribuída ---
    public String idLocal;
    public List<ReservaDistribuidaRef> reservasRemotas;

    // --- transação distribuída / 2PC leve ---
    public String txId;
    public String estadoTx;
    public List<String> participantes;
    public List<String> trechosTx;
    public String motivo;
    public Long expiraEm;
    public Long proximaTentativaEm;

    /** Referência a um trecho: o id da carona mais o par de cidades. */
    public static class TrechoRef {
        public String caronaId;
        public String origem;
        public String destino;

        public TrechoRef() {
        }

        public TrechoRef(String caronaId, String origem, String destino) {
            this.caronaId = caronaId;
            this.origem = origem;
            this.destino = destino;
        }
    }

    public static class ReservaDistribuidaRef {
        public String empresaId;
        public String idRemota;

        public ReservaDistribuidaRef() {
        }

        public ReservaDistribuidaRef(String empresaId, String idRemota) {
            this.empresaId = empresaId;
            this.idRemota = idRemota;
        }
    }

    // ===================== construtores de conveniência =====================

    public static Evento usuarioCadastrado(Usuario u) {
        Evento e = new Evento();
        e.tipo = TipoEvento.USUARIO_CADASTRADO;
        e.login = u.getLogin();
        e.senha = u.getSenha();
        e.usuarioId = u.getId();
        e.tipoUser = u.getTipoUser() == null ? null : u.getTipoUser().name();
        return e;
    }

    public static Evento usuarioTipoAlterado(String login, String tipoUser) {
        Evento e = new Evento();
        e.tipo = TipoEvento.USUARIO_TIPO_ALTERADO;
        e.login = login;
        e.tipoUser = tipoUser;
        return e;
    }

    public static Evento caronaCriada(Carona c) {
        Evento e = new Evento();
        e.tipo = TipoEvento.CARONA_CRIADA;
        e.id = c.getId();
        e.empresaId = c.getEmpresaId();
        e.motorista = c.getNomeMotorista();
        e.rota = new ArrayList<>();
        for (Cidades cidade : c.getRota()) {
            e.rota.add(cidade.name());
        }
        e.data = c.getData();
        e.hora = c.getHora();
        e.vagas = c.getVagasTotais();
        e.ativa = c.isAtivaOuNao();
        e.precos = new ArrayList<>(c.getPrecoPorTrecho());
        return e;
    }

    public static Evento caronaCancelada(String idCarona) {
        Evento e = new Evento();
        e.tipo = TipoEvento.CARONA_CANCELADA;
        e.id = idCarona;
        return e;
    }

    public static Evento reservaCriada(Reserva r) {
        Evento e = new Evento();
        e.tipo = TipoEvento.RESERVA_CRIADA;
        e.id = r.getId();
        e.passageiroId = r.getPassageiroId();
        e.login = r.getLoginPassageiro();
        e.trechos = new ArrayList<>();
        for (Trecho t : r.getTrechos()) {
            e.trechos.add(new TrechoRef(t.getCaronaId(),
                    t.getCidadeOrigem().name(), t.getCidadeDestino().name()));
        }
        return e;
    }

    public static Evento reservaCancelada(String idReserva) {
        Evento e = new Evento();
        e.tipo = TipoEvento.RESERVA_CANCELADA;
        e.id = idReserva;
        return e;
    }

    public static Evento reservaDistribuidaIniciada(String idLocal, String login, List<ReservaDistribuidaRef> reservasRemotas) {
        Evento e = new Evento();
        e.tipo = TipoEvento.RESERVA_DISTRIBUIDA_INICIADA;
        e.id = idLocal;
        e.login = login;
        e.idLocal = idLocal;
        e.reservasRemotas = reservasRemotas == null ? new ArrayList<>() : new ArrayList<>(reservasRemotas);
        return e;
    }

    public static Evento reservaDistribuidaConfirmada(String idLocal, List<ReservaDistribuidaRef> reservasRemotas) {
        Evento e = new Evento();
        e.tipo = TipoEvento.RESERVA_DISTRIBUIDA_CONFIRMADA;
        e.id = idLocal;
        e.idLocal = idLocal;
        e.login = idLocal;
        e.reservasRemotas = reservasRemotas == null ? new ArrayList<>() : new ArrayList<>(reservasRemotas);
        return e;
    }

    public static Evento reservaDistribuidaCancelada(String idLocal, List<ReservaDistribuidaRef> reservasRemotas) {
        Evento e = new Evento();
        e.tipo = TipoEvento.RESERVA_DISTRIBUIDA_CANCELADA;
        e.id = idLocal;
        e.idLocal = idLocal;
        e.login = idLocal;
        e.reservasRemotas = reservasRemotas == null ? new ArrayList<>() : new ArrayList<>(reservasRemotas);
        return e;
    }

    public static Evento txIniciada(String txId, String login, List<String> participantes, List<String> trechosTx) {
        Evento e = new Evento();
        e.tipo = TipoEvento.TX_INICIADA;
        e.txId = txId;
        e.login = login;
        e.participantes = participantes == null ? new ArrayList<>() : new ArrayList<>(participantes);
        e.trechosTx = trechosTx == null ? new ArrayList<>() : new ArrayList<>(trechosTx);
        e.estadoTx = "INICIADA";
        return e;
    }

    public static Evento txPreparada(String txId, String empresaId, List<String> trechosTx, long expiraEm) {
        Evento e = new Evento();
        e.tipo = TipoEvento.TX_PREPARADA;
        e.txId = txId;
        e.empresaId = empresaId;
        e.trechosTx = trechosTx == null ? new ArrayList<>() : new ArrayList<>(trechosTx);
        e.estadoTx = "PREPARADA";
        e.expiraEm = expiraEm;
        return e;
    }

    public static Evento txCommit(String txId) {
        Evento e = new Evento();
        e.tipo = TipoEvento.TX_COMMIT;
        e.txId = txId;
        e.estadoTx = "COMMIT";
        return e;
    }

    public static Evento txAbort(String txId, String motivo) {
        Evento e = new Evento();
        e.tipo = TipoEvento.TX_ABORT;
        e.txId = txId;
        e.motivo = motivo;
        e.estadoTx = "ABORT";
        return e;
    }

    public static Evento txCommitConfirmado(String txId, String empresaId) {
        Evento e = new Evento();
        e.tipo = TipoEvento.TX_COMMIT_CONFIRMADO;
        e.txId = txId;
        e.empresaId = empresaId;
        e.estadoTx = "COMMIT_CONFIRMADO";
        return e;
    }

    public static Evento txAbortConfirmado(String txId, String empresaId) {
        Evento e = new Evento();
        e.tipo = TipoEvento.TX_ABORT_CONFIRMADO;
        e.txId = txId;
        e.empresaId = empresaId;
        e.estadoTx = "ABORT_CONFIRMADO";
        return e;
    }

    public static Evento txRetryScheduled(String txId, long proximaTentativaEm, String motivo) {
        Evento e = new Evento();
        e.tipo = TipoEvento.TX_RETRY_SCHEDULED;
        e.txId = txId;
        e.proximaTentativaEm = proximaTentativaEm;
        e.motivo = motivo;
        e.estadoTx = "RETRY";
        return e;
    }
}
