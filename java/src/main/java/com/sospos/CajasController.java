package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.db.DatabaseService.CajaInfo;
import com.sospos.model.UserSession;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class CajasController {

    @FXML private FlowPane cajasGrid;

    private final DatabaseService db = DatabaseService.getInstance();
    private final NumberFormat fmt = NumberFormat.getIntegerInstance(new Locale("es", "CL"));

    @FXML
    public void initialize() {
        cargarCajas();
    }

    private void cargarCajas() {
        cajasGrid.getChildren().clear();
        List<CajaInfo> cajas = db.getCajas();

        if (cajas.isEmpty()) {
            Label lbl = new Label("No hay cajas disponibles.\nSincroniza para obtener tus cajas.");
            lbl.getStyleClass().add("label-sub");
            lbl.setStyle("-fx-text-alignment:center;");
            lbl.setWrapText(true);
            cajasGrid.getChildren().add(lbl);
            return;
        }

        for (CajaInfo c : cajas) {
            cajasGrid.getChildren().add(crearTarjeta(c));
        }
    }

    private VBox crearTarjeta(CajaInfo c) {
        VBox card = new VBox(0);
        card.getStyleClass().add("pu-grid-card");
        card.setPrefWidth(320);
        card.setMinHeight(260);

        boolean abierta = c.isAbierta();
        String accentColor = abierta ? "#10B981" : "#94A3B8";

        // ── Banda decorativa superior ──
        Region accent = new Region();
        accent.setPrefHeight(4);
        accent.setMaxHeight(4);
        accent.setStyle("-fx-background-color: linear-gradient(to right,"
                + accentColor + ", -pu-brand-light);"
                + " -fx-background-radius: 14 14 0 0;");

        // ── Body ──
        VBox body = new VBox(16);
        body.setStyle("-fx-padding: 22 22 18 22;");

        // Header (avatar + nombre + badge)
        StackPane avatar = new StackPane();
        avatar.setMinSize(44, 44); avatar.setMaxSize(44, 44);
        avatar.setStyle("-fx-background-color: linear-gradient(to bottom right,"
                + (abierta ? "-pu-brand-light" : "-pu-bg-overlay") + ","
                + (abierta ? "-pu-brand" : "-pu-bg-base") + ");"
                + " -fx-background-radius: 12;");
        String initial = (c.nombre != null && !c.nombre.isBlank())
                ? c.nombre.trim().substring(0, 1).toUpperCase() : "·";
        Label lAvatar = new Label(initial);
        lAvatar.setStyle("-fx-font-size:18px; -fx-font-weight:bold; -fx-text-fill:white;");
        avatar.getChildren().add(lAvatar);

        VBox nombreBox = new VBox(2);
        Label lblNombre = new Label(c.nombre != null ? c.nombre : "Caja");
        lblNombre.getStyleClass().add("caja-nombre");
        Label lblId = new Label("ID " + c.idCorto());
        lblId.getStyleClass().add("caja-id");
        nombreBox.getChildren().addAll(lblNombre, lblId);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label badge = new Label(abierta ? "ABIERTA" : "CERRADA");
        badge.getStyleClass().add(abierta ? "badge-abierta" : "badge-cerrada");

        HBox topRow = new HBox(12, avatar, nombreBox, spacer, badge);
        topRow.setAlignment(Pos.CENTER_LEFT);

        // Separador suave
        Region sep = new Region();
        sep.getStyleClass().add("pu-divider");

        // Info
        VBox infoBox = new VBox(10);
        String estadoTexto = abierta ? "Disponible para ventas" : "Requiere apertura";
        infoBox.getChildren().add(filaInfo(
                abierta ? "ON" : "—", accentColor,
                "Estado", estadoTexto));

        if (c.bodegaNombre != null && !c.bodegaNombre.isBlank()) {
            infoBox.getChildren().add(filaInfo("BD", "#7C3AED", "Bodega", c.bodegaNombre));
        }

        // Botón
        Button btnEntrar = new Button(abierta ? "Entrar a la caja  →" : "Aperturar caja  →");
        btnEntrar.getStyleClass().add(abierta ? "btn-primary" : "btn-secondary");
        btnEntrar.setMaxWidth(Double.MAX_VALUE);
        btnEntrar.setOnAction(e -> entrarACaja(c));

        body.getChildren().addAll(topRow, sep, infoBox, btnEntrar);

        card.getChildren().addAll(accent, body);
        return card;
    }

    private HBox filaInfo(String tag, String tagColor, String label, String valor) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        StackPane iconBox = new StackPane();
        iconBox.getStyleClass().add("icono-info");
        iconBox.setStyle("-fx-background-color:" + tagColor + "1A;"
                + " -fx-background-radius:8;");
        Label ico = new Label(tag);
        ico.getStyleClass().add("pu-form-label");
        ico.setStyle("-fx-text-fill:" + tagColor + ";");
        iconBox.getChildren().add(ico);

        VBox texto = new VBox(1);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("info-label");
        Label val = new Label(valor);
        val.getStyleClass().add("info-value");
        texto.getChildren().addAll(lbl, val);

        row.getChildren().addAll(iconBox, texto);
        return row;
    }

    private void entrarACaja(CajaInfo c) {
        UserSession.getInstance().setCajaId(c.supabaseId);
        try { App.showPOS(); } catch (Exception e) { e.printStackTrace(); }
    }

    private void aperturarCaja(CajaInfo c) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Aperturar Caja");
        alert.setHeaderText(c.nombre);
        alert.setContentText("Para aperturar la caja necesitas conexión a Supabase.\n" +
                "Conéctate a internet y sincroniza desde el dashboard.");
        alert.showAndWait();
    }

    private void cerrarSesionCaja(CajaInfo c) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Cerrar Sesión");
        confirm.setHeaderText("¿Cerrar sesión en " + c.nombre + "?");
        confirm.setContentText("Esto requiere conexión a Supabase.");
        confirm.showAndWait().ifPresent(r -> {
            if (r == ButtonType.OK) {
                new Alert(Alert.AlertType.INFORMATION,
                        "Para cerrar caja conéctate a internet y usa el sistema principal.").showAndWait();
            }
        });
    }

    @FXML
    void onVolver() {
        try { App.showDashboard(); } catch (Exception e) { e.printStackTrace(); }
    }
}
