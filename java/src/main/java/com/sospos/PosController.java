package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.db.SupabaseService;
import com.sospos.model.ItemCarrito;
import com.sospos.model.Producto;
import com.sospos.model.UserSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import javafx.scene.Scene;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.input.KeyCode;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import com.sospos.service.BoletaPdfService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class PosController {

    @FXML private FlowPane productsGrid;
    @FXML private TextField searchField;
    @FXML private Label timeLabel;
    @FXML private Label dateLabel;
    @FXML private VBox cartItemsBox;
    @FXML private Label itemsCountLabel;
    @FXML private Label subtotalLabel;
    @FXML private Label totalLabel;
    @FXML private Label emptyCartLabel;
    @FXML private Label cajaLabel;
    @FXML private Label syncStatusLabel;
    @FXML private Button btnEnviarDTE;

    private List<Producto> todosLosProductos = new ArrayList<>();
    private final List<ItemCarrito> carrito = new ArrayList<>();
    private final NumberFormat fmt = NumberFormat.getIntegerInstance(new Locale("es", "CL"));
    private final DatabaseService db = DatabaseService.getInstance();
    private final SupabaseService supa = SupabaseService.getInstance();

    // Datos de la última venta (para la boleta)
    private static class DatosVenta {
        List<ItemCarrito> items;
        int    ventaId;
        double total;
        String metodoPago;
        double vuelto;
        int    tipoDTE;      // 39=boleta, 33=factura
        String clienteRut;
        String clienteNombre;
        String clienteDireccion;
        String folio;
        String trackId;
        String pdfBase64; // PDF del servidor (con folio y TED real)
    }
    private DatosVenta ultimaVenta;

    @FXML
    public void initialize() {
        iniciarReloj();
        actualizarCajaLabel();
        cargarProductos();
        configurarBusqueda();
        actualizarTotales();
        iniciarMonitorEstadoSync();
    }

    // ─── Indicador de estado online/offline + contingencias pendientes ───────
    private void iniciarMonitorEstadoSync() {
        actualizarEstadoSync(); // primer ping inmediato
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(15), e -> actualizarEstadoSync()));
        t.setCycleCount(Timeline.INDEFINITE);
        t.play();
    }

    private void actualizarEstadoSync() {
        if (syncStatusLabel == null) return;
        new Thread(() -> {
            boolean online = supa.testConexion();
            int pendientes = db.countContingenciasPendientes();
            Platform.runLater(() -> {
                if (online) {
                    String txt = pendientes > 0
                        ? "● Online (sync " + pendientes + ")"
                        : "● Online";
                    syncStatusLabel.setText(txt);
                    syncStatusLabel.getStyleClass().setAll("status-online");
                    syncStatusLabel.setStyle("-fx-font-size:11px;");
                } else {
                    String txt = pendientes > 0
                        ? "⚠ Offline — " + pendientes + " pendiente" + (pendientes == 1 ? "" : "s")
                        : "⚠ Offline";
                    syncStatusLabel.setText(txt);
                    syncStatusLabel.getStyleClass().setAll("status-offline");
                    syncStatusLabel.setStyle("-fx-font-size:11px;");
                }
            });
        }, "sync-status").start();
    }

    // ─── Caja ─────────────────────────────────────────────────────────────────
    private void actualizarCajaLabel() {
        if (cajaLabel == null) return;
        String cajaId = UserSession.getInstance().getCajaId();
        if (cajaId == null) { cajaLabel.setText("CAJA"); return; }
        db.getCajas().stream()
                .filter(c -> cajaId.equals(c.supabaseId))
                .findFirst()
                .ifPresent(c -> cajaLabel.setText(c.nombre != null ? c.nombre.toUpperCase() : "CAJA"));
    }

    // ─── Reloj ────────────────────────────────────────────────────────────────
    private void iniciarReloj() {
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("EEEE dd 'de' MMMM", new Locale("es", "CL"));
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            timeLabel.setText(LocalTime.now().format(timeFmt));
            dateLabel.setText(LocalDate.now().format(dateFmt));
        }));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        timeLabel.setText(LocalTime.now().format(timeFmt));
        dateLabel.setText(LocalDate.now().format(dateFmt));
    }

    // ─── Productos ────────────────────────────────────────────────────────────
    private void cargarProductos() {
        todosLosProductos = db.getProductosPOS();
        mostrarProductos(todosLosProductos);
        if (todosLosProductos.isEmpty()) {
            Label hint = new Label("No hay productos sincronizados.\nVuelve al Dashboard y sincroniza.");
            hint.getStyleClass().add("label-sub");
            hint.setStyle("-fx-text-alignment:center;");
            hint.setWrapText(true);
            productsGrid.getChildren().add(hint);
        }
    }

    private void mostrarProductos(List<Producto> lista) {
        productsGrid.getChildren().clear();
        for (Producto p : lista) productsGrid.getChildren().add(crearTarjeta(p));
    }

    // Paleta de gradientes para el fallback (rota por hash del nombre)
    private static final String[] GRADIENTS = {
        "linear-gradient(to bottom right, #7C3AED, #4C1D95)",
        "linear-gradient(to bottom right, #2563EB, #1E40AF)",
        "linear-gradient(to bottom right, #059669, #065F46)",
        "linear-gradient(to bottom right, #D97706, #92400E)",
        "linear-gradient(to bottom right, #DC2626, #991B1B)",
        "linear-gradient(to bottom right, #0891B2, #164E63)",
        "linear-gradient(to bottom right, #7C3AED, #2563EB)",
        "linear-gradient(to bottom right, #DB2777, #9D174D)",
    };

    private VBox crearTarjeta(Producto producto) {
        VBox card = new VBox();
        card.getStyleClass().setAll("product-card", "pu-grid-card");
        card.setPrefWidth(190);
        card.setMaxWidth(190);

        // ── Área de imagen ──
        StackPane imgArea = new StackPane();
        imgArea.getStyleClass().add("product-img-area");
        imgArea.setPrefHeight(110);

        String url = producto.getImagenUrl();
        if (url != null && !url.isBlank()) {
            // Imagen real — carga async para no bloquear UI
            ImageView iv = new ImageView();
            iv.setFitWidth(190);
            iv.setFitHeight(110);
            iv.setPreserveRatio(false);
            iv.getStyleClass().add("product-img");
            imgArea.getChildren().add(iv);
            // Fallback mientras carga
            String grad = GRADIENTS[Math.abs(producto.getNombre().hashCode()) % GRADIENTS.length];
            imgArea.setStyle("-fx-background-color: " + grad + "; -fx-background-radius: 14 14 0 0;");
            Task<Image> imgTask = new Task<>() {
                @Override protected Image call() { return new Image(url, 190, 110, false, true, false); }
            };
            imgTask.setOnSucceeded(e -> Platform.runLater(() -> {
                iv.setImage(imgTask.getValue());
                imgArea.setStyle("-fx-background-color: transparent;");
            }));
            new Thread(imgTask, "img-" + producto.getId()).start();
        } else {
            // Fallback: gradiente + inicial del nombre
            String grad = GRADIENTS[Math.abs(producto.getNombre().hashCode()) % GRADIENTS.length];
            imgArea.setStyle("-fx-background-color: " + grad + "; -fx-background-radius: 14 14 0 0;");
            String inicial = producto.getNombre().isBlank() ? "?" :
                    producto.getNombre().substring(0, 1).toUpperCase();
            Label ini = new Label(inicial);
            ini.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: rgba(255,255,255,0.9);");
            imgArea.getChildren().add(ini);
        }

        // Badge categoría
        Label catBadge = new Label(producto.getCategoria());
        catBadge.getStyleClass().add("badge-brand");
        catBadge.setStyle("-fx-font-size: 9px;");
        StackPane.setAlignment(catBadge, Pos.TOP_LEFT);
        StackPane.setMargin(catBadge, new Insets(8, 0, 0, 8));
        imgArea.getChildren().add(catBadge);

        // ── Nombre ──
        Label nombre = new Label(producto.getNombre());
        nombre.getStyleClass().add("product-name");
        nombre.setWrapText(true);
        nombre.setMaxHeight(38);
        VBox.setMargin(nombre, new Insets(10, 10, 2, 10));

        // ── Footer precio + botón ──
        Label precio = new Label("$" + fmt.format((long) producto.getPrecio()));
        precio.getStyleClass().add("product-price");

        Button btnAdd = new Button("+");
        btnAdd.getStyleClass().add("btn-add");
        btnAdd.setOnAction(e -> agregarAlCarrito(producto));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(precio, spacer, btnAdd);
        footer.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(footer, new Insets(0, 10, 10, 10));

        card.getChildren().addAll(imgArea, nombre, footer);
        return card;
    }

    // ─── Búsqueda / Escáner de código de barras ──────────────────────────────
    private void configurarBusqueda() {
        // Filtro en tiempo real mientras se escribe
        searchField.textProperty().addListener((obs, old, nuevo) -> {
            String texto = nuevo.toLowerCase().trim();
            if (texto.isEmpty()) {
                mostrarProductos(todosLosProductos);
            } else {
                List<Producto> filtrados = todosLosProductos.stream()
                        .filter(p -> p.getNombre().toLowerCase().contains(texto)
                                || (p.getCodigoBarras() != null && p.getCodigoBarras().contains(texto)))
                        .toList();
                mostrarProductos(filtrados);
            }
        });

        // Enter = escáner de código de barras (o búsqueda directa)
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                String texto = searchField.getText().trim();
                if (texto.isEmpty()) return;

                // 1. Buscar coincidencia exacta de código de barras
                Optional<Producto> porBarras = todosLosProductos.stream()
                        .filter(p -> texto.equals(p.getCodigoBarras()))
                        .findFirst();

                if (porBarras.isPresent()) {
                    agregarAlCarrito(porBarras.get());
                    searchField.clear();
                    return;
                }

                // 2. Si hay exactamente un resultado filtrado, agregarlo
                List<Producto> filtrados = todosLosProductos.stream()
                        .filter(p -> p.getNombre().toLowerCase().contains(texto.toLowerCase())
                                || (p.getCodigoBarras() != null && p.getCodigoBarras().contains(texto)))
                        .toList();

                if (filtrados.size() == 1) {
                    agregarAlCarrito(filtrados.get(0));
                    searchField.clear();
                }
            }
        });
    }

    // ─── Carrito ──────────────────────────────────────────────────────────────
    private void agregarAlCarrito(Producto producto) {
        for (ItemCarrito item : carrito) {
            if (item.getProducto().getId() == producto.getId()) {
                item.setCantidad(item.getCantidad() + 1);
                renderizarCarrito();
                return;
            }
        }
        carrito.add(new ItemCarrito(producto));
        renderizarCarrito();
    }

    private void renderizarCarrito() {
        cartItemsBox.getChildren().clear();
        if (carrito.isEmpty()) {
            emptyCartLabel.setVisible(true);
            emptyCartLabel.setManaged(true);
        } else {
            emptyCartLabel.setVisible(false);
            emptyCartLabel.setManaged(false);
            for (ItemCarrito item : carrito) cartItemsBox.getChildren().add(crearFilaCarrito(item));
        }
        actualizarTotales();
    }

    private HBox crearFilaCarrito(ItemCarrito item) {
        Label nombre = new Label(item.getProducto().getNombre());
        nombre.getStyleClass().add("cart-item-name");
        nombre.setMaxWidth(140);
        nombre.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button menos = new Button("−");
        menos.getStyleClass().add("qty-btn");
        Label qty = new Label(String.valueOf(item.getCantidad()));
        qty.getStyleClass().add("qty-label");
        Button mas = new Button("+");
        mas.getStyleClass().add("qty-btn");
        menos.setOnAction(e -> {
            if (item.getCantidad() > 1) item.setCantidad(item.getCantidad() - 1);
            else carrito.remove(item);
            renderizarCarrito();
        });
        mas.setOnAction(e -> { item.setCantidad(item.getCantidad() + 1); renderizarCarrito(); });
        HBox qtyBox = new HBox(4, menos, qty, mas);
        qtyBox.setAlignment(Pos.CENTER);
        Label subtotal = new Label("$" + fmt.format((long) item.getSubtotal()));
        subtotal.getStyleClass().add("cart-item-subtotal");
        VBox info = new VBox(2, nombre, qtyBox);
        HBox fila = new HBox(8, info, spacer, subtotal);
        fila.setAlignment(Pos.CENTER_LEFT);
        fila.getStyleClass().add("cart-item-row");
        return fila;
    }

    private void actualizarTotales() {
        double total = carrito.stream().mapToDouble(ItemCarrito::getSubtotal).sum();
        int items = carrito.stream().mapToInt(ItemCarrito::getCantidad).sum();
        subtotalLabel.setText("$" + fmt.format((long) total));
        totalLabel.setText("$" + fmt.format((long) total));
        itemsCountLabel.setText(items + (items == 1 ? " artículo" : " artículos"));
    }

    // ─── Dialog de pago (estilo Pudú Premium) ──────────────────────────────────
    @FXML
    void onProcederAlPago() {
        if (carrito.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "El carrito está vacío.").showAndWait();
            return;
        }
        double total = carrito.stream().mapToDouble(ItemCarrito::getSubtotal).sum();
        mostrarDialogoPago(total);
    }

    private void mostrarDialogoPago(double total) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Finalizar Venta");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setMinWidth(820);
        dialog.getDialogPane().setPrefWidth(820);
        dialog.getDialogPane().getStyleClass().add("dialog-pane");
        dialog.getDialogPane().getStylesheets().add(
            App.class.getResource("/com/sospos/styles/theme.css").toExternalForm());
        dialog.getDialogPane().getStylesheets().add(
            App.class.getResource("/com/sospos/styles/pos.css").toExternalForm());

        ButtonType btnFinalizar = new ButtonType("Finalizar Transacción", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancelar  = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(btnFinalizar, btnCancelar);

        // ── HEADER: Total a pagar ─────────────────────────────────────────────
        VBox headerBox = new VBox(6);
        headerBox.setAlignment(Pos.CENTER);
        headerBox.setPadding(new Insets(28, 28, 22, 28));
        headerBox.getStyleClass().add("pu-modal-header");
        headerBox.setStyle("-fx-background-color: -pu-bg-surface;");
        
        Label lblTotalTitle = new Label("TOTAL A PAGAR");
        lblTotalTitle.getStyleClass().setAll("badge", "badge-brand");
        lblTotalTitle.setStyle("-fx-letter-spacing:2px;");
        
        Label lblTotal = new Label("$" + fmt.format((long) total));
        lblTotal.getStyleClass().add("label-heading");
        lblTotal.setStyle("-fx-font-size:46px; -fx-text-fill: -pu-brand-light;");
        headerBox.getChildren().addAll(lblTotalTitle, lblTotal);

        // ── LEFT COLUMN: Método de pago + Tipo documento ──────────────────────
        VBox colIzq = new VBox(22);
        colIzq.setPrefWidth(350);
        colIzq.setPadding(new Insets(24));
        colIzq.getStyleClass().add("pu-modal-body");
        colIzq.setStyle("-fx-background-color: -pu-bg-surface;");

        Label lblSecMetodo = new Label("MÉTODO DE PAGO");
        lblSecMetodo.getStyleClass().add("pu-form-label");
        lblSecMetodo.setStyle("-fx-letter-spacing:1.5px;");

        // Shared state
        String[] metodoPagoRef = {"efectivo"};
        VBox panelVuelto = new VBox(10);
        panelVuelto.setVisible(true);
        panelVuelto.setManaged(true);
        panelVuelto.getStyleClass().add("pu-grid-card");
        panelVuelto.setStyle("-fx-padding:16; -fx-background-color: -pu-bg-elevated;");

        long sugerido = (long)(Math.ceil(total / 1000.0) * 1000);
        Label lblRecibidoLbl = new Label("Monto recibido ($)");
        lblRecibidoLbl.getStyleClass().add("pu-form-label");
        
        TextField txtRecibido = new TextField(String.valueOf(sugerido));
        txtRecibido.getStyleClass().add("field-base");
        txtRecibido.setStyle("-fx-font-size:16px; -fx-font-weight:bold;");
        
        Label lblVuelto = new Label();
        lblVuelto.setStyle("-fx-font-size:14px; -fx-font-weight:700;");
        Runnable calcVuelto = () -> {
            try {
                double rec = Double.parseDouble(txtRecibido.getText().trim());
                double v   = rec - total;
                lblVuelto.setText("Vuelto: $" + fmt.format((long) Math.max(0, v)));
                lblVuelto.getStyleClass().setAll(v >= 0 ? "status-online" : "status-error");
                lblVuelto.setStyle("-fx-font-size:15px;");
            } catch (NumberFormatException ex) { lblVuelto.setText(""); }
        };
        txtRecibido.textProperty().addListener((o, a, n) -> calcVuelto.run());
        calcVuelto.run();
        panelVuelto.getChildren().addAll(lblRecibidoLbl, txtRecibido, lblVuelto);

        // Payment method toggle buttons
        ToggleGroup grupoPago = new ToggleGroup();
        String[][] metodos = {
            {"Efectivo",  "efectivo"},
            {"Tarjeta",   "tarjeta"},
            {"Transferencia / Otro", "otro"}
        };
        VBox metodosBox = new VBox(8);
        for (String[] m : metodos) {
            ToggleButton tb = new ToggleButton(m[0]);
            tb.setToggleGroup(grupoPago);
            tb.setUserData(m[1]);
            tb.setMaxWidth(Double.MAX_VALUE);
            tb.setPrefHeight(50);
            tb.getStyleClass().add("btn-secondary"); // Base style
            tb.selectedProperty().addListener((obs, o, s) -> {
                if (s) {
                    tb.setStyle("-fx-background-color:rgba(124,58,237,0.16); -fx-border-color: -pu-brand; -fx-text-fill: -pu-brand-light;");
                    metodoPagoRef[0] = (String) tb.getUserData();
                    boolean esEfectivo = "efectivo".equals(metodoPagoRef[0]);
                    panelVuelto.setVisible(esEfectivo);
                    panelVuelto.setManaged(esEfectivo);
                } else {
                    tb.setStyle("");
                }
            });
            metodosBox.getChildren().add(tb);
        }
        ((ToggleButton) metodosBox.getChildren().get(0)).setSelected(true);

        // Document type section
        Label lblSecTipo = new Label("TIPO DE DOCUMENTO");
        lblSecTipo.getStyleClass().add("pu-form-label");
        lblSecTipo.setStyle("-fx-letter-spacing:1.5px;");
        
        ToggleGroup grupoTipo = new ToggleGroup();
        ToggleButton tbBoleta = new ToggleButton("BOLETA");
        tbBoleta.setToggleGroup(grupoTipo);
        ToggleButton tbFactura = new ToggleButton("FACTURA");
        tbFactura.setToggleGroup(grupoTipo);
        
        for (ToggleButton tb : new ToggleButton[]{tbBoleta, tbFactura}) {
            tb.getStyleClass().add("btn-secondary");
            tb.setMaxWidth(Double.MAX_VALUE);
            tb.setPrefHeight(50);
            HBox.setHgrow(tb, Priority.ALWAYS);
            tb.selectedProperty().addListener((obs, o, s) -> {
                if (s) tb.setStyle("-fx-background-color:rgba(124,58,237,0.16); -fx-border-color: -pu-brand; -fx-text-fill: -pu-brand-light;");
                else tb.setStyle("");
            });
        }
        tbBoleta.setSelected(true);
        HBox tipoBox = new HBox(10, tbBoleta, tbFactura);

        colIzq.getChildren().addAll(lblSecMetodo, metodosBox, panelVuelto, sep(4), lblSecTipo, tipoBox);

        // ── RIGHT COLUMN: Customer data ───────────────────────────────────────
        VBox colDer = new VBox(16);
        colDer.setPadding(new Insets(24));
        colDer.getStyleClass().add("pu-modal-body");
        colDer.setStyle("-fx-border-color: -pu-border; -fx-border-width: 0 0 0 1;");
        HBox.setHgrow(colDer, Priority.ALWAYS);

        Label lblSecCliente = new Label("DATOS DEL CLIENTE");
        lblSecCliente.getStyleClass().add("pu-form-label");
        lblSecCliente.setStyle("-fx-letter-spacing:1.5px;");

        Label lblRut = new Label("RUT");
        lblRut.getStyleClass().add("pu-form-label");
        TextField txtRut = new TextField("66.666.666-6");
        txtRut.getStyleClass().add("field-base");

        Label lblNomCliente = new Label("NOMBRE / RAZÓN SOCIAL");
        lblNomCliente.getStyleClass().add("pu-form-label");
        TextField txtNombre = new TextField("CLIENTE ESTIMADO");
        txtNombre.getStyleClass().add("field-base");

        Label lblDir = new Label("DIRECCIÓN (OPCIONAL)");
        lblDir.getStyleClass().add("pu-form-label");
        TextField txtDir = new TextField("Sin dirección");
        txtDir.getStyleClass().add("field-base");

        grupoTipo.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == tbFactura) {
                txtRut.setPromptText("RUT del cliente...");
                txtNombre.setPromptText("Razón social...");
                if ("66.666.666-6".equals(txtRut.getText())) txtRut.clear();
                if ("CLIENTE ESTIMADO".equals(txtNombre.getText())) txtNombre.clear();
            } else {
                if (txtRut.getText().isBlank()) txtRut.setText("66.666.666-6");
                if (txtNombre.getText().isBlank()) txtNombre.setText("CLIENTE ESTIMADO");
            }
        });

        colDer.getChildren().addAll(lblSecCliente, lblRut, txtRut,
                lblNomCliente, txtNombre, lblDir, txtDir);

        // ── Assemble layout ───────────────────────────────────────────────────
        HBox centro = new HBox(colIzq, colDer);
        centro.setStyle("-fx-background-color: -pu-bg-surface;");

        VBox mainContent = new VBox(headerBox, centro);
        mainContent.setStyle("-fx-background-color: -pu-bg-base; -fx-background-radius:20;");
        dialog.getDialogPane().setContent(mainContent);

        // Style action buttons
        Button btnF = (Button) dialog.getDialogPane().lookupButton(btnFinalizar);
        btnF.getStyleClass().add("btn-primary");

        Button btnC = (Button) dialog.getDialogPane().lookupButton(btnCancelar);
        btnC.getStyleClass().add("btn-secondary");

        // Style the dialog pane button bar background
        dialog.getDialogPane().lookupAll(".button-bar").forEach(node ->
            node.setStyle("-fx-background-color: -pu-bg-surface; -fx-padding:16 24;")
        );

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == btnFinalizar) {
            String metodo = metodoPagoRef[0];
            double vueltoVal = 0;
            if ("efectivo".equals(metodo)) {
                try { vueltoVal = Double.parseDouble(txtRecibido.getText().trim()) - total; }
                catch (NumberFormatException ignored) {}
            }
            int tipoDTE = tbFactura.isSelected() ? 33 : 39;
            String rut  = txtRut.getText().trim();
            String nom  = txtNombre.getText().trim();
            String dir  = txtDir.getText().trim();
            procesarPago(metodo, total, vueltoVal, tipoDTE, rut, nom, dir);
        }
    }

    private Region sep(double h) {
        Region r = new Region();
        r.setPrefHeight(h);
        return r;
    }

    // ─── Procesar pago ────────────────────────────────────────────────────────
    private void procesarPago(String metodoPago, double total, double vuelto,
                              int tipoDTE, String clienteRut, String clienteNombre,
                              String clienteDireccion) {
        ultimaVenta = new DatosVenta();
        ultimaVenta.items            = new ArrayList<>(carrito);
        ultimaVenta.total            = total;
        ultimaVenta.metodoPago       = metodoPago;
        ultimaVenta.vuelto           = vuelto;
        ultimaVenta.tipoDTE          = tipoDTE;
        ultimaVenta.clienteRut       = clienteRut;
        ultimaVenta.clienteNombre    = clienteNombre;
        ultimaVenta.clienteDireccion = clienteDireccion;

        int ventaId = db.guardarVenta(ultimaVenta.items, metodoPago, total);
        if (ventaId < 0) {
            new Alert(Alert.AlertType.ERROR, "Error al guardar la venta.").showAndWait();
            return;
        }
        ultimaVenta.ventaId = ventaId;

        // Limpiar carrito (el stock se gestiona desde el sistema web)
        carrito.clear();
        renderizarCarrito();
        cargarProductos();

        final List<ItemCarrito> itemsSnap = new ArrayList<>(ultimaVenta.items);
        final int    idSnap     = ventaId;
        final double totalSnap  = total;
        final double vueltoSnap = vuelto;
        final int    tipoSnap   = tipoDTE;
        final String metodoSnap = metodoPago;
        final String rutSnap    = clienteRut;
        final String nomSnap    = clienteNombre;
        final String dirSnap    = clienteDireccion;

        // Detectar conectividad antes de decidir el flujo
        boolean online = false; // PRUEBAS: forzar modo contingencia siempre

        if (!online) {
            // ── MODO OFFLINE: guardar como nota de contingencia, no emitir DTE ──
            String idUuid     = java.util.UUID.randomUUID().toString();
            String numeroNota = db.generarNumeroNota();
            String fechaUtc   = java.time.Instant.now().toString();
            String itemsJson  = construirItemsJsonContingencia(itemsSnap);

            long mntTotal = Math.round(totalSnap);
            long mntNeto  = Math.round(totalSnap / 1.19);
            long mntIva   = mntTotal - mntNeto;

            String giroSnap   = db.getConfig("negocio_giro");
            String correoSnap = db.getConfig("negocio_email");
            db.guardarContingencia(
                idUuid, numeroNota, fechaUtc, itemsJson,
                (int) mntNeto, (int) mntIva, (int) mntTotal,
                rutSnap, nomSnap, dirSnap, giroSnap, correoSnap
            );

            ultimaVenta.folio = "NOTA " + numeroNota;
            mostrarTicketContingencia(numeroNota);
            return;
        }

        // ── MODO ONLINE: flujo existente, mostrar boleta y emitir DTE ──
        mostrarBoleta();

        new Thread(() -> {
            // 1. Insertar en Supabase inmediato → aparece en POS-Matic con "pendiente"
            supa.sincronizarVentaASupabase(db, idSnap, itemsSnap, metodoSnap, totalSnap, vueltoSnap, tipoSnap);

            // 2. Auto-emitir DTE (boleta/factura electrónica)
            SupabaseService.DTEResult dteResult = supa.generarDTE(
                    db, idSnap, totalSnap, itemsSnap, tipoSnap, rutSnap, nomSnap, dirSnap);

            if (dteResult.ok) {
                if (dteResult.pdfBase64 != null && !dteResult.pdfBase64.isBlank())
                    db.saveDtePdf(idSnap, dteResult.pdfBase64);
                Platform.runLater(() -> {
                    ultimaVenta.folio     = String.valueOf(dteResult.folio);
                    ultimaVenta.trackId   = dteResult.trackId;
                    ultimaVenta.pdfBase64 = dteResult.pdfBase64;
                });
                // 3. PATCH en Supabase con folio + PDF real
                supa.actualizarVentaDTE(db, idSnap, dteResult);
            }
        }, "dte-sync-" + ventaId).start();
    }

    // ─── Contingencia: helpers ────────────────────────────────────────────────

    private String construirItemsJsonContingencia(List<ItemCarrito> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            ItemCarrito it = items.get(i);
            if (i > 0) sb.append(",");
            long pUnit = Math.round(it.getProducto().getPrecio());
            long sub   = Math.round(it.getSubtotal());
            sb.append("{\"nombre\":\"")
              .append(it.getProducto().getNombre().replace("\\", "\\\\").replace("\"", "\\\""))
              .append("\",\"cantidad\":").append(it.getCantidad())
              .append(",\"precio_unitario\":").append(pUnit)
              .append(",\"descuento\":0")
              .append(",\"subtotal\":").append(sub)
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private void mostrarTicketContingencia(String numeroNota) {
        final long mntTotal = Math.round(ultimaVenta.total);
        final long mntNeto  = Math.round(ultimaVenta.total / 1.19);
        final long mntIva   = mntTotal - mntNeto;
        final String nombreNegocio = nvl(db.getConfig("negocio_razon_social"),
                nvl(UserSession.getInstance().getNombreNegocio(), "Mi Negocio"));
        final String rutNeg      = nvl(db.getConfig("negocio_rut"), "");
        final String giroNeg     = nvl(db.getConfig("negocio_giro"), "");
        final String dirNeg      = nvl(db.getConfig("negocio_direccion"), "");
        final String telNeg      = nvl(db.getConfig("negocio_telefono"), "");
        final String fechaStr    = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Santiago"))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        final List<ItemCarrito> items    = new ArrayList<>(ultimaVenta.items);
        final String clienteNombreSnap   = nvl(ultimaVenta.clienteNombre, "Consumidor Final");
        final String clienteRutSnap      = nvl(ultimaVenta.clienteRut, "66666666-6");
        final String clienteDirSnap      = nvl(ultimaVenta.clienteDireccion, "");
        final String metodoPagoSnap      = nvl(ultimaVenta.metodoPago, "otro");
        final double vueltoSnap          = ultimaVenta.vuelto;
        final int    ventaIdSnap         = ultimaVenta.ventaId;

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.TRANSPARENT);
        javafx.stage.Window owner = productsGrid.getScene().getWindow();
        stage.initOwner(owner);

        // ── Dot pulsante ──
        Label dot = new Label("●");
        dot.setStyle("-fx-font-size:9px; -fx-text-fill:white;");
        Timeline dotAnim = new Timeline(
            new KeyFrame(Duration.ZERO,       ev -> dot.setOpacity(1.0)),
            new KeyFrame(Duration.millis(700), ev -> dot.setOpacity(0.2))
        );
        dotAnim.setCycleCount(Timeline.INDEFINITE);
        dotAnim.setAutoReverse(true);
        dotAnim.play();

        // ── Header morado ──
        Label hBadge = new Label("OFFLINE");
        hBadge.getStyleClass().setAll("badge");
        hBadge.setStyle("-fx-background-color:rgba(255,255,255,0.22); -fx-text-fill:white; -fx-letter-spacing:1.5;");
        
        Label hTitle = new Label("Nota de Venta — Modo Offline");
        hTitle.getStyleClass().add("pu-form-section-title");
        hTitle.setStyle("-fx-text-fill:white; -fx-font-size:15px;");

        Region hSp = new Region();
        HBox.setHgrow(hSp, Priority.ALWAYS);
        HBox header = new HBox(10, hBadge, hTitle, hSp, dot);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("pu-modal-header");
        header.setStyle("-fx-background-color: -pu-brand-dark;");

        // ── Logo ──
        // Logo: usamos directamente el PNG (ya viene con su propio recuadro
        // violeta). Sin StackPane envolvente para evitar el doble marco.
        StackPane logoBox = new StackPane();
        logoBox.setMinSize(56, 56);
        logoBox.setMaxSize(56, 56);
        try {
            Image logoImg = new Image(
                App.class.getResource("/com/sospos/images/pudu-logo-new.png").toExternalForm(),
                56, 56, true, true);
            ImageView iv = new ImageView(logoImg);
            iv.setFitWidth(56); iv.setFitHeight(56);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            logoBox.getChildren().add(iv);
        } catch (Exception ex) {
            logoBox.setStyle("-fx-background-color: linear-gradient(to bottom right, -pu-brand, -pu-brand-dark);"
                    + " -fx-background-radius: 14;");
            Label fb = new Label("P");
            fb.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: white;");
            logoBox.getChildren().add(fb);
        }
        logoBox.setEffect(new DropShadow(12, 0, 4, Color.rgb(124, 58, 237, 0.30)));

        Label brandTag = new Label("RESPALDO LOCAL SINCRONIZADO");
        brandTag.getStyleClass().add("pu-form-label");
        
        Label brandDesc = new Label("El sistema emitirá su boleta/factura al restaurarse la conexión.");
        brandDesc.getStyleClass().add("label-sub");
        brandDesc.setTextAlignment(TextAlignment.CENTER);
        brandDesc.setMaxWidth(360);
        brandDesc.setWrapText(true);

        VBox brandSection = new VBox(10, logoBox, brandTag, brandDesc);
        brandSection.setAlignment(Pos.CENTER);

        // ── Papel térmico ──
        VBox paper = new VBox(0);
        paper.setStyle("-fx-background-color:white; -fx-padding:16 20 16 20;"
                + " -fx-border-color:#F1F5F9; -fx-border-width:1; -fx-border-radius:8;"
                + " -fx-background-radius:8;");
        paper.setMaxWidth(300);

        centrado(paper, "COMPROBANTE DE VENTA",
            "-fx-font-family:'Courier New'; -fx-font-size:10.5px; -fx-font-weight:bold; -fx-text-fill:#374151;");
        centrado(paper, nombreNegocio,
            "-fx-font-family:'Courier New'; -fx-font-size:10px; -fx-text-fill:#6B7280;");
        if (!rutNeg.isBlank())
            centrado(paper, "R.U.T: " + rutNeg,
                "-fx-font-family:'Courier New'; -fx-font-size:10px; -fx-text-fill:#6B7280;");
        paper.getChildren().add(sep(8));
        centrado(paper, "N° " + numeroNota,
            "-fx-font-family:'Courier New'; -fx-font-size:10px; -fx-text-fill:#6B7280;");
        centrado(paper, "FECHA: " + fechaStr,
            "-fx-font-family:'Courier New'; -fx-font-size:10px; -fx-text-fill:#6B7280;");
        paper.getChildren().add(sep(8));
        paper.getChildren().add(lineaDash());
        paper.getChildren().add(sep(8));

        for (ItemCarrito item : items) {
            long sub = Math.round(item.getSubtotal());
            Label lNom = new Label(item.getCantidad() + "x " + item.getProducto().getNombre());
            lNom.setStyle("-fx-font-family:'Courier New'; -fx-font-size:11px; -fx-font-weight:bold; -fx-text-fill:#1E1924;");
            lNom.setWrapText(true);
            lNom.setMaxWidth(210);
            Region iSp = new Region();
            HBox.setHgrow(iSp, Priority.ALWAYS);
            Label lSub = new Label("$" + fmt.format(sub));
            lSub.setStyle("-fx-font-family:'Courier New'; -fx-font-size:11px; -fx-text-fill:#1E1924;");
            HBox row = new HBox(lNom, iSp, lSub);
            row.setPadding(new Insets(2, 0, 2, 0));
            paper.getChildren().add(row);
        }

        paper.getChildren().add(sep(8));
        paper.getChildren().add(lineaDash());
        paper.getChildren().add(sep(8));
        filaReciboMono(paper, "SUBTOTAL", "$" + fmt.format(mntNeto));
        filaReciboMono(paper, "IVA (19%)", "$" + fmt.format(mntIva));
        paper.getChildren().add(sep(6));

        // Total grande
        Label lblTNom = new Label("TOTAL");
        lblTNom.setStyle("-fx-font-family:'Courier New'; -fx-font-size:13px; -fx-font-weight:bold; -fx-text-fill:#1E1924;");
        Label lblTVal = new Label("$" + fmt.format(mntTotal));
        lblTVal.setStyle("-fx-font-size:22px; -fx-font-weight:bold; -fx-text-fill: -pu-brand-light;");
        Region tSp2 = new Region();
        HBox.setHgrow(tSp2, Priority.ALWAYS);
        HBox rowTotal = new HBox(lblTNom, tSp2, lblTVal);
        rowTotal.setAlignment(Pos.CENTER);
        paper.getChildren().add(rowTotal);
        paper.getChildren().add(sep(10));
        paper.getChildren().add(lineaDash());
        paper.getChildren().add(sep(8));
        centrado(paper, "* Documento válido como respaldo de operación interna en modo contingencia.",
            "-fx-font-family:'Courier New'; -fx-font-size:9px; -fx-text-fill:#9CA3AF; -fx-font-style:italic;");
        paper.setEffect(new DropShadow(18, 0, 6, Color.rgb(0, 0, 0, 0.1)));

        // ── Botones ──
        Button btnAceptar = new Button("ACEPTAR");
        btnAceptar.getStyleClass().add("btn-primary");
        btnAceptar.setMaxWidth(Double.MAX_VALUE);

        Button btnImprimir = new Button("Imprimir");
        btnImprimir.getStyleClass().add("btn-secondary");
        btnImprimir.setMaxWidth(Double.MAX_VALUE);

        Button btnGuardarPdf = new Button("Guardar PDF");
        btnGuardarPdf.getStyleClass().add("btn-secondary");
        btnGuardarPdf.setMaxWidth(Double.MAX_VALUE);
        btnGuardarPdf.setStyle("-fx-text-fill: -pu-brand-light;");

        HBox row2 = new HBox(10, btnImprimir, btnGuardarPdf);
        HBox.setHgrow(btnImprimir, Priority.ALWAYS);
        HBox.setHgrow(btnGuardarPdf, Priority.ALWAYS);
        row2.setMaxWidth(Double.MAX_VALUE);

        VBox btnsBox = new VBox(10, btnAceptar, row2);
        btnsBox.setAlignment(Pos.CENTER);
        btnsBox.setPadding(new Insets(8, 0, 0, 0));

        // ── Footer decoration (tres puntos) ──
        Label fd1 = new Label();
        fd1.setStyle("-fx-background-color:rgba(164,19,236,0.2); -fx-background-radius:4;"
            + "-fx-min-width:28; -fx-max-width:28; -fx-min-height:4; -fx-max-height:4;");
        Label fd2 = new Label();
        fd2.setStyle("-fx-background-color:rgba(164,19,236,0.2); -fx-background-radius:4;"
            + "-fx-min-width:4; -fx-max-width:4; -fx-min-height:4; -fx-max-height:4;");
        Label fd3 = new Label();
        fd3.setStyle("-fx-background-color:rgba(164,19,236,0.2); -fx-background-radius:4;"
            + "-fx-min-width:4; -fx-max-width:4; -fx-min-height:4; -fx-max-height:4;");
        HBox footerDots = new HBox(6, fd1, fd2, fd3);
        footerDots.setAlignment(Pos.CENTER);
        footerDots.setPadding(new Insets(14, 0, 14, 0));
        footerDots.setStyle("-fx-background-color:rgba(248,235,255,0.6);");

        // ── Content area ──
        VBox content = new VBox(20, brandSection, paper, btnsBox);
        content.getStyleClass().add("pu-modal-body");
        content.setAlignment(Pos.TOP_CENTER);

        // ── Tarjeta principal ──
        VBox card = new VBox(0, header, content, footerDots);
        card.getStyleClass().add("pu-modal-card");
        card.setMaxWidth(380);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane root = new StackPane(card);
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color:rgba(0,0,0,0.45);");

        btnAceptar.setOnAction(ev -> { dotAnim.stop(); stage.close(); });

        btnImprimir.setOnAction(ev -> {
            VBox bPrint = construirBoletaContingencia(items, numeroNota, nombreNegocio, rutNeg, fechaStr, mntTotal, mntNeto, mntIva);
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) { new Alert(Alert.AlertType.WARNING, "No se encontró impresora.").showAndWait(); return; }
            if (job.showPrintDialog(stage)) {
                bPrint.applyCss();
                bPrint.layout();
                if (job.printPage(bPrint)) job.endJob();
                else new Alert(Alert.AlertType.ERROR, "Error al imprimir.").showAndWait();
            }
        });

        btnGuardarPdf.setOnAction(ev -> {
            BoletaPdfService.BoletaData data = new BoletaPdfService.BoletaData(
                nombreNegocio, rutNeg, giroNeg, dirNeg, telNeg,
                "NOTA DE VENTA", numeroNota,
                clienteNombreSnap, clienteRutSnap, clienteDirSnap,
                items, (double) mntTotal, metodoPagoSnap, vueltoSnap, null, ventaIdSnap);
            Task<File> pdfTask = new Task<>() {
                @Override protected File call() throws Exception { return BoletaPdfService.generarPdf(data); }
            };
            pdfTask.setOnSucceeded(pev -> Platform.runLater(() -> {
                File tmpPdf = pdfTask.getValue();
                FileChooser fc = new FileChooser();
                fc.setTitle("Guardar Nota de Venta PDF");
                fc.setInitialFileName("nota_" + numeroNota + ".pdf");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo PDF", "*.pdf"));
                File dest = fc.showSaveDialog(stage);
                if (dest != null) {
                    try {
                        Files.copy(tmpPdf.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        java.awt.Desktop.getDesktop().open(dest);
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.ERROR, "Error al guardar PDF: " + ex.getMessage()).showAndWait();
                    }
                }
            }));
            pdfTask.setOnFailed(pev -> Platform.runLater(() ->
                new Alert(Alert.AlertType.ERROR, "Error generando PDF:\n" + pdfTask.getException().getMessage()).showAndWait()));
            new Thread(pdfTask, "pdf-cont").start();
        });

        Scene scene = new Scene(root, owner.getWidth(), owner.getHeight());
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/theme.css").toExternalForm());
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/pos.css").toExternalForm());
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setX(owner.getX());
        stage.setY(owner.getY());
        stage.show();
    }

    // ─── Boleta visual (estilo ticket impresora) ──────────────────────────────
    private void mostrarBoleta() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Boleta Electrónica");
        dialog.setHeaderText(null);

        ButtonType btnImprimir = new ButtonType("Imprimir", ButtonBar.ButtonData.LEFT);
        ButtonType btnPdf      = new ButtonType("Descargar PDF", ButtonBar.ButtonData.LEFT);
        ButtonType btnCerrar   = new ButtonType("Cerrar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnImprimir, btnPdf, btnCerrar);

        VBox boleta = construirBoleta();
        ScrollPane scroll = new ScrollPane(boleta);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(580);
        scroll.setStyle("-fx-background-color:white; -fx-background:white;");
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().setPrefWidth(440);
        dialog.getDialogPane().setStyle("-fx-background-color:white;");

        // Estilo botón imprimir
        Button btnI = (Button) dialog.getDialogPane().lookupButton(btnImprimir);
        btnI.setStyle("-fx-background-color:#0F172A; -fx-text-fill:white; -fx-font-size:13px;"
                + "-fx-font-weight:bold; -fx-background-radius:8; -fx-padding:10 20;");
        btnI.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            imprimirBoleta(boleta);
        });

        // Estilo botón PDF
        Button btnP = (Button) dialog.getDialogPane().lookupButton(btnPdf);
        btnP.setStyle("-fx-background-color:#7C3AED; -fx-text-fill:white; -fx-font-size:13px;"
                + "-fx-font-weight:bold; -fx-background-radius:8; -fx-padding:10 20;");
        btnP.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            descargarBoletaPdf();
        });

        dialog.showAndWait();
    }

    // ─── Generar y descargar PDF de boleta ────────────────────────────────────
    private void descargarBoletaPdf() {
        // Si el servidor devolvió un PDF (con folio y TED real), usarlo directamente
        if (ultimaVenta.pdfBase64 != null && !ultimaVenta.pdfBase64.isBlank()) {
            try {
                byte[] pdfBytes = java.util.Base64.getDecoder().decode(ultimaVenta.pdfBase64);
                java.io.File tmp = java.io.File.createTempFile("boleta_", ".pdf");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) { fos.write(pdfBytes); }
                FileChooser fc = new FileChooser();
                fc.setTitle("Guardar Boleta PDF");
                fc.setInitialFileName("boleta_" + ultimaVenta.folio + ".pdf");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Archivo PDF", "*.pdf"));
                File dest = fc.showSaveDialog(productsGrid.getScene().getWindow());
                if (dest != null) {
                    java.nio.file.Files.copy(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    java.awt.Desktop.getDesktop().open(dest);
                }
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Error al guardar PDF: " + ex.getMessage()).showAndWait();
            }
            return;
        }

        String nombreNegocio = db.getConfig("negocio_razon_social");
        if (nombreNegocio == null || nombreNegocio.isBlank())
            nombreNegocio = UserSession.getInstance().getNombreNegocio();
        if (nombreNegocio == null || nombreNegocio.isBlank()) nombreNegocio = "Mi Negocio";

        String tipoDTENombre = ultimaVenta.tipoDTE == 33
                ? "FACTURA ELECTRONICA" : "BOLETA ELECTRONICA";

        BoletaPdfService.BoletaData data = new BoletaPdfService.BoletaData(
                nombreNegocio,
                nvl(db.getConfig("negocio_rut"),       ""),
                nvl(db.getConfig("negocio_giro"),      ""),
                nvl(db.getConfig("negocio_direccion"), ""),
                nvl(db.getConfig("negocio_telefono"),  ""),
                tipoDTENombre,
                ultimaVenta.folio,
                nvl(ultimaVenta.clienteNombre,    "Cliente Estimado"),
                nvl(ultimaVenta.clienteRut,       "66666666-6"),
                nvl(ultimaVenta.clienteDireccion, ""),
                ultimaVenta.items,
                ultimaVenta.total,
                nvl(ultimaVenta.metodoPago, "otro"),
                ultimaVenta.vuelto,
                ultimaVenta.trackId,
                ultimaVenta.ventaId
        );

        Task<File> task = new Task<>() {
            @Override protected File call() throws Exception {
                return BoletaPdfService.generarPdf(data);
            }
        };

        task.setOnSucceeded(ev -> Platform.runLater(() -> {
            File tmpPdf = task.getValue();
            FileChooser fc = new FileChooser();
            fc.setTitle("Guardar Boleta PDF");
            fc.setInitialFileName("boleta_venta_" + ultimaVenta.ventaId + ".pdf");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Archivo PDF", "*.pdf"));
            File dest = fc.showSaveDialog(productsGrid.getScene().getWindow());
            if (dest != null) {
                try {
                    Files.copy(tmpPdf.toPath(), dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    java.awt.Desktop.getDesktop().open(dest);
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR,
                            "No se pudo guardar el PDF:\n" + ex.getMessage()).showAndWait();
                }
            }
        }));

        task.setOnFailed(ev -> Platform.runLater(() ->
                new Alert(Alert.AlertType.ERROR,
                        "Error generando PDF:\n" + task.getException().getMessage())
                        .showAndWait()));

        new Thread(task, "pdf-gen").start();
    }

    private VBox construirBoleta() {
        // Datos del negocio desde config
        String nombreNegocio = db.getConfig("negocio_razon_social");
        if (nombreNegocio == null || nombreNegocio.isBlank())
            nombreNegocio = UserSession.getInstance().getNombreNegocio();
        if (nombreNegocio == null || nombreNegocio.isBlank()) nombreNegocio = "Mi Negocio";

        String rutNegocio  = nvl(db.getConfig("negocio_rut"), "");
        String giroNeg     = nvl(db.getConfig("negocio_giro"), "");
        String dirNeg      = nvl(db.getConfig("negocio_direccion"), "");
        String telNeg      = nvl(db.getConfig("negocio_telefono"), "");

        DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        String fechaHora = LocalDateTime.now().format(dtFmt);

        long mntTotal = Math.round(ultimaVenta.total);
        long mntNeto  = Math.round(ultimaVenta.total / 1.19);
        long iva       = mntTotal - mntNeto;

        String tipoDTENombre = ultimaVenta.tipoDTE == 33 ? "FACTURA ELECTRÓNICA" : "BOLETA ELECTRÓNICA";
        String folioNum      = ultimaVenta.folio != null ? ultimaVenta.folio : "PENDIENTE";

        VBox boleta = new VBox(0);
        boleta.setStyle("-fx-background-color:white; -fx-padding:24 28;");
        boleta.setMaxWidth(400);

        // ── Encabezado negocio ──
        String nombreNegocioFinal = nombreNegocio;
        centrado(boleta, nombreNegocioFinal, "-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:#0F172A;");

        if (!giroNeg.isBlank())
            centrado(boleta, giroNeg, "-fx-font-size:11px; -fx-text-fill:#374151;");
        if (!rutNegocio.isBlank())
            centrado(boleta, "RUT: " + rutNegocio, "-fx-font-size:11px; -fx-text-fill:#374151;");
        if (!dirNeg.isBlank())
            centrado(boleta, dirNeg, "-fx-font-size:11px; -fx-text-fill:#374151;");
        if (!telNeg.isBlank())
            centrado(boleta, "Tel: " + telNeg, "-fx-font-size:11px; -fx-text-fill:#374151;");

        boleta.getChildren().add(sep(8));
        centrado(boleta, tipoDTENombre + " #" + folioNum,
                "-fx-font-size:13px; -fx-font-weight:bold; -fx-text-fill:#0F172A;");
        centrado(boleta, "Fecha: " + fechaHora,
                "-fx-font-size:11px; -fx-text-fill:#374151;");

        boleta.getChildren().add(sep(10));
        boleta.getChildren().add(lineaTicket());
        boleta.getChildren().add(sep(8));

        // ── Datos cliente ──
        izq(boleta, "Cliente: " + ultimaVenta.clienteNombre,
                "-fx-font-size:11px; -fx-text-fill:#374151;");
        izq(boleta, "RUT: " + ultimaVenta.clienteRut,
                "-fx-font-size:11px; -fx-text-fill:#374151;");
        if (!ultimaVenta.clienteDireccion.isBlank() && !"Sin dirección".equals(ultimaVenta.clienteDireccion))
            izq(boleta, "Dirección: " + ultimaVenta.clienteDireccion,
                    "-fx-font-size:11px; -fx-text-fill:#374151;");

        boleta.getChildren().add(sep(10));
        boleta.getChildren().add(lineaTicket());
        boleta.getChildren().add(sep(8));

        // ── Detalle ──
        Label lblDet = new Label("Detalle:");
        lblDet.setStyle("-fx-font-size:12px; -fx-font-weight:bold; -fx-text-fill:#0F172A;");
        boleta.getChildren().add(lblDet);
        boleta.getChildren().add(sep(6));

        for (ItemCarrito item : ultimaVenta.items) {
            long pUnit = Math.round(item.getProducto().getPrecio());
            long sub   = Math.round(item.getSubtotal());
            // Fila: "Nx Nombre   $subtotal"
            HBox filaItem = new HBox();
            Label lNom = new Label(item.getCantidad() + "x " + item.getProducto().getNombre());
            lNom.setStyle("-fx-font-size:12px; -fx-text-fill:#1F2937;");
            lNom.setWrapText(true);
            lNom.setMaxWidth(220);
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Label lSub = new Label("$" + fmt.format(sub));
            lSub.setStyle("-fx-font-size:12px; -fx-font-weight:bold; -fx-text-fill:#1F2937;");
            filaItem.getChildren().addAll(lNom, sp, lSub);
            boleta.getChildren().add(filaItem);
            // Sub-fila precio unitario
            Label lPU = new Label("   Precio unitario: $" + fmt.format(pUnit));
            lPU.setStyle("-fx-font-size:10px; -fx-text-fill:#6B7280;");
            boleta.getChildren().add(lPU);
            boleta.getChildren().add(sep(4));
        }

        boleta.getChildren().add(sep(6));
        boleta.getChildren().add(lineaTicket());
        boleta.getChildren().add(sep(8));

        // ── Totales ──
        filaTotales(boleta, "Subtotal:", "$" + fmt.format(mntNeto), false);
        filaTotales(boleta, "IVA (19%):", "$" + fmt.format(iva), false);
        boleta.getChildren().add(sep(4));
        filaTotales(boleta, "Total:", "$" + fmt.format(mntTotal), true);

        boleta.getChildren().add(sep(10));
        boleta.getChildren().add(lineaTicket());
        boleta.getChildren().add(sep(8));

        // ── Método de pago ──
        String metNom = switch (ultimaVenta.metodoPago) {
            case "efectivo" -> "efectivo";
            case "tarjeta"  -> "tarjeta";
            default          -> "otro";
        };
        centrado(boleta, "Método de pago: " + metNom,
                "-fx-font-size:11px; -fx-text-fill:#374151;");
        if ("efectivo".equals(ultimaVenta.metodoPago) && ultimaVenta.vuelto > 0)
            centrado(boleta, "Vuelto: $" + fmt.format((long) ultimaVenta.vuelto),
                    "-fx-font-size:11px; -fx-font-weight:bold; -fx-text-fill:#059669;");

        boleta.getChildren().add(sep(8));
        boleta.getChildren().add(lineaTicket());
        boleta.getChildren().add(sep(10));

        // ── Estado DTE ──
        if (ultimaVenta.folio != null) {
            centrado(boleta, "Timbre electrónico SII",
                    "-fx-font-size:10px; -fx-text-fill:#6B7280;");
            if (ultimaVenta.trackId != null)
                centrado(boleta, "Track ID: " + ultimaVenta.trackId,
                        "-fx-font-size:10px; -fx-text-fill:#6B7280;");
        } else {
            Label lblPend = new Label("Timbre electrónico pendiente");
            lblPend.setStyle("-fx-font-size:11px; -fx-text-fill:#D97706; -fx-font-weight:bold;");
            lblPend.setMaxWidth(Double.MAX_VALUE);
            lblPend.setAlignment(Pos.CENTER);
            boleta.getChildren().add(lblPend);
        }

        boleta.getChildren().add(sep(12));
        centrado(boleta, "¡Gracias por su compra!",
                "-fx-font-size:13px; -fx-font-weight:bold; -fx-text-fill:#0F172A;");
        boleta.getChildren().add(sep(6));
        centrado(boleta, "Verifique en www.sii.cl",
                "-fx-font-size:10px; -fx-text-fill:#9CA3AF;");

        return boleta;
    }

    // helpers boleta
    private void centrado(VBox p, String txt, String style) {
        Label l = new Label(txt);
        l.setStyle(style);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        l.setWrapText(true);
        l.setTextAlignment(TextAlignment.CENTER);
        p.getChildren().add(l);
    }

    private void izq(VBox p, String txt, String style) {
        Label l = new Label(txt);
        l.setStyle(style);
        l.setWrapText(true);
        p.getChildren().add(l);
    }

    private HBox lineaTicket() {
        Label l = new Label("─".repeat(46));
        l.setStyle("-fx-font-size:9px; -fx-text-fill:#D1D5DB;");
        l.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(l);
        return box;
    }

    private HBox lineaDash() {
        Label l = new Label("- - - - - - - - - - - - - - - - - - - - - - - - - - - -");
        l.setStyle("-fx-font-family:'Courier New'; -fx-font-size:8px; -fx-text-fill:#D1D5DB;");
        l.setMaxWidth(Double.MAX_VALUE);
        return new HBox(l);
    }

    private void filaReciboMono(VBox p, String label, String valor) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-family:'Courier New'; -fx-font-size:11px; -fx-text-fill:#6B7280;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label val = new Label(valor);
        val.setStyle("-fx-font-family:'Courier New'; -fx-font-size:11px; -fx-text-fill:#6B7280;");
        HBox row = new HBox(lbl, sp, val);
        row.setPadding(new Insets(2, 0, 2, 0));
        p.getChildren().add(row);
    }

    private VBox construirBoletaContingencia(List<ItemCarrito> items, String numeroNota,
            String nombreNeg, String rutNeg, String fechaStr,
            long total, long neto, long iva) {
        VBox v = new VBox(4);
        v.setStyle("-fx-background-color:white; -fx-padding:20;");
        centrado(v, nombreNeg,         "-fx-font-weight:bold; -fx-font-size:14px; -fx-text-fill:#0F172A;");
        if (!rutNeg.isBlank())
            centrado(v, "RUT: " + rutNeg, "-fx-font-size:11px; -fx-text-fill:#374151;");
        centrado(v, "NOTA DE VENTA N° " + numeroNota,
                "-fx-font-size:12px; -fx-font-weight:bold; -fx-text-fill:#0F172A;");
        centrado(v, "Fecha: " + fechaStr, "-fx-font-size:11px; -fx-text-fill:#374151;");
        v.getChildren().add(lineaTicket());
        for (ItemCarrito it : items) {
            long sub = Math.round(it.getSubtotal());
            Label ln = new Label(it.getCantidad() + "x " + it.getProducto().getNombre());
            ln.setStyle("-fx-font-size:12px;"); ln.setWrapText(true); ln.setMaxWidth(220);
            Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
            Label ls = new Label("$" + fmt.format(sub));
            ls.setStyle("-fx-font-size:12px; -fx-font-weight:bold;");
            HBox row = new HBox(ln, sp, ls);
            v.getChildren().add(row);
        }
        v.getChildren().add(lineaTicket());
        filaTotales(v, "Subtotal:", "$" + fmt.format(neto),  false);
        filaTotales(v, "IVA (19%):", "$" + fmt.format(iva),  false);
        filaTotales(v, "Total:",     "$" + fmt.format(total), true);
        return v;
    }

    private void filaTotales(VBox p, String label, String valor, boolean bold) {
        String style = bold
                ? "-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:#0F172A;"
                : "-fx-font-size:11px; -fx-text-fill:#374151;";
        Label lbl = new Label(label);
        lbl.setStyle(style);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label val = new Label(valor);
        val.setStyle(style);
        HBox row = new HBox(lbl, sp, val);
        row.setPadding(new Insets(2, 0, 2, 0));
        p.getChildren().add(row);
    }

    private String nvl(String s, String def) { return (s == null || s.isBlank()) ? def : s; }

    // ─── Imprimir ─────────────────────────────────────────────────────────────
    private void imprimirBoleta(VBox boleta) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            new Alert(Alert.AlertType.WARNING, "No se encontró impresora.").showAndWait();
            return;
        }
        if (job.showPrintDialog(boleta.getScene().getWindow())) {
            if (job.printPage(boleta)) job.endJob();
            else new Alert(Alert.AlertType.ERROR, "Error al imprimir.").showAndWait();
        }
    }

    // ─── Enviar DTEs pendientes ───────────────────────────────────────────────
    @FXML
    void onEnviarDTEPendientes() {
        List<DatabaseService.VentaDTE> pendientes = db.getVentasPendientesDTE();
        if (pendientes.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No hay boletas pendientes.").showAndWait();
            return;
        }
        Task<Boolean> pingTask = new Task<>() {
            @Override protected Boolean call() { return supa.testConexion(); }
        };
        pingTask.setOnSucceeded(e -> {
            if (!pingTask.getValue()) {
                new Alert(Alert.AlertType.WARNING, "Sin conexión a internet.").showAndWait();
                return;
            }
            if (btnEnviarDTE != null) btnEnviarDTE.setDisable(true);
            Task<Integer> dteTask = new Task<>() {
                @Override protected Integer call() { return supa.procesarColaDTE(db); }
            };
            dteTask.setOnSucceeded(ev -> {
                if (btnEnviarDTE != null) btnEnviarDTE.setDisable(false);
                int emitidos = dteTask.getValue();
                int err = pendientes.size() - emitidos;
                String msg = emitidos + " boleta(s) emitida(s) al SII.";
                if (err > 0) msg += "\n" + err + " con error — verifica el servidor DTE (localhost:3000).";
                new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
            });
            dteTask.setOnFailed(ev -> {
                if (btnEnviarDTE != null) btnEnviarDTE.setDisable(false);
                new Alert(Alert.AlertType.ERROR, "Error al enviar.").showAndWait();
            });
            new Thread(dteTask, "dte-envio").start();
        });
        new Thread(pingTask, "ping").start();
    }

    // ─── Navegación ───────────────────────────────────────────────────────────
    @FXML
    void onLogout() {
        try { App.showDashboard(); } catch (Exception e) { e.printStackTrace(); }
    }
}
