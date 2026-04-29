package com.sospos.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sospos.model.UserSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Conecta con Supabase: autenticación + sync de datos vía REST API.
 */
public class SupabaseService {

    private static final String BASE_URL = "https://miuirpcrkfnfngvplhqp.supabase.co";
    private static final String ANON_KEY  = "sb_publishable_i4Gr8f8Pc-0XyCsRt60kFA_3b8K2v5r";

    private static SupabaseService instance;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean online = false;

    private SupabaseService() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static SupabaseService getInstance() {
        if (instance == null) instance = new SupabaseService();
        return instance;
    }

    // ─── Autenticación ────────────────────────────────────────────────────────

    /**
     * Inicia sesión con email/contraseña usando Supabase Auth.
     * Si tiene éxito, rellena la UserSession y retorna LoginResult.ok = true.
     */
    public LoginResult login(String email, String password) {
        LoginResult result = new LoginResult();
        try {
            // Escapar comillas en email y password por seguridad básica
            String safeEmail    = email.replace("\"", "");
            String safePassword = password.replace("\"", "");
            String body = "{\"email\":\"" + safeEmail + "\",\"password\":\"" + safePassword + "\"}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/auth/v1/token?grant_type=password"))
                    .header("apikey", ANON_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());

            if (resp.statusCode() == 200) {
                result.accessToken  = json.path("access_token").asText();
                result.refreshToken = json.path("refresh_token").asText();
                result.userId       = json.path("user").path("id").asText();
                result.email        = json.path("user").path("email").asText();

                // Obtener nombre del negocio desde profiles
                result.nombreNegocio = fetchNombreNegocio(result.userId, result.accessToken);

                // Guardar en sesión en memoria y persistir token en SQLite
                UserSession.getInstance().setFrom(
                        result.userId, result.accessToken, result.email, result.nombreNegocio);

                online = true;
                result.ok = true;
            } else {
                // Supabase retorna error en "error_description" o "msg"
                String msg = json.path("error_description").asText(null);
                if (msg == null || msg.isBlank()) msg = json.path("msg").asText(null);
                if (msg == null || msg.isBlank()) msg = "Correo o contraseña incorrectos.";
                result.error = msg;
            }
        } catch (Exception e) {
            result.error = "Sin conexión a internet.";
        }
        return result;
    }

    /** Obtiene nombre_negocio desde profiles usando el access token del usuario */
    private String fetchNombreNegocio(String userId, String accessToken) {
        try {
            String endpoint = "/rest/v1/profiles?select=nombre_negocio,nombre_empresa&id=eq." + userId;
            JsonNode arr = fetchJson(endpoint, accessToken);
            if (arr.isArray() && arr.size() > 0) {
                JsonNode p = arr.get(0);
                String nombre = p.path("nombre_negocio").asText(null);
                if (nombre == null || nombre.isBlank())
                    nombre = p.path("nombre_empresa").asText(null);
                return nombre;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // ─── Conectividad ─────────────────────────────────────────────────────────

    /** Ping rápido a Supabase. Retorna true si el servidor responde (cualquier 2xx/4xx = alcanzable). */
    public boolean testConexion() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/rest/v1/"))
                    .header("apikey", ANON_KEY)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            online = resp.statusCode() < 500;
        } catch (Exception e) {
            online = false;
        }
        return online;
    }

    public boolean isOnline() { return online; }

    // ─── Sync Supabase → SQLite ───────────────────────────────────────────────

    /**
     * Descarga productos y precios del usuario autenticado desde Supabase
     * y los guarda/actualiza en la base de datos local SQLite.
     */
    public SyncResult syncFromSupabase(DatabaseService db) {
        // Renovar token proactivamente antes de cualquier request
        ensureFreshToken(db);

        UserSession session = UserSession.getInstance();
        String userId = session.getUserId();
        String token  = session.getAccessToken() != null ? session.getAccessToken() : ANON_KEY;

        SyncResult result = new SyncResult();
        try {
            if (userId == null || userId.isBlank()) {
                result.error = "No hay sesión activa.";
                return result;
            }

            // Productos del usuario (filtrado por user_id)
            JsonNode prods = fetchJson(
                    "/rest/v1/productos?select=*&user_id=eq." + userId + "&activo=eq.true&order=id",
                    token);
            result.productos = db.upsertProductos(prods);

            // Precios del usuario
            JsonNode precios = fetchJson(
                    "/rest/v1/precios?select=*&user_id=eq." + userId + "&activo=eq.true&order=id",
                    token);
            result.precios = db.upsertPrecios(precios);

            // Categorías (puede que RLS las bloquee — no falla si retorna [])
            try {
                JsonNode cats = fetchJson(
                        "/rest/v1/categorias?select=*&user_id=eq." + userId,
                        token);
                result.categorias = db.upsertCategorias(cats);
            } catch (Exception ignored) {}

            // Cajas del usuario (tabla "cajas" con join a bodegas)
            try {
                JsonNode cajas = fetchJson(
                        "/rest/v1/cajas?select=*,bodegas(nombre)&user_id=eq." + userId,
                        token);
                result.cajas = db.upsertCajasRegistro(cajas);
            } catch (Exception ignored) {}

            // Perfil del negocio (RUT, giro, logo, email, dirección casa matriz)
            try {
                JsonNode profile = fetchJson(
                        "/rest/v1/profiles?select=*&id=eq." + userId + "&limit=1", token);
                if (profile.isArray() && profile.size() > 0) {
                    JsonNode p = profile.get(0);
                    String razonSocial = p.path("nombre_empresa").asText(null);
                    if (razonSocial == null || razonSocial.isBlank())
                        razonSocial = p.path("nombre_negocio").asText(null);
                    db.setConfigValue("negocio_rut",          p.path("rut").asText(null));
                    db.setConfigValue("negocio_razon_social", razonSocial);
                    db.setConfigValue("negocio_giro",         p.path("giro").asText(null));
                    db.setConfigValue("negocio_telefono",     p.path("telefono").asText(null));
                    db.setConfigValue("negocio_logo_url",     p.path("logo_url").asText(null));
                    db.setConfigValue("negocio_email",        p.path("email").asText(null));
                    // profiles.direccion = casa matriz (default)
                    String dirProfile = p.path("direccion").asText(null);
                    if (dirProfile != null && !dirProfile.isBlank())
                        db.setConfigValue("negocio_casa_matriz", dirProfile);
                }
            } catch (Exception ignored) {}

            // Sucursal casa matriz (override si está flagueada en tabla sucursales)
            try {
                JsonNode sucursales = fetchJson(
                        "/rest/v1/sucursales?select=direccion,es_casa_matriz&user_id=eq."
                        + userId + "&es_casa_matriz=eq.true&limit=1", token);
                if (sucursales.isArray() && sucursales.size() > 0) {
                    String dirCM = sucursales.get(0).path("direccion").asText(null);
                    if (dirCM != null && !dirCM.isBlank())
                        db.setConfigValue("negocio_casa_matriz", dirCM);
                }
            } catch (Exception ignored) {}

            // Configuración SII (sucursal del DTE, comuna, ciudad, email facturación)
            try {
                JsonNode siiCfg = fetchJson(
                        "/rest/v1/sii_configuracion?select=*&user_id=eq." + userId + "&limit=1", token);
                if (siiCfg.isArray() && siiCfg.size() > 0) {
                    JsonNode s = siiCfg.get(0);
                    String dir = s.path("sucursal_direccion").asText(null);
                    String comuna = s.path("comuna").asText(null);
                    String ciudad = s.path("ciudad").asText(null);
                    StringBuilder suc = new StringBuilder();
                    if (dir != null && !dir.isBlank()) suc.append(dir);
                    if (comuna != null && !comuna.isBlank()) {
                        if (suc.length() > 0) suc.append(", ");
                        suc.append(comuna);
                    }
                    if (ciudad != null && !ciudad.isBlank() && !ciudad.equals(comuna)) {
                        if (suc.length() > 0) suc.append(", ");
                        suc.append(ciudad);
                    }
                    String sucursal = suc.toString();
                    if (!sucursal.isBlank()) {
                        db.setConfigValue("negocio_sucursal", sucursal);
                        // Mantener negocio_direccion para retrocompatibilidad
                        db.setConfigValue("negocio_direccion", sucursal);
                    }
                    String emailFact = s.path("email").asText(null);
                    if (emailFact != null && !emailFact.isBlank())
                        db.setConfigValue("negocio_email", emailFact);
                }
            } catch (Exception ignored) {}

            online = true;
            result.ok = true;
        } catch (Exception e) {
            online = false;
            result.error = e.getMessage();
            if (e.getMessage() != null && e.getMessage().contains("HTTP 401"))
                result.sessionExpired = true;
            e.printStackTrace();
        }
        return result;
    }

    // ─── HTTP helpers ──────────────────────────────────────────────────────────

    private JsonNode fetchJson(String endpoint, String token) throws Exception {
        HttpRequest req = buildRequest(endpoint, token);
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " → " + endpoint);
        }
        return mapper.readTree(resp.body());
    }

    private HttpRequest buildRequest(String endpoint, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .header("apikey", ANON_KEY)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
    }

    // ─── DTE: Generar boleta electrónica ─────────────────────────────────────

    /**
     * Llama al servidor Proyecto-main para generar la boleta electrónica.
     * Si el servidor no responde, retorna ok=false y la venta queda pendiente.
     */
    /** Sobrecarga para uso simple (boleta consumidor final) */
    public DTEResult generarDTE(DatabaseService db, int ventaId, double total,
                                java.util.List<com.sospos.model.ItemCarrito> items) {
        return generarDTE(db, ventaId, total, items, 39, "66666666-6", "Cliente Estimado", "");
    }

    public DTEResult generarDTE(DatabaseService db, int ventaId, double total,
                                java.util.List<com.sospos.model.ItemCarrito> items,
                                int tipoDTE, String receptorRut, String receptorNombre,
                                String receptorDireccion) {
        DTEResult res = new DTEResult();
        try {
            // Construir JSON para /api/sos-boleta
            // SOS almacena precios brutos (con IVA). El servidor espera precios NETO y aplica IVA.
            // Dividimos por 1.19 para convertir bruto → neto antes de enviar.
            double ivaFactor = (tipoDTE == 41) ? 1.0 : 1.19; // TD 41 = boleta exenta, sin IVA
            StringBuilder itemsJson = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                com.sospos.model.ItemCarrito item = items.get(i);
                long precioUnit = Math.round(item.getProducto().getPrecio() / ivaFactor);
                long montoItem  = Math.round(item.getSubtotal() / ivaFactor);
                if (i > 0) itemsJson.append(",");
                itemsJson.append("{")
                    .append("\"nombre\":\"").append(escaparJson(item.getProducto().getNombre())).append("\",")
                    .append("\"cantidad\":").append(item.getCantidad()).append(",")
                    .append("\"precio\":").append(precioUnit).append(",")
                    .append("\"monto\":").append(montoItem)
                    .append("}");
            }
            itemsJson.append("]");

            // Receptor
            String rutSinPuntos = receptorRut.replace(".", "");
            String receptorJson = "{\"rutRecep\":\"" + escaparJson(rutSinPuntos) + "\","
                    + "\"rznSocRecep\":\"" + escaparJson(receptorNombre) + "\""
                    + (receptorDireccion != null && !receptorDireccion.isBlank()
                        ? ",\"dirRecep\":\"" + escaparJson(receptorDireccion) + "\""
                        : "")
                    + "}";

            // Emisor desde config SQLite (sincronizado de Supabase profile)
            // NOTA: rutEmisor NO se sobreescribe — debe coincidir con el CAF en sos-config.json
            String razonSocial = db.getConfig("negocio_razon_social");
            String giro        = db.getConfig("negocio_giro");
            String direccion   = db.getConfig("negocio_direccion");
            StringBuilder emisorOverride = new StringBuilder("{");
            if (razonSocial != null && !razonSocial.isBlank())
                emisorOverride.append("\"rznSoc\":\"").append(escaparJson(razonSocial)).append("\",");
            if (giro != null && !giro.isBlank())
                emisorOverride.append("\"giroEmis\":\"").append(escaparJson(giro)).append("\",");
            if (direccion != null && !direccion.isBlank())
                emisorOverride.append("\"dirOrigen\":\"").append(escaparJson(direccion)).append("\",");
            // Quitar última coma si existe
            String emisorJson = emisorOverride.toString().replaceAll(",\\s*$", "") + "}";

            String currentUserId = com.sospos.model.UserSession.getInstance().getUserId();
            String body = "{"
                + "\"user_id\":\"" + (currentUserId != null ? currentUserId : "") + "\","
                + "\"tipoDTE\":" + tipoDTE + ","
                + "\"receptor\":" + receptorJson + ","
                + "\"items\":" + itemsJson + ","
                + "\"emisorOverride\":" + emisorJson + ","
                + "\"enviar\":true"
                + "}";

            String servidorUrl = db.getConfig("servidor_dte_url");
            if (servidorUrl == null || servidorUrl.isBlank()) servidorUrl = "https://sii-pudu-server-527116133865.us-central1.run.app";
            String servidorToken = db.getConfig("servidor_dte_token");
            if (servidorToken == null || servidorToken.isBlank()) servidorToken = "b6e0f7a0a3f7f2f0c7c3f7a0e6f3d5b9a5c2e3f7a9d8c1b0f6e2d7c4b3a1f0e9";

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(servidorUrl + "/api/sos-boleta"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30));
            if (servidorToken != null && !servidorToken.isBlank())
                reqBuilder.header("Authorization", "Bearer " + servidorToken);
            HttpRequest req = reqBuilder.build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());

            if (resp.statusCode() == 200 && json.path("success").asBoolean(false)) {
                res.ok         = true;
                res.folio      = json.path("folio").asInt(0);
                res.trackId    = json.path("trackId").asText(null);
                res.pdfBase64  = json.path("pdf_base64").asText(null);
                if ("null".equals(res.pdfBase64)) res.pdfBase64 = null;
                db.marcarVentaDTE(ventaId, "emitido", res.folio, res.trackId);
            } else {
                res.error = json.path("error").asText("Error del servidor DTE");
                // No marcar "error" — queda como "pendiente" para reintentar
            }
        } catch (Exception e) {
            res.error = e.getMessage();
            // Servidor no disponible — queda pendiente para reintentar
        }
        return res;
    }

    /** Procesa todas las ventas con DTE pendiente. Retorna el número de boletas emitidas. */
    public int procesarColaDTE(DatabaseService db) {
        java.util.List<DatabaseService.VentaDTE> pendientes = db.getVentasPendientesDTE();
        int emitidos = 0;
        for (DatabaseService.VentaDTE v : pendientes) {
            java.util.List<com.sospos.model.ItemCarrito> items = new java.util.ArrayList<>();
            for (DatabaseService.VentaDTE.ItemDTE itemDTE : v.items) {
                com.sospos.model.Producto prod = new com.sospos.model.Producto(
                        0, itemDTE.nombre, itemDTE.precioUnitario, itemDTE.cantidad, "");
                com.sospos.model.ItemCarrito ic = new com.sospos.model.ItemCarrito(prod);
                ic.setCantidad(itemDTE.cantidad);
                items.add(ic);
            }
            DTEResult res = generarDTE(db, v.id, v.total, items);
            if (res.ok) {
                emitidos++;
                actualizarVentaDTE(db, v.id, res);
            }
        }
        return emitidos;
    }

    /**
     * Crea la venta en Supabase para que aparezca en POS-Matic.
     * Se llama en background inmediatamente después de procesar el pago (sin DTE todavía).
     * Guarda el UUID retornado en SQLite para poder hacer PATCH cuando llegue el DTE.
     * Retorna el UUID de la venta creada, o null si falla.
     */
    public String sincronizarVentaASupabase(DatabaseService db, int ventaId,
                                             java.util.List<com.sospos.model.ItemCarrito> items,
                                             String metodoPago, double total, double vuelto,
                                             int tipoDTE) {
        try {
            String token  = UserSession.getInstance().getAccessToken();
            String userId = UserSession.getInstance().getUserId();
            System.err.println("[SOS-sync] token=" + (token != null ? token.substring(0,20)+"..." : "NULL") + " userId=" + userId);
            if (token == null || userId == null) { System.err.println("[SOS-sync] Abortando: token o userId nulo"); return null; }

            long mntTotal = Math.round(total);
            long mntNeto  = Math.round(total / 1.19);
            long impuesto = mntTotal - mntNeto;
            String tipoDteStr = tipoDTE == 33 ? "factura" : "boleta";

            // ── 1. Insertar venta (sin DTE aún — se actualiza en actualizarVentaDTE) ──
            StringBuilder venta = new StringBuilder("{");
            venta.append("\"user_id\":\"").append(userId).append("\",");
            venta.append("\"total\":").append(mntTotal).append(",");
            venta.append("\"subtotal\":").append(mntNeto).append(",");
            venta.append("\"impuesto\":").append(impuesto).append(",");
            venta.append("\"descuento\":0,");
            venta.append("\"metodo_pago\":\"").append(escaparJson(metodoPago)).append("\",");
            venta.append("\"estado\":\"completada\",");
            venta.append("\"tipo_dte\":\"").append(tipoDteStr).append("\",");
            venta.append("\"estado_sii\":\"pendiente\"");
            if ("efectivo".equals(metodoPago)) {
                venta.append(",\"monto_recibido\":").append(Math.round(total + vuelto));
                venta.append(",\"vuelto\":").append(Math.round(vuelto));
            }
            venta.append("}");

            HttpRequest reqVenta = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/rest/v1/ventas"))
                    .header("apikey", ANON_KEY)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=representation")
                    .POST(HttpRequest.BodyPublishers.ofString(venta.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> respVenta = http.send(reqVenta, HttpResponse.BodyHandlers.ofString());
            if (respVenta.statusCode() != 201) {
                System.err.println("[SOS] Sync falló. Status: " + respVenta.statusCode() + " | Body: " + respVenta.body());
                return null;
            }

            JsonNode ventaJson = mapper.readTree(respVenta.body());
            String ventaUuid = ventaJson.isArray() ? ventaJson.get(0).path("id").asText(null)
                                                   : ventaJson.path("id").asText(null);
            if (ventaUuid == null) return null;

            // Guardar UUID en SQLite para poder hacer PATCH cuando el DTE sea emitido
            if (db != null) db.setSupabaseVentaId(ventaId, ventaUuid);

            // ── 2. Insertar detalles ──
            for (com.sospos.model.ItemCarrito item : items) {
                int sbProdId = item.getProducto().getSupabaseProductId();
                if (sbProdId <= 0) continue; // sin ID Supabase, omitir

                StringBuilder det = new StringBuilder("{");
                det.append("\"venta_id\":\"").append(ventaUuid).append("\",");
                det.append("\"producto_id\":").append(sbProdId).append(",");
                det.append("\"producto_nombre\":\"").append(escaparJson(item.getProducto().getNombre())).append("\",");
                det.append("\"cantidad\":").append(item.getCantidad()).append(",");
                det.append("\"precio_unitario\":").append(Math.round(item.getProducto().getPrecio())).append(",");
                det.append("\"subtotal\":").append(Math.round(item.getSubtotal())).append(",");
                det.append("\"descuento_producto\":0");
                det.append("}");

                HttpRequest reqDet = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/rest/v1/detalles_venta"))
                        .header("apikey", ANON_KEY)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(det.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();
                http.send(reqDet, HttpResponse.BodyHandlers.ofString());
            }
            return ventaUuid;
        } catch (Exception e) {
            System.err.println("[SOS] Error sync venta Supabase: " + e.getMessage());
            return null;
        }
    }

    /**
     * Hace PATCH en Supabase para actualizar el folio, trackId, PDF y estado_sii
     * de una venta que ya fue sincronizada previamente.
     */
    public void actualizarVentaDTE(DatabaseService db, int ventaId, DTEResult dteResult) {
        if (dteResult == null || !dteResult.ok) return;
        String uuid = db.getSupabaseVentaId(ventaId);
        if (uuid == null || uuid.isBlank()) {
            System.err.println("[SOS] actualizarVentaDTE: sin UUID para ventaId=" + ventaId);
            return;
        }
        try {
            String token = UserSession.getInstance().getAccessToken();
            if (token == null) return;

            StringBuilder patch = new StringBuilder("{");
            patch.append("\"folio_dte\":").append(dteResult.folio).append(",");
            patch.append("\"estado_sii\":\"enviada\",");
            if (dteResult.trackId != null)
                patch.append("\"trackid_sii\":\"").append(escaparJson(dteResult.trackId)).append("\",")
                     .append("\"dte_track_id\":\"").append(escaparJson(dteResult.trackId)).append("\",");
            if (dteResult.pdfBase64 != null && !dteResult.pdfBase64.isBlank())
                patch.append("\"dte_pdf_base64\":\"").append(dteResult.pdfBase64).append("\",");
            // Quitar coma final
            String body = patch.toString().replaceAll(",\\s*$", "") + "}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/rest/v1/ventas?id=eq." + uuid))
                    .header("apikey", ANON_KEY)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=minimal")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                System.err.println("[SOS] PATCH DTE falló. Status: " + resp.statusCode() + " | " + resp.body());
            else
                System.err.println("[SOS] PATCH DTE OK: venta=" + ventaId + " folio=" + dteResult.folio);
        } catch (Exception e) {
            System.err.println("[SOS] Error PATCH DTE: " + e.getMessage());
        }
    }

    private String escaparJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ─── Contingencia: sync de notas offline → Supabase ──────────────────────

    /**
     * Sube todas las contingencias pendientes a la tabla contingencia_ventas.
     * Idempotente: usa Prefer: resolution=ignore-duplicates (ON CONFLICT DO NOTHING).
     * El POS Matic web (dueño del negocio) las convierte luego en DTE.
     */
    public void sincronizarContingencias(DatabaseService db) {
        if (!testConexion()) return;
        if (!UserSession.getInstance().isLoggedIn()) return;

        // Renovar access_token antes de sincronizar para evitar 401 por expiración
        ensureFreshToken(db);

        java.util.List<java.util.Map<String, Object>> pendientes = db.getContingenciasPendientes();
        if (pendientes.isEmpty()) return;

        System.out.println("[SYNC] Sincronizando " + pendientes.size() + " contingencias pendientes...");
        String deviceId = db.getDeviceId();
        String userId   = UserSession.getInstance().getUserId();
        String token    = UserSession.getInstance().getAccessToken();
        if (token == null || userId == null) return;

        int batch = 0;
        for (java.util.Map<String, Object> c : pendientes) {
            if (batch++ >= 50) break; // máx 50 por ciclo
            String idUuid = (String) c.get("id_uuid");
            try {
                StringBuilder b = new StringBuilder("{");
                b.append("\"id\":\"").append(idUuid).append("\",");
                b.append("\"user_id\":\"").append(userId).append("\",");
                b.append("\"sos_device_id\":\"").append(escaparJson(deviceId)).append("\",");
                b.append("\"numero_nota\":\"").append(escaparJson((String) c.get("numero_nota"))).append("\",");
                b.append("\"fecha_venta\":\"").append(c.get("fecha_venta")).append("\",");
                b.append("\"items\":").append(c.get("items_json")).append(",");
                b.append("\"monto_neto\":").append(c.get("monto_neto")).append(",");
                b.append("\"monto_iva\":").append(c.get("monto_iva")).append(",");
                b.append("\"monto_total\":").append(c.get("monto_total")).append(",");
                b.append("\"rut_receptor\":").append(jsonStringOrNull((String) c.get("rut_receptor"))).append(",");
                b.append("\"razon_social_receptor\":").append(jsonStringOrNull((String) c.get("razon_social"))).append(",");
                b.append("\"direccion_receptor\":").append(jsonStringOrNull((String) c.get("direccion"))).append(",");
                b.append("\"giro_receptor\":").append(jsonStringOrNull((String) c.get("giro"))).append(",");
                b.append("\"correo_receptor\":").append(jsonStringOrNull((String) c.get("correo"))).append(",");
                b.append("\"sincronizado_at\":\"").append(java.time.Instant.now().toString()).append("\"");
                b.append("}");

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/rest/v1/contingencia_ventas"))
                    .header("apikey",        ANON_KEY)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type",  "application/json")
                    .header("Prefer",        "resolution=ignore-duplicates")
                    .POST(HttpRequest.BodyPublishers.ofString(b.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();

                if (code == 201 || code == 200 || code == 204) {
                    db.marcarContingenciaSincronizada(idUuid);
                    System.out.println("[SYNC] OK: " + c.get("numero_nota"));
                } else if (code == 401 || code == 403) {
                    // Token expirado: abortar el ciclo, próximo login lo refresca
                    System.err.println("[SYNC] Token rechazado (HTTP " + code + ") — sync abortado");
                    return;
                } else {
                    db.marcarContingenciaError(idUuid, "HTTP " + code + ": " + resp.body());
                    System.err.println("[SYNC] Error " + code + " en " + idUuid + ": " + resp.body());
                }

                Thread.sleep(200);
            } catch (Exception e) {
                db.marcarContingenciaError(idUuid, e.getMessage());
                System.err.println("[SYNC] Excepción en " + idUuid + ": " + e.getMessage());
            }
        }
        System.out.println("[SYNC] Ciclo completado.");
    }

    private String jsonStringOrNull(String val) {
        if (val == null || val.trim().isEmpty()) return "null";
        return "\"" + escaparJson(val) + "\"";
    }

    // ─── Refresh token ────────────────────────────────────────────────────────

    /**
     * Intercambia un refresh_token por nuevos access_token + refresh_token.
     * Llama esto al iniciar la app para renovar un token guardado expirado.
     */
    public LoginResult refreshSession(String refreshToken) {
        LoginResult result = new LoginResult();
        try {
            String body = "{\"refresh_token\":\"" + refreshToken + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/auth/v1/token?grant_type=refresh_token"))
                    .header("apikey", ANON_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            if (resp.statusCode() == 200) {
                result.accessToken  = json.path("access_token").asText();
                result.refreshToken = json.path("refresh_token").asText();
                result.userId       = json.path("user").path("id").asText();
                result.email        = json.path("user").path("email").asText();
                result.ok = true;
                online = true;
            } else {
                result.error = "refresh_token inválido o expirado";
            }
        } catch (Exception e) {
            result.error = e.getMessage();
        }
        return result;
    }

    /**
     * Intenta renovar el access_token usando el refresh_token guardado en SQLite.
     * No falla si el refresh no está disponible o el servidor no responde.
     */
    private void ensureFreshToken(DatabaseService db) {
        try {
            String rt = db.getConfig("refresh_token");
            if (rt == null || rt.isBlank()) return;
            LoginResult r = refreshSession(rt);
            if (r.ok) {
                db.saveSession(r.userId, r.email,
                        UserSession.getInstance().getNombreNegocio(), r.accessToken);
                db.setConfigValue("refresh_token", r.refreshToken);
                UserSession.getInstance().setFrom(r.userId, r.accessToken, r.email,
                        UserSession.getInstance().getNombreNegocio());
            }
        } catch (Exception ignored) {}
    }

    // ─── Clases de resultado ──────────────────────────────────────────────────

    public static class LoginResult {
        public boolean ok = false;
        public String userId, accessToken, refreshToken, email, nombreNegocio, error;
    }

    public static class SyncResult {
        public boolean ok = false;
        public boolean sessionExpired = false;
        public int categorias, productos, precios, cajas, dtesEmitidos;
        public String error;

        public String resumen() {
            if (!ok) return "Error: " + error;
            String r = productos + " productos, " + precios + " precios, " + cajas + " cajas sincronizados.";
            if (dtesEmitidos > 0) r += "\n" + dtesEmitidos + " boleta(s) emitida(s) al SII.";
            return r;
        }
    }

    public static class DTEResult {
        public boolean ok = false;
        public int folio;
        public String trackId;
        public String pdfBase64;
        public String error;
    }
}
