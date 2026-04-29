package com.sospos.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.sospos.model.ItemCarrito;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Genera boletas en formato PDF (80 mm de ancho, estilo térmico),
 * igual al formato de pos-matic.
 */
public class BoletaPdfService {

    // ── Dimensiones página (80 mm) ──────────────────────────────────────────
    private static final float MM    = 2.8346f;
    private static final float PW    = 80 * MM;   // 226.8 pt
    private static final float MX    = 5  * MM;   // margen izq/der: 5 mm
    private static final float TW    = PW - 2*MX; // ancho texto: 70 mm

    // ── Tamaños de fuente ───────────────────────────────────────────────────
    private static final float FS_BIG = 11f;
    private static final float FS_MED = 9f;
    private static final float FS_SM  = 8f;

    // ── Alturas de línea ────────────────────────────────────────────────────
    private static final float LH_BIG = 14f;
    private static final float LH_MED = 12f;
    private static final float LH_SM  = 10.5f;
    private static final float GAP    = 5f;   // separación entre secciones

    // ── Datos de entrada ────────────────────────────────────────────────────
    public record BoletaData(
        String nombreNegocio,
        String rutNeg,
        String giroNeg,
        String dirNeg,        // legacy: si no se usa Suc/C.M, este se imprime suelto
        String telNeg,
        String tipoDTE,
        String folio,
        String clienteNombre,
        String clienteRut,
        String clienteDireccion,
        List<ItemCarrito> items,
        double total,
        String metodoPago,
        double vuelto,
        String trackId,
        int ventaId,
        // Nuevos campos para layout estilo POS Matic
        String logoUrl,       // URL del logo del negocio (PNG/JPG)
        String sucursal,      // "Suc: ..."
        String casaMatriz,    // "C.M: ..."
        String emailNeg,
        String tedXml         // XML <TED>...</TED> para generar PDF417
    ) {
        // Constructor de retrocompatibilidad
        public BoletaData(String nombreNegocio, String rutNeg, String giroNeg, String dirNeg,
                          String telNeg, String tipoDTE, String folio, String clienteNombre,
                          String clienteRut, String clienteDireccion, List<ItemCarrito> items,
                          double total, String metodoPago, double vuelto, String trackId, int ventaId) {
            this(nombreNegocio, rutNeg, giroNeg, dirNeg, telNeg, tipoDTE, folio, clienteNombre,
                 clienteRut, clienteDireccion, items, total, metodoPago, vuelto, trackId, ventaId,
                 null, null, null, null, null);
        }
    }

