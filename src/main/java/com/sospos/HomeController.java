package com.sospos;

import com.sospos.db.DatabaseService;
import com.sospos.db.SupabaseService;
import com.sospos.model.UserSession;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;

public class HomeController {

    @FXML private TextField     emailField;
    @FXML private PasswordField passwordField;
    @FXML private Button        btnLogin;
    @FXML private Button        btnOffline;
    @FXML private Label         errorLabel;
    @FXML private Label         sessionLabel;

    private final DatabaseService db   = DatabaseService.getInstance();
    private final SupabaseService supa = SupabaseService.getInstance();

    @FXML
    public void initialize() {
        // Si hay sesión guardada localmente, pre-llenar y mostrar botón offline
        String savedUserId  = db.getConfig("user_id");
        String savedEmail   = db.getConfig("email");
        String savedNombre  = db.getConfig("nombre_negocio");

        if (savedUserId != null && !savedUserId.isBlank()) {
            // Cargar sesión en memoria (incluyendo token persistido)
            String savedToken        = db.getConfig("access_token");
            String savedRefreshToken = db.getConfig("refresh_token");
            UserSession.getInstance().setFrom(savedUserId, savedToken, savedEmail, savedNombre);

            // Refrescar access_token silenciosamente en background
            if (savedRefreshToken != null && !savedRefreshToken.isBlank()) {
                new Thread(() -> {
                    SupabaseService.LoginResult r = supa.refreshSession(savedRefreshToken);
                    if (r.ok) {
                        db.saveSession(r.userId, r.email, savedNombre, r.accessToken);
                        db.setConfigValue("refresh_token", r.refreshToken);
                        Platform.runLater(() ->
                            UserSession.getInstance().setFrom(r.userId, r.accessToken, r.email, savedNombre));
                    }
                }, "token-refresh").start();
            }

            // Mostrar info y botón offline
            if (savedEmail != null) emailField.setText(savedEmail);
            String nombre = (savedNombre != null && !savedNombre.isBlank())
                    ? savedNombre : savedEmail;
            if (sessionLabel != null) {
                sessionLabel.setText("Sesión guardada: " + nombre);
                sessionLabel.setVisible(true);
                sessionLabel.setManaged(true);
            }
            if (btnOffline != null) {
                btnOffline.setText("Continuar como " + nombre + "  →");
                btnOffline.setVisible(true);
                btnOffline.setManaged(true);
            }
        }
    }

    // ─── Login con Supabase Auth ──────────────────────────────────────────────

    @FXML
    void onLogin() {
        String email    = emailField.getText().trim();
        String password = passwordField.getText();

        if (email.isEmpty() || password.isEmpty()) {
            setError("Ingresa tu correo y contraseña.");
            return;
        }

        setError("");
        setLoading(true, "Ingresando...");

        Task<SupabaseService.LoginResult> loginTask = new Task<>() {
            @Override protected SupabaseService.LoginResult call() {
                return supa.login(email, password);
            }
        };

        loginTask.setOnSucceeded(e -> {
            SupabaseService.LoginResult result = loginTask.getValue();
            if (result.ok) {
                // Guardar sesión en SQLite (incluye refresh_token para renovación automática)
                db.saveSession(result.userId, result.email, result.nombreNegocio, result.accessToken);
                db.setConfigValue("refresh_token", result.refreshToken);
                setLoading(true, "Descargando productos...");

                // Sync productos en segundo plano → luego ir al dashboard
                Task<SupabaseService.SyncResult> syncTask = new Task<>() {
                    @Override protected SupabaseService.SyncResult call() {
                        return supa.syncFromSupabase(db);
                    }
                };
                syncTask.setOnSucceeded(se -> routeAfterLogin());
                syncTask.setOnFailed(se   -> routeAfterLogin());
                new Thread(syncTask, "sync-on-login").start();
            } else {
                setLoading(false, "Ingresar");
                setError(result.error != null ? result.error : "Credenciales incorrectas.");
            }
        });

        loginTask.setOnFailed(e -> {
            setLoading(false, "Ingresar");
            setError("Error de conexión. Intenta de nuevo.");
        });

        new Thread(loginTask, "login-task").start();
    }

    // ─── Continuar sin conexión (sesión previa guardada) ─────────────────────

    @FXML
    void onOffline() {
        goToDashboard();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void routeAfterLogin() {
        // Tras login siempre mostramos el dashboard para que el usuario
        // tenga contexto visual de su sesión. La sincronización online sigue
        // ejecutándose en background desde App.iniciarMonitorSync().
        Platform.runLater(this::goToDashboard);
    }

    private void goToDashboard() {
        try { App.showDashboard(); } catch (Exception e) { e.printStackTrace(); }
    }

    private void setError(String msg) {
        if (errorLabel != null) errorLabel.setText(msg);
    }

    private void setLoading(boolean loading, String btnText) {
        btnLogin.setDisable(loading);
        btnLogin.setText(btnText);
    }
}
