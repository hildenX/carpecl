package com.sospos.model;

public class Producto {
    private int id;
    private int supabaseProductId;
    private String nombre;
    private double precio;
    private int stock;
    private String categoria;
    private String codigoBarras;
    private String imagenUrl;

    public Producto(int id, String nombre, double precio, int stock, String categoria) {
        this.id = id;
        this.nombre = nombre;
        this.precio = precio;
        this.stock = stock;
        this.categoria = categoria;
    }

    public int getId() { return id; }
    public int getSupabaseProductId() { return supabaseProductId; }
    public void setSupabaseProductId(int id) { this.supabaseProductId = id; }
    public String getNombre() { return nombre; }
    public double getPrecio() { return precio; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
    public String getCategoria() { return categoria; }
    public String getCodigoBarras() { return codigoBarras; }
    public void setCodigoBarras(String codigoBarras) { this.codigoBarras = codigoBarras; }
    public String getImagenUrl() { return imagenUrl; }
    public void setImagenUrl(String imagenUrl) { this.imagenUrl = imagenUrl; }
}
