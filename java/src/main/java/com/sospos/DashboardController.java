package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.db.SupabaseService;
import com.sospos.model.UserSession;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;

public class DashboardController {

    @FXML private Label statusLabel;
    @FXML private Label pendingLabel;
    @FXML private Label dtePendingLabel;
    @FXML private Label negocioLabel;
    @FXML private Button btnSync;
    @FXML private ProgressIndicator syncSpinner;

    private final DatabaseService db = DatabaseService.getInstance();
    private final SupabaseService supa = SupabaseService.getInstance();

    @FXML
    public void initialize() {
        // Nombre del negocio en el header
        String nombreNegocio = db.getConfig("nombre_negocio");
        if (negocioLabel != null && nombreNegocio != null && !nombreNegocio.isBlank())
            negocioLabel.setText(nombreNegocio);

        // Mostrar estado local mientras se verifica conexión
        int pending = db.countPendientesSync();
        pendingLabel.setText(pending > 0
            ? pending + " venta" + (pending != 1 ? "s" : "") + " sin sincronizar"
            : "Todo sincronizado");
        actualizarContadorDTEs();
        setEstadoOffline();

        // Verificar conexión en segundo plano
        Task<Boolean> checkTask = new Task<>() {
            @Override protected Boolean call() { return supa.testConexion(); }
        };
        checkTask.setOnSucceeded(e -> {
            if (checkTask.getValue()) setEstadoOnline();
            else setEstadoOffline();
        });
        new Thread(checkTask, "supabase-ping").start();
    }

    private void setEstadoOnline() {
        statusLabel.setText("● En línea");
        statusLabel.getStyleClass().setAll("status-online");
        if (btnSync != null) btnSync.setDisable(false);
    }

    private void setEstadoOffline() {
        statusLabel.setText("● Sin conexión");
        statusLabel.getStyleClass().setAll("status-offline");
        if (btnSync != null) btnSync.setDisable(true);
    }

    // ─── Módulos ──────────────────────────────────────────────────────────────

    private void actualizarContadorDTEs() {
        if (dtePendingLabel == null) return;
        int dtes = db.countDTEsPendientes();
        dtePendingLabel.setText(dtes + " pendiente" + (dtes != 1 ? "s" : ""));
        if (dtes > 0) {
            dtePendingLabel.getStyleClass().setAll("status-offline");
        } else {
            dtePendingLabel.getStyleClass().setAll("label-sub");
        }
    }

    @FXML
    void onDTEs() {
        try { App.showDTEs(); } catch (Exception e) { e.printStackTrace(); }
    }

    @FXML
    void onProductos() {
        try { App.showInventario(); } catch (Exception e) { e.printStackTrace(); }
    }

    @FXML
    void onCajas() {
        try { App.showCajas(); } catch (Exception e) { e.printStackTrace(); }
    }

    @FXML
    void onCerrarSesion() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "¿Cerrar sesión y volver al inicio?", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Cerrar sesión");
        confirm.showAndWait().ifPresent(r -> {
            if (r != ButtonType.OK) return;
            // Limpia tokens y user_id en SQLite y en memoria
            db.clearSession();
            UserSession.getInstance().clear();
            try { App.showHome(); }
            catch (Exception e) { e.printStackTrace(); }
        });
    }

    @FXML
    void onSincronizacion() {
        if (!supa.isOnline()) {
            new Alert(Alert.AlertType.WARNING,
                    "Sin conexión a Supabase. Verifica tu internet.").showAndWait();
            return;
        }

        // Deshabilitar botón y mostrar spinner durante sync
        if (btnSync != null) btnSync.setDisable(true);
        if (syncSpinner != null) syncSpinner.setVisible(true);
        statusLabel.setText("● Sincronizando...");
        statusLabel.getStyleClass().setAll("status-offline");

        Task<SupabaseService.SyncResult> syncTask = new Task<>() {
            @Override
            protected SupabaseService.SyncResult call() {
                return supa.syncFromSupabase(db);
            }
        };

        syncTask.setOnSucceeded(e -> {
            SupabaseService.SyncResult result = syncTask.getValue();
            if (syncSpinner != null) syncSpinner.setVisible(false);
            if (result.ok) {
                // Procesar cola de DTEs pendientes en segundo plano
                Task<Integer> dteTask = new Task<>() {
                    @Override protected Integer call() { return supa.procesarColaDTE(db); }
                };
                dteTask.setOnSucceeded(ev -> {
                    result.dtesEmitidos = dteTask.getValue();
                    setEstadoOnline();
                    new Alert(Alert.AlertType.INFORMATION,
                            "Sincronización completa:\n" + result.resumen()).showAndWait();
                    int pending = db.countPendientesSync();
                    pendingLabel.setText(pending + " venta" + (pending != 1 ? "s" : "") + " pendiente" + (pending != 1 ? "s" : ""));
                    actualizarContadorDTEs();
                });
                dteTask.setOnFailed(ev -> {
                    setEstadoOnline();
                    new Alert(Alert.AlertType.INFORMATION,
                            "Sincronización completa:\n" + result.resumen()).showAndWait();
                });
                new Thread(dteTask, "dte-queue").start();
            } else {
                setEstadoOffline();
                if (btnSync != null) btnSync.setDisable(false);
                if (result.sessionExpired) {
                    new Alert(Alert.AlertType.WARNING,
                            "Tu sesión expiró. Inicia sesión nuevamente para sincronizar.")
                            .showAndWait();
                    try { App.showHome(); } catch (Exception ignored) {}
                } else {
                    new Alert(Alert.AlertType.ERROR,
                            "Error en sincronización:\n" + result.error).showAndWait();
                }
            }
        });

        syncTask.setOnFailed(e -> {
            if (syncSpinner != null) syncSpinner.setVisible(false);
            setEstadoOffline();
        });

        new Thread(syncTask, "supabase-sync").start();
    }
}
