package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.model.ProductoDetalle;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class InventarioController {

    @FXML private TextField searchField;
    @FXML private ComboBox<String> filtroCategoria;
    @FXML private TableView<ProductoDetalle> tablaProductos;
    @FXML private Label statTotal;
    @FXML private Label statCategorias;
    @FXML private Label mostrando;
    @FXML private Label pageLabel;
    @FXML private Button btnPrevPage;
    @FXML private Button btnNextPage;

    private final DatabaseService db = DatabaseService.getInstance();
    private final NumberFormat fmt = NumberFormat.getIntegerInstance(new Locale("es", "CL"));
    private ObservableList<ProductoDetalle> datos = FXCollections.observableArrayList();
    private int currentPage = 1;
    private int pageSize = 7;
    private int pageCount = 1;

    @FXML
    public void initialize() {
        configurarFiltros();
        configurarTabla();
        cargarStats();
        cargarDatos();
        configurarBusqueda();
    }

    // ─── Filtros ─────────────────────────────────────────────────────────────
    private void configurarFiltros() {
        List<String> cats = db.getCategorias();
        filtroCategoria.setItems(FXCollections.observableArrayList(cats));
        filtroCategoria.setValue("Todas");
    }

    // ─── Tabla ───────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void configurarTabla() {
        // Paleta de colores para los avatares por inicial
        final String[] avatarColors = {
            "#7C3AED", "#0EA5E9", "#10B981", "#F59E0B",
            "#EC4899", "#6366F1", "#14B8A6", "#EF4444"
        };

        TableColumn<ProductoDetalle, String> colNombre = new TableColumn<>("PRODUCTO");
        colNombre.setCellValueFactory(new PropertyValueFactory<>("nombre"));
        colNombre.setPrefWidth(420);
        colNombre.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String nombre, boolean empty) {
                super.updateItem(nombre, empty);
                if (empty || nombre == null) { setGraphic(null); setText(null); return; }
                ProductoDetalle p = getTableRow().getItem();

                // Avatar con inicial
                String inicial = nombre.trim().isEmpty()
                        ? "·" : nombre.trim().substring(0, 1).toUpperCase();
                String color = avatarColors[Math.abs(nombre.hashCode()) % avatarColors.length];
                StackPane avatar = new StackPane();
                avatar.setMinSize(36, 36);
                avatar.setMaxSize(36, 36);
                avatar.setStyle("-fx-background-color:" + color + "; -fx-background-radius:8;");
                Label lIni = new Label(inicial);
                lIni.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:white;");
                avatar.getChildren().add(lIni);

                VBox info = new VBox(2);
                Label lNombre = new Label(nombre);
                lNombre.setStyle("-fx-font-weight:bold; -fx-font-size:13.5px; -fx-text-fill: -pu-text-primary;");
                String sku = p != null && p.getSku() != null ? p.getSku() : "";
                String subtxt = sku.isEmpty()
                        ? (p != null && p.getCategoria() != null ? p.getCategoria() : "")
                        : "SKU " + sku;
                Label lSub = new Label(subtxt);
                lSub.setStyle("-fx-font-size:11.5px; -fx-text-fill: -pu-text-secondary;");
                info.getChildren().addAll(lNombre, lSub);

                HBox box = new HBox(12, avatar, info);
                box.setAlignment(Pos.CENTER_LEFT);
                box.setPadding(new Insets(0, 0, 0, 4));
                setGraphic(box);
                setText(null);
            }
        });

        TableColumn<ProductoDetalle, String> colCategoria = new TableColumn<>("CATEGORÍA");
        colCategoria.setCellValueFactory(new PropertyValueFactory<>("categoria"));
        colCategoria.setPrefWidth(220);
        colCategoria.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String cat, boolean empty) {
                super.updateItem(cat, empty);
                if (empty) { setGraphic(null); setText(null); return; }
                String txt = (cat == null || cat.isBlank()) ? "Sin categoría" : cat;
                Label chip = new Label(txt);
                chip.getStyleClass().setAll("badge", "badge-brand");
                chip.setStyle("-fx-font-size:11px;");
                HBox wrap = new HBox(chip);
                wrap.setAlignment(Pos.CENTER_LEFT);
                setGraphic(wrap);
                setText(null);
            }
        });

        TableColumn<ProductoDetalle, Double> colPrecio = new TableColumn<>("PRECIO");
        colPrecio.setCellValueFactory(new PropertyValueFactory<>("precioVenta"));
        colPrecio.setPrefWidth(160);
        colPrecio.setStyle("-fx-alignment:CENTER_RIGHT;");
        colPrecio.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double precio, boolean empty) {
                super.updateItem(precio, empty);
                if (empty || precio == null) { setText(null); return; }
                setText("$" + fmt.format(precio.longValue()));
                setStyle("-fx-font-weight:bold; -fx-font-size:14px; -fx-text-fill: -pu-text-primary; "
                        + "-fx-alignment:CENTER_RIGHT; -fx-padding:0 24 0 0;");
            }
        });

        tablaProductos.getColumns().addAll(colNombre, colCategoria, colPrecio);
        tablaProductos.setItems(datos);
        tablaProductos.setRowFactory(tv -> {
            TableRow<ProductoDetalle> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    mostrarDialogoEditar(row.getItem());
                }
            });
            return row;
        });
        tablaProductos.setPlaceholder(new Label("No hay productos que mostrar"));
    }

    // ─── Stats ────────────────────────────────────────────────────────────────
    private void cargarStats() {
        List<ProductoDetalle> todos = db.getProductos(null, null, 0);
        // Contar productos únicos (mismo criterio que la tabla)
        java.util.HashSet<String> claves = new java.util.HashSet<>();
        for (ProductoDetalle p : todos) {
            String nom = p.getNombre() == null ? "" : p.getNombre().trim().toLowerCase();
            String sku = p.getSku() == null ? "" : p.getSku().trim().toLowerCase();
            claves.add(nom + "|" + sku);
        }
        statTotal.setText(String.valueOf(claves.size()));
        statCategorias.setText(String.valueOf(Math.max(0, db.getCategorias().size() - 1)));
    }

    // ─── Carga de datos ───────────────────────────────────────────────────────
    private void cargarDatos() {
        String busqueda = searchField != null ? searchField.getText() : null;
        String cat = filtroCategoria != null ? filtroCategoria.getValue() : null;
        int catId = resolverCategoriaId(cat);

        List<ProductoDetalle> lista = db.getProductos(busqueda, null, catId);

        // Dedupe por (nombre normalizado + SKU): el catálogo viene con duplicados
        // del sync; mostramos un solo registro por producto lógico.
        LinkedHashMap<String, ProductoDetalle> unicos = new LinkedHashMap<>();
        for (ProductoDetalle p : lista) {
            String nom = p.getNombre() == null ? "" : p.getNombre().trim().toLowerCase();
            String sku = p.getSku() == null ? "" : p.getSku().trim().toLowerCase();
            String key = nom + "|" + sku;
            unicos.putIfAbsent(key, p);
        }
        java.util.List<ProductoDetalle> deduped = new java.util.ArrayList<>(unicos.values());

        int total = deduped.size();
        pageCount = Math.max(1, (int) Math.ceil((double) total / pageSize));
        if (currentPage > pageCount) currentPage = pageCount;
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(total, start + pageSize);
        java.util.List<ProductoDetalle> pageItems = deduped.subList(start, end);

        datos.setAll(pageItems);
        if (pageLabel != null) pageLabel.setText("Página " + currentPage + " de " + pageCount);
        if (btnPrevPage != null) btnPrevPage.setDisable(currentPage <= 1);
        if (btnNextPage != null) btnNextPage.setDisable(currentPage >= pageCount);
        if (mostrando != null)
            mostrando.setText("Mostrando " + pageItems.size()
                    + " de " + total + " producto" + (pageItems.size() != 1 ? "s" : ""));
    }

    private int resolverCategoriaId(String catNombre) {
        if (catNombre == null || catNombre.equals("Todas")) return 0;
        return db.getCategoriaMap().entrySet().stream()
                .filter(e -> e.getValue().equals(catNombre))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(0);
    }

    private void configurarBusqueda() {
        searchField.textProperty().addListener((obs, old, val) -> {
            currentPage = 1;
            cargarDatos();
        });
    }

    @FXML void onFiltrar() { currentPage = 1; cargarDatos(); }

    @FXML
    void onPrevPage() {
        if (currentPage > 1) {
            currentPage--;
            cargarDatos();
        }
    }

    @FXML
    void onNextPage() {
        if (currentPage < pageCount) {
            currentPage++;
            cargarDatos();
        }
    }

    @FXML
    void onVolver() {
        try { App.showDashboard(); } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── Diálogo Editar Producto ──────────────────────────────────────────────
    private void mostrarDialogoEditar(ProductoDetalle p) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Editar Producto");
        dialog.setHeaderText(null);

        // ── Campos ──
        TextField fNombre     = styledField(p.getNombre());
        TextField fPrecioVenta = styledField(fmt.format((long) p.getPrecioVenta()));
        TextField fPrecioCosto = styledField(fmt.format((long) p.getPrecioCosto()));
        TextField fStock       = styledField(String.valueOf(p.getStock()));
        TextField fStockMin    = styledField(String.valueOf(p.getStockMinimo()));
        TextField fStockMax    = styledField(String.valueOf(p.getStockMaximo()));
        TextField fCodBarras   = styledField(nvl(p.getCodigoBarras()));
        TextField fSku         = styledField(nvl(p.getSku()));
        TextArea  fDescripcion = new TextArea(nvl(p.getDescripcion()));
        fDescripcion.setPrefRowCount(3);
        fDescripcion.setWrapText(true);
        fDescripcion.getStyleClass().add("field-base");

        CheckBox chkBalanza = new CheckBox("Producto de balanza (precio por peso)");
        chkBalanza.setSelected(p.isEsBalanza());
        chkBalanza.getStyleClass().add("label-sub");
        chkBalanza.setStyle("-fx-text-fill: -pu-text-primary;");

        // Categoría ComboBox
        Map<Integer, String> catMap = db.getCategoriaMap();
        ComboBox<String> cbCat = new ComboBox<>();
        cbCat.getStyleClass().add("combo-box");
        cbCat.setPrefWidth(Double.MAX_VALUE);
        cbCat.getItems().add("Sin categoría");
        int[] catIds = new int[catMap.size() + 1];
        catIds[0] = 0;
        int idx = 1;
        for (Map.Entry<Integer, String> e : catMap.entrySet()) {
            cbCat.getItems().add(e.getValue());
            catIds[idx++] = e.getKey();
        }
        // Seleccionar la categoría actual
        String catActual = p.getCategoria();
        if (catActual != null && cbCat.getItems().contains(catActual))
            cbCat.setValue(catActual);
        else
            cbCat.setValue("Sin categoría");

        // ── Layout ──
        VBox content = new VBox(16);
        content.setPadding(new Insets(24));
        content.setPrefWidth(480);

        String tipoLabel = p.getTipo() != null
                ? Character.toUpperCase(p.getTipo().charAt(0)) + p.getTipo().substring(1)
                : "Producto";
        Label titulo = new Label("Editar " + tipoLabel);
        titulo.getStyleClass().add("label-heading");
        titulo.setStyle("-fx-font-size:20px;");

        content.getChildren().addAll(
                titulo,
                seccion("Información del Producto",
                        fila("Nombre del Producto", fNombre),
                        fila2("Precio de Venta ($)", fPrecioVenta, "Precio de Costo ($)", fPrecioCosto)
                ),
                seccion("Control de Inventario",
                        fila2("Stock Actual", fStock, "Stock Mínimo", fStockMin),
                        fila("Stock Máximo", fStockMax)
                ),
                seccion("Clasificación",
                        fila("Categoría", cbCat),
                        fila2("Código de Barras", fCodBarras, "SKU", fSku)
                ),
                seccion("Opciones Especiales", chkBalanza),
                seccion("Descripción", fDescripcion)
        );

        // ── Botón Actualizar ──
        Button btnActualizar = new Button("Actualizar Producto");
        btnActualizar.getStyleClass().add("btn-primary");
        btnActualizar.setMaxWidth(Double.MAX_VALUE);

        Label lblError = new Label("");
        lblError.getStyleClass().add("status-error");
        lblError.setStyle("-fx-font-size:12px;");

        content.getChildren().addAll(lblError, btnActualizar);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(600);

        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getStyleClass().add("dialog-pane");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        // Ocultar el botón Cancel del DialogPane y manejarlo con el nuestro
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelBtn.setVisible(false);
        cancelBtn.setManaged(false);

        dialog.setResultConverter(bt -> false);

        btnActualizar.setOnAction(e -> {
            try {
                p.setNombre(fNombre.getText().trim());
                p.setPrecioVenta(parseMonto(fPrecioVenta.getText()));
                p.setPrecioCosto(parseMonto(fPrecioCosto.getText()));
                p.setStock(parseInt(fStock.getText()));
                p.setStockMinimo(parseInt(fStockMin.getText()));
                p.setStockMaximo(parseInt(fStockMax.getText()));
                p.setCodigoBarras(fCodBarras.getText().trim());
                p.setSku(fSku.getText().trim());
                p.setDescripcion(fDescripcion.getText().trim());
                p.setEsBalanza(chkBalanza.isSelected());

                // Resolver categoría
                int selIdx = cbCat.getSelectionModel().getSelectedIndex();
                p.setCategoriaId(selIdx >= 0 ? catIds[selIdx] : 0);

                if (p.getNombre().isEmpty()) {
                    lblError.setText("El nombre del producto es obligatorio.");
                    return;
                }

                db.updateProducto(p);
                cargarDatos();
                cargarStats();
                dialog.setResult(true);
                dialog.close();
            } catch (Exception ex) {
                lblError.setText("Error: " + ex.getMessage());
            }
        });

        dialog.showAndWait();
    }

    // ─── Helpers de UI ────────────────────────────────────────────────────────

    private VBox seccion(String titulo, javafx.scene.Node... filas) {
        VBox box = new VBox(10);
        Label lbl = new Label(titulo);
        lbl.getStyleClass().add("pu-form-section-title");
        Region sep = new Region();
        sep.getStyleClass().add("pu-divider");
        box.getChildren().addAll(lbl, sep);
        box.getChildren().addAll(filas);
        return box;
    }

    private VBox fila(String label, javafx.scene.Node control) {
        VBox box = new VBox(4);
        Label lbl = new Label(label);
        lbl.getStyleClass().add("pu-form-label");
        if (control instanceof Region) ((Region) control).setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(lbl, control);
        return box;
    }

    private HBox fila2(String lbl1, javafx.scene.Node ctrl1, String lbl2, javafx.scene.Node ctrl2) {
        VBox col1 = fila(lbl1, ctrl1);
        VBox col2 = fila(lbl2, ctrl2);
        HBox.setHgrow(col1, Priority.ALWAYS);
        HBox.setHgrow(col2, Priority.ALWAYS);
        HBox row = new HBox(12, col1, col2);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private TextField styledField(String value) {
        TextField tf = new TextField(value);
        tf.getStyleClass().add("field-base");
        return tf;
    }

    private String nvl(String s) { return s != null ? s : ""; }

    private double parseMonto(String s) {
        try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    // ─── Confirmar eliminar ───────────────────────────────────────────────────
    private void confirmarEliminar(ProductoDetalle p) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Eliminar Producto");
        a.setHeaderText("¿Eliminar \"" + p.getNombre() + "\"?");
        a.setContentText("Esta acción no se puede deshacer.");
        a.showAndWait().ifPresent(r -> {
            if (r == ButtonType.OK) cargarDatos();
        });
    }
}
