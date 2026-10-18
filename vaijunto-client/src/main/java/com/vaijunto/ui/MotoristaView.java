package com.vaijunto.ui;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import model.Cidades;
import model.TipoUser;
import network.DTOResponse;
import sessao.SessaoUsuario;
import util.ClienteTask;


/**
 * Dashboard destinado ao usuário motorista.
 *
 * Permite montar uma rota com várias cidades, informar data, horário, vagas
 * e preço de cada trecho, e publicar uma nova carona.
 *
 * Também permite listar as caronas do motorista, visualizar seus dados,
 * consultar passageiros por trecho, cancelar caronas, alterar o tipo de
 * usuário e encerrar a sessão.
 *
 * A tela realiza validações básicas antes de enviar os dados ao servidor,
 * como verificar cidades repetidas, preços inválidos, horários inválidos e
 * quantidade correta de trechos.
 */
public class MotoristaView extends VBox {
    private final SessaoUsuario sessao;
    private final NavegacaoManager navegacao;
    private final VBox rotaEditor = new VBox(8);
    private final List<ComboBox<Cidades>> cidadesRota = new ArrayList<>();
    private final List<TextField> precosTrechos = new ArrayList<>();
    private final DatePicker data = new DatePicker();
    private final TextField hora = new TextField();
    private final Spinner<Integer> vagas = new Spinner<>(1, 99, 1);
    private final TextField idCarona = new TextField();
    private final ListView<String> caronas = new ListView<>();
    private final TextArea detalhes = new TextArea();
    private final TextField status = new TextField();
    private final Button publicar = new Button("Oferecer carona");
    private final Button listar = new Button("Minhas caronas");

    public MotoristaView(SessaoUsuario sessao, NavegacaoManager navegacao) {
        this.sessao = sessao;
        this.navegacao = navegacao;
        montarLayout();
    }

    private void montarLayout() {
        setSpacing(12);
        setPadding(new Insets(24));
        status.setEditable(false);
        VBox conteudo = new VBox(12);
        conteudo.setFillWidth(true);

        Label titulo = new Label("Dashboard do Motorista - " + sessao.getLogin());
        titulo.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        List<Cidades> disponiveis = List.of(Cidades.values()).stream()
                .filter(Cidades::isDisponivel).toList();
        data.setPromptText("Data");
        hora.setPromptText("Hora (HH:mm)");

        GridPane formulario = new GridPane();
        formulario.setHgap(8);
        formulario.setVgap(8);
        formulario.add(new Label("Data:"), 0, 0);
        formulario.add(data, 1, 0);
        formulario.add(new Label("Hora:"), 0, 1);
        formulario.add(hora, 1, 1);
        formulario.add(new Label("Vagas:"), 0, 2);
        formulario.add(vagas, 1, 2);

        Button adicionarTrecho = new Button("Adicionar cidade");
        adicionarTrecho.setOnAction(e -> adicionarCidade(disponiveis));
        Button removerTrecho = new Button("Remover última cidade");
        removerTrecho.setOnAction(e -> removerUltimaCidade());
        HBox controlesRota = new HBox(8, adicionarTrecho, removerTrecho);
        rotaEditor.setPadding(new Insets(8, 0, 8, 0));
        adicionarCidade(disponiveis);
        adicionarCidade(disponiveis);

        publicar.setOnAction(e -> oferecerCarona());
        listar.setOnAction(e -> listarCaronas());
        caronas.setPlaceholder(new Label("Nenhuma carona carregada."));
        caronas.setPrefHeight(300);
        caronas.setMinHeight(220);
        detalhes.setEditable(false);
        detalhes.setWrapText(true);
        detalhes.setPrefRowCount(3);
        detalhes.setPromptText("Selecione uma carona para copiar seus dados.");
        caronas.getSelectionModel().selectedItemProperty()
                .addListener((obs, antigo, novo) -> detalhes.setText(novo == null ? "" : novo));

        idCarona.setPromptText("ID da carona");
        Button cancelar = new Button("Cancelar carona");
        cancelar.setOnAction(e -> cancelarCarona());
        Button passageiros = new Button("Consultar passageiros");
        passageiros.setOnAction(e -> consultarPassageiros());
        HBox operacoes = new HBox(8, idCarona, cancelar, passageiros);

        Button trocar = new Button("Mudar para Passageiro");
        trocar.setOnAction(e -> mudarTipo());
        Button sair = new Button("Sair");
        sair.setOnAction(e -> sair());

        conteudo.getChildren().addAll(titulo, formulario, new Label("Rota e preço de cada trecho:"),
                rotaEditor, controlesRota, publicar, listar, caronas, detalhes,
                operacoes, trocar, sair, status);

        ScrollPane rolagem = new ScrollPane(conteudo);
        rolagem.setFitToWidth(true);
        rolagem.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rolagem.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        rolagem.setPannable(true);
        VBox.setVgrow(rolagem, javafx.scene.layout.Priority.ALWAYS);
        getChildren().add(rolagem);
    }

