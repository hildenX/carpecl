# SOS POS — Especificación de Sincronización con POS Matic

**Versión:** 1.0 — 2026-04-25  
**Destino:** Colaborador que implementa el módulo de contingencia en el lado SOS  
**Contraparte (backend/cloud):** POS Matic — Supabase + React

---

## Resumen del flujo

```
┌─────────────────────────────────────────────────────┐
│              SOS — Modo OFFLINE                      │
│  1. Usuario vende sin internet                       │
│  2. SOS guarda "notas de venta" en SQLite local     │
│  3. Imprime ticket interno (NO es DTE)               │
└─────────────────────────────────────────────────────┘
                      │
                      │  Al detectar reconexión
                      ▼
┌─────────────────────────────────────────────────────┐
│              SOS — Sincronización                    │
│  4. SOS se autentica en Supabase (email/pwd)        │
│  5. SOS sube notas pendientes a contingencia_ventas │
│  6. Marca localmente como "sincronizada"             │
└─────────────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────┐
│              POS Matic — Usuario actúa              │
│  7. Usuario ve notas en "Módulo de Contingencia"    │
│  8. Selecciona cuáles emitir y como qué tipo        │
│  9. POS Matic asigna folio y emite masivo al SII    │
│ 10. POS Matic descuenta inventario automáticamente  │
└─────────────────────────────────────────────────────┘
```

**CRÍTICO: El SOS NUNCA asigna folios DTE ni emite al SII.** Solo guarda ventas y las sincroniza. Los folios y la emisión ocurren siempre en POS Matic.

---

## 1. Autenticación

El SOS pide al usuario su email y contraseña al instalarse (las mismas credenciales de POS Matic). Con estas credenciales obtiene un JWT de Supabase para todas las operaciones posteriores.

### Endpoint de autenticación

```
POST https://<SUPABASE_URL>/auth/v1/token?grant_type=password
Content-Type: application/json
apikey: <SUPABASE_ANON_KEY>

{
  "email": "usuario@ejemplo.com",
  "password": "su_contraseña"
}
```

### Respuesta exitosa

```json
{
  "access_token": "eyJ...",
  "refresh_token": "...",
  "expires_in": 3600,
  "user": {
    "id": "uuid-del-usuario",
    "email": "usuario@ejemplo.com"
  }
}
```

- Guardar `access_token`, `refresh_token` y `user.id` de forma segura (no en texto plano).
- El `access_token` expira en 1 hora — usar `refresh_token` para renovarlo sin pedir contraseña de nuevo.
- El `user.id` es el `user_id` que se usará en todas las inserciones a Supabase.

### Renovar token

```
POST https://<SUPABASE_URL>/auth/v1/token?grant_type=refresh_token
Content-Type: application/json
apikey: <SUPABASE_ANON_KEY>

{
  "refresh_token": "<refresh_token_guardado>"
}
```

---

## 2. Tabla destino: `contingencia_ventas`

Esta tabla existe en Supabase POS Matic. El SOS inserta directamente via REST.

### Columnas que el SOS debe enviar

| Columna | Tipo | Requerido | Descripción |
|---|---|---|---|
| `id` | UUID | **SÍ** | Generado por SOS (UUID v4). Nunca repetir. |
| `user_id` | UUID | **SÍ** | Del JWT (`user.id` de la autenticación) |
| `sos_device_id` | text | No | Identificador del terminal (ej: "CAJA-1", hostname) |
| `numero_nota` | text | **SÍ** | Número interno SOS (ej: "CONT-20260425-0001") |
| `fecha_venta` | timestamptz | **SÍ** | Momento exacto de la venta en **UTC ISO 8601** (ver nota timezone) |
| `items` | JSON array | **SÍ** | Array de ítems (ver estructura abajo) |
| `monto_neto` | integer | **SÍ** | Monto neto en pesos chilenos (sin IVA) |
| `monto_iva` | integer | **SÍ** | Monto IVA en pesos chilenos |
| `monto_total` | integer | **SÍ** | Total = neto + iva |
| `rut_receptor` | text | No | Si el cliente lo entregó (requerido para emitir factura después) |
| `razon_social_receptor` | text | No | Nombre empresa del cliente |
| `direccion_receptor` | text | No | Dirección del cliente |
| `giro_receptor` | text | No | Giro comercial del cliente |
| `correo_receptor` | text | No | Email del cliente |

