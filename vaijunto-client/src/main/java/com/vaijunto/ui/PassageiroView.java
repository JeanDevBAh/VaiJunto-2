package com.vaijunto.ui;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.text.NumberFormat;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import model.Cidades;
import model.TipoUser;
import network.DTOResponse;
import sessao.SessaoUsuario;
import util.ClienteTask;

/**
 * Dashboard destinado ao usuário passageiro.
 *
 * Permite selecionar origem, destino e data para pesquisar itinerários
 * disponíveis no servidor.
 *
 * O passageiro pode selecionar um itinerário, realizar a reserva, consultar
 * suas reservas e cancelar uma reserva existente.
 *
 * As operações de rede são executadas de forma assíncrona e os resultados são
 * refletidos nas listas visuais da interface.
 */
public class PassageiroView extends VBox {

    private final SessaoUsuario sessao;
    private final NavegacaoManager navegacao;
    private final ComboBox<Cidades> campoOrigem = new ComboBox<>();
    private final ComboBox<Cidades> campoDestino = new ComboBox<>();
    private final DatePicker campoData = new DatePicker();
    private final ListView<String> resultados = new ListView<>();
    private final ListView<String> reservas = new ListView<>();
    private final TextField campoReserva = new TextField();
    private final Label labelStatus = new Label();
    private final Button btnBuscar = new Button("Buscar viagens");
    private final Button btnReservar = new Button("Reservar selecionada");

    public PassageiroView(SessaoUsuario sessao, NavegacaoManager navegacao) {
        this.sessao = sessao;
        this.navegacao = navegacao;
        montarLayout();
    }

