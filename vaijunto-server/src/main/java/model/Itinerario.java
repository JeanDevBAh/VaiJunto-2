package model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * Representa um caminho completo entre a origem e o destino do passageiro.
 *
 * Um itinerário é formado por um ou mais trechos, que podem pertencer a
 * caronas de motoristas diferentes.
 *
 * A classe calcula o preço total somando o preço de todos os trechos e oferece
 * informações como origem, destino, data e quantidade de conexões.
 */
public class Itinerario {
    private final List<Trecho> trechos;
    private final double precoTotal;

    public Itinerario(List<Trecho> trechos) {
        if (trechos == null || trechos.isEmpty()) {
            throw new IllegalArgumentException("Um itinerário deve conter pelo menos um trecho.");
        }
        this.trechos = new ArrayList<>(trechos);
        this.precoTotal = calcularPrecoTotal();
    }

    
    private double calcularPrecoTotal() {
        double total = 0.0;
        for (Trecho t : this.trechos) {
            total += t.getPreco();
        }
        return total;
    }

    public Cidades getOrigem() {
        return trechos.get(0).getCidadeOrigem();
    }

    public Cidades getDestino() {
        return trechos.get(trechos.size() - 1).getCidadeDestino();
    }

    public String getData() {
        return trechos.get(0).getData();
    }

    public int getQuantidadeParadas() {
        return trechos.size() - 1; // 1 trecho = 0 paradas (direto), 2 trechos = 1 conexão
    }

    public List<Trecho> getTrechos() {
        return Collections.unmodifiableList(trechos);
    }

    public double getPrecoTotal() {
        return precoTotal;
    }

    @Override
    public String toString() {
        return "Itinerario de " + getOrigem() + " ate " + getDestino() + 
               " | Data: " + getData() + 
               " | Conexoes: " + getQuantidadeParadas() + 
               " | Total: R$ " + String.format("%.2f", precoTotal);
    }
}