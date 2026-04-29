# Estrategia de Facturación Offline (Chile / SII)

> **Estado:** especificación técnica. NO implementado todavía.
> **Alcance:** emisión de Boletas Electrónicas (DTE 39) y Facturas Electrónicas (DTE 33) cuando el POS no tiene conexión a internet, manteniendo validez tributaria ante el SII.

---

## 1. Contexto del problema

El SII (Servicio de Impuestos Internos de Chile) exige que todo DTE (Documento Tributario Electrónico) sea:

1. **Numerado con un folio autorizado** previamente por el SII mediante un archivo **CAF** (Código de Autorización de Folios).
2. **Firmado digitalmente** con el certificado del contribuyente (.pfx / .p12).
3. **Reportado al SII** dentro de los plazos legales (boletas: resumen diario antes de las 12:00 del día siguiente; facturas: envío individual al recibirla el receptor o, en contingencia, dentro de las 12 hrs siguientes a la recuperación de conexión).

La emisión **no requiere internet en el momento mismo de la venta** siempre que el contribuyente:

- Tenga **folios CAF descargados localmente** (rango reservado, ej. 1–1000).
- Tenga el **certificado digital** instalado en el equipo.
- Pueda **enviar el lote al SII al recuperar conexión**.

Esto se conoce oficialmente como **modo contingencia**.

---

## 2. Componentes necesarios

### 2.1 Archivos del contribuyente
| Archivo | Origen | Frecuencia | Almacenamiento sugerido |
|---|---|---|---|
| `CAF_<rango>.xml` | sii.cl (Folios autorizados) | Cuando se agotan folios | `~/sos-pos/caf/` |
| `certificado.pfx` | proveedor (eCertChile, etc.) | Cada 1–3 años | `~/sos-pos/cert/` |
| `clave.txt` (cifrada) | usuario | igual que el cert. | DB local cifrada |

### 2.2 Tablas SQLite nuevas

```sql
CREATE TABLE caf_folios (
  id INTEGER PRIMARY KEY,
  tipo_dte INTEGER NOT NULL,        -- 33 factura, 39 boleta
  rango_desde INTEGER NOT NULL,
  rango_hasta INTEGER NOT NULL,
  caf_xml TEXT NOT NULL,            -- contenido completo del XML CAF
  fecha_autorizacion TEXT NOT NULL,
  agotado INTEGER DEFAULT 0
);

CREATE TABLE folios_usados (
  id INTEGER PRIMARY KEY,
  tipo_dte INTEGER NOT NULL,
  folio INTEGER NOT NULL,
  venta_id INTEGER NOT NULL,
  estado TEXT NOT NULL,             -- 'pendiente_envio' | 'enviado' | 'aceptado' | 'rechazado'
  xml_firmado TEXT,                 -- DTE firmado listo para enviar
  track_id_sii TEXT,                -- ID que devuelve el SII al recibir el lote
  fecha_emision TEXT NOT NULL,
  fecha_envio TEXT,
  UNIQUE(tipo_dte, folio)
);
```

---

## 3. Flujo de emisión offline

```
┌──────────────────────────────────────────────────────────┐
│ 1. Usuario presiona "Finalizar Transacción"             │
└──────────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│ 2. ¿Hay folio CAF disponible para el tipo de DTE?       │
│    SELECT MIN(folio_libre) FROM caf_folios              │
└──────────────────────────────────────────────────────────┘
              │ sí                      │ no
              ▼                          ▼
┌──────────────────────┐    ┌──────────────────────────┐
│ 3a. Asignar folio    │    │ 3b. ERROR: solicitar     │
│ Reservar en          │    │ folios al SII (necesita  │
│ folios_usados        │    │ internet). Bloquear venta│
│ estado='pendiente'   │    │ o marcar como nota.      │
└──────────────────────┘    └──────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────┐
│ 4. Construir XML del DTE (DTE.xsd del SII)              │
│    - Encabezado, Detalle, Totales                       │
│    - Timbre (TED) calculado con CAF + clave RSA         │
└──────────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│ 5. Firmar el XML con el certificado digital (XAdES)     │
│    Guardar en folios_usados.xml_firmado                 │
└──────────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│ 6. Generar PDF/representación impresa con TED + folio   │
│    El cliente se lleva esto. Es VÁLIDO tributariamente. │
└──────────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│ 7. Enviar al SII en background cuando vuelva internet:  │
│    - Boletas (39): RCOF diario antes de 12:00 día sig.  │
│    - Facturas (33): EnvioDTE individual ASAP            │
└──────────────────────────────────────────────────────────┘
```

