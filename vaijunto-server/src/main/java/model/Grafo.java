package model;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
/**
 * Grafo será a representação do mapa onde os vertices são as cidades e os 
 * trechos as arestas direcionadas
 */
public class Grafo {
    //Map armazena dados em pare de chave e valor, permitindo apenas chaves unicas sem repetição
    private final Map<Cidades, List<Trecho>> arestas;

    public Grafo() {
        this.arestas = new HashMap<>();//tabela hash com chave e valor
        for (Cidades cidade : Cidades.values()) {//adiciona por padrão todas as cidades do enum como chaves
            arestas.put(cidade, new CopyOnWriteArrayList<>());
        }
    }//O grafo é implementado como uma lista de adjacencias 

    public synchronized void addTrechos(List<Trecho> trechos) {//synchronized impede multiplas threads de acessar o metodo
        for (Trecho t : trechos) {
            arestas.get(t.getCidadeOrigem()).add(t);
        }
    }
    //remove trechos da carona caso o id seja o mesmo
    public synchronized void removeTrechosDaCarona(String caronaId) {
        for (List<Trecho> lista : arestas.values()) {
            lista.removeIf(t -> t.getCaronaId().equals(caronaId));
        }
    }

    //retorna a lista de itinerarios disponiveis para o cliente
    public List<Itinerario> buscarItinerarios(Cidades origem, Cidades destino, String dataDesejada) {
        List<Itinerario> resultado = new ArrayList<>();

        // Validações de entrada: nulos, origem == destino e cidades bloqueadas (ex: Tahiti)
        if (origem == null || destino == null || origem == destino || dataDesejada == null) {
            return resultado;
        }
        if (!origem.isDisponivel() || !destino.isDisponivel()) {
            return resultado;
        }
        
        List<Trecho> caminhoAtual = new ArrayList<>();
        Set<Cidades> cidadesVisitadas = new HashSet<>();//hashset para inserir cidades visitadas pois ele so admite valores diferentes

        cidadesVisitadas.add(origem);
        buscaCaminhoDFS(origem, destino, dataDesejada, cidadesVisitadas, caminhoAtual, resultado);

        return resultado;
    }

    private void buscaCaminhoDFS(Cidades atual, Cidades destino, String dataDesejada,
                                 Set<Cidades> visitadas, List<Trecho> caminhoAtual, 
                                 List<Itinerario> resultado) {
        if (atual == destino) {//achamos a rota valida
            resultado.add(new Itinerario(new ArrayList<>(caminhoAtual)));//salva o itinerario com o caminho achado
            return;
        }      
        
        for (Trecho trecho : arestas.get(atual)) {
            Cidades proxima = trecho.getCidadeDestino();
            
            boolean mesmaData = trecho.getData() != null && trecho.getData().equals(dataDesejada);
            boolean temVagas = trecho.getLugaresDisponiveis() > 0;
            boolean naoVisitada = !visitadas.contains(proxima);
            boolean cidadeAtiva = proxima.isDisponivel();

            if (mesmaData && temVagas && naoVisitada && cidadeAtiva) {
                visitadas.add(proxima);
                caminhoAtual.add(trecho);

                buscaCaminhoDFS(proxima, destino, dataDesejada, visitadas, caminhoAtual, resultado);

                // Backtracking
                caminhoAtual.remove(caminhoAtual.size() - 1);
                visitadas.remove(proxima);
            }
        }
    }
}