package controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import model.Carona;
import model.Cidades;
import model.Grafo;
import model.Trecho;
import model.Usuario;
import wal.Evento;
import wal.Wal;
 

/**
 * Gerencia o ciclo de vida das caronas oferecidas pelos motoristas.
 *
 * Cria caronas, verifica duplicidade de rota, data e horário, registra as
 * caronas em memória e adiciona seus trechos ao grafo de viagens.
 *
 * Também permite listar caronas de um motorista, consultar passageiros por
 * trecho e cancelar caronas.
 *
 * Ao cancelar uma carona, seus trechos são removidos do grafo para que não
 * sejam mais encontrados em novas buscas.
 */
public class CaronaService {
    private final Map<String, Carona>  caronas;
    private final Grafo grafo;

    // Null = sem log
    private final Wal wal;

    public CaronaService(Grafo mapa){
        this(mapa, null);
    }

    public CaronaService(Grafo mapa, Wal wal){
        this.caronas = new ConcurrentHashMap<>();
        this.grafo = mapa;
        this.wal = wal;
    }

    // ---------- replay (NÃO grava no log) ----------

    public void aplicarCriacao(Evento e) {
        List<Cidades> rota = new ArrayList<>();
        for (String nome : e.rota) {
            rota.add(Cidades.valueOf(nome));
        }
        Carona carona = new Carona(e.id, rota, e.data, e.hora, e.vagas, e.ativa, e.precos, e.motorista);
        caronas.put(carona.getId(), carona);
        if (carona.isAtivaOuNao()) {
            grafo.addTrechos(carona.getTrechos());
        }
    }

    public void aplicarCancelamento(Evento e) {
        Carona carona = caronas.remove(e.id);
        if (carona != null) {
            carona.setAtivaOuNao(false);
            grafo.removeTrechosDaCarona(e.id);
        }
    }

    /** Localiza um trecho pelo id da carona e pelas cidades (usado para religar reservas no replay). */
    public Trecho buscarTrecho(String caronaId, Cidades origem, Cidades destino) {
        Carona carona = caronas.get(caronaId);
        if (carona == null) {
            return null;
        }
        for (Trecho t : carona.getTrechos()) {
            if (t.getCidadeOrigem() == origem && t.getCidadeDestino() == destino) {
                return t;
            }
        }
        return null;
    }
    
    public synchronized String criarCarona(List<Cidades> rota, String data, String hora,
                  int vagasTotais, boolean ativaOuNao, List<Double> precos, String motorista){
        for (Carona existente : caronas.values()) {
            if (Objects.equals(motorista, existente.getNomeMotorista())
                    && Objects.equals(data, existente.getData())
                    && Objects.equals(hora, existente.getHora())
                    && Objects.equals(rota, existente.getRota())) {
                throw new IllegalArgumentException(
                        "Você já publicou uma carona com a mesma rota, data e horário.");
            }
        }
        Carona carona = new Carona(rota, data, hora, vagasTotais, ativaOuNao, precos, motorista);
        if (wal != null) {
            wal.adicionar(Evento.caronaCriada(carona)); // registrar ANTES de aplicar
        }
        caronas.put(carona.getId(), carona);
        if (carona.isAtivaOuNao()) {
            grafo.addTrechos(carona.getTrechos());
        }
        return carona.getId();
    }

    public List<Carona> buscaCaronasPorMotorista(String motorista){
        List<Carona> resultado = new ArrayList<>();
        if (motorista == null || motorista.isBlank()) {
            return resultado;
        }
        for (Carona carona : caronas.values()) {
            if (motorista.equals(carona.getNomeMotorista())) {
                resultado.add(carona);
            }
        }
        return resultado;
    }

    public Map<String, List<String>> consultarPassageirosDaCarona(String idCarona, String loginMotorista) {
        Carona carona = caronas.get(idCarona);
        
        // Valida se a carona existe e pertence ao motorista que fez a requisição
        if (carona == null || !carona.getNomeMotorista().equals(loginMotorista)) {
            return null;
        }

        // Usa LinkedHashMap para manter a ordem cronológica dos trechos da rota
        Map<String, List<String>> mapaPassageiros = new LinkedHashMap<>();

        for (Trecho trecho : carona.getTrechos()) {
            String nomeTrecho = trecho.getCidadeOrigem().getNome() + " -> " + trecho.getCidadeDestino().getNome();
            
            List<String> listaLogins = new ArrayList<>();
            for (Usuario passageiro : trecho.getPassageiros()) {
                listaLogins.add(passageiro.getLogin());
            }
            
            mapaPassageiros.put(nomeTrecho, listaLogins);
        }

        return mapaPassageiros;
    }

    // synchronized: o "validar -> registrar -> aplicar" não pode se intercalar com outro cancelamento
    public synchronized boolean cancelarCarona(String id, String loginMotorista){
        if (id == null || loginMotorista == null || loginMotorista.isBlank()) {
            return false;
        }
        Carona carona = caronas.get(id);
        if (carona != null && carona.isAtivaOuNao()
                && loginMotorista.equals(carona.getNomeMotorista())) {
            if (wal != null) {
                wal.adicionar(Evento.caronaCancelada(id)); // registrar ANTES de aplicar
            }
            carona.setAtivaOuNao(false);
            grafo.removeTrechosDaCarona(id);
            caronas.remove(id);
            return true;
        }
        return false;
    }


}