    private void oferecerCarona() {
        if (data.getValue() == null || hora.getText().isBlank()) {
            status.setText("Preencha todos os dados da carona.");
            return;
        }
        try {
            LocalTime.parse(hora.getText().trim());
            List<String> rota = new ArrayList<>();
            Set<Cidades> cidadesUsadas = new HashSet<>();
            for (ComboBox<Cidades> cidade : cidadesRota) {
                if (cidade.getValue() == null || !cidadesUsadas.add(cidade.getValue())) {
                    status.setText("Escolha cidades diferentes para toda a rota.");
                    return;
                }
                rota.add(cidade.getValue().name());
            }
            List<Double> precos = new ArrayList<>();
            for (TextField campoPreco : precosTrechos) {
                double valor = Double.parseDouble(campoPreco.getText().trim().replace(',', '.'));
                if (!Double.isFinite(valor) || valor < 0) {
                    throw new NumberFormatException();
                }
                precos.add(valor);
            }
            if (precos.size() != rota.size() - 1) {
                status.setText("Informe um preço para cada trecho da rota.");
                return;
            }
            executarOperacao("Publicando carona...", () -> sessao.getClient().oferecerCarona(
                    sessao.getToken(), data.getValue().toString(), hora.getText().trim(),
                    rota, precos, vagas.getValue()), resposta -> {
                        status.setText(resposta.getMensagem()
                                + (resposta.getDados() == null ? "" : " ID: " + resposta.getDados()));
                    });
        } catch (NumberFormatException erro) {
            status.setText("Preço inválido.");
        } catch (java.time.format.DateTimeParseException erro) {
            status.setText("Hora inválida. Use HH:mm.");
        }
    }

    private void adicionarCidade(List<Cidades> disponiveis) {
        ComboBox<Cidades> cidade = new ComboBox<>();
        cidade.getItems().setAll(disponiveis);
        cidade.setPromptText("Cidade " + (cidadesRota.size() + 1));
        cidadesRota.add(cidade);
        if (cidadesRota.size() > 1) {
            TextField precoTrecho = new TextField();
            precoTrecho.setPromptText("Preço até esta cidade");
            precosTrechos.add(precoTrecho);
        }
        renderizarRota();
    }

    private void removerUltimaCidade() {
        if (cidadesRota.size() <= 2) {
            status.setText("A rota precisa ter pelo menos duas cidades.");
            return;
        }
        cidadesRota.remove(cidadesRota.size() - 1);
        precosTrechos.remove(precosTrechos.size() - 1);
        renderizarRota();
    }

    private void renderizarRota() {
        rotaEditor.getChildren().clear();
        for (int i = 0; i < cidadesRota.size(); i++) {
            HBox trecho = new HBox(8);
            trecho.getChildren().add(new Label(i == 0 ? "Origem:" : "Cidade " + (i + 1) + ":"));
            trecho.getChildren().add(cidadesRota.get(i));
            if (i > 0) {
                trecho.getChildren().add(new Label("Preço do trecho anterior:"));
                trecho.getChildren().add(precosTrechos.get(i - 1));
            }
            rotaEditor.getChildren().add(trecho);
        }
    }

