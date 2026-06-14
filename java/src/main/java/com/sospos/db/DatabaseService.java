package com.sospos.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.sospos.model.ItemCarrito;
import com.sospos.model.Producto;
import com.sospos.model.ProductoDetalle;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite local — replica las tablas clave de Supabase:
 * productos, categorias, precios, inventario
 */
public class DatabaseService {

    private static DatabaseService instance;
    private Connection conn;

    private static final String DB_PATH =
            System.getProperty("user.home") + "/sos-pos/sos-pos.db";

    private DatabaseService() {
        try {
            // Crear carpeta si no existe
            new java.io.File(System.getProperty("user.home") + "/sos-pos").mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            crearTablas();
            migrarColumnasSync();
        } catch (SQLException e) {
            throw new RuntimeException("Error al inicializar SQLite: " + e.getMessage(), e);
        }
    }

    public static DatabaseService getInstance() {
        if (instance == null) instance = new DatabaseService();
        return instance;
    }

    // ─── Crear tablas (misma estructura que Supabase) ────────────────────────
    private void crearTablas() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS categorias (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    supabase_id TEXT UNIQUE,
                    nombre TEXT NOT NULL,
                    descripcion TEXT,
                    color TEXT DEFAULT '#7C3AED',
                    icono TEXT,
                    activo INTEGER DEFAULT 1,
                    tipo TEXT DEFAULT 'normal',
                    categoria_padre_id INTEGER
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS productos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    supabase_id TEXT UNIQUE,
                    nombre TEXT NOT NULL,
                    descripcion TEXT,
                    categoria_id INTEGER,
                    activo INTEGER DEFAULT 1,
                    codigo_barras TEXT,
                    sku TEXT,
                    tipo TEXT DEFAULT 'comun',
                    es_balanza INTEGER DEFAULT 0,
                    imagen_url TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (categoria_id) REFERENCES categorias(id)
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS precios (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    supabase_id TEXT UNIQUE,
                    producto_id INTEGER,
                    tipo_lista TEXT DEFAULT 'minorista',
                    precio_venta REAL DEFAULT 0,
                    precio_costo REAL DEFAULT 0,
                    incluye_iva INTEGER DEFAULT 0,
                    activo INTEGER DEFAULT 1,
                    FOREIGN KEY (producto_id) REFERENCES productos(id)
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS inventario (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    supabase_id TEXT UNIQUE,
                    producto_id INTEGER UNIQUE,
                    stock_actual INTEGER DEFAULT 0,
                    stock_minimo INTEGER DEFAULT 5,
                    stock_maximo INTEGER DEFAULT 100,
                    FOREIGN KEY (producto_id) REFERENCES productos(id)
                )""");

            // Configuración local (sesión guardada, etc.)
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS config (
                    clave TEXT PRIMARY KEY,
                    valor TEXT
                )""");

            // Cajas registradoras
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cajas_registro (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    supabase_id TEXT UNIQUE,
                    nombre TEXT,
                    estado TEXT DEFAULT 'cerrada',
                    cajero_nombre TEXT,
                    monto_apertura REAL DEFAULT 0,
                    bodega_nombre TEXT,
                    activo INTEGER DEFAULT 1
                )""");

            // Cola de ventas pendientes de sync con Supabase
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tabla TEXT NOT NULL,
                    operacion TEXT NOT NULL,
                    datos_json TEXT NOT NULL,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    sincronizado INTEGER DEFAULT 0
                )""");