**No enviar:** `estado`, `tipo_dte_asignado`, `venta_id`, `folio_dte`, `tipo_dte_emitido`, `error_mensaje` — estos los maneja POS Matic.

### Estructura de `items` (JSON array)

```json
[
  {
    "producto_id": "uuid-del-producto-o-null",
    "nombre": "Nombre del producto",
    "cantidad": 2,
    "precio_unitario": 5000,
    "descuento": 0,
    "subtotal": 10000
  }
]
```

- `producto_id`: UUID del producto en Supabase si existe en el catálogo. `null` si es producto libre.
- `precio_unitario` y `subtotal` en pesos enteros (sin decimales).
- `descuento` en pesos (no porcentaje).

---

## 3. Inserción via API REST Supabase

### Endpoint

```
POST https://<SUPABASE_URL>/rest/v1/contingencia_ventas
Content-Type: application/json
apikey: <SUPABASE_ANON_KEY>
Authorization: Bearer <access_token>
Prefer: resolution=ignore-duplicates
```

El header `Prefer: resolution=ignore-duplicates` activa `ON CONFLICT DO NOTHING`. Si el SOS reintenta una sync y el UUID ya existe, Supabase lo ignora silenciosamente — **nunca habrá duplicados**.

### Body — insertar una venta

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "user_id": "uuid-del-usuario-autenticado",
  "sos_device_id": "CAJA-1",
  "numero_nota": "CONT-20260425-0001",
  "fecha_venta": "2026-04-25T14:33:00Z",
  "items": [
    {
      "producto_id": null,
      "nombre": "Café americano",
      "cantidad": 2,
      "precio_unitario": 2000,
      "descuento": 0,
      "subtotal": 4000
    }
  ],
  "monto_neto": 3361,
  "monto_iva": 639,
  "monto_total": 4000,
  "rut_receptor": null,
  "razon_social_receptor": null
}
```

### Body — insertar múltiples ventas (batch)

Enviar un array JSON en lugar de un objeto:

```json
[
  { "id": "uuid-1", "user_id": "...", ... },
  { "id": "uuid-2", "user_id": "...", ... }
]
```

Máximo recomendado: 50 ventas por request.

### Respuesta exitosa

```
HTTP 201 Created  (o 200 si Prefer incluye return=representation)
```

---

## 4. Cálculo de montos

Chile usa IVA del 19%. Para ventas afectas:

```
monto_neto  = round(precio_venta_con_iva / 1.19)
monto_iva   = monto_total - monto_neto
monto_total = suma de todos los subtotales de items
```

Para productos exentos de IVA (tipo 41 / boleta exenta):
```
monto_neto  = monto_total
monto_iva   = 0
```

Los valores van en pesos chilenos enteros (sin decimales).

---

## 5. Timezone — CRÍTICO

**El SII rechaza documentos con fecha incorrecta.** Chile opera en `America/Santiago` (UTC-3 en verano, UTC-4 en invierno).

**Regla:** Guardar `fecha_venta` siempre como UTC ISO 8601. POS Matic convierte a hora local Chile al emitir.

```java
// Java — obtener timestamp UTC en el momento de la venta
Instant ahora = Instant.now();
String fechaVentaUtc = ahora.toString();  // "2026-04-25T02:33:00.000Z"
```

**NO hacer:** `LocalDate.now().toString()` — esto usa la fecha del sistema del PC que puede estar en otra zona horaria.

**NO truncar a fecha sin hora** — guardar timestamp completo con hora y timezone.

---

## 6. Generación de UUID

Usar UUID v4 (aleatorio). En Java:

```java
import java.util.UUID;
String id = UUID.randomUUID().toString();
// "550e8400-e29b-41d4-a716-446655440000"
```

Guardar este UUID en la tabla SQLite local para poder marcar la venta como "sincronizada" una vez confirmada.

---

## 7. Número de nota (`numero_nota`)

Formato sugerido para que sea legible en POS Matic:

```
CONT-{YYYY}{MM}{DD}-{SECUENCIAL_4_DIGITOS}
Ejemplo: CONT-20260425-0001
```

El secuencial puede ser un autoincrement local en SQLite, reiniciando cada día o siendo global por dispositivo.

---

## 8. Detección de conectividad y ciclo de sync

### Flujo recomendado

```java
// Cada vez que se detecta conexión (ping o listener de red):
1. Obtener ventas locales con estado != 'sincronizada'
2. Verificar que access_token es válido (renovar si necesario)
3. POST batch a Supabase (máx 50 por llamada)
4. Si HTTP 201 → marcar cada venta como 'sincronizada' en SQLite
5. Si HTTP 4xx/5xx → reintentar en próximo ciclo (el UUID garantiza idempotencia)
```

### Columnas SQLite locales sugeridas

```sql
CREATE TABLE ventas_contingencia (
  id               TEXT PRIMARY KEY,  -- UUID v4
  numero_nota      TEXT NOT NULL,
  fecha_venta      TEXT NOT NULL,     -- UTC ISO 8601
  items_json       TEXT NOT NULL,     -- JSON serializado
  monto_neto       INTEGER NOT NULL,
  monto_iva        INTEGER NOT NULL,
  monto_total      INTEGER NOT NULL,
  rut_receptor     TEXT,
  razon_social     TEXT,
  estado_local     TEXT DEFAULT 'pendiente',  -- pendiente | sincronizada
  sincronizado_at  TEXT,
  created_at       TEXT DEFAULT (datetime('now'))
);
```

---

## 9. Ticket impreso en modo offline

Cuando no hay internet, el SOS debe imprimir un ticket **interno** (no DTE) que deje constancia de la venta. El cliente sabe que recibirá el DTE cuando el sistema se reconecte.

**Estructura mínima del ticket:**

```
━━━━━━━━━━━━━━━━━━━━━━━━━━
      NOTA DE VENTA
  (Documento no tributario)
