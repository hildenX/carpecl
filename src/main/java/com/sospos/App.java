package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.db.SupabaseService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App extends Application {

    private static Stage primaryStage;
    private static ScheduledExecutorService syncScheduler;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;
        stage.setTitle("SOS POS - PUDÚ Tecnología");

        // Ícono de la aplicación (ventana + taskbar)
        stage.getIcons().add(new Image(
            Objects.requireNonNull(App.class.getResourceAsStream("/com/sospos/images/pudu-logo-new.png"))
        ));

        DatabaseService.getInstance().inicializarDeviceId();
        iniciarMonitorSync();
        ensureAutoStart();

        String savedUserId = DatabaseService.getInstance().getConfig("user_id");

        if (savedUserId != null && !savedUserId.isBlank()) {
            // Restaurar sesión en memoria desde SQLite para que las llamadas
            // a Supabase y al servidor DTE tengan user_id/token disponibles.
            DatabaseService dbi = DatabaseService.getInstance();
            com.sospos.model.UserSession.getInstance().setFrom(
                savedUserId,
                dbi.getConfig("access_token"),
                dbi.getConfig("email"),
                dbi.getConfig("nombre_negocio")
            );
            // Sesión guardada: abrir directamente el dashboard. La sincronización
            // online sigue corriendo en background desde iniciarMonitorSync().
            try {
                showDashboard();
                primaryStage.show();
            } catch (Exception e) {
                e.printStackTrace();
            }

        } else {
            // Sin sesión previa → pantalla de login
            showHome();
            stage.show();
        }
    }

    @Override
    public void stop() {
        if (syncScheduler != null && !syncScheduler.isShutdown()) syncScheduler.shutdownNow();
    }

    // ── Modo bandeja (online) ────────────────────────────────────────────────

    /** Oculta la ventana, instala tray icon y sincroniza en background. */
    public static void goTrayMode() {
        Platform.setImplicitExit(false);
        TrayManager.install(primaryStage);
        primaryStage.hide();
        TrayManager.updateTooltip("SOS POS — Sincronizando...");

        new Thread(() -> {
            try {
                SupabaseService.getInstance()
                    .sincronizarContingencias(DatabaseService.getInstance());
                TrayManager.updateTooltip("SOS POS — Online ✓");
            } catch (Exception e) {
                TrayManager.updateTooltip("SOS POS — Error al sincronizar");
            }
        }, "tray-init-sync").start();
    }

    // ── Navegación ──────────────────────────────────────────────────────────

    private static void showFixed(String fxml, String css, int w, int h) throws Exception {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/sospos/" + fxml));
        Scene scene = new Scene(loader.load(), w, h);
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/theme.css").toExternalForm());
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/" + css).toExternalForm());
        primaryStage.setMaximized(false);
        primaryStage.setResizable(true);
        primaryStage.setScene(scene);
        primaryStage.setWidth(w);
        primaryStage.setHeight(h);
        primaryStage.setResizable(false);
        primaryStage.centerOnScreen();
    }

    private static void showMaximized(String fxml, String css) throws Exception {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("/com/sospos/" + fxml));
        javafx.scene.Parent root = loader.load();
        // Tamaño inicial razonable por si el stage no estaba maximizado todavía
        Scene scene = new Scene(root,
                javafx.stage.Screen.getPrimary().getVisualBounds().getWidth(),
                javafx.stage.Screen.getPrimary().getVisualBounds().getHeight());
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/theme.css").toExternalForm());
        scene.getStylesheets().add(App.class.getResource("/com/sospos/styles/" + css).toExternalForm());
        primaryStage.setResizable(true);
        primaryStage.setScene(scene);
        // Toggle para forzar el re-maximize aunque ya estuviese maximizado
        primaryStage.setMaximized(false);
        primaryStage.setMaximized(true);
    }

    public static void showHome() throws Exception {
        showMaximized("home.fxml", "home.css");
    }

    public static void showDashboard() throws Exception {
        showMaximized("dashboard.fxml", "dashboard.css");
    }

    public static void showInventario() throws Exception {
        showMaximized("inventario.fxml", "inventario.css");
    }

    public static void showCajas() throws Exception {
        showMaximized("cajas.fxml", "cajas.css");
    }

    public static void showDTEs() throws Exception {
        showMaximized("dtes.fxml", "dtes.css");
    }

    public static void showPOS() throws Exception {
        showMaximized("pos.fxml", "pos.css");
    }

    // ── Inicio automático con Windows ────────────────────────────────────────

    /**
     * Registra el ejecutable en el inicio de Windows (HKCU Run).
     * Solo aplica cuando se corre como .exe instalado (jpackage).
     */
    private static void ensureAutoStart() {
        String cmd = ProcessHandle.current().info().command().orElse("");
        if (cmd.isBlank() || !cmd.toLowerCase().endsWith(".exe")) return;

        try {
            // Verificar si ya está registrado
            Process check = new ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "SOS POS"
            ).start();
            check.waitFor();
            if (check.exitValue() == 0) return;

            // Registrar
            new ProcessBuilder(
                "reg", "add",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "SOS POS",
                "/t", "REG_SZ",
                "/d", "\"" + cmd + "\"",
                "/f"
            ).start().waitFor();
            System.out.println("[AUTOSTART] Registrado en inicio de Windows.");
        } catch (Exception e) {
            System.err.println("[AUTOSTART] No se pudo registrar: " + e.getMessage());
        }
    }

    // ── Sync background ──────────────────────────────────────────────────────

    private static void iniciarMonitorSync() {
        if (syncScheduler != null && !syncScheduler.isShutdown()) return;
        syncScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sos-sync");
            t.setDaemon(true);
            return t;
        });

        syncScheduler.scheduleAtFixedRate(() -> {
            try {
                SupabaseService.getInstance()
                    .sincronizarContingencias(DatabaseService.getInstance());
            } catch (Throwable t) {
                System.err.println("[SYNC-CONT] Error: " + t.getMessage());
            }
        }, 10, 30, TimeUnit.SECONDS);

        syncScheduler.scheduleAtFixedRate(() -> {
            try {
                com.sospos.model.UserSession session = com.sospos.model.UserSession.getInstance();
                if (!session.isLoggedIn()) return;
                SupabaseService.getInstance().syncFromSupabase(DatabaseService.getInstance());
                System.out.println("[SYNC-PROD] Productos actualizados en background.");
            } catch (Throwable t) {
                System.err.println("[SYNC-PROD] Error: " + t.getMessage());
            }
        }, 300, 3600, TimeUnit.SECONDS);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
