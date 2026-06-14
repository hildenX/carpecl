package com.sospos.model;

import javafx.beans.property.*;

public class ProductoDetalle {
    private final IntegerProperty id = new SimpleIntegerProperty();
    private final StringProperty nombre = new SimpleStringProperty();
    private final StringProperty categoria = new SimpleStringProperty();
    private final StringProperty tipo = new SimpleStringProperty();
    private final IntegerProperty stock = new SimpleIntegerProperty();
    private final IntegerProperty stockMinimo = new SimpleIntegerProperty();
    private final IntegerProperty stockMaximo = new SimpleIntegerProperty();
    private final DoubleProperty precioVenta = new SimpleDoubleProperty();
    private final DoubleProperty precioCosto = new SimpleDoubleProperty();
    private final BooleanProperty activo = new SimpleBooleanProperty(true);
    private final StringProperty codigoBarras = new SimpleStringProperty();
    private final StringProperty sku = new SimpleStringProperty();
    private final StringProperty descripcion = new SimpleStringProperty();
    private final BooleanProperty esBalanza = new SimpleBooleanProperty(false);
    private int categoriaId;

    // ─── Constructores ───────────────────────────────────────────────────────
    public ProductoDetalle() {}

    public ProductoDetalle(int id, String nombre, String categoria, String tipo,
                           int stock, int stockMinimo, double precioVenta) {
        this.id.set(id);
        this.nombre.set(nombre);
        this.categoria.set(categoria);
        this.tipo.set(tipo);
        this.stock.set(stock);
        this.stockMinimo.set(stockMinimo);
        this.precioVenta.set(precioVenta);
    }

    // ─── Properties ──────────────────────────────────────────────────────────
    public IntegerProperty idProperty()          { return id; }
    public StringProperty nombreProperty()       { return nombre; }
    public StringProperty categoriaProperty()    { return categoria; }
    public StringProperty tipoProperty()         { return tipo; }
    public IntegerProperty stockProperty()       { return stock; }
    public IntegerProperty stockMinimoProperty() { return stockMinimo; }
    public IntegerProperty stockMaximoProperty() { return stockMaximo; }
    public DoubleProperty precioVentaProperty()  { return precioVenta; }
    public DoubleProperty precioCostoProperty()  { return precioCosto; }
    public BooleanProperty activoProperty()      { return activo; }
    public StringProperty codigoBarrasProperty() { return codigoBarras; }
    public StringProperty skuProperty()          { return sku; }
    public StringProperty descripcionProperty()  { return descripcion; }
    public BooleanProperty esBalanzaProperty()   { return esBalanza; }

    // ─── Getters / Setters ───────────────────────────────────────────────────
    public int getId()              { return id.get(); }
    public String getNombre()       { return nombre.get(); }
    public String getCategoria()    { return categoria.get(); }
    public String getTipo()         { return tipo.get(); }
    public int getStock()           { return stock.get(); }
    public int getStockMinimo()     { return stockMinimo.get(); }
    public int getStockMaximo()     { return stockMaximo.get(); }
    public double getPrecioVenta()  { return precioVenta.get(); }
    public double getPrecioCosto()  { return precioCosto.get(); }
    public boolean isActivo()       { return activo.get(); }
    public String getCodigoBarras() { return codigoBarras.get(); }
    public String getSku()          { return sku.get(); }
    public String getDescripcion()  { return descripcion.get(); }
    public boolean isEsBalanza()    { return esBalanza.get(); }
    public int getCategoriaId()     { return categoriaId; }

    public void setId(int v)              { id.set(v); }
    public void setNombre(String v)       { nombre.set(v); }
    public void setCategoria(String v)    { categoria.set(v); }
    public void setTipo(String v)         { tipo.set(v); }
    public void setStock(int v)           { stock.set(v); }
    public void setStockMinimo(int v)     { stockMinimo.set(v); }
    public void setStockMaximo(int v)     { stockMaximo.set(v); }
    public void setPrecioVenta(double v)  { precioVenta.set(v); }
    public void setPrecioCosto(double v)  { precioCosto.set(v); }
    public void setActivo(boolean v)      { activo.set(v); }
    public void setCodigoBarras(String v) { codigoBarras.set(v); }
    public void setSku(String v)          { sku.set(v); }
    public void setDescripcion(String v)  { descripcion.set(v); }
    public void setEsBalanza(boolean v)   { esBalanza.set(v); }
    public void setCategoriaId(int v)     { categoriaId = v; }

    public boolean stockBajo() {
        return stockMinimo.get() > 0 && stock.get() <= stockMinimo.get();
    }
}
