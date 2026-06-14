package com.sospos;

import javafx.fxml.FXML;
import javafx.scene.control.*;

public class LoginController {

    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    @FXML
    public void initialize() {
        errorLabel.setVisible(false);
    }

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
        } catch (Exception e) {
            errorLabel.setText("Error al cargar el sistema.");
            errorLabel.setVisible(true);
            e.printStackTrace();
        }
    }
}