**Punto clave:** el documento entregado al cliente en el paso 6 ya es legalmente válido porque lleva el TED (Timbre Electrónico) calculado con la clave del CAF. El envío al SII es un proceso administrativo posterior.

---

## 4. Diferencias por tipo de DTE

### Boleta Electrónica (DTE 39)
- **Envío al SII:** se acumulan boletas del día y se envía un **RCOF** (Resumen de Consumo de Folios) antes de las 12:00 del día siguiente.
- **Tolerancia offline:** alta. Hasta 24 hrs sin conexión sin riesgo legal.
- **Endpoint SII:** `https://palena.sii.cl/cgi_dte/UPL/DTEUpload` (producción).

### Factura Electrónica (DTE 33)
- **Envío al SII:** documento individual, ASAP.
- **Tolerancia offline:** menor. Si la contingencia dura más de 24 hrs, hay que notificarlo formalmente al SII (declaración jurada de contingencia).
- **Receptor también recibe XML** por correo.

---

## 5. Librerías Java sugeridas

| Función | Librería | Notas |
|---|---|---|
| Firma XAdES | `xades4j` (Apache 2.0) | Soporta XAdES-BES requerido por SII |
| Manipular XML | JAXP estándar JDK | suficiente |
| Schema validation | `javax.xml.validation` | validar contra DTE_v10.xsd |
| Generar PDF | librería PDF ya usada (`BoletaPdfService`) | extender |
| HTTP a SII | `java.net.http.HttpClient` | ya disponible JDK 21 |
| Cifrar clave .pfx en SQLite | JCA + AES-GCM | clave maestra derivada del user_id |

---

## 6. Tareas de implementación (cuando se decida programar)

1. [ ] Pantalla de configuración de empresa: subir CAF y certificado.
2. [ ] Servicio `CafService`: parsear, validar y rotar folios.
3. [ ] Servicio `DteBuilder`: construir XML del DTE conforme al schema del SII.
4. [ ] Servicio `TedCalculator`: timbre electrónico (RSA con clave del CAF).
5. [ ] Servicio `XmlSigner`: firma XAdES-BES del DTE.
6. [ ] Servicio `SiiUploader`: envío al SII y trackeo de respuesta.
7. [ ] Job en background (extender `iniciarMonitorSync` en `App.java`): detecta conexión y vacía cola `folios_usados.estado='pendiente_envio'`.
8. [ ] Pantalla de "Estado SII": muestra documentos pendientes, aceptados, rechazados.
9. [ ] Migrar la lógica actual de "nota de contingencia" a `folios_usados`.
10. [ ] Pruebas en ambiente de **certificación SII (maullin.sii.cl)** antes de producción.

---

## 7. Estado actual del POS

Hoy (2026-04-28) el flujo en `PosController.procesarPago` está forzado a `online = false` (modo contingencia siempre) y se guarda como **nota de contingencia interna**, sin folio SII real. Es decir, lo que se entrega al cliente **no es un DTE válido**, es solo un comprobante interno. Migrar esto al flujo del punto 3 es el trabajo pendiente.

## 8. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Se acaban folios sin internet | Alerta al usuario cuando queden < 50 folios. Bloquear si llega a 0. |
| Clave del .pfx expuesta | AES-GCM con clave derivada de PBKDF2(user_id + salt). Nunca en logs. |
| Reloj del equipo desincronizado | Validar contra hora servidor SII al recuperar conexión. Rechazar fechas futuras. |
| Doble emisión del mismo folio | UNIQUE(tipo_dte, folio) en SQLite + transacción atómica al asignar. |
| Equipo se rompe con folios usados sin enviar | Backup automático de `folios_usados` a Supabase tras cada venta. |
