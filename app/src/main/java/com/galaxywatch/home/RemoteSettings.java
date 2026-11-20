package com.galaxywatch.home;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class RemoteSettings {
    static final String ENABLED = "remote_enabled";
    static final String ORIGIN = "remote_origin";
    private static final String TOKEN = "remote_token";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "galaxywatch.home.remote";
    private static final String AES_GCM = "AES/GCM/NoPadding";

    record Config(String origin, String token) {}

    static String origin(String value) {
        try {
            URI parsed = new URI(value.trim());
            String path = parsed.getRawPath();
            int port = parsed.getPort();
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path)) || port == 0 || port > 65535) {
                throw new IllegalArgumentException();
            }
            return new URI("https", null, parsed.getHost(), port, null, null, null).toASCIIString();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    static boolean validToken(String value) {
        return value.matches("[A-Za-z0-9_-]{32,128}");
    }

    static Config load(SharedPreferences settings) throws GeneralSecurityException, IOException {
        String server = origin(settings.getString(ORIGIN, ""));
        String token = unseal(server, settings.getString(TOKEN, ""));
        if (!validToken(token)) throw new GeneralSecurityException();
        return new Config(server, token);
    }

    static void save(SharedPreferences settings, String server, String token)
        throws GeneralSecurityException, IOException {
        if (!validToken(token)) throw new IllegalArgumentException();
        settings.edit().putString(ORIGIN, server).putString(TOKEN, seal(server, token)).apply();
    }

    static String unseal(String purpose, String value) throws GeneralSecurityException, IOException {
        String[] encoded = value.split("\\.", -1);
        if (encoded.length != 2) throw new IllegalArgumentException();
        KeyStore keys = keyStore();
        SecretKey key = (SecretKey) keys.getKey(KEY_ALIAS, null);
        if (key == null) throw new GeneralSecurityException();
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, decode(encoded[0])));
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(decode(encoded[1])), StandardCharsets.UTF_8);
    }

    static synchronized String seal(String purpose, String value)
        throws GeneralSecurityException, IOException {
        KeyStore keys = keyStore();
        SecretKey key = (SecretKey) keys.getKey(KEY_ALIAS, null);
        if (key == null) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            key = generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        return encode(cipher.getIV()) + "." + encode(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }

    private static String encode(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
    private static byte[] decode(String text) { return Base64.decode(text, Base64.NO_WRAP); }
}