    private void montarLayout() {
        setSpacing(12);
        setPadding(new Insets(24));

        Label titulo = new Label("Dashboard do Passageiro - " + sessao.getLogin());
        titulo.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        List<Cidades> cidades = List.of(Cidades.values()).stream()
                .filter(Cidades::isDisponivel)
                .toList();
        campoOrigem.getItems().setAll(cidades);
        campoDestino.getItems().setAll(cidades);
        campoOrigem.setPromptText("Origem");
        campoDestino.setPromptText("Destino");
        campoData.setPromptText("Data");

        GridPane busca = new GridPane();
        busca.setHgap(8);
        busca.setVgap(8);
        busca.add(new Label("Origem:"), 0, 0);
        busca.add(campoOrigem, 1, 0);
        busca.add(new Label("Destino:"), 0, 1);
        busca.add(campoDestino, 1, 1);
        busca.add(new Label("Data:"), 0, 2);
        busca.add(campoData, 1, 2);

        btnBuscar.setOnAction(e -> buscarViagens());
        btnReservar.setDisable(true);
        btnReservar.setOnAction(e -> reservarSelecionada());
        resultados.setPlaceholder(new Label("Nenhuma viagem pesquisada."));
        resultados.setPrefHeight(180);
        resultados.setCellFactory(lista -> {
            return new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean vazio) {
                    super.updateItem(item, vazio);
                    setText(vazio ? null : item);
                    setGraphic(null);
                    setWrapText(!vazio);
                    setPrefHeight(vazio ? 0 : Region.USE_COMPUTED_SIZE);
                }
            };
        });
        resultados.getSelectionModel().selectedItemProperty()
                .addListener((obs, antigo, novo) -> btnReservar.setDisable(novo == null));

        HBox reserva = new HBox(8);
        campoReserva.setPromptText("ID da reserva para cancelar");
        Button btnCancelar = new Button("Cancelar reserva");
        btnCancelar.setOnAction(e -> cancelarReserva());
        reserva.getChildren().addAll(campoReserva, btnCancelar);
        Button btnMinhasReservas = new Button("Consultar minhas reservas");
        btnMinhasReservas.setOnAction(e -> consultarReservas());
        reservas.setPlaceholder(new Label("Nenhuma reserva encontrada."));
        reservas.setPrefHeight(160);

        Button btnVirarMotorista = new Button("Mudar para Motorista");
        btnVirarMotorista.setOnAction(e -> mudarTipo());
        Button btnSair = new Button("Sair");
        btnSair.setOnAction(e -> sair());

        getChildren().addAll(
                titulo, busca, btnBuscar, resultados, btnReservar,
                reserva, btnMinhasReservas, reservas, btnVirarMotorista, btnSair, labelStatus
        );
        consultarReservas();
    }

    private void buscarViagens() {
        if (campoOrigem.getValue() == null || campoDestino.getValue() == null
                || campoData.getValue() == null) {
            labelStatus.setText("Informe origem, destino e data.");
            return;
        }
        if (campoOrigem.getValue() == campoDestino.getValue()) {
            labelStatus.setText("Origem e destino devem ser diferentes.");
            return;
        }

        setOperacaoEmAndamento(true);
        labelStatus.setText("Buscando viagens...");
        resultados.getItems().clear();
        btnReservar.setDisable(true);
        ClienteTask.<DTOResponse<?>>executar(
                () -> sessao.getClient().buscarViagens(
                        campoOrigem.getValue().name(),
                        campoDestino.getValue().name(),
                        campoData.getValue().toString()),
                resposta -> {
                    setOperacaoEmAndamento(false);
                    if (!resposta.isSucesso()) {
                        labelStatus.setText(resposta.getMensagem());
                        return;
                    }
                    Object dados = resposta.getDados();
                    if (dados instanceof List<?> lista) {
                        for (Object itinerario : lista) {
                            resultados.getItems().add(formatarItinerario(itinerario));
                        }
                    }
                    labelStatus.setText(resultados.getItems().isEmpty()
                            ? "Nenhuma viagem encontrada."
                            : "Selecione uma viagem para reservar.");
                },
                erro -> {
                    setOperacaoEmAndamento(false);
                    labelStatus.setText("Erro de conexão: " + mensagemErro(erro));
                });
    }

    private void reservarSelecionada() {
        int indice = resultados.getSelectionModel().getSelectedIndex();
        if (indice < 0) {
            labelStatus.setText("Selecione uma viagem.");
            return;
        }

        setOperacaoEmAndamento(true);
        labelStatus.setText("Confirmando reserva...");
        ClienteTask.<DTOResponse<?>>executar(
                () -> sessao.getClient().reservar(indice, sessao.getToken()),
                resposta -> {
                    setOperacaoEmAndamento(false);
                    labelStatus.setText(resposta.getMensagem());
                    if (resposta.isSucesso() && resposta.getDados() != null) {
                        campoReserva.setText(String.valueOf(resposta.getDados()));
                        consultarReservas();
                    }
                },
                erro -> {
                    setOperacaoEmAndamento(false);
                    labelStatus.setText("Erro de conexão: " + mensagemErro(erro));
                });
    }

    private void consultarReservas() {
        ClienteTask.<DTOResponse<?>>executar(
                () -> sessao.getClient().minhasReservas(sessao.getToken()),
                resposta -> {
                    if (!resposta.isSucesso()) {
                        labelStatus.setText(resposta.getMensagem());
                        return;
                    }
                    reservas.getItems().clear();
                    Object dados = resposta.getDados();
                    if (dados instanceof List<?> lista) {
                        for (Object reserva : lista) {
                            reservas.getItems().add(formatarReserva(reserva));
                        }
                    }
                    labelStatus.setText(reservas.getItems().isEmpty()
                            ? "Você ainda não possui reservas."
                            : "Reservas atualizadas.");
                },
                erro -> labelStatus.setText("Erro de conexão: " + mensagemErro(erro)));
    }

    private void cancelarReserva() {
        String id = campoReserva.getText();
        if (id == null || id.isBlank()) {
            labelStatus.setText("Informe o ID da reserva.");
            return;
        }

        setOperacaoEmAndamento(true);
        ClienteTask.<DTOResponse<?>>executar(
                () -> sessao.getClient().cancelaReserva(sessao.getToken(), id.trim()),
                resposta -> {
                    setOperacaoEmAndamento(false);
                    labelStatus.setText(resposta.getMensagem());
                    if (resposta.isSucesso()) {
                        campoReserva.clear();
                        consultarReservas();
                    }
                },
                erro -> {
                    setOperacaoEmAndamento(false);
                    labelStatus.setText("Erro de conexão: " + mensagemErro(erro));
                });
    }

    private void mudarTipo() {
        labelStatus.setText("Atualizando tipo de usuário...");
        ClienteTask.<DTOResponse<?>>executar(
                () -> sessao.getClient().mudarTipo(sessao.getToken(), TipoUser.MOTORISTA),
                resposta -> {
                    if (!resposta.isSucesso()) {
                        labelStatus.setText(resposta.getMensagem());
                        return;
                    }
                    sessao.atualizarTipoUsuario(TipoUser.MOTORISTA);
                    RoteadorDashboard.mostrarDashboardCorreto(sessao, navegacao);
                },
                erro -> labelStatus.setText("Erro de conexão: " + mensagemErro(erro)));
    }

    private void sair() {
        labelStatus.setText("Encerrando sessão...");
        ClienteTask.<DTOResponse<?>>executar(
                () -> sessao.getClient().logout(sessao.getToken()),
                resposta -> {
                    if (!resposta.isSucesso()) {
                        labelStatus.setText(resposta.getMensagem());
                        return;
                    }
                    try {
                        sessao.fechar();
                    } catch (java.io.IOException erro) {
                        labelStatus.setText("Não foi possível fechar a conexão: " + mensagemErro(erro));
                        return;
                    }
                    navegacao.mostrar(new LoginView(navegacao));
                },
                erro -> labelStatus.setText("Erro de conexão: " + mensagemErro(erro)));
    }

    private void setOperacaoEmAndamento(boolean emAndamento) {
        btnBuscar.setDisable(emAndamento);
        btnReservar.setDisable(emAndamento || resultados.getSelectionModel().getSelectedItem() == null);
    }

    private static String mensagemErro(Throwable erro) {
        return erro.getMessage() == null ? erro.getClass().getSimpleName() : erro.getMessage();
    }

    private static String formatarItinerario(Object valor) {
        if (!(valor instanceof Map<?, ?> itinerario)) {
            return String.valueOf(valor);
        }

        StringBuilder texto = new StringBuilder();
        Object trechos = itinerario.get("trechos");
        if (trechos instanceof List<?> lista && !lista.isEmpty()) {
            texto.append("Rota: ");
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) {
                    texto.append(" -> ");
                }
                if (lista.get(i) instanceof Map<?, ?> trecho) {
                    texto.append(nomeCidade(trecho.get("cidadeOrigem")));
                    if (i == lista.size() - 1) {
                        texto.append(" -> ").append(nomeCidade(trecho.get("cidadeDestino")));
                    }
                }
            }
            texto.append('\n');

            Map<?, ?> primeiroTrecho = (Map<?, ?>) lista.get(0);
            texto.append("Data: ").append(texto(primeiroTrecho.get("data")))
                    .append(" | Motorista: ").append(texto(primeiroTrecho.get("motorista"))).append('\n');
            texto.append("Trechos: ");
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) {
                    texto.append(" | ");
                }
                if (lista.get(i) instanceof Map<?, ?> trecho) {
                    texto.append(nomeCidade(trecho.get("cidadeOrigem")))
                            .append(" - ").append(nomeCidade(trecho.get("cidadeDestino")))
                            .append(" (").append(formatarPreco(trecho.get("preco"))).append(')');
                }
            }
        } else {
            texto.append("Itinerário sem trechos detalhados");
        }
        texto.append('\n').append("Total: ")
                .append(formatarPreco(itinerario.get("precoTotal")));
        return texto.toString();
    }

    private static String formatarReserva(Object valor) {
        if (!(valor instanceof Map<?, ?> reserva)) {
            return String.valueOf(valor);
        }
        StringBuilder texto = new StringBuilder("ID: ")
                .append(texto(reserva.get("id")))
                .append(" | Status: ")
                .append(Boolean.TRUE.equals(reserva.get("ativa")) ? "ATIVA" : "CANCELADA")
                .append('\n');
        texto.append("Passageiro: ").append(texto(reserva.get("loginPassageiro")))
                .append(" | Total: ").append(formatarPreco(reserva.get("precoTotal")));
        Object trechos = reserva.get("trechos");
        if (trechos instanceof List<?> lista && !lista.isEmpty()) {
            texto.append('\n').append("Rota: ");
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) {
                    texto.append(" -> ");
                }
                if (lista.get(i) instanceof Map<?, ?> trecho) {
                    texto.append(nomeCidade(trecho.get("cidadeOrigem")));
                    if (i == lista.size() - 1) {
                        texto.append(" -> ").append(nomeCidade(trecho.get("cidadeDestino")));
                    }
                }
            }
        }
        return texto.toString();
    }

    private static String nomeCidade(Object valor) {
        if (valor == null) {
            return "-";
        }
        try {
            return Cidades.fromString(String.valueOf(valor)).getNome();
        } catch (IllegalArgumentException erro) {
            return String.valueOf(valor);
        }
    }

    private static String formatarPreco(Object valor) {
        if (!(valor instanceof Number numero)) {
            return "R$ -";
        }
        NumberFormat formato = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
        return formato.format(numero.doubleValue());
    }

    private static String texto(Object valor) {
        return valor == null ? "-" : String.valueOf(valor);
    }
}
