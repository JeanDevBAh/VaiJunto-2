package model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
/**
 * Representa uma reserva realizada por um passageiro.
 *
 * Armazena identificador da reserva, passageiro, login, trechos reservados,
 * preço total e estado de atividade.
 *
 * Ao ser cancelada, libera os lugares ocupados em todos os seus trechos.
 * A operação é protegida para evitar cancelamentos repetidos ou inconsistentes.
 */
public class Reserva {
    private final String id;
    private final String passageiroId;
    private final String loginPassageiro;
    private final List<Trecho> trechos;
    private final double precoTotal;
    private boolean ativa;

    public Reserva(String passageiroId, String loginPassageiro, List<Trecho> trechos) {
        this.id = UUID.randomUUID().toString();
        this.passageiroId = passageiroId;
        this.loginPassageiro = loginPassageiro;
        this.trechos = new ArrayList<>(trechos);
        this.precoTotal = calcularPrecoTotal(trechos);
        this.ativa = true;
    }

    /** Usado no replay do log: recria a reserva com o id original. */
    public Reserva(String id, String passageiroId, String loginPassageiro, List<Trecho> trechos) {
        this.id = id;
        this.passageiroId = passageiroId;
        this.loginPassageiro = loginPassageiro;
        this.trechos = new ArrayList<>(trechos);
        this.precoTotal = calcularPrecoTotal(trechos);
        this.ativa = true;
    }

    private double calcularPrecoTotal(List<Trecho> trechos) {
        double soma = 0.0;
        for (Trecho t : trechos) {
            soma += t.getPreco();
        }
        return soma;
    }

    // Libera os assentos ocupados caso o passageiro cancele a reserva
  public synchronized void cancelar() {
        if (this.ativa) {
            this.ativa = false;
            for (Trecho t : this.trechos) {
                t.liberarLugar(loginPassageiro);
            }
        }
    }

    // Getters
    public String getId() { return id; }
    public String getPassageiroId() { return passageiroId; }
    public String getLoginPassageiro() { return loginPassageiro; }
    public List<Trecho> getTrechos() { return Collections.unmodifiableList(trechos); }
    public double getPrecoTotal() { return precoTotal; }
    public boolean isAtiva() { return ativa; }
}