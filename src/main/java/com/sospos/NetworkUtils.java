package com.sospos;

import java.net.HttpURLConnection;
import java.net.URL;

public class NetworkUtils {

    private static final String TEST_URL = "https://supabase.com";
    private static final int TIMEOUT_MS = 3000;

    /** Returns true if the machine can reach the internet. */
    public static boolean isOnline() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(TEST_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("HEAD");
            conn.connect();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code < 500;
        } catch (Exception e) {
            return false;
        }
    }
}