    private void listarCaronas() {
        executarOperacao("Carregando caronas...", () ->
                sessao.getClient().minhasCaronas(sessao.getToken()), resposta -> {
                    caronas.getItems().clear();
                    if (resposta.isSucesso() && resposta.getDados() instanceof List<?> lista) {
                        for (Object carona : lista) {
                            caronas.getItems().add(formatarCarona(carona));
                        }
                    }
                    status.setText(resposta.getMensagem());
                });
    }

    private void cancelarCarona() {
        if (idCarona.getText().isBlank()) {
            status.setText("Informe o ID da carona.");
            return;
        }
        executarOperacao("Cancelando carona...", () ->
                sessao.getClient().cancelaCarona(sessao.getToken(), idCarona.getText().trim()),
                resposta -> status.setText(resposta.getMensagem()));
    }

    private void consultarPassageiros() {
        if (idCarona.getText().isBlank()) {
            status.setText("Informe o ID da carona.");
            return;
        }
        executarOperacao("Consultando passageiros...", () ->
                sessao.getClient().consultaPassageiros(sessao.getToken(), idCarona.getText().trim()),
                resposta -> {
                    caronas.getItems().clear();
                    if (resposta.isSucesso() && resposta.getDados() instanceof java.util.Map<?, ?> mapa) {
                        mapa.forEach((trecho, nomes) -> caronas.getItems().add(trecho + ": " + nomes));
                    }
                    status.setText(resposta.getMensagem());
                });
    }

    private void mudarTipo() {
        executarOperacao("Atualizando tipo...", () ->
                sessao.getClient().mudarTipo(sessao.getToken(), TipoUser.PASSAGEIRO), resposta -> {
                    if (!resposta.isSucesso()) {
                        status.setText(resposta.getMensagem());
                        return;
                    }
                    sessao.atualizarTipoUsuario(TipoUser.PASSAGEIRO);
                    RoteadorDashboard.mostrarDashboardCorreto(sessao, navegacao);
                });
    }

    private void sair() {
        executarOperacao("Encerrando sessão...", () ->
                sessao.getClient().logout(sessao.getToken()), resposta -> {
                    if (!resposta.isSucesso()) {
                        status.setText(resposta.getMensagem());
                        return;
                    }
                    try {
                        sessao.fechar();
                        navegacao.mostrar(new LoginView(navegacao));
                    } catch (java.io.IOException erro) {
                        status.setText("Erro ao fechar conexão: " + mensagem(erro));
                    }
                });
    }

    private void executarOperacao(String andamento,
            java.util.concurrent.Callable<DTOResponse<?>> chamada,
            java.util.function.Consumer<DTOResponse<?>> sucesso) {
        publicar.setDisable(true);
        listar.setDisable(true);
        status.setText(andamento);
        ClienteTask.<DTOResponse<?>>executar(chamada, resposta -> {
            publicar.setDisable(false);
            listar.setDisable(false);
            sucesso.accept(resposta);
        }, erro -> {
            publicar.setDisable(false);
            listar.setDisable(false);
            status.setText("Erro de conexão: " + mensagem(erro));
        });
    }

    private static String mensagem(Throwable erro) {
        return erro.getMessage() == null ? erro.getClass().getSimpleName() : erro.getMessage();
    }

    private static String formatarCarona(Object valor) {
        if (!(valor instanceof java.util.Map<?, ?> mapa)) {
            return String.valueOf(valor);
        }
        String id = texto(mapa.get("id"));
        String data = texto(mapa.get("data"));
        String hora = texto(mapa.get("hora"));
        String vagas = texto(mapa.get("vagasTotais"));
        String rota = formatarRota(mapa.get("rota"));
        return "ID: " + id + " | " + rota + " | " + data + " " + hora + " | vagas: " + vagas;
    }

    private static String formatarRota(Object valor) {
        if (!(valor instanceof List<?> lista)) {
            return "-";
        }
        return lista.stream().map(MotoristaView::texto).collect(java.util.stream.Collectors.joining(" -> "));
    }

    private static String texto(Object valor) {
        return valor == null ? "-" : String.valueOf(valor);
    }
}
