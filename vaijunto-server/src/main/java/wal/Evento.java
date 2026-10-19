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
}