            // Ventas offline
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ventas (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fecha TEXT DEFAULT CURRENT_TIMESTAMP,
                    total REAL DEFAULT 0,
                    subtotal REAL DEFAULT 0,
                    impuesto REAL DEFAULT 0,
                    descuento REAL DEFAULT 0,
                    metodo_pago TEXT DEFAULT 'efectivo',
                    estado TEXT DEFAULT 'completada',
                    tipo_dte TEXT DEFAULT 'boleta',
                    notas TEXT,
                    sincronizado INTEGER DEFAULT 0,
                    dte_estado TEXT DEFAULT 'pendiente',
                    dte_folio INTEGER,
                    dte_track_id TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS detalles_venta (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    venta_id INTEGER NOT NULL,
                    producto_id INTEGER,
                    producto_nombre TEXT,
                    cantidad INTEGER DEFAULT 1,
                    precio_unitario REAL DEFAULT 0,
                    subtotal REAL DEFAULT 0,
                    FOREIGN KEY (venta_id) REFERENCES ventas(id)
                )""");

            // Ventas offline pendientes de subir a Supabase como nota de contingencia.
            // El SOS NO emite DTE — solo guarda y sincroniza. POS Matic web convierte la nota en DTE.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS contingencia_ventas (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    id_uuid      TEXT NOT NULL UNIQUE,
                    numero_nota  TEXT NOT NULL,
                    fecha_venta  TEXT NOT NULL,
                    items_json   TEXT NOT NULL,
                    monto_neto   INTEGER NOT NULL DEFAULT 0,
                    monto_iva    INTEGER NOT NULL DEFAULT 0,
                    monto_total  INTEGER NOT NULL DEFAULT 0,
                    rut_receptor TEXT,
                    razon_social TEXT,
                    direccion    TEXT,
                    giro         TEXT,
                    correo       TEXT,
                    estado_sync  TEXT NOT NULL DEFAULT 'pendiente',
                    error_msg    TEXT,
                    created_at   TEXT DEFAULT (datetime('now'))
                )""");
        }
    }

    // ─── Contingencia (modo offline) ─────────────────────────────────────────

    /** Inserta una venta de contingencia en SQLite local (idempotente por id_uuid). */
    public void guardarContingencia(String idUuid, String numeroNota, String fechaVentaUtc,
                                    String itemsJson, int montoNeto, int montoIva, int montoTotal,
                                    String rutReceptor, String razonSocial,
                                    String direccion, String giro, String correo) {
        String sql = """
            INSERT OR IGNORE INTO contingencia_ventas
            (id_uuid, numero_nota, fecha_venta, items_json,
             monto_neto, monto_iva, monto_total,
             rut_receptor, razon_social, direccion, giro, correo)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, idUuid);
            ps.setString(2, numeroNota);
            ps.setString(3, fechaVentaUtc);
            ps.setString(4, itemsJson);
            ps.setInt(5, montoNeto);
            ps.setInt(6, montoIva);
            ps.setInt(7, montoTotal);
            ps.setString(8, rutReceptor);
            ps.setString(9, razonSocial);
            ps.setString(10, direccion);
            ps.setString(11, giro);
            ps.setString(12, correo);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public List<Map<String, Object>> getContingenciasPendientes() {
        List<Map<String, Object>> lista = new ArrayList<>();
        String sql = "SELECT * FROM contingencia_ventas WHERE estado_sync='pendiente' ORDER BY id";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("id",           rs.getInt("id"));
                row.put("id_uuid",      rs.getString("id_uuid"));
                row.put("numero_nota",  rs.getString("numero_nota"));
                row.put("fecha_venta",  rs.getString("fecha_venta"));
                row.put("items_json",   rs.getString("items_json"));
                row.put("monto_neto",   rs.getInt("monto_neto"));
                row.put("monto_iva",    rs.getInt("monto_iva"));
                row.put("monto_total",  rs.getInt("monto_total"));
                row.put("rut_receptor", rs.getString("rut_receptor"));
                row.put("razon_social", rs.getString("razon_social"));
                row.put("direccion",    rs.getString("direccion"));
                row.put("giro",         rs.getString("giro"));
                row.put("correo",       rs.getString("correo"));
                lista.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return lista;
    }

    public int countContingenciasPendientes() {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM contingencia_ventas WHERE estado_sync='pendiente'");
            return rs.getInt(1);
        } catch (SQLException e) { return 0; }
    }

    public void marcarContingenciaSincronizada(String idUuid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE contingencia_ventas SET estado_sync='sincronizada' WHERE id_uuid=?")) {
            ps.setString(1, idUuid);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void marcarContingenciaError(String idUuid, String error) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE contingencia_ventas SET estado_sync='error', error_msg=? WHERE id_uuid=?")) {
            ps.setString(1, error);
            ps.setString(2, idUuid);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /** Genera correlativo CONT-YYYYMMDD-NNNN persistente por día en config. */
    public String generarNumeroNota() {
        String hoy = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key = "notaSeq_" + hoy;
        String actual = getConfig(key);
        int siguiente = (actual == null || actual.isEmpty()) ? 1 : Integer.parseInt(actual) + 1;
        setConfig(key, String.valueOf(siguiente));
        return String.format("CONT-%s-%04d", hoy, siguiente);
    }

    private void ejecutar(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // ─── Consultas de Productos ──────────────────────────────────────────────

    public List<ProductoDetalle> getProductos(String busqueda, String tipo, int categoriaId) {
        List<ProductoDetalle> lista = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.nombre, c.nombre AS categoria, p.categoria_id, p.tipo,
                   COALESCE(i.stock_actual,0) AS stock,
                   COALESCE(i.stock_minimo,0) AS stock_minimo,
                   COALESCE(i.stock_maximo,100) AS stock_maximo,
                   COALESCE(pr.precio_venta,0) AS precio_venta,
                   COALESCE(pr.precio_costo,0) AS precio_costo,
                   p.activo, p.codigo_barras, p.sku, p.descripcion, p.es_balanza
            FROM productos p
            LEFT JOIN categorias c ON p.categoria_id = c.id
            LEFT JOIN inventario i ON i.producto_id = p.id
            LEFT JOIN precios pr ON pr.producto_id = p.id AND pr.activo = 1
            WHERE p.activo = 1
            """);

        List<Object> params = new ArrayList<>();

        if (busqueda != null && !busqueda.isBlank()) {
            sql.append(" AND (LOWER(p.nombre) LIKE ? OR LOWER(p.codigo_barras) LIKE ? OR LOWER(p.sku) LIKE ?)");
            String b = "%" + busqueda.toLowerCase() + "%";
            params.add(b); params.add(b); params.add(b);
        }
        if (tipo != null && !tipo.equals("Todos")) {
            sql.append(" AND p.tipo = ?");
            params.add(tipo.toLowerCase());
        }
        if (categoriaId > 0) {
            sql.append(" AND p.categoria_id = ?");
            params.add(categoriaId);
        }
        sql.append(" ORDER BY p.nombre");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ProductoDetalle p = new ProductoDetalle();
                p.setId(rs.getInt("id"));
                p.setNombre(rs.getString("nombre"));
                p.setCategoria(rs.getString("categoria") != null ? rs.getString("categoria") : "Sin categoría");
                p.setTipo(rs.getString("tipo"));
                p.setStock(rs.getInt("stock"));
                p.setStockMinimo(rs.getInt("stock_minimo"));
                p.setStockMaximo(rs.getInt("stock_maximo"));
                p.setPrecioVenta(rs.getDouble("precio_venta"));
                p.setPrecioCosto(rs.getDouble("precio_costo"));
                p.setActivo(rs.getInt("activo") == 1);
                p.setCodigoBarras(rs.getString("codigo_barras"));
                p.setSku(rs.getString("sku"));
                p.setDescripcion(rs.getString("descripcion"));
                p.setEsBalanza(rs.getInt("es_balanza") == 1);
                p.setCategoriaId(rs.getInt("categoria_id"));
                lista.add(p);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return lista;
    }

    public List<String> getCategorias() {
        List<String> lista = new ArrayList<>();
        lista.add("Todas");
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT nombre FROM categorias WHERE activo=1 ORDER BY nombre");
            while (rs.next()) lista.add(rs.getString("nombre"));
        } catch (SQLException e) { e.printStackTrace(); }
        return lista;
    }

    public int countStockBajo() {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM inventario WHERE stock_actual <= stock_minimo AND stock_minimo > 0");
            return rs.getInt(1);
        } catch (SQLException e) { return 0; }
    }

    public double getValorTotal() {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                "SELECT SUM(i.stock_actual * pr.precio_venta) FROM inventario i " +
                "JOIN precios pr ON pr.producto_id = i.producto_id AND pr.activo=1");
            return rs.getDouble(1);
        } catch (SQLException e) { return 0; }
    }

    public Map<Integer, String> getCategoriaMap() {
        Map<Integer, String> map = new LinkedHashMap<>();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT id, nombre FROM categorias WHERE activo=1 ORDER BY nombre");
            while (rs.next()) map.put(rs.getInt("id"), rs.getString("nombre"));
        } catch (SQLException e) { e.printStackTrace(); }
        return map;
    }

    public void updateProducto(ProductoDetalle p) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE productos SET nombre=?, descripcion=?, categoria_id=?, " +
                    "codigo_barras=?, sku=?, es_balanza=? WHERE id=?")) {
                ps.setString(1, p.getNombre());
                ps.setString(2, p.getDescripcion());
                if (p.getCategoriaId() > 0) ps.setInt(3, p.getCategoriaId());
                else ps.setNull(3, Types.INTEGER);
                ps.setString(4, p.getCodigoBarras());
                ps.setString(5, p.getSku());
                ps.setInt(6, p.isEsBalanza() ? 1 : 0);
                ps.setInt(7, p.getId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE precios SET precio_venta=?, precio_costo=? WHERE producto_id=? AND activo=1")) {
                ps.setDouble(1, p.getPrecioVenta());
                ps.setDouble(2, p.getPrecioCosto());
                ps.setInt(3, p.getId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE inventario SET stock_actual=?, stock_minimo=?, stock_maximo=? WHERE producto_id=?")) {
                ps.setInt(1, p.getStock());
                ps.setInt(2, p.getStockMinimo());
                ps.setInt(3, p.getStockMaximo());
                ps.setInt(4, p.getId());
                ps.executeUpdate();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public List<CajaInfo> getCajas() {
        List<CajaInfo> lista = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                "SELECT supabase_id, nombre, estado, cajero_nombre, monto_apertura, bodega_nombre " +
                "FROM cajas_registro WHERE activo=1 ORDER BY nombre");
            while (rs.next()) {
                CajaInfo c = new CajaInfo();
                c.supabaseId   = rs.getString("supabase_id");
                c.nombre       = rs.getString("nombre");
                c.estado       = rs.getString("estado");
                c.cajeroNombre = rs.getString("cajero_nombre");
                c.montoApertura = rs.getDouble("monto_apertura");
                c.bodegaNombre = rs.getString("bodega_nombre");
                lista.add(c);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return lista;
    }

    public int upsertCajasRegistro(JsonNode array) throws SQLException {
        int count = 0;
        String sql = """
            INSERT INTO cajas_registro(supabase_id, nombre, estado, cajero_nombre,
                                       monto_apertura, bodega_nombre, activo)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(supabase_id) DO UPDATE SET
              nombre=excluded.nombre, estado=excluded.estado,
              cajero_nombre=excluded.cajero_nombre, monto_apertura=excluded.monto_apertura,
              bodega_nombre=excluded.bodega_nombre, activo=excluded.activo
            """;
        for (JsonNode n : array) {
            String sbId = n.path("id").asText(null);
            if (sbId == null || sbId.equals("null")) continue;
            // Extraer nombre del cajero (puede estar en sesion_actual→usuario→nombre o cajero_nombre)
            String cajeroNombre = n.path("cajero_nombre").asText(null);
            if (cajeroNombre == null || cajeroNombre.isBlank()) {
                cajeroNombre = n.path("sesion_actual").path("usuario").path("nombre").asText(null);
            }
            // Bodega puede venir como objeto join o como campo
            String bodegaNombre = n.path("bodega_nombre").asText(null);
            if (bodegaNombre == null || bodegaNombre.isBlank()) {
                bodegaNombre = n.path("bodegas").path("nombre").asText(null);
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, sbId);
                ps.setString(2, n.path("nombre").asText("Caja"));
                ps.setString(3, n.path("estado").asText("cerrada"));
                ps.setString(4, cajeroNombre);
                ps.setDouble(5, n.path("monto_apertura").asDouble(0));
                ps.setString(6, bodegaNombre);
                ps.setInt(7, n.path("activo").asBoolean(true) ? 1 : 0);
                ps.executeUpdate();
                count++;
            }
        }
        return count;
    }

    public static class CajaInfo {
        public String supabaseId, nombre, estado, cajeroNombre, bodegaNombre;
        public double montoApertura;
        public boolean isAbierta() { return "abierta".equalsIgnoreCase(estado); }
        public String idCorto() {
            if (supabaseId == null || supabaseId.length() < 8) return "?";
            return supabaseId.substring(0, 8).toUpperCase();
        }
    }

    public int countPendientesSync() {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sync_queue WHERE sincronizado=0");
            return rs.getInt(1);
        } catch (SQLException e) { return 0; }
    }

    public Connection getConn() { return conn; }

    // ─── Configuración / Sesión ───────────────────────────────────────────────

    public void saveSession(String userId, String email, String nombreNegocio) {
        setConfig("user_id", userId);
        setConfig("email", email);
        setConfig("nombre_negocio", nombreNegocio != null ? nombreNegocio : "");
    }

    public void saveSession(String userId, String email, String nombreNegocio, String accessToken) {
        saveSession(userId, email, nombreNegocio);
        if (accessToken != null) setConfig("access_token", accessToken);
    }

    public void clearSession() {
        setConfig("user_id", null);
        setConfig("email", null);
        setConfig("nombre_negocio", null);
        setConfig("access_token", null);
        setConfig("refresh_token", null);
    }

    public void setConfigValue(String clave, String valor) { setConfig(clave, valor); }

    private void setConfig(String clave, String valor) {
        try {
            String sql = "INSERT OR REPLACE INTO config(clave, valor) VALUES(?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, clave);
                ps.setString(2, valor);
                ps.executeUpdate();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public String getConfig(String clave) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT valor FROM config WHERE clave=?")) {
            ps.setString(1, clave);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    /** Genera y persiste un identificador único para esta terminal (una sola vez). */
    public void inicializarDeviceId() {
        String existing = getConfig("deviceId");
        if (existing != null && !existing.isEmpty()) return;
        String hostname = "CAJA";
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
            if (hostname == null || hostname.isBlank()) hostname = "CAJA";
            if (hostname.length() > 12) hostname = hostname.substring(0, 12);
        } catch (Exception ignored) {}
        String deviceId = hostname.toUpperCase() + "-" + (1000 + new java.util.Random().nextInt(9000));
        setConfig("deviceId", deviceId);
        System.out.println("[SOS] Device ID generado: " + deviceId);
    }

    public String getDeviceId() {
        String id = getConfig("deviceId");
        return (id != null && !id.isEmpty()) ? id : "SOS-DESCONOCIDO";
    }

    // ─── Migración: agregar columnas si no existen ───────────────────────────
    private void migrarColumnasSync() throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String tabla : new String[]{"categorias", "productos", "precios", "inventario"}) {
                try { st.execute("ALTER TABLE " + tabla + " ADD COLUMN supabase_id TEXT"); }
                catch (SQLException ignored) {}
                try { st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_" + tabla + "_sb ON "
                        + tabla + "(supabase_id) WHERE supabase_id IS NOT NULL"); }
                catch (SQLException ignored) {}
            }
            // Migrar columnas DTE en ventas (bases de datos existentes)
            try { st.execute("ALTER TABLE ventas ADD COLUMN dte_estado TEXT DEFAULT 'pendiente'"); }
            catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE ventas ADD COLUMN dte_folio INTEGER"); }
            catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE ventas ADD COLUMN dte_track_id TEXT"); }
            catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE productos ADD COLUMN imagen_url TEXT"); }
            catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE ventas ADD COLUMN dte_pdf TEXT"); }
            catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE ventas ADD COLUMN supabase_venta_id TEXT"); }
            catch (SQLException ignored) {}
            // Migrar columnas receptor en contingencia_ventas (bases de datos existentes)
            try { st.execute("ALTER TABLE contingencia_ventas ADD COLUMN direccion TEXT"); }
            catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE contingencia_ventas ADD COLUMN giro TEXT"); }
            catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE contingencia_ventas ADD COLUMN correo TEXT"); }
            catch (SQLException ignored) {}
        }
    }

    /** Retorna el dte_estado actual de una venta (para evitar doble emisión) */
    public String getDteEstado(int ventaId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT dte_estado FROM ventas WHERE id=?")) {
            ps.setInt(1, ventaId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    /** Guarda el UUID de Supabase para poder hacer PATCH posterior */
    public void setSupabaseVentaId(int ventaId, String uuid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ventas SET supabase_venta_id=? WHERE id=?")) {
            ps.setString(1, uuid);
            ps.setInt(2, ventaId);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /** Retorna el UUID de Supabase guardado, o null si no se sincronizó todavía */
    public String getSupabaseVentaId(int ventaId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT supabase_venta_id FROM ventas WHERE id=?")) {
            ps.setInt(1, ventaId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    /** Guarda el PDF en base64 generado por el servidor (con TED/PDF417) */
    public void saveDtePdf(int ventaId, String pdfBase64) {
        if (pdfBase64 == null || pdfBase64.isBlank()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ventas SET dte_pdf=? WHERE id=?")) {
            ps.setString(1, pdfBase64);
            ps.setInt(2, ventaId);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /** Retorna el PDF en base64 guardado por el servidor, o null si no existe */
    public String getDtePdf(int ventaId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT dte_pdf FROM ventas WHERE id=?")) {
            ps.setInt(1, ventaId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    // ─── Upsert desde Supabase (INSERT OR UPDATE por supabase_id) ────────────

    public int upsertCategorias(JsonNode array) throws SQLException {
        int count = 0;
        String sql = """
            INSERT INTO categorias(supabase_id, nombre, descripcion, color, icono, activo, tipo)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(supabase_id) DO UPDATE SET
              nombre=excluded.nombre, descripcion=excluded.descripcion,
              color=excluded.color, icono=excluded.icono,
              activo=excluded.activo, tipo=excluded.tipo
            """;
        for (JsonNode n : array) {
            String sbId = n.path("id").asText(null);
            if (sbId == null || sbId.equals("null")) continue;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, sbId);
                ps.setString(2, n.path("nombre").asText("?"));
                ps.setString(3, n.path("descripcion").asText(null));
                ps.setString(4, n.path("color").asText("#7C3AED"));
                ps.setString(5, n.path("icono").asText(null));
                ps.setInt(6, n.path("activo").asBoolean(true) ? 1 : 0);
                ps.setString(7, n.path("tipo").asText("normal"));
                ps.executeUpdate();
                count++;
            }
        }
        return count;
    }

    public int upsertProductos(JsonNode array) throws SQLException {
        int count = 0;
        String sql = """
            INSERT INTO productos(supabase_id, nombre, descripcion, categoria_id, activo,
                                  codigo_barras, sku, tipo, imagen_url)
            VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(supabase_id) DO UPDATE SET
              nombre=excluded.nombre, descripcion=excluded.descripcion,
              categoria_id=excluded.categoria_id, activo=excluded.activo,
              codigo_barras=excluded.codigo_barras, sku=excluded.sku, tipo=excluded.tipo,
              imagen_url=excluded.imagen_url
            """;
        for (JsonNode n : array) {
            String sbId = n.path("id").asText(null);
            if (sbId == null || sbId.equals("null")) continue;
            String sbCatId = n.path("categoria_id").asText(null);
            Integer localCatId = (sbCatId != null && !sbCatId.equals("null"))
                    ? getLocalIdBySbId("categorias", sbCatId) : null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, sbId);
                ps.setString(2, n.path("nombre").asText("?"));
                ps.setString(3, n.path("descripcion").asText(null));
                if (localCatId != null) ps.setInt(4, localCatId);
                else ps.setNull(4, Types.INTEGER);
                ps.setInt(5, n.path("activo").asBoolean(true) ? 1 : 0);
                ps.setString(6, n.path("codigo_barras").asText(null));
                ps.setString(7, n.path("sku").asText(null));
                ps.setString(8, n.path("tipo").asText("comun"));
                ps.setString(9, n.path("imagen_url").asText(null));
                ps.executeUpdate();
                count++;
            }
        }
        return count;
    }

    public int upsertPrecios(JsonNode array) throws SQLException {
        int count = 0;
        String sql = """
            INSERT INTO precios(supabase_id, producto_id, tipo_lista, precio_venta, precio_costo,
                                incluye_iva, activo)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(supabase_id) DO UPDATE SET
              producto_id=excluded.producto_id, precio_venta=excluded.precio_venta,
              precio_costo=excluded.precio_costo, incluye_iva=excluded.incluye_iva,
              activo=excluded.activo
            """;
        for (JsonNode n : array) {
            String sbId = n.path("id").asText(null);
            if (sbId == null || sbId.equals("null")) continue;
            String sbProdId = n.path("producto_id").asText(null);
            Integer localProdId = (sbProdId != null && !sbProdId.equals("null"))
                    ? getLocalIdBySbId("productos", sbProdId) : null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, sbId);
                if (localProdId != null) ps.setInt(2, localProdId);
                else ps.setNull(2, Types.INTEGER);
                ps.setString(3, n.path("tipo_lista").asText("minorista"));
                ps.setDouble(4, n.path("precio_venta").asDouble(0));
                ps.setDouble(5, n.path("precio_costo").asDouble(0));
                ps.setInt(6, n.path("incluye_iva").asBoolean(false) ? 1 : 0);
                ps.setInt(7, n.path("activo").asBoolean(true) ? 1 : 0);
                ps.executeUpdate();
                count++;
            }
        }
        return count;
    }

    public int upsertInventario(JsonNode array) throws SQLException {
        int count = 0;
        String sql = """
            INSERT INTO inventario(supabase_id, producto_id, stock_actual, stock_minimo, stock_maximo)
            VALUES(?,?,?,?,?)
            ON CONFLICT(supabase_id) DO UPDATE SET
              producto_id=excluded.producto_id, stock_actual=excluded.stock_actual,
              stock_minimo=excluded.stock_minimo, stock_maximo=excluded.stock_maximo
            """;
        for (JsonNode n : array) {
            String sbId = n.path("id").asText(null);
            if (sbId == null || sbId.equals("null")) continue;
            String sbProdId = n.path("producto_id").asText(null);
            Integer localProdId = (sbProdId != null && !sbProdId.equals("null"))
                    ? getLocalIdBySbId("productos", sbProdId) : null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, sbId);
                if (localProdId != null) ps.setInt(2, localProdId);
                else ps.setNull(2, Types.INTEGER);
                ps.setInt(3, n.path("stock_actual").asInt(0));
                ps.setInt(4, n.path("stock_minimo").asInt(5));
                ps.setInt(5, n.path("stock_maximo").asInt(100));
                ps.executeUpdate();
                count++;
            }
        }
        return count;
    }

    /** Busca el id local de un registro por su supabase_id en la tabla indicada */
    private Integer getLocalIdBySbId(String tabla, String sbId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM " + tabla + " WHERE supabase_id = ?")) {
            ps.setString(1, sbId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    // ─── POS: Productos ───────────────────────────────────────────────────────

    /** Retorna todos los productos activos con precio y stock para el POS */
    public List<Producto> getProductosPOS() {
        List<Producto> lista = new ArrayList<>();
        // La subquery de precios garantiza exactamente UNA fila por producto,
        // priorizando la lista 'minorista'. Sin esto, múltiples listas de precio
        // causan que el mismo producto aparezca duplicado en el POS.
        String sql = """
            SELECT p.id, p.supabase_id, p.nombre, p.codigo_barras, p.imagen_url,
                   COALESCE(pr.precio_venta, 0) AS precio_venta,
                   COALESCE(i.stock_actual, 0)  AS stock,
                   COALESCE(c.nombre, 'Sin categoría') AS categoria
            FROM productos p
            LEFT JOIN precios pr ON pr.id = (
                SELECT id FROM precios
                WHERE producto_id = p.id AND activo = 1
                ORDER BY CASE tipo_lista WHEN 'minorista' THEN 0 ELSE 1 END, id
                LIMIT 1
            )
            LEFT JOIN inventario i ON i.producto_id = p.id
            LEFT JOIN categorias c ON c.id = p.categoria_id
            WHERE p.activo = 1
            ORDER BY p.nombre
            """;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Producto prod = new Producto(
                        rs.getInt("id"),
                        rs.getString("nombre"),
                        rs.getDouble("precio_venta"),
                        rs.getInt("stock"),
                        rs.getString("categoria")
                );
                prod.setCodigoBarras(rs.getString("codigo_barras"));
                prod.setImagenUrl(rs.getString("imagen_url"));
                try { prod.setSupabaseProductId(Integer.parseInt(rs.getString("supabase_id"))); }
                catch (Exception ignored) {}
                lista.add(prod);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return lista;
    }

    // ─── POS: Guardar venta ───────────────────────────────────────────────────

    /**
     * Guarda una venta en SQLite y retorna su ID local.
     * También descuenta el stock de cada producto.
     */
    public int guardarVenta(List<ItemCarrito> items, String metodoPago, double total) {
        int ventaId = -1;
        try {
            conn.setAutoCommit(false);

            // Calcular totales para boleta (IVA incluido en precio)
            long mntTotal  = Math.round(total);
            long mntNeto   = Math.round(total / 1.19);
            long iva        = mntTotal - mntNeto;

            String sqlVenta = """
                INSERT INTO ventas(fecha, total, subtotal, impuesto, metodo_pago,
                                   estado, tipo_dte, dte_estado, created_at)
                VALUES(datetime('now','localtime'), ?, ?, ?, ?, 'completada', 'boleta', 'pendiente',
                       datetime('now','localtime'))
                """;
            try (PreparedStatement ps = conn.prepareStatement(sqlVenta,
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, mntTotal);
                ps.setLong(2, mntNeto);
                ps.setLong(3, iva);
                ps.setString(4, metodoPago);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) ventaId = keys.getInt(1);
            }

            if (ventaId < 0) { conn.rollback(); return -1; }

            // Detalles de la venta — el stock se gestiona desde el sistema web,
            // no se modifica aquí.
            String sqlDetalle = """
                INSERT INTO detalles_venta(venta_id, producto_id, producto_nombre,
                                           cantidad, precio_unitario, subtotal)
                VALUES(?,?,?,?,?,?)
                """;

            for (ItemCarrito item : items) {
                try (PreparedStatement ps = conn.prepareStatement(sqlDetalle)) {
                    ps.setInt(1, ventaId);
                    ps.setInt(2, item.getProducto().getId());
                    ps.setString(3, item.getProducto().getNombre());
                    ps.setInt(4, item.getCantidad());
                    ps.setDouble(5, item.getProducto().getPrecio());
                    ps.setDouble(6, item.getSubtotal());
                    ps.executeUpdate();
                }
            }

            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            e.printStackTrace();
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
        return ventaId;
    }

    // ─── POS: DTE pendientes ──────────────────────────────────────────────────

    public static class VentaDTE {
        public int    id;
        public String fecha;
        public double total;
        public String metodoPago;
        public String tipoDTE;    // 'boleta' | 'factura'
        public int    folio;      // 0 si aún no emitido
        public String trackId;
        public String dteEstado;  // 'pendiente' | 'emitido' | 'error'
        public List<ItemDTE> items = new ArrayList<>();

        public static class ItemDTE {
            public String nombre;
            public int    cantidad;
            public long   precioUnitario;
            public long   montoItem;
        }
    }

    /** Cuenta ventas con DTE pendiente */
    public int countDTEsPendientes() {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ventas WHERE dte_estado='pendiente'");
            return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); return 0; }
    }

    /** Retorna ventas con DTE pendiente de generar */
    public List<VentaDTE> getVentasPendientesDTE() {
        List<VentaDTE> lista = new ArrayList<>();
        String sqlV = """
            SELECT id, fecha, total, metodo_pago,
                   COALESCE(tipo_dte,'boleta') AS tipo_dte,
                   COALESCE(dte_folio,0)       AS dte_folio,
                   dte_track_id
            FROM ventas WHERE dte_estado='pendiente' ORDER BY id
            """;
        String sqlD = "SELECT producto_nombre, cantidad, precio_unitario, subtotal FROM detalles_venta WHERE venta_id=?";
        try (Statement st = conn.createStatement(); ResultSet rsV = st.executeQuery(sqlV)) {
            while (rsV.next()) {
                VentaDTE v = new VentaDTE();
                v.id         = rsV.getInt("id");
                v.fecha      = rsV.getString("fecha");
                v.total      = rsV.getDouble("total");
                v.metodoPago = rsV.getString("metodo_pago");
                v.tipoDTE    = rsV.getString("tipo_dte");
                v.folio      = rsV.getInt("dte_folio");
                v.trackId    = rsV.getString("dte_track_id");

                try (PreparedStatement ps = conn.prepareStatement(sqlD)) {
                    ps.setInt(1, v.id);
                    ResultSet rsD = ps.executeQuery();
                    while (rsD.next()) {
                        VentaDTE.ItemDTE item = new VentaDTE.ItemDTE();
                        item.nombre         = rsD.getString("producto_nombre");
                        item.cantidad       = rsD.getInt("cantidad");
                        item.precioUnitario = Math.round(rsD.getDouble("precio_unitario"));
                        item.montoItem      = Math.round(rsD.getDouble("subtotal"));
                        v.items.add(item);
                    }
                }
                lista.add(v);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return lista;
    }

    /** Retorna todas las ventas (con su estado DTE) ordenadas por fecha desc, con límite */
    public List<VentaDTE> getVentasHistorico(int limit) {
        List<VentaDTE> lista = new ArrayList<>();
        String sqlV = """
            SELECT id, fecha, total, metodo_pago,
                   COALESCE(tipo_dte,'boleta')        AS tipo_dte,
                   COALESCE(dte_folio,0)              AS dte_folio,
                   dte_track_id,
                   COALESCE(dte_estado,'pendiente')   AS dte_estado
            FROM ventas ORDER BY id DESC LIMIT ?
            """;
        String sqlD = "SELECT producto_nombre, cantidad, precio_unitario, subtotal FROM detalles_venta WHERE venta_id=?";
        try (PreparedStatement psV = conn.prepareStatement(sqlV)) {
            psV.setInt(1, limit);
            ResultSet rsV = psV.executeQuery();
            while (rsV.next()) {
                VentaDTE v = new VentaDTE();
                v.id         = rsV.getInt("id");
                v.fecha      = rsV.getString("fecha");
                v.total      = rsV.getDouble("total");
                v.metodoPago = rsV.getString("metodo_pago");
                v.tipoDTE    = rsV.getString("tipo_dte");
                v.folio      = rsV.getInt("dte_folio");
                v.trackId    = rsV.getString("dte_track_id");
                v.dteEstado  = rsV.getString("dte_estado");
                try (PreparedStatement ps = conn.prepareStatement(sqlD)) {
                    ps.setInt(1, v.id);
                    ResultSet rsD = ps.executeQuery();
                    while (rsD.next()) {
                        VentaDTE.ItemDTE item = new VentaDTE.ItemDTE();
                        item.nombre         = rsD.getString("producto_nombre");
                        item.cantidad       = rsD.getInt("cantidad");
                        item.precioUnitario = Math.round(rsD.getDouble("precio_unitario"));
                        item.montoItem      = Math.round(rsD.getDouble("subtotal"));
                        v.items.add(item);
                    }
                }
                lista.add(v);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return lista;
    }

    /** Descuenta el stock local de todos los productos de una venta */
    public void descontarStockPorVenta(int ventaId) {
        String sqlItems  = "SELECT producto_id, cantidad FROM detalles_venta WHERE venta_id=? AND producto_id IS NOT NULL AND producto_id > 0";
        String sqlUpdate = "UPDATE inventario SET stock_actual = MAX(0, stock_actual - ?) WHERE producto_id = ?";
        try (PreparedStatement psQ = conn.prepareStatement(sqlItems)) {
            psQ.setInt(1, ventaId);
            ResultSet rs = psQ.executeQuery();
            while (rs.next()) {
                int pid = rs.getInt("producto_id");
                int qty = rs.getInt("cantidad");
                try (PreparedStatement psU = conn.prepareStatement(sqlUpdate)) {
                    psU.setInt(1, qty);
                    psU.setInt(2, pid);
                    psU.executeUpdate();
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /** Actualiza el estado DTE de una venta */
    public void marcarVentaDTE(int ventaId, String estado, int folio, String trackId) {
        String sql = "UPDATE ventas SET dte_estado=?, dte_folio=?, dte_track_id=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, estado);
            if (folio > 0) ps.setInt(2, folio); else ps.setNull(2, Types.INTEGER);
            ps.setString(3, trackId);
            ps.setInt(4, ventaId);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }
}