━━━━━━━━━━━━━━━━━━━━━━━━━━
N° CONT-20260425-0001
Fecha: 25/04/2026 14:33

Café americano   x2   $4.000
━━━━━━━━━━━━━━━━━━━━━━━━━━
TOTAL:               $4.000
IVA incl.

Su documento tributario
(boleta/factura) será emitido
cuando se restaure la conexión.
━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## 10. Variables de entorno / configuración

El SOS necesita estas constantes (pueden ir en un archivo `.env` o en la configuración de instalación):

```
SUPABASE_URL=https://<proyecto>.supabase.co
SUPABASE_ANON_KEY=eyJ...  (clave pública, no la service_role)
```

Estas las proporciona el administrador de POS Matic. **No hardcodear en el código fuente.**

---

## 11. Credenciales del microservicio Node.js (DTE local)

> **El microservicio Node.js local NO emite DTEs durante contingencia.**  
> Su función en este contexto se limita a:
> - Generar el PDF del ticket interno (nota de venta)
> - Eventualmente: pre-validar el XML DTE localmente antes de sync (opcional, no requerido)

La emisión real al SII siempre la hace POS Matic cuando hay internet.

---

## 12. Checklist de integración

- [ ] Usuario puede ingresar email/contraseña en configuración del SOS
- [ ] SOS guarda JWT y `user_id` de forma persistente (Keychain / archivo cifrado)
- [ ] SOS renueva el token automáticamente antes de sync
- [ ] Cada venta offline genera un UUID v4 único guardado en SQLite
- [ ] `fecha_venta` se guarda como UTC ISO completo (con hora)
- [ ] Ticket impreso incluye el número de nota interno
- [ ] Sync detecta reconexión de red (listener o polling cada 30s)
- [ ] Sync usa batch de máx 50 ventas por request
- [ ] Header `Prefer: resolution=ignore-duplicates` en todos los POSTs
- [ ] Ventas sincronizadas se marcan localmente para no reenviar
- [ ] La pantalla de estado muestra "X notas pendientes de sincronizar"

---

## 13. Contacto / preguntas

Coordinar con el equipo POS Matic para:
- Obtener `SUPABASE_URL` y `SUPABASE_ANON_KEY` del proyecto de producción
- Confirmar UUIDs de productos del catálogo si se quiere mapear `producto_id`
- Verificar en Supabase Studio que las inserciones lleguen correctamente antes de hacer pruebas de emisión masiva