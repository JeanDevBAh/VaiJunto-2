package network;

import java.util.List;

import model.TipoUser;
/**
 * Objeto de transferência utilizado nas requisições enviadas pelo cliente.
 *
 * Reúne todos os campos possíveis do protocolo remoto, como ação, login,
 * senha, token, data, horário, rota, preços, quantidade de vagas e índices
 * de itinerários.
 *
 * O Gson utiliza essa classe para converter mensagens JSON recebidas pelo
 * socket em objetos Java.
 */
public class DTORequest {
    private String acao;
    private String data;
    private String hora;
    private List<String> rota;
    private List<Double> precos;
    private String idCarona;
    private String token;
    private Boolean ativaOuNao;
    private String login;
    private String senha;
    private String origem;
    private String destino;
    private Integer vagas;
    private String idReserva; 
    private TipoUser tipoUser;
    private Integer indiceItinerario;
    

    // Construtor vazio (necessário para o Gson desserializar direito)
    public DTORequest() {}

    // Getters e Setters
    public String getAcao() {
        return acao;
    }

    public void setAcao(String acao) {
        this.acao = acao;
    }
    
    public TipoUser getTipoUser() {
        return tipoUser;
    }

    public void setTipoUser(TipoUser tipoUser) {
        this.tipoUser = tipoUser;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getSenha() {
        return senha;
    }

    public void setSenha(String senha) {
        this.senha = senha;
    }

    public String getOrigem() {
        return origem;
    }

    public void setOrigem(String origem) {
        this.origem = origem;
    }

    public String getDestino() {
        return destino;
    }

    public void setDestino(String destino) {
        this.destino = destino;
    }

    public Integer getVagas() {
        return vagas;
    }

    public void setVagas(int vagas) {
        this.vagas = vagas;
    }

    public String getIdReserva() {
        return idReserva;
    }

    public void setIdReserva(String idReserva) {
        this.idReserva = idReserva;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getHora() {
        return hora;
    }

    public void setHora(String hora) {
        this.hora = hora;
    }

    public List<String> getRota() {
        return rota;
    }

    public void setRota(List<String> rota) {
        this.rota = rota;
    }

    public List<Double> getPrecos() {
        return precos;
    }

    public void setPrecos(List<Double> precos) {
        this.precos = precos;
    }

    public String getIdCarona() {
        return idCarona;
    }

    public void setIdCarona(String idCarona) {
        this.idCarona = idCarona;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Boolean isAtivaOuNao() {
        return ativaOuNao;
    }

    public void setAtivaOuNao(boolean ativaOuNao) {
        this.ativaOuNao = ativaOuNao;
    }

    public Integer getIndiceItinerario() {
        return indiceItinerario;
    }

    public void setIndiceItinerario(Integer indiceItinerario) {
        this.indiceItinerario = indiceItinerario;
    }

}
