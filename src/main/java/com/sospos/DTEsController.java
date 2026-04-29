package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.db.SupabaseService;
import com.sospos.model.ItemCarrito;
import com.sospos.model.Producto;
import com.sospos.service.BoletaPdfService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DTEsController {

    @FXML private Label countLabel;
    @FXML private VBox  listaContainer;
    @FXML private VBox  histContainer;
    @FXML private Label histCountLabel;
    @FXML private Button btnEnviarTodos;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator spinner;

    private final DatabaseService db   = DatabaseService.getInstance();
    private final SupabaseService supa = SupabaseService.getInstance();
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("es", "CL"));

    @FXML
    public void initialize() {
        spinner.setVisible(false);
        cargarLista();
        cargarHistorico();
    }

    // ─── Tab Pendientes ───────────────────────────────────────────────────────

    private void cargarLista() {
        List<DatabaseService.VentaDTE> pendientes = db.getVentasPendientesDTE();
        listaContainer.getChildren().clear();

        int count = pendientes.size();
        countLabel.setText(count + " DTE" + (count != 1 ? "s" : "") + " pendiente" + (count != 1 ? "s" : ""));
        btnEnviarTodos.setDisable(count == 0);

        if (count == 0) {
            listaContainer.getChildren().add(buildEmptyState(
                "Bandeja al día",
                "No tienes DTEs pendientes de envío al SII."));
            return;
        }

        for (DatabaseService.VentaDTE v : pendientes) {
            listaContainer.getChildren().add(buildFilaPendiente(v));
        }
    }

    private VBox buildEmptyState(String titulo, String descripcion) {
        StackPane iconBox = new StackPane();
        iconBox.setMinSize(72, 72);
        iconBox.setMaxSize(72, 72);
        iconBox.setStyle("-fx-background-color: rgba(124,58,237,0.08);"
                + " -fx-background-radius: 999;"
                + " -fx-border-color: rgba(124,58,237,0.18);"
                + " -fx-border-width: 1;"
                + " -fx-border-radius: 999;");
        Label iconLabel = new Label("OK");
        iconLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #7C3AED;"
                + " -fx-letter-spacing: 1.5px;");
        iconBox.getChildren().add(iconLabel);

        Label tit = new Label(titulo);
        tit.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #0F172A;");

        Label desc = new Label(descripcion);
        desc.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");
        desc.setWrapText(true);
        desc.setMaxWidth(360);

        VBox box = new VBox(14, iconBox, tit, desc);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(80, 0, 60, 0));
        return box;
    }

    private HBox buildFilaPendiente(DatabaseService.VentaDTE v) {
        HBox fila = new HBox(16);
        fila.getStyleClass().setAll("dte-fila", "pu-grid-card");
        fila.setAlignment(Pos.CENTER_LEFT);
        fila.setPadding(new Insets(16, 20, 16, 20));

        StackPane icono = new StackPane();
        icono.getStyleClass().add("dte-icono");
        Label iconLetter = new Label("#" + v.id);
        iconLetter.setStyle("-fx-font-size:11px; -fx-font-weight:bold; -fx-text-fill: -pu-brand-light;");
        icono.getChildren().add(iconLetter);

        VBox info = new VBox(4);
        HBox.setHgrow(info, Priority.ALWAYS);

        String fechaCorta = formatFecha(v.fecha);
        Label titulo = new Label("Venta #" + v.id + "  ·  " + fechaCorta);
        titulo.getStyleClass().add("dte-titulo");

        String resumen = v.items.isEmpty() ? "Sin detalle"
                : v.items.get(0).nombre + (v.items.size() > 1 ? " y " + (v.items.size() - 1) + " más" : "");
        Label detalle = new Label(resumen);
        detalle.getStyleClass().add("dte-detalle");
        info.getChildren().addAll(titulo, detalle);

        Label total = new Label("$" + fmt.format((long) v.total));
        total.getStyleClass().add("dte-total");

        Label badge = new Label("Pendiente");
        badge.getStyleClass().add("dte-badge-pendiente");

        Button btnPdf = new Button("PDF");
        btnPdf.getStyleClass().add("btn-pdf-dte");
        btnPdf.setOnAction(e -> descargarPdfVenta(v));

        Button btnEnviar = new Button("Enviar");
        btnEnviar.getStyleClass().add("btn-enviar-uno");
        btnEnviar.setOnAction(e -> enviarUno(v, fila, badge, btnEnviar));

        fila.getChildren().addAll(icono, info, total, badge, btnPdf, btnEnviar);
        return fila;
    }

    private void enviarUno(DatabaseService.VentaDTE v, HBox fila, Label badge, Button btn) {
        String estadoActual = db.getDteEstado(v.id);
        if (!"pendiente".equals(estadoActual)) { cargarLista(); return; }

        btn.setDisable(true);
        badge.setText("Enviando...");
        badge.getStyleClass().setAll("dte-badge-enviando");

        Task<SupabaseService.DTEResult> task = new Task<>() {
            @Override
            protected SupabaseService.DTEResult call() {
                return supa.generarDTE(db, v.id, v.total, toCarrito(v.items));
            }
        };

        task.setOnSucceeded(e -> {
            SupabaseService.DTEResult res = task.getValue();
            if (res.ok) {
                if (res.pdfBase64 != null && !res.pdfBase64.isBlank())
                    db.saveDtePdf(v.id, res.pdfBase64);
                badge.setText("Folio " + res.folio);
                badge.getStyleClass().setAll("dte-badge-emitido");
                btn.setVisible(false);
                actualizarContador();
                new Thread(() -> supa.actualizarVentaDTE(db, v.id, res), "patch-supabase-" + v.id).start();
                Platform.runLater(this::cargarHistorico);
            } else {
                badge.setText("Error");
                badge.getStyleClass().setAll("dte-badge-error");
                btn.setDisable(false);
                mostrarError("No se pudo enviar la venta #" + v.id + ":\n" + res.error);
            }
        });

        task.setOnFailed(e -> {
            badge.setText("Error");
            badge.getStyleClass().setAll("dte-badge-error");
            btn.setDisable(false);
        });

        new Thread(task, "dte-envio-" + v.id).start();
    }

    @FXML
    void onEnviarTodos() {
        List<DatabaseService.VentaDTE> pendientes = db.getVentasPendientesDTE();
        if (pendientes.isEmpty()) return;

        btnEnviarTodos.setDisable(true);
        spinner.setVisible(true);
        statusLabel.setText("● Enviando cola de documentos...");
        statusLabel.getStyleClass().setAll("status-offline");

        Task<int[]> task = new Task<>() {
            @Override
            protected int[] call() {
                int ok = 0, err = 0;
                for (DatabaseService.VentaDTE v : pendientes) {
                    SupabaseService.DTEResult res = supa.generarDTE(db, v.id, v.total, toCarrito(v.items));
                    if (res.ok) { ok++; supa.actualizarVentaDTE(db, v.id, res); }
                    else err++;
                }
                return new int[]{ok, err};
            }
        };

        task.setOnSucceeded(e -> {
            int[] r = task.getValue();
            int result = r[0];
            spinner.setVisible(false);
            btnEnviarTodos.setDisable(false);
            if (result > 0) {
                statusLabel.setText("✓ " + result + " documentos enviados.");
                statusLabel.getStyleClass().setAll("status-online");
                cargarLista();
                cargarHistorico();
            } else {
                statusLabel.setText("⚠ Error al enviar documentos.");
                statusLabel.getStyleClass().setAll("status-error");
            }
        });

        task.setOnFailed(e -> {
            spinner.setVisible(false);
            btnEnviarTodos.setDisable(false);
            statusLabel.setText("⚠ Error de red.");
            statusLabel.getStyleClass().setAll("status-error");
        });

        new Thread(task, "dte-enviar-todos").start();
    }

    // ─── Tab Histórico ────────────────────────────────────────────────────────

    private void cargarHistorico() {
        List<DatabaseService.VentaDTE> todas = db.getVentasHistorico(200);
        histContainer.getChildren().clear();

        int n = todas.size();
        histCountLabel.setText(n + " venta" + (n != 1 ? "s" : "") + " registrada" + (n != 1 ? "s" : ""));

        if (n == 0) {
            histContainer.getChildren().add(buildEmptyState(
                "Aún sin ventas",
                "Cuando registres ventas aparecerán aquí con su estado SII."));
            return;
        }

        for (DatabaseService.VentaDTE v : todas) {
            histContainer.getChildren().add(buildFilaHistorico(v));
        }
    }

    private HBox buildFilaHistorico(DatabaseService.VentaDTE v) {
        HBox fila = new HBox(16);
        fila.getStyleClass().add("pu-grid-card");
        fila.setAlignment(Pos.CENTER_LEFT);
        fila.setPadding(new Insets(14, 20, 14, 20));
        fila.setStyle("-fx-cursor: hand; -fx-background-radius: 12;");

        String estado = nvl(v.dteEstado, "pendiente");

        // Ícono: chip de estado a color
        StackPane icono = new StackPane();
        icono.getStyleClass().add("dte-icono");
        String col = estadoColor(estado);
        icono.setStyle("-fx-background-color:" + col + "1A; -fx-background-radius:10;");
        Label iconLetter = new Label(estadoIcono(estado));
        iconLetter.setStyle("-fx-font-size:11px; -fx-font-weight:bold; -fx-text-fill:" + col + ";");
        icono.getChildren().add(iconLetter);

        // Info
        VBox info = new VBox(3);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label titulo = new Label("Venta #" + v.id + "  ·  " + formatFecha(v.fecha));
        titulo.getStyleClass().add("dte-titulo");

        String resumen = v.items.isEmpty() ? "Sin detalle"
                : v.items.get(0).nombre + (v.items.size() > 1 ? " y " + (v.items.size() - 1) + " más" : "");
        Label detalle = new Label(resumen + "  ·  " + tipoLabel(v.tipoDTE));
        detalle.getStyleClass().add("dte-detalle");
        info.getChildren().addAll(titulo, detalle);

        // Total
        Label total = new Label("$" + fmt.format((long) v.total));
        total.getStyleClass().add("dte-total");

        // Badge
        Label badge = new Label(badgeTexto(v));
        badge.getStyleClass().add(badgeClase(estado));

        // Botón PDF
        Button btnPdf = new Button("PDF");
        btnPdf.getStyleClass().add("btn-ghost");
        btnPdf.setStyle("-fx-text-fill: -pu-brand-light;");
        btnPdf.setOnAction(e -> { e.consume(); descargarPdfVenta(v); });

        fila.getChildren().addAll(icono, info, total, badge, btnPdf);
        fila.setOnMouseClicked(e -> mostrarDetalleVenta(v));
        return fila;
    }

    @FXML
    void onRefrescarHistorico() {
        cargarHistorico();
    }

    // ─── Modal de detalle ─────────────────────────────────────────────────────

    private void mostrarDetalleVenta(DatabaseService.VentaDTE v) {
        String estado    = nvl(v.dteEstado, "pendiente");
        long   mntTotal  = Math.round(v.total);
        long   mntNeto   = Math.round(v.total / 1.19);
        long   mntIva    = mntTotal - mntNeto;

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.TRANSPARENT);
        javafx.stage.Window owner = histContainer.getScene().getWindow();
        stage.initOwner(owner);

        // ── Header (color según estado) ──
        String headerColor = switch (estado) {
            case "emitido"  -> "#10B981";
            case "error"    -> "#EF4444";
            default         -> "#F59E0B";
        };
        StackPane hIco = new StackPane();
        hIco.setMinSize(34, 34); hIco.setMaxSize(34, 34);
        hIco.setStyle("-fx-background-color:rgba(255,255,255,0.18); -fx-background-radius:10;");
        Label hIcoLbl = new Label(estadoIcono(estado));
        hIcoLbl.setStyle("-fx-font-size:11px; -fx-font-weight:bold; -fx-text-fill:white;");
        hIco.getChildren().add(hIcoLbl);
        Label hTitle = new Label("Venta #" + v.id);
        hTitle.setStyle("-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:white;");
        Label hSub = new Label(tipoLabel(v.tipoDTE) + "  ·  " + formatFecha(v.fecha));
        hSub.setStyle("-fx-font-size:11px; -fx-text-fill:rgba(255,255,255,0.75);");
        VBox hTexts = new VBox(2, hTitle, hSub);
        Region hSpacer = new Region();
        HBox.setHgrow(hSpacer, Priority.ALWAYS);

        Label badgeDet = new Label(badgeTexto(v));
        badgeDet.getStyleClass().setAll("badge", badgeClase(estado));

        HBox header = new HBox(12, hIco, hTexts, hSpacer, badgeDet);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("pu-modal-header");
        header.setStyle("-fx-background-color: -pu-bg-overlay;");

        // ── Info grid (2 columnas) ──
        VBox infoGrid = new VBox(8);
        infoGrid.getStyleClass().add("pu-modal-body");
        infoGrid.setStyle("-fx-background-color: transparent; -fx-padding: 24;");

        infoGrid.getChildren().add(filaInfo("Método de pago", capitalize(nvl(v.metodoPago, "—"))));
        if (v.folio > 0)
            infoGrid.getChildren().add(filaInfo("Folio DTE", String.valueOf(v.folio)));
        if (v.trackId != null && !v.trackId.isBlank())
            infoGrid.getChildren().add(filaInfo("Track ID", v.trackId));

        // ── Items del detalle ──
        VBox itemsBox = new VBox(4);
        itemsBox.setStyle("-fx-padding: 0 24;");
        for (DatabaseService.VentaDTE.ItemDTE item : v.items) {
            Label lNom = new Label(item.cantidad + "x  " + item.nombre);
            lNom.setStyle("-fx-font-size:12px; -fx-text-fill: -pu-text-primary;");
            lNom.setWrapText(true);
            lNom.setMaxWidth(260);
            Region iSp = new Region();
            HBox.setHgrow(iSp, Priority.ALWAYS);
            Label lSub = new Label("$" + fmt.format(item.montoItem));
            lSub.setStyle("-fx-font-size:12px; -fx-font-weight:bold; -fx-text-fill: -pu-text-primary;");
            HBox row = new HBox(lNom, iSp, lSub);
            row.setPadding(new Insets(3, 0, 3, 0));
            itemsBox.getChildren().add(row);
        }

        // Separator
        Region sep1 = new Region(); sep1.getStyleClass().add("pu-divider");
        Region sep2 = new Region(); sep2.getStyleClass().add("pu-divider");
        VBox.setMargin(sep1, new Insets(10, 24, 10, 24));
        VBox.setMargin(sep2, new Insets(10, 24, 10, 24));

        // ── Totales ──
        VBox totalesBox = new VBox(4);
        totalesBox.setStyle("-fx-padding: 0 24;");
        totalesBox.getChildren().addAll(
            filaTotales("Subtotal", "$" + fmt.format(mntNeto), false),
            filaTotales("IVA (19%)", "$" + fmt.format(mntIva), false)
        );
        // Total grande
        Label lblTNom = new Label("TOTAL");
        lblTNom.getStyleClass().add("pu-form-section-title");
        Label lblTVal = new Label("$" + fmt.format(mntTotal));
        lblTVal.setStyle("-fx-font-size:24px; -fx-font-weight:bold; -fx-text-fill: -pu-brand-light;");
        Region tSp = new Region();
        HBox.setHgrow(tSp, Priority.ALWAYS);
        HBox rowTotal = new HBox(lblTNom, tSp, lblTVal);
        rowTotal.setAlignment(Pos.CENTER);
        rowTotal.setPadding(new Insets(6, 0, 0, 0));
        totalesBox.getChildren().add(rowTotal);

        // ── Botones ──
        Button btnPdf = new Button("Descargar PDF");
        btnPdf.getStyleClass().add("btn-primary");
        btnPdf.setOnAction(e -> descargarPdfVenta(v));

        Button btnCerrar = new Button("Cerrar");
        btnCerrar.getStyleClass().add("btn-secondary");
        btnCerrar.setOnAction(e -> stage.close());

        HBox botonesBox = new HBox(12, btnPdf, btnCerrar);
        botonesBox.setAlignment(Pos.CENTER_RIGHT);
        botonesBox.setPadding(new Insets(6, 0, 0, 0));

        // ── Content ──
        VBox content = new VBox(0, infoGrid, sep1, itemsBox, sep2, totalesBox, botonesBox);
        content.setStyle("-fx-background-color: transparent;");

        // ── Footer dots ──
        Label fd1 = new Label();
        fd1.setStyle("-fx-background-color:" + headerColor + "33; -fx-background-radius:4;"
            + "-fx-min-width:28; -fx-max-width:28; -fx-min-height:4; -fx-max-height:4;");
        Label fd2 = new Label();
        fd2.setStyle("-fx-background-color:" + headerColor + "33; -fx-background-radius:4;"
            + "-fx-min-width:4; -fx-max-width:4; -fx-min-height:4; -fx-max-height:4;");
        Label fd3 = new Label();
        fd3.setStyle("-fx-background-color:" + headerColor + "33; -fx-background-radius:4;"
            + "-fx-min-width:4; -fx-max-width:4; -fx-min-height:4; -fx-max-height:4;");
        HBox footerDots = new HBox(6, fd1, fd2, fd3);
        footerDots.setAlignment(Pos.CENTER);
        footerDots.setPadding(new Insets(12, 0, 12, 0));
        footerDots.setStyle("-fx-background-color: -pu-bg-base; -fx-background-radius: 0 0 20 20;");

        // ── Card ──
        VBox card = new VBox(0, header, content, footerDots);
        card.getStyleClass().add("pu-modal-card");
        card.setMaxWidth(500);

        StackPane root = new StackPane(card);
        root.setAlignment(Pos.CENTER);
        root.getStyleClass().add("pu-modal-root");

        Scene scene = new Scene(root, owner.getWidth(), owner.getHeight());
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/theme.css").toExternalForm());
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/dtes.css").toExternalForm());
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setX(owner.getX());
        stage.setY(owner.getY());
        stage.show();
    }

    // ── helpers del modal ──

    private HBox filaInfo(String label, String valor) {
        Label lbl = new Label(label);
        lbl.getStyleClass().add("pu-form-label");
        lbl.setMinWidth(160);
        Label val = new Label(valor);
        val.setStyle("-fx-font-size:12px; -fx-font-weight:bold; -fx-text-fill: -pu-text-primary;");
        val.setWrapText(true);
        HBox row = new HBox(8, lbl, val);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox filaTotales(String label, String valor, boolean bold) {
        String style = bold
            ? "-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill: -pu-text-primary;"
            : "-fx-font-size:12px; -fx-text-fill: -pu-text-secondary;";
        Label lbl = new Label(label);
        lbl.setStyle(style);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label val = new Label(valor);
        val.setStyle(style);
        HBox row = new HBox(lbl, sp, val);
        row.setPadding(new Insets(2, 0, 2, 0));
        return row;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String formatFecha(String fecha) {
        if (fecha == null) return "—";
        return fecha.length() >= 16 ? fecha.substring(0, 16).replace("T", " ") : fecha;
    }

    private String estadoIcono(String estado) {
        return switch (estado) {
            case "emitido" -> "OK";
            case "error"   -> "ERR";
            default        -> "·";
        };
    }

    private String estadoColor(String estado) {
        return switch (estado) {
            case "emitido" -> "#10B981";
            case "error"   -> "#EF4444";
            default        -> "#F59E0B";
        };
    }

    private String tipoLabel(String tipo) {
        if (tipo == null) return "Boleta";
        return tipo.contains("factura") ? "Factura" : "Boleta";
    }

    private String badgeTexto(DatabaseService.VentaDTE v) {
        String estado = nvl(v.dteEstado, "pendiente");
        return switch (estado) {
            case "emitido" -> "Emitido";
            case "error"   -> "Error";
            default        -> "Pendiente";
        };
    }

    private String badgeClase(String estado) {
        return switch (estado) {
            case "emitido"  -> "dte-badge-emitido";
            case "error"    -> "dte-badge-error";
            case "enviando" -> "dte-badge-enviando";
            default         -> "dte-badge-pendiente";
        };
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ─── PDF ──────────────────────────────────────────────────────────────────

    private void descargarPdfVenta(DatabaseService.VentaDTE v) {
        String storedPdf = db.getDtePdf(v.id);
        if (storedPdf != null && !storedPdf.isBlank()) {
            Platform.runLater(() -> {
                try {
                    byte[] pdfBytes = java.util.Base64.getDecoder().decode(storedPdf);
                    File tmp = File.createTempFile("boleta_dte_" + v.id + "_", ".pdf");
                    tmp.deleteOnExit();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) { fos.write(pdfBytes); }
                    FileChooser fc = new FileChooser();
                    fc.setTitle("Guardar Boleta PDF");
                    fc.setInitialFileName("boleta_venta_" + v.id + ".pdf");
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo PDF", "*.pdf"));
                    File dest = fc.showSaveDialog(listaContainer.getScene().getWindow());
                    if (dest != null) {
                        Files.copy(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        java.awt.Desktop.getDesktop().open(dest);
                    }
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "No se pudo guardar el PDF:\n" + ex.getMessage()).showAndWait();
                }
            });
            return;
        }

        // Fallback: generar PDF local
        String nombreNeg = nvl(db.getConfig("negocio_razon_social"), "Mi Negocio");
        String tdteNom   = "factura".equalsIgnoreCase(v.tipoDTE) ? "FACTURA ELECTRONICA" : "BOLETA ELECTRONICA";
        String folioStr  = v.folio > 0 ? String.valueOf(v.folio) : null;

        BoletaPdfService.BoletaData data = new BoletaPdfService.BoletaData(
                nombreNeg,
                nvl(db.getConfig("negocio_rut"),       ""),
                nvl(db.getConfig("negocio_giro"),      ""),
                nvl(db.getConfig("negocio_direccion"), ""),
                nvl(db.getConfig("negocio_telefono"),  ""),
                tdteNom, folioStr,
                "Cliente Estimado", "66666666-6", "",
                toCarrito(v.items), v.total, nvl(v.metodoPago, "otro"),
                0, v.trackId, v.id
        );

        Task<File> task = new Task<>() {
            @Override protected File call() throws Exception { return BoletaPdfService.generarPdf(data); }
        };

        task.setOnSucceeded(ev -> Platform.runLater(() -> {
            File tmp = task.getValue();
            FileChooser fc = new FileChooser();
            fc.setTitle("Guardar Boleta PDF");
            fc.setInitialFileName("boleta_venta_" + v.id + ".pdf");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo PDF", "*.pdf"));
            File dest = fc.showSaveDialog(listaContainer.getScene().getWindow());
            if (dest != null) {
                try {
                    Files.copy(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    java.awt.Desktop.getDesktop().open(dest);
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "No se pudo guardar:\n" + ex.getMessage()).showAndWait();
                }
            }
        }));

        task.setOnFailed(ev -> Platform.runLater(() ->
            new Alert(Alert.AlertType.ERROR, "Error generando PDF:\n" + task.getException().getMessage()).showAndWait()));

        new Thread(task, "pdf-dte-" + v.id).start();
    }

    // ─── Misc ─────────────────────────────────────────────────────────────────

    private String nvl(String s, String def) { return (s == null || s.isBlank()) ? def : s; }

    @FXML
    void onVolver() {
        try { App.showDashboard(); } catch (Exception e) { e.printStackTrace(); }
    }

    private void actualizarContador() {
        int count = db.countDTEsPendientes();
        Platform.runLater(() -> {
            countLabel.setText(count + " DTE" + (count != 1 ? "s" : "") + " pendiente" + (count != 1 ? "s" : ""));
            btnEnviarTodos.setDisable(count == 0);
        });
    }

    private List<ItemCarrito> toCarrito(List<DatabaseService.VentaDTE.ItemDTE> itemsDTE) {
        List<ItemCarrito> items = new ArrayList<>();
        for (DatabaseService.VentaDTE.ItemDTE i : itemsDTE) {
            Producto p = new Producto(0, i.nombre, i.precioUnitario, i.cantidad, "");
            ItemCarrito ic = new ItemCarrito(p);
            ic.setCantidad(i.cantidad);
            items.add(ic);
        }
        return items;
    }

    private void mostrarError(String msg) {
        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, msg).showAndWait());
    }
}
