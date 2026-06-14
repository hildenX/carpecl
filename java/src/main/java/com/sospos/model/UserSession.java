package com.sospos.model;

/**
 * Singleton que guarda la sesión del usuario autenticado.
 * Se llena al hacer login con Supabase Auth.
 */
public class UserSession {

    private static UserSession instance;

    private String userId;       // UUID del usuario en Supabase
    private String accessToken;  // JWT para llamadas autenticadas
    private String email;
    private String nombreNegocio;
    private String cajaId;       // UUID de la caja seleccionada

    private UserSession() {}

    public static UserSession getInstance() {
        if (instance == null) instance = new UserSession();
        return instance;
    }

    public boolean isLoggedIn() {
        return userId != null && !userId.isEmpty();
    }

    public void setFrom(String userId, String accessToken, String email, String nombreNegocio) {
        this.userId       = userId;
        this.accessToken  = accessToken;
        this.email        = email;
        this.nombreNegocio = nombreNegocio;
    }

    public void clear() {
        userId = null; accessToken = null; email = null; nombreNegocio = null; cajaId = null;
    }

    public String getUserId()        { return userId; }
    public String getAccessToken()   { return accessToken; }
    public String getEmail()         { return email; }
    public String getNombreNegocio() { return nombreNegocio; }
    public String getCajaId()        { return cajaId; }
    public void setCajaId(String v)  { cajaId = v; }
}