    // ── Generación principal ────────────────────────────────────────────────
    public static File generarPdf(BoletaData d) throws IOException {
        // Pre-cargar logo y barcode TED para conocer altura exacta
        BufferedImage logoImg = cargarLogo(d.logoUrl());
        BufferedImage tedImg  = generarPdf417(d.tedXml());
        float logoH = (logoImg != null) ? 50f : 0f;
        float tedH  = (tedImg  != null) ? 70f : 0f;

        float pageH = estimarAltura(d) + logoH + tedH;

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PW, pageH));
            doc.addPage(page);

            // Fuentes: usamos COURIER_BOLD también como "regular" para que toda
            // la boleta tenga trazo grueso (estilo recibo térmico bien legible).
            PDType1Font fontR = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
            PDType1Font fontB = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            NumberFormat fmt  = NumberFormat.getIntegerInstance(new Locale("es", "CL"));

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = pageH - GAP;

                // ══════════════ LOGO ══════════════
                if (logoImg != null) {
                    PDImageXObject pdLogo = pngFromBuffered(doc, logoImg);
                    float lw = 45f, lh = 45f;
                    float lx = (PW - lw) / 2f;
                    y -= lh;
                    cs.drawImage(pdLogo, lx, y, lw, lh);
                    y -= 4f;
                }

                // ══════════════ ENCABEZADO NEGOCIO ══════════════
                y -= LH_BIG;
                ctr(cs, fontB, FS_BIG, y, s(d.nombreNegocio()));
                if (!s(d.giroNeg()).isBlank()) {
                    for (String ln : wrap(fontR, FS_SM, s(d.giroNeg()), TW)) {
                        y -= LH_SM;
                        ctr(cs, fontR, FS_SM, y, ln);
                    }
                }
                if (!s(d.rutNeg()).isBlank()) {
                    y -= LH_MED;
                    ctr(cs, fontR, FS_MED, y, "RUT: " + s(d.rutNeg()));
                }
                // Suc: ... (sucursal donde se emite el DTE)
                if (!s(d.sucursal()).isBlank()) {
                    for (String ln : wrap(fontR, FS_SM, "Suc: " + s(d.sucursal()), TW)) {
                        y -= LH_SM;
                        ctr(cs, fontR, FS_SM, y, ln);
                    }
                }
                // C.M: ... (casa matriz)
                if (!s(d.casaMatriz()).isBlank()) {
                    for (String ln : wrap(fontR, FS_SM, "C.M: " + s(d.casaMatriz()), TW)) {
                        y -= LH_SM;
                        ctr(cs, fontR, FS_SM, y, ln);
                    }
                }
                // Si no hay Suc/C.M y existe la dirección legacy, renderizarla
                if (s(d.sucursal()).isBlank() && s(d.casaMatriz()).isBlank()
                    && !s(d.dirNeg()).isBlank()) {
                    for (String ln : wrap(fontR, FS_SM, s(d.dirNeg()), TW)) {
                        y -= LH_SM;
                        ctr(cs, fontR, FS_SM, y, ln);
                    }
                }
                if (!s(d.telNeg()).isBlank()) {
                    y -= LH_SM;
                    ctr(cs, fontR, FS_SM, y, "Tel: " + s(d.telNeg()));
                }
                if (!s(d.emailNeg()).isBlank()) {
                    y -= LH_SM;
                    ctr(cs, fontR, FS_SM, y, s(d.emailNeg()));
                }

                y -= GAP;
                line(cs, y);
                y -= GAP;

                // ══════════════ TIPO DOC + FOLIO ══════════════
                String tdteNom = s(d.tipoDTE() != null ? d.tipoDTE() : "BOLETA ELECTRONICA");
                String fechaStr = LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

                y -= LH_BIG;
                ctr(cs, fontB, FS_BIG, y, tdteNom);
                y -= LH_MED;
                ctr(cs, fontB, FS_MED, y, "N" + (char) 176 + " " + (d.folio() != null ? d.folio() : "PENDIENTE"));
                y -= LH_SM;
                ctr(cs, fontR, FS_SM, y, "Fecha: " + fechaStr);

                y -= GAP;
                line(cs, y);
                y -= GAP;

                // ══════════════ CLIENTE ══════════════
                y -= LH_MED;
                lft(cs, fontR, FS_MED, y, "Cliente: " + s(d.clienteNombre()));
                y -= LH_MED;
                lft(cs, fontR, FS_MED, y, "RUT:     " + s(d.clienteRut()));
                String dir = d.clienteDireccion();
                if (dir != null && !dir.isBlank() && !"Sin dirección".equals(dir)) {
                    y -= LH_SM;
                    lft(cs, fontR, FS_SM, y, "Dir:     " + s(dir));
                }

                y -= GAP;
                line(cs, y);
                y -= GAP;

                // ══════════════ CABECERA DETALLE ══════════════
                y -= LH_MED;
                lft(cs, fontB, FS_MED, y, "PRODUCTO");
                rgt(cs, fontB, FS_MED, y, "TOTAL");
                y -= 3f;
                lineLight(cs, y);
                y -= 3f;

                // ══════════════ ITEMS ══════════════
                for (ItemCarrito item : d.items()) {
                    long pUnit = Math.round(item.getProducto().getPrecio());
                    long sub   = Math.round(item.getSubtotal());
                    String nomLine = item.getCantidad() + "x " + s(item.getProducto().getNombre());
                    String totLine = "$" + fmt.format(sub);

                    float nomW = fontR.getStringWidth(nomLine) / 1000f * FS_MED;
                    float totW = fontR.getStringWidth(totLine) / 1000f * FS_MED;

                    if (nomW + totW + 4f <= TW) {
                        y -= LH_MED;
                        lft(cs, fontR, FS_MED, y, nomLine);
                        rgt(cs, fontR, FS_MED, y, totLine);
                    } else {
                        List<String> nomWrap = wrap(fontR, FS_MED, nomLine, TW - totW - 4f);
                        boolean first = true;
                        for (String wl : nomWrap) {
                            y -= LH_MED;
                            lft(cs, fontR, FS_MED, y, wl);
                            if (first) {
                                rgt(cs, fontR, FS_MED, y, totLine);
                                first = false;
                            }
                        }
                    }
                    y -= LH_SM;
                    lft(cs, fontR, FS_SM, y, "  $" + fmt.format(pUnit) + " c/u");
                }

                y -= GAP;
                line(cs, y);
                y -= GAP;

                // ══════════════ TOTALES ══════════════
                long mntTotal = Math.round(d.total());
                long mntNeto  = Math.round(d.total() / 1.19);
                long iva       = mntTotal - mntNeto;

                y -= LH_MED;
                lft(cs, fontR, FS_MED, y, "Neto:");
                rgt(cs, fontR, FS_MED, y, "$" + fmt.format(mntNeto));
                y -= LH_MED;
                lft(cs, fontR, FS_MED, y, "IVA (19%):");
                rgt(cs, fontR, FS_MED, y, "$" + fmt.format(iva));
                y -= 4f;
                lineThick(cs, y);
                y -= 4f;
                y -= LH_BIG;
                lft(cs, fontB, FS_BIG, y, "TOTAL:");
                rgt(cs, fontB, FS_BIG, y, "$" + fmt.format(mntTotal));

                y -= GAP;
                line(cs, y);
                y -= GAP;

                // ══════════════ MÉTODO DE PAGO ══════════════
                String metNom = switch (d.metodoPago() != null ? d.metodoPago() : "") {
                    case "efectivo" -> "Efectivo";
                    case "tarjeta"  -> "Tarjeta";
                    default          -> "Otro";
                };
                y -= LH_MED;
                ctr(cs, fontR, FS_MED, y, "Metodo de pago: " + metNom);
                if ("efectivo".equals(d.metodoPago()) && d.vuelto() > 0) {
                    y -= LH_MED;
                    ctr(cs, fontB, FS_MED, y, "Vuelto: $" + fmt.format((long) d.vuelto()));
                }

                y -= GAP;
                line(cs, y);
                y -= GAP;

                // ══════════════ TIMBRE ELECTRONICO (PDF417) ══════════════
                if (tedImg != null) {
                    PDImageXObject pdTed = pngFromBuffered(doc, tedImg);
                    float bw = TW;
                    float bh = bw * (float) tedImg.getHeight() / (float) tedImg.getWidth();
                    if (bh > 70f) { bh = 70f; bw = bh * (float) tedImg.getWidth() / (float) tedImg.getHeight(); }
                    float bx = (PW - bw) / 2f;
                    y -= bh;
                    cs.drawImage(pdTed, bx, y, bw, bh);
                    y -= LH_SM;
                    ctr(cs, fontR, FS_SM, y, "Timbre Electronico SII");
                    y -= LH_SM;
                    ctr(cs, fontR, FS_SM, y, "Verifique en www.sii.cl");
                } else if (d.folio() != null) {
                    y -= LH_SM;
                    ctr(cs, fontR, FS_SM, y, "Timbre Electronico SII");
                    if (d.trackId() != null) {
                        y -= LH_SM;
                        ctr(cs, fontR, FS_SM, y, "Track ID: " + s(d.trackId()));
                    }
                } else {
                    y -= LH_SM;
                    ctr(cs, fontR, FS_SM, y, "Timbre electronico pendiente");
                    y -= LH_SM;
                    ctr(cs, fontR, FS_SM, y, "DTE no enviado aun al SII");
                }

                y -= GAP;
                line(cs, y);
                y -= GAP;

                // ══════════════ PIE ══════════════
                y -= LH_BIG;
                ctr(cs, fontB, FS_BIG, y, "Gracias por su compra!");
                y -= LH_SM;
                ctr(cs, fontR, FS_SM, y, "Verifique en www.sii.cl");
            }

            File out = File.createTempFile("boleta_" + d.ventaId() + "_", ".pdf");
            out.deleteOnExit();
            doc.save(out);
            return out;
        }
    }

    // ── Estimación de altura ────────────────────────────────────────────────
    private static float estimarAltura(BoletaData d) {
        float h = GAP * 2;
        // Encabezado negocio
        h += LH_BIG;
        if (!s(d.giroNeg()).isBlank())  h += LH_SM;
        if (!s(d.rutNeg()).isBlank())   h += LH_MED;
        if (!s(d.dirNeg()).isBlank())   h += LH_SM * Math.max(1, s(d.dirNeg()).length() / 32 + 1);
        if (!s(d.telNeg()).isBlank())   h += LH_SM;
        h += GAP * 2 + 1;  // separator
        // Tipo doc
        h += LH_BIG + LH_MED + LH_SM * 2;
        h += GAP * 2 + 1;
        // Cliente
        h += LH_MED * 2 + LH_SM;
        h += GAP * 2 + 1;
        // Detalle header
        h += LH_MED + 6;
        // Items
        for (ItemCarrito item : d.items()) {
            String nomLine = item.getCantidad() + "x " + s(item.getProducto().getNombre());
            int wrapLines = Math.max(1, nomLine.length() / 22 + 1);
            h += LH_MED * wrapLines + LH_SM;
        }
        h += GAP * 2 + 1;
        // Totales
        h += LH_MED * 2 + 8 + LH_BIG;
        h += GAP * 2 + 1;
        // Pago
        h += LH_MED + ("efectivo".equals(d.metodoPago()) && d.vuelto() > 0 ? LH_MED : 0);
        h += GAP * 2 + 1;
        // DTE
        h += LH_SM * (d.folio() != null ? (d.trackId() != null ? 2 : 1) : 2);
        h += GAP * 2 + 1;
        // Pie
        h += LH_BIG + LH_SM + GAP * 3;
        return h + 30;  // margen de seguridad
    }

    // ── Helpers de dibujo ───────────────────────────────────────────────────

    /** Texto centrado */
    private static void ctr(PDPageContentStream cs, PDFont f, float sz, float y, String txt) throws IOException {
        if (txt == null || txt.isBlank()) return;
        float tw = f.getStringWidth(txt) / 1000f * sz;
        float x  = Math.max(MX, (PW - tw) / 2f);
        cs.beginText(); cs.setFont(f, sz); cs.newLineAtOffset(x, y); cs.showText(txt); cs.endText();
    }

    /** Texto alineado a la izquierda */
    private static void lft(PDPageContentStream cs, PDFont f, float sz, float y, String txt) throws IOException {
        if (txt == null || txt.isBlank()) return;
        cs.beginText(); cs.setFont(f, sz); cs.newLineAtOffset(MX, y); cs.showText(txt); cs.endText();
    }

    /** Texto alineado a la derecha */
    private static void rgt(PDPageContentStream cs, PDFont f, float sz, float y, String txt) throws IOException {
        if (txt == null || txt.isBlank()) return;
        float tw = f.getStringWidth(txt) / 1000f * sz;
        float x  = PW - MX - tw;
        cs.beginText(); cs.setFont(f, sz); cs.newLineAtOffset(x, y); cs.showText(txt); cs.endText();
    }

    /** Línea separadora gris */
    private static void line(PDPageContentStream cs, float y) throws IOException {
        cs.setStrokingColor(0.75f, 0.75f, 0.75f);
        cs.setLineWidth(0.4f);
        cs.moveTo(MX, y); cs.lineTo(PW - MX, y); cs.stroke();
        cs.setStrokingColor(0f, 0f, 0f);
    }

    /** Línea separadora muy clara */
    private static void lineLight(PDPageContentStream cs, float y) throws IOException {
        cs.setStrokingColor(0.85f, 0.85f, 0.85f);
        cs.setLineWidth(0.3f);
        cs.moveTo(MX, y); cs.lineTo(PW - MX, y); cs.stroke();
        cs.setStrokingColor(0f, 0f, 0f);
    }

    /** Línea gruesa oscura (antes del total) */
    private static void lineThick(PDPageContentStream cs, float y) throws IOException {
        cs.setStrokingColor(0.15f, 0.15f, 0.15f);
        cs.setLineWidth(0.8f);
        cs.moveTo(MX, y); cs.lineTo(PW - MX, y); cs.stroke();
        cs.setStrokingColor(0f, 0f, 0f);
    }

    /** Word-wrap de texto en anchura máxima */
    private static List<String> wrap(PDFont f, float sz, String text, float maxW) throws IOException {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String test = cur.isEmpty() ? w : cur + " " + w;
            if (f.getStringWidth(test) / 1000f * sz > maxW && !cur.isEmpty()) {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur = new StringBuilder(test);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines.isEmpty() ? List.of(text) : lines;
    }

    /** Filtrar chars fuera de WinAnsiEncoding (emojis, símbolos Unicode) */
    private static String s(String text) {
        if (text == null) return "";
        return text.chars()
                .filter(c -> c < 256)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** Carga el logo desde URL HTTP/S. Devuelve null si falla o no hay URL. */
    private static BufferedImage cargarLogo(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return ImageIO.read(new URL(url));
        } catch (Exception e) {
            System.err.println("[BoletaPdf] No se pudo cargar logo: " + e.getMessage());
            return null;
        }
    }

    /** Genera un BufferedImage PDF417 a partir del XML del TED. Null si TED vacío. */
    private static BufferedImage generarPdf417(String tedXml) {
        if (tedXml == null || tedXml.isBlank()) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.CHARACTER_SET, "ISO-8859-1");
            hints.put(EncodeHintType.PDF417_COMPACT, Boolean.FALSE);
            BitMatrix matrix = new MultiFormatWriter()
                    .encode(tedXml, BarcodeFormat.PDF_417, 600, 180, hints);
            return MatrixToImageWriter.toBufferedImage(matrix);
        } catch (Exception e) {
            System.err.println("[BoletaPdf] No se pudo generar PDF417: " + e.getMessage());
            return null;
        }
    }

    /** Convierte un BufferedImage en un PDImageXObject vía PNG en memoria. */
    private static PDImageXObject pngFromBuffered(PDDocument doc, BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return PDImageXObject.createFromByteArray(doc, baos.toByteArray(), "img");
    }
}
