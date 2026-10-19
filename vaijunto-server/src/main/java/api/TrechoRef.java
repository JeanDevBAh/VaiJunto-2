package api;

import model.Trecho;

public class TrechoRef {
    public String trechoId;
    public String empresaId;
    public String caronaId;
    public String origem;
    public String destino;
    public String data;
    public double preco;
    public int vagas;

    public static TrechoRef de(Trecho t){
        TrechoRef r = new TrechoRef();
        r.caronaId = t.getCaronaId();
        r.trechoId = t.getTrechoId();
        r.empresaId = t.getEmpresaId();
        r.origem = t.getCidadeOrigem().getNome();
        r.destino = t.getCidadeDestino().getNome();
        r.data = t.getData();
        r.preco = t.getPreco();
        r.vagas = t.getLugaresDisponiveis();

        return r;
    }


}
