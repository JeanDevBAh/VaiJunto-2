package model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
/**
 * Representa uma parte individual de uma carona entre duas cidades.
 *
 * Cada trecho controla sua origem, destino, data, preço, motorista, número
 * total de lugares, vagas disponíveis e passageiros confirmados.
 *
 * Os métodos de reserva e liberação de lugares são sincronizados para evitar
 * que duas operações concorrentes alterem a quantidade de vagas de forma
 * inconsistente.
 */
public class Trecho {
    private String caronaId;
    private final String empresaId;   // empresa DONA do trecho (só ela decide as vagas)
    private String data;
    private Cidades cidadeOrigem;
    private Cidades cidadeDestino;
    private String motorista;
    private final int totalLugares;
    private int lugaresDisponiveis;
    private double preco;
    private List<Usuario> passageiros;

    public Trecho(String caronaId, String empresaId, String data, Cidades cidadeOrigem, Cidades cidadeDestino,
         int totalLugares, double preco, String motorista) {
        this.caronaId = caronaId;
        this.empresaId = empresaId;
        this.data = data;
        this.cidadeOrigem = cidadeOrigem;
        this.cidadeDestino = cidadeDestino;
        this.totalLugares = totalLugares;
        this.lugaresDisponiveis = totalLugares;
        this.motorista = motorista;
        this.preco = preco;
        this.passageiros = new ArrayList<>();
    }

    public synchronized boolean reservarLugar(Usuario passageiro) {
        if (this.lugaresDisponiveis > 0) {
            this.lugaresDisponiveis--;
            this.passageiros.add(passageiro);
            return true;
        }
        return false;
    }

    // CORREÇÃO: idem, libera a vaga e remove o passageiro atomicamente.
    public synchronized void liberarLugar(String loginPassageiro) {
        if (this.lugaresDisponiveis < this.totalLugares) {
            this.lugaresDisponiveis++;
        }
        this.passageiros.removeIf(usuario -> usuario.getLogin().equals(loginPassageiro));
    }

    public String getCaronaId() {
        return caronaId;
    }

    public String getEmpresaId() {
        return empresaId;
    }

    /**
     * Identificador global do trecho, igual em todos os servidores do consórcio.
     * Exemplo: "EMPRESA_B:3f2a9c...:VALENTINE>RHODES"
     * Serve para o servidor de um passageiro saber a QUEM pedir a vaga.
     */
    public String getTrechoId() {
        return empresaId + ":" + caronaId + ":" + cidadeOrigem.name() + ">" + cidadeDestino.name();
    }

    public void setCaronaId(String caronaId) {
        this.caronaId = caronaId;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public Cidades getCidadeOrigem() {
        return cidadeOrigem;
    }

    public void setCidadeOrigem(Cidades cidadeOrigem) {
        this.cidadeOrigem = cidadeOrigem;
    }

    public Cidades getCidadeDestino() {
        return cidadeDestino;
    }

    public void setCidadeDestino(Cidades cidadeDestino) {
        this.cidadeDestino = cidadeDestino;
    }

    public int getTotalLugares() {
        return totalLugares;
    }

    public synchronized int getLugaresDisponiveis() {
        return lugaresDisponiveis;
    }

    public synchronized void setLugaresDisponiveis(int lugaresDisponiveis) {
        this.lugaresDisponiveis = lugaresDisponiveis;
    }

    public double getPreco() {
        return preco;
    }

    public void setPreco(double preco) {
        this.preco = preco;
    }

    public String getMotorista() {
        return motorista;
    }

    public void setMotorista(String motorista) {
        this.motorista = motorista;
    }

    public synchronized List<Usuario> getPassageiros() {
        return new ArrayList<>(passageiros);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Trecho trecho = (Trecho) o;
        return Objects.equals(caronaId, trecho.caronaId) &&
               cidadeOrigem == trecho.cidadeOrigem &&
               cidadeDestino == trecho.cidadeDestino &&
               Objects.equals(data, trecho.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caronaId, data, cidadeOrigem, cidadeDestino);
    }
}