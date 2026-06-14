# SOS POS — Prompt de Implementación para Colaborador

> **Instrucciones de uso:** Copia este archivo completo y úsalo como contexto de trabajo.
> Para cada sección marcada con **[VERIFICAR PRIMERO]**: revisa si ya está implementado,
> prueba que funcione, y si está OK continúa con lo siguiente. Solo implementa lo que falte.

---

## Contexto del proyecto

**SOS POS** es un sistema de punto de venta de escritorio (Java/JavaFX) para negocios chilenos.
Tiene dos componentes:

- **App Java** (`/SOS pos/SOS pos/`) — interfaz de escritorio con SQLite local
- **Microservicio Node.js** (`/SOS pos/SOS pos/Proyecto-main/`) — generación y firma de DTEs

La app se conecta a **Supabase** (PostgreSQL en la nube) que es el backend compartido con
**POS Matic** (app web del mismo negocio).

**El objetivo de este trabajo:** cuando el usuario no tenga internet, el SOS guarda las ventas
localmente como "notas de contingencia". Cuando vuelve la conexión, las sube automáticamente
a la tabla `contingencia_ventas` en Supabase. Desde POS Matic (app web), el dueño del negocio
las convierte en DTE (boleta/factura) y las envía al SII.

**El SOS NUNCA emite DTE durante contingencia. Solo guarda y sincroniza.**

---

## Credenciales y configuración

```
Supabase URL:      https://miuirpcrkfnfngvplhqp.supabase.co
Supabase Anon Key: sb_publishable_i4Gr8f8Pc-0XyCsRt60kFA_3b8K2v5r
```

Están en `/Proyecto-main/.env`. La app Java las lee desde `DatabaseService` o hardcodeadas
en `SupabaseService` — busca `miuirpcrkfnfngvplhqp` en el código para encontrarlas.

---

## Arquitectura de archivos existente

```
SOS pos/SOS pos/src/main/java/com/sospos/
├── App.java                    — launcher, maneja navegación entre pantallas
├── Launcher.java               — entry point
├── LoginController.java        — UI de login (⚠️ INCOMPLETO — ver Tarea 1)
├── PosController.java          — pantalla principal de ventas (796 líneas)
├── DashboardController.java    — dashboard de ventas
├── InventarioController.java   — gestión de inventario
├── CajasController.java        — caja registradora
├── HomeController.java         — menú principal
├── db/
│   ├── DatabaseService.java    — SQLite local (740 líneas)
│   └── SupabaseService.java    — sincronización con Supabase (475 líneas)
└── model/
    ├── UserSession.java        — singleton de sesión del usuario autenticado
    ├── Producto.java           — modelo simple de producto
    ├── ItemCarrito.java        — ítem del carrito
    └── ProductoDetalle.java    — producto detallado con propiedades JavaFX
```

---

## Estado actual del código relevante

### LoginController.java (INCOMPLETO)
```java
@FXML
void onLogin() {
    String email = emailField.getText().trim();
    String password = passwordField.getText();

    if (email.isEmpty() || password.isEmpty()) {
        errorLabel.setText("Por favor ingresa tu email y contraseña.");
        errorLabel.setVisible(true);
        return;
    }

    // TODO: validar contra Supabase o SQLite local
    // Por ahora cualquier credencial pasa al POS
    try {
        App.showPOS();
    } catch (Exception e) { ... }
}
```
**Problema:** El login no valida nada. Hay que conectarlo a `SupabaseService.login()`.

### UserSession.java (COMPLETO)
```java
// Singleton — ya funciona, no tocar
UserSession.getInstance().setFrom(userId, accessToken, email, nombreNegocio);
UserSession.getInstance().getUserId();    // UUID del usuario
UserSession.getInstance().getAccessToken(); // JWT para Supabase
```

### SupabaseService.java — método login() (REVISAR)
Busca el método `login(String email, String password)`. Debe hacer:
```
POST https://miuirpcrkfnfngvplhqp.supabase.co/auth/v1/token?grant_type=password
Headers: apikey: <anon_key>, Content-Type: application/json
Body: {"email":"...","password":"..."}
```
Respuesta: `{ "access_token": "...", "user": { "id": "uuid" } }`

### DatabaseService.java — tablas SQLite existentes
Las tablas ya creadas en `crearTablas()`:
- `config` — key/value (guarda `deviceId`, `userEmail`, etc.)
- `ventas` — ventas completadas (con `dte_estado`, `dte_folio`, `dte_track_id`)
- `detalles_venta` — ítems de cada venta
- `sync_queue` — cola de sync pendiente
- `productos`, `categorias`, `precios`, `inventario`, `cajas_registro`

**Falta:** tabla `contingencia_ventas` para modo offline.

---

## TAREAS A IMPLEMENTAR

---

### TAREA 1 — Completar Login con Supabase

**[VERIFICAR PRIMERO]** Abre la app y prueba loguearte con credenciales reales de POS Matic.
Si accede correctamente al POS Y `UserSession.getInstance().getUserId()` tiene un UUID válido
(no null), esta tarea está lista. Continúa a Tarea 2.

**Si falta implementar:**

Archivo: `LoginController.java`

Reemplaza el método `onLogin()` completo:

```java
@FXML
void onLogin() {
    String email    = emailField.getText().trim();
    String password = passwordField.getText();

    if (email.isEmpty() || password.isEmpty()) {
        errorLabel.setText("Por favor ingresa tu email y contraseña.");
        errorLabel.setVisible(true);
        return;
    }

    errorLabel.setVisible(false);
    // Deshabilitar botón para evitar doble click
    loginButton.setDisable(true);

    // Login en hilo aparte para no bloquear UI
    new Thread(() -> {
        try {
            SupabaseService svc = SupabaseService.getInstance();
            boolean ok = svc.login(email, password);  // llena UserSession internamente

            javafx.application.Platform.runLater(() -> {
                loginButton.setDisable(false);
                if (ok && UserSession.getInstance().isLoggedIn()) {
                    // Guardar email para próximo inicio (opcional)
                    DatabaseService.getInstance().setConfig("lastEmail", email);
                    try {
                        App.showHome();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    errorLabel.setText("Credenciales incorrectas. Intenta de nuevo.");
                    errorLabel.setVisible(true);
                }
            });
        } catch (Exception e) {
            javafx.application.Platform.runLater(() -> {
                loginButton.setDisable(false);
                errorLabel.setText("Sin conexión. Verifica tu internet.");
                errorLabel.setVisible(true);
            });
        }
    }).start();
}
```

Asegúrate de que `SupabaseService.login()` llame a `UserSession.getInstance().setFrom(...)`.
Si el método login no existe o no lo hace, impleméntalo así:

```java
// En SupabaseService.java
public boolean login(String email, String password) {
    try {
        String url = SUPABASE_URL + "/auth/v1/token?grant_type=password";
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JSONObject json = new JSONObject(response.body());
            String accessToken = json.getString("access_token");
            String userId      = json.getJSONObject("user").getString("id");
            String userEmail   = json.getJSONObject("user").getString("email");

            // Guardar sesión
            UserSession.getInstance().setFrom(userId, accessToken, userEmail, "");
            // Persistir token para modo offline
            DatabaseService.getInstance().setConfig("accessToken",  accessToken);
            DatabaseService.getInstance().setConfig("userId",       userId);
            DatabaseService.getInstance().setConfig("userEmail",    userEmail);
            return true;
        }
        return false;
    } catch (Exception e) {
        e.printStackTrace();
        return false;
    }
}
```

**Prueba de humo Tarea 1:**
1. Inicia la app → pantalla de login
2. Ingresa email/contraseña reales de POS Matic
3. Debe navegar a la pantalla principal
4. En consola/log debe aparecer el UUID del usuario (no null)
5. Con credenciales incorrectas debe mostrar error sin crashear

---

### TAREA 2 — Identificador de dispositivo (Device ID)

**[VERIFICAR PRIMERO]** Busca en `DatabaseService` o `config` table si ya existe un
`deviceId` persistido. Si hay algo como `"CAJA-1"` o un UUID guardado en SQLite config,
esta tarea está lista.

**Si falta implementar:**

Al iniciar la app (en `App.java` o `DatabaseService.init()`), generar un ID único
para este terminal y guardarlo permanentemente:

```java
// En DatabaseService.java — llamar una sola vez al inicializar
public void inicializarDeviceId() {
    String existingId = getConfig("deviceId");
    if (existingId == null || existingId.isEmpty()) {
        // Generar ID legible: hostname + 4 dígitos aleatorios
        String hostname = "CAJA";
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
            if (hostname.length() > 12) hostname = hostname.substring(0, 12);
        } catch (Exception ignored) {}
        String deviceId = hostname.toUpperCase() + "-" + (1000 + new java.util.Random().nextInt(9000));
        setConfig("deviceId", deviceId);
        System.out.println("[SOS] Device ID generado: " + deviceId);
    }
}

public String getDeviceId() {
    String id = getConfig("deviceId");
    return (id != null && !id.isEmpty()) ? id : "SOS-DESCONOCIDO";
}
```

Llamar `DatabaseService.getInstance().inicializarDeviceId()` en `App.start()`.

---

### TAREA 3 — Tabla SQLite para contingencia offline

**[VERIFICAR PRIMERO]** Busca en `DatabaseService.crearTablas()` si existe:
```sql
CREATE TABLE IF NOT EXISTS contingencia_ventas (...)
```
Si existe con los campos `id_uuid`, `fecha_venta`, `items_json`, `estado_sync`, esta tarea está lista.

**Si falta implementar:**

En `DatabaseService.java`, dentro del método `crearTablas()`, agrega esta tabla:

```java
// Tabla para ventas offline pendientes de sincronizar con Supabase
stmt.execute("""
    CREATE TABLE IF NOT EXISTS contingencia_ventas (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        id_uuid         TEXT NOT NULL UNIQUE,     -- UUID v4 generado localmente
        numero_nota     TEXT NOT NULL,             -- ej: CONT-20260425-0001
        fecha_venta     TEXT NOT NULL,             -- UTC ISO 8601: 2026-04-25T14:33:00Z
        items_json      TEXT NOT NULL,             -- JSON array de ítems
        monto_neto      INTEGER NOT NULL DEFAULT 0,
        monto_iva       INTEGER NOT NULL DEFAULT 0,
        monto_total     INTEGER NOT NULL DEFAULT 0,
        rut_receptor    TEXT,
        razon_social    TEXT,
        direccion       TEXT,
        giro            TEXT,
        correo          TEXT,
        estado_sync     TEXT NOT NULL DEFAULT 'pendiente',  -- pendiente | sincronizada | error
        error_msg       TEXT,
        created_at      TEXT DEFAULT (datetime('now'))
    )
""");
```

También agrega estos métodos a `DatabaseService`:

```java
// Guardar una venta de contingencia en SQLite
public void guardarContingencia(String idUuid, String numeroNota, String fechaVentaUtc,
                                 String itemsJson, int montoNeto, int montoIva, int montoTotal,
                                 String rutReceptor, String razonSocial) {
    String sql = """
        INSERT OR IGNORE INTO contingencia_ventas
        (id_uuid, numero_nota, fecha_venta, items_json,
         monto_neto, monto_iva, monto_total, rut_receptor, razon_social)
        VALUES (?,?,?,?,?,?,?,?,?)
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
        ps.executeUpdate();
    } catch (Exception e) { e.printStackTrace(); }
}

// Obtener todas las contingencias pendientes de sincronizar
public java.util.List<java.util.Map<String, Object>> getContingenciasPendientes() {
    java.util.List<java.util.Map<String, Object>> lista = new java.util.ArrayList<>();
    String sql = "SELECT * FROM contingencia_ventas WHERE estado_sync = 'pendiente' ORDER BY id";
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
        while (rs.next()) {
            java.util.Map<String, Object> row = new java.util.HashMap<>();
            row.put("id",            rs.getInt("id"));
            row.put("id_uuid",       rs.getString("id_uuid"));
            row.put("numero_nota",   rs.getString("numero_nota"));
            row.put("fecha_venta",   rs.getString("fecha_venta"));
            row.put("items_json",    rs.getString("items_json"));
            row.put("monto_neto",    rs.getInt("monto_neto"));
            row.put("monto_iva",     rs.getInt("monto_iva"));
            row.put("monto_total",   rs.getInt("monto_total"));
            row.put("rut_receptor",  rs.getString("rut_receptor"));
            row.put("razon_social",  rs.getString("razon_social"));
            lista.add(row);
        }
    } catch (Exception e) { e.printStackTrace(); }
    return lista;
}

// Marcar contingencia como sincronizada
public void marcarContingenciaSincronizada(String idUuid) {
    try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE contingencia_ventas SET estado_sync='sincronizada' WHERE id_uuid=?")) {
        ps.setString(1, idUuid);
        ps.executeUpdate();
    } catch (Exception e) { e.printStackTrace(); }
}

// Marcar contingencia con error
public void marcarContingenciaError(String idUuid, String error) {
    try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE contingencia_ventas SET estado_sync='error', error_msg=? WHERE id_uuid=?")) {
        ps.setString(1, error);
        ps.setString(2, idUuid);
        ps.executeUpdate();
    } catch (Exception e) { e.printStackTrace(); }
}

// Generar número de nota secuencial por día
public String generarNumeroNota() {
    String hoy = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    String key = "notaSeq_" + hoy;
    String actual = getConfig(key);
    int siguiente = (actual == null || actual.isEmpty()) ? 1 : Integer.parseInt(actual) + 1;
    setConfig(key, String.valueOf(siguiente));
    return String.format("CONT-%s-%04d", hoy, siguiente);
}
```

---

### TAREA 4 — Detección de conectividad a internet

**[VERIFICAR PRIMERO]** Busca en `SupabaseService` o en cualquier clase si existe
`isOnline()`, `hayConexion()` o similar. Si hay un método que haga un ping y retorne
boolean, verifica que funcione y continúa.

**Si falta implementar:**

En `SupabaseService.java`, agrega:

```java
// Verifica conexión intentando alcanzar Supabase
public boolean isOnline() {
    try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(SUPABASE_URL + "/rest/v1/"))
            .header("apikey", SUPABASE_ANON_KEY)
            .timeout(java.time.Duration.ofSeconds(4))
            .GET()
            .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() < 500;
    } catch (Exception e) {
        return false;
    }
}
```

---

### TAREA 5 — Guardar venta como contingencia en modo offline

**[VERIFICAR PRIMERO]** En `PosController.java`, busca el método que procesa el
checkout/pago (probablemente `procesarPago()`, `confirmarVenta()` o similar).
¿Tiene algún bloque que verifique `isOnline()` y guarde diferente si no hay internet?
Si ya detecta modo offline y guarda en `contingencia_ventas` SQLite, verifica y continúa.

**Si falta implementar:**

Localiza en `PosController.java` el método que completa la venta. Busca donde se llama a
`supabaseService.generarDTE()` o `SupabaseService.getInstance().sincronizarVenta...`.

Agrega esta lógica ANTES del intento de DTE:

```java
private void procesarVentaFinal(List<ItemCarrito> items, double total,
                                  String metodoPago, String rutReceptor, String razonSocial) {
    SupabaseService svc = SupabaseService.getInstance();
    DatabaseService db  = DatabaseService.getInstance();

    // Calcular montos
    int montoTotal = (int) Math.round(total);
    int montoNeto  = (int) Math.round(total / 1.19);
    int montoIva   = montoTotal - montoNeto;

    // Construir JSON de ítems
    StringBuilder itemsJson = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
        ItemCarrito item = items.get(i);
        if (i > 0) itemsJson.append(",");
        itemsJson.append(String.format(
            "{\"nombre\":\"%s\",\"cantidad\":%d,\"precio_unitario\":%d,\"descuento\":0,\"subtotal\":%d}",
            item.getProducto().getNombre().replace("\"", "'"),
            item.getCantidad(),
            (int) item.getProducto().getPrecio(),
            (int) (item.getProducto().getPrecio() * item.getCantidad())
        ));
    }
    itemsJson.append("]");

    if (!svc.isOnline()) {
        // ── MODO OFFLINE: guardar como contingencia ─────────────────────────
        String idUuid      = java.util.UUID.randomUUID().toString();
        String numeroNota  = db.generarNumeroNota();
        // UTC ISO 8601 — NUNCA truncar a fecha sin hora
        String fechaVenta  = java.time.Instant.now().toString();  // ej: 2026-04-25T14:33:00Z

        db.guardarContingencia(
            idUuid, numeroNota, fechaVenta, itemsJson.toString(),
            montoNeto, montoIva, montoTotal, rutReceptor, razonSocial
        );

        // Imprimir ticket NOTA DE VENTA (no DTE)
        mostrarTicketContingencia(numeroNota, items, montoTotal);

        mostrarMensaje("Venta guardada como Nota de Venta #" + numeroNota +
                       "\nSe convertirá en DTE cuando haya conexión.");
    } else {
        // ── MODO ONLINE: flujo normal DTE ────────────────────────────────────
        // ... código existente de generarDTE / sincronizarVenta ...
    }
}
```

**Ticket de contingencia** — muestra un diálogo simple (o imprime si hay impresora):

```java
private void mostrarTicketContingencia(String numeroNota, List<ItemCarrito> items, int total) {
    StringBuilder ticket = new StringBuilder();
    ticket.append("══════════════════════════\n");
    ticket.append("       NOTA DE VENTA       \n");
    ticket.append("  (Documento no tributario)\n");
    ticket.append("══════════════════════════\n");
    ticket.append("N° ").append(numeroNota).append("\n");
    ticket.append("Fecha: ").append(
        java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Santiago"))
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    ).append("\n\n");
    for (ItemCarrito item : items) {
        ticket.append(String.format("%-18s x%d  $%,.0f\n",
            item.getProducto().getNombre(), item.getCantidad(),
            item.getProducto().getPrecio() * item.getCantidad()));
    }
    ticket.append("──────────────────────────\n");
    ticket.append(String.format("TOTAL:         $%,d\n", total));
    ticket.append("  (IVA incluido)\n\n");
    ticket.append("Su boleta/factura será emitida\n");
    ticket.append("al restaurarse la conexión.\n");
    ticket.append("══════════════════════════\n");

    // Mostrar en diálogo — reemplaza por impresión térmica si corresponde
    javafx.application.Platform.runLater(() -> {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Nota de Venta — Modo Offline");
        alert.setHeaderText(null);
        javafx.scene.control.TextArea ta = new javafx.scene.control.TextArea(ticket.toString());
        ta.setEditable(false);
        ta.setFont(javafx.scene.text.Font.font("Monospaced", 12));
        alert.getDialogPane().setContent(ta);
        alert.showAndWait();
    });
}
```

**Prueba de humo Tarea 5:**
1. Desconecta el cable de red / desactiva WiFi
2. Haz una venta en el POS
3. Debe mostrar el diálogo "Nota de Venta" con número CONT-YYYYMMDD-XXXX
4. Verifica en SQLite: `SELECT * FROM contingencia_ventas;` debe tener un registro con `estado_sync='pendiente'`

---

### TAREA 6 — Sincronización automática con Supabase al reconectarse

Esta es la tarea más importante. Cuando vuelve internet, el SOS sube las contingencias
pendientes a la tabla `contingencia_ventas` en Supabase.

**[VERIFICAR PRIMERO]** Busca en el código si existe un servicio o hilo que se active
al detectar conexión y llame a algún método `sincronizarContingencias()`. Si existe
y funciona (los registros aparecen en Supabase), esta tarea está lista.

**Si falta implementar:**

Agrega en `SupabaseService.java`:

```java
// Sube todas las contingencias pendientes a Supabase
// IDEMPOTENTE: usa INSERT con ON CONFLICT DO NOTHING (header Prefer)
public void sincronizarContingencias() {
    if (!isOnline()) return;
    if (!UserSession.getInstance().isLoggedIn()) return;

    DatabaseService db = DatabaseService.getInstance();
    java.util.List<java.util.Map<String, Object>> pendientes = db.getContingenciasPendientes();

    if (pendientes.isEmpty()) return;

    System.out.println("[SYNC] Sincronizando " + pendientes.size() + " contingencias pendientes...");
    String deviceId = db.getDeviceId();
    String userId   = UserSession.getInstance().getUserId();
    String token    = UserSession.getInstance().getAccessToken();

    for (java.util.Map<String, Object> c : pendientes) {
        String idUuid = (String) c.get("id_uuid");
        try {
            // Construir JSON del body
            String body = String.format("""
                {
                  "id":                    "%s",
                  "user_id":               "%s",
                  "sos_device_id":         "%s",
                  "numero_nota":           "%s",
                  "fecha_venta":           "%s",
                  "items":                 %s,
                  "monto_neto":            %d,
                  "monto_iva":             %d,
                  "monto_total":           %d,
                  "rut_receptor":          %s,
                  "razon_social_receptor": %s
                }
                """,
                idUuid,
                userId,
                deviceId,
                c.get("numero_nota"),
                c.get("fecha_venta"),
                c.get("items_json"),        // ya es JSON array string
                c.get("monto_neto"),
                c.get("monto_iva"),
                c.get("monto_total"),
                jsonStringOrNull((String) c.get("rut_receptor")),
                jsonStringOrNull((String) c.get("razon_social"))
            );

            String url = SUPABASE_URL + "/rest/v1/contingencia_ventas";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("apikey",       SUPABASE_ANON_KEY)
                .header("Authorization","Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Prefer",       "resolution=ignore-duplicates")  // ON CONFLICT DO NOTHING
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 201 || resp.statusCode() == 200) {
                db.marcarContingenciaSincronizada(idUuid);
                System.out.println("[SYNC] OK: " + c.get("numero_nota"));
            } else {
                db.marcarContingenciaError(idUuid, "HTTP " + resp.statusCode() + ": " + resp.body());
                System.err.println("[SYNC] Error " + resp.statusCode() + " para " + idUuid);
            }

            // Pausa breve entre registros para no saturar la API
            Thread.sleep(200);

        } catch (Exception e) {
            db.marcarContingenciaError(idUuid, e.getMessage());
            System.err.println("[SYNC] Excepción para " + idUuid + ": " + e.getMessage());
        }
    }
    System.out.println("[SYNC] Sincronización completada.");
}

// Helper: convierte string a JSON string o "null" si vacío
private String jsonStringOrNull(String val) {
    if (val == null || val.trim().isEmpty()) return "null";
    return "\"" + val.replace("\"", "'") + "\"";
}
```

**Ahora activa la sincronización automática.** En `App.java` o en `HomeController.java`,
inicia un hilo periódico que verifique y sincronice:

```java
// En App.java — dentro de start() DESPUÉS de cargar la pantalla principal
private void iniciarMonitorSync() {
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        .scheduleAtFixedRate(() -> {
            try {
                SupabaseService.getInstance().sincronizarContingencias();
            } catch (Exception e) {
                System.err.println("[SYNC] Error en ciclo de sync: " + e.getMessage());
            }
        }, 10,   // delay inicial: 10 segundos tras arrancar
           30,   // cada 30 segundos verifica
           java.util.concurrent.TimeUnit.SECONDS);
}
```

Llama `iniciarMonitorSync()` en `App.start()` después del login exitoso, o en
`HomeController.initialize()`.

**Prueba de humo Tarea 6:**
1. Genera 2-3 ventas en modo offline (WiFi desactivado) → aparecen en SQLite como `pendiente`
2. Activa WiFi / conecta red
3. Espera máx 30 segundos
4. En la consola debe aparecer `[SYNC] OK: CONT-...` para cada una
5. Entra a Supabase Studio → tabla `contingencia_ventas` → deben aparecer los registros
6. El campo `estado_sync` en SQLite debe cambiar a `sincronizada`

---

### TAREA 7 — Indicador visual de modo offline / sync

**[VERIFICAR PRIMERO]** ¿Hay algún indicador en la UI (ícono, badge, texto) que muestre
si el SOS está online u offline? ¿Aparece cuántas notas están pendientes? Si sí, verifica
y continúa.

**Si falta implementar:**

En `PosController.java` o en `HomeController.java`, agrega un label pequeño:

```java
// En el FXML agrega un Label arriba a la derecha:
// <Label fx:id="syncStatusLabel" text="● Online" style="-fx-text-fill: green;" />

@FXML private Label syncStatusLabel;

// En initialize() o en un hilo periódico cada 15s:
private void actualizarEstadoSync() {
    new Thread(() -> {
        boolean online = SupabaseService.getInstance().isOnline();
        int pendientes = DatabaseService.getInstance().getContingenciasPendientes().size();
        javafx.application.Platform.runLater(() -> {
            if (online) {
                syncStatusLabel.setText("● Online" + (pendientes > 0 ? " (sync...)" : ""));
                syncStatusLabel.setStyle("-fx-text-fill: #22c55e;");
            } else {
                syncStatusLabel.setText("⚠ Offline" + (pendientes > 0 ? " — " + pendientes + " pendientes" : ""));
                syncStatusLabel.setStyle("-fx-text-fill: #f59e0b;");
            }
        });
    }).start();
}
```

---

## Tabla en Supabase (ya existe — solo verificar)

La tabla `contingencia_ventas` ya fue creada en Supabase con este esquema:

```sql
id                    uuid PRIMARY KEY         -- UUID v4 del SOS
user_id               uuid NOT NULL            -- del JWT del usuario
sos_device_id         text                     -- ej: "LAPTOP-JUAN-1234"
numero_nota           text NOT NULL            -- "CONT-20260425-0001"
fecha_venta           timestamptz NOT NULL     -- UTC ISO 8601
items                 jsonb NOT NULL           -- [{nombre, cantidad, precio_unitario, descuento, subtotal}]
monto_neto            integer
monto_iva             integer
monto_total           integer
rut_receptor          text                     -- null si consumidor final
razon_social_receptor text
estado                text DEFAULT 'pendiente' -- lo maneja POS Matic web
...
```

**RLS activado:** el JWT del usuario solo puede ver/escribir sus propios registros.

**Para verificar que la tabla existe:** entra a Supabase Studio
(https://supabase.com/dashboard/project/miuirpcrkfnfngvplhqp/editor)
y ejecuta: `SELECT count(*) FROM contingencia_ventas;`

---

## Criterios de éxito (smoke tests finales)

Antes de dar por terminada la implementación, verifica que pasan TODOS estos:

```
□ 1. Login real con credenciales POS Matic → accede al sistema sin error
□ 2. Login con credenciales incorrectas → muestra error, no crashea
□ 3. Desconectar red → indicador cambia a "Offline"
□ 4. Venta offline → aparece diálogo Nota de Venta con número CONT-YYYYMMDD-XXXX
□ 5. Venta offline → registro en SQLite con estado_sync='pendiente'
□ 6. Reconectar red → en ≤30s aparece en consola "[SYNC] OK: CONT-..."
□ 7. Después del sync → registro en Supabase tabla contingencia_ventas
□ 8. Después del sync → registro SQLite tiene estado_sync='sincronizada'
□ 9. Reintentar sync con el mismo UUID → Supabase no duplica (ON CONFLICT DO NOTHING)
□ 10. UserSession.getInstance().getUserId() tiene UUID válido después del login
```

Si pasan los 10, el módulo de contingencia está listo para producción.
Lo que sigue (convertir notas en DTE) lo hace el dueño del negocio desde POS Matic web en /contingencia.

---

## Notas adicionales importantes

- **Timezone:** `fecha_venta` SIEMPRE como `Instant.now().toString()` (UTC). Nunca `LocalDate.now()`.
- **UUID:** `UUID.randomUUID().toString()` para cada venta. Nunca usar el ID autoincrement de SQLite.
- **No emitir DTE:** el SOS en contingencia NO llama a `/api/sos-boleta` ni al SII. Solo guarda y sube.
- **Token expirado:** si `isOnline()` es true pero las llamadas devuelven 401, hay que refrescar el token. Llama a `SupabaseService.login()` de nuevo con las credenciales guardadas en SQLite config.
- **Batch size:** si hay más de 50 contingencias pendientes, envía de a 50 por ciclo de sync.
