package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.db.SupabaseService;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.*;
import java.net.URL;

public class TrayManager {

    private static TrayIcon trayIcon;

    /**
     * Instala el ícono en la bandeja del sistema.
     * Llamar desde el hilo JavaFX después de Platform.setImplicitExit(false).
     */
    public static void install(Stage primaryStage) {
        if (!SystemTray.isSupported()) return;
        if (trayIcon != null) return; // ya instalado

        try {
            URL iconUrl = TrayManager.class.getResource("/com/sospos/images/pudu-logo-new.png");
            Image img = Toolkit.getDefaultToolkit()
                .getImage(iconUrl)
                .getScaledInstance(16, 16, Image.SCALE_SMOOTH);

            PopupMenu menu = new PopupMenu();

            MenuItem itemOpen = new MenuItem("Abrir Dashboard");
            itemOpen.addActionListener(e -> Platform.runLater(() -> {
                try {
                    App.showDashboard();
                    primaryStage.show();
                    primaryStage.toFront();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }));

            MenuItem itemSync = new MenuItem("Sincronizar ahora");
            itemSync.addActionListener(e -> new Thread(() -> {
                updateTooltip("SOS POS — Sincronizando...");
                try {
                    SupabaseService.getInstance()
                        .sincronizarContingencias(DatabaseService.getInstance());
                    updateTooltip("SOS POS — Online");
                    showMessage("SOS POS", "Sincronización completada.");
                } catch (Exception ex) {
                    updateTooltip("SOS POS — Error al sincronizar");
                }
            }, "tray-sync").start());

            MenuItem itemSalir = new MenuItem("Salir");
            itemSalir.addActionListener(e -> {
                remove();
                Platform.exit();
                System.exit(0);
            });

            menu.add(itemOpen);
            menu.add(itemSync);
            menu.addSeparator();
            menu.add(itemSalir);

            trayIcon = new TrayIcon(img, "SOS POS — Online", menu);
            trayIcon.setImageAutoSize(true);

            // Doble clic → abrir dashboard
            trayIcon.addActionListener(e -> itemOpen.getActionListeners()[0].actionPerformed(e));

            SystemTray.getSystemTray().add(trayIcon);

        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    public static void updateTooltip(String text) {
        if (trayIcon != null) trayIcon.setToolTip(text);
    }

    public static void showMessage(String title, String msg) {
        if (trayIcon != null)
            trayIcon.displayMessage(title, msg, TrayIcon.MessageType.INFO);
    }

    public static void remove() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    public static boolean isInstalled() {
        return trayIcon != null;
    }
}