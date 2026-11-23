package com.galaxywatch.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;

final class WbCloud {
    static final String ACTIVE = "wb_cloud_active";
    static final String VERIFY = "wb_cloud_verify_on_watch";
    static final String SERIAL = "wb_cloud_controller_serial";
    static final String PROFILE = "wb_cloud_profile";
    static final String PREVIOUS_PROFILE = "wb_cloud_previous_profile";
    static final String IMPORT_ID = "wb_cloud_import_id";
    static final String PREVIOUS_IMPORT_ID = "wb_cloud_previous_import_id";
    static final String CONFIG_REVISION = "wb_cloud_config_revision";
    static final String STORAGE_NOTICE = "Часы не сохранили доступ. Повторяем";
    private static final String CLOUD_API = "https://wirenboard.cloud/api/v1/";
    private static final String PENDING_PROFILE = "wb-cloud.json";
    private static final String ONLINE_PREFIX = "wb-cloud-online-";
    private static final int MAX_PROFILE_BYTES = 8192;
    private static final int MAX_REDIRECTS = 5;

    static final class LoginRequired extends IOException {}
    static final class StorageUnavailable extends IOException {}
    static final class RejectedTunnel extends IOException {}

    record Profile(String serial, int port, String gatewayKey, String refresh) {
        Profile {
            if (!serial.matches("[A-Za-z0-9]{6,32}") || port < 1024 || port > 65535
                || !RemoteSettings.validToken(gatewayKey) || !validJwt(refresh)) throw new IllegalArgumentException();
        }

        String origin() { return "https://" + serial.toLowerCase(Locale.ROOT) + "-" + port + ".apps.wirenboard.cloud"; }

        JSONObject json() {
            return new JSONObject(Map.of("serial", serial, "port", port, "gatewayKey", gatewayKey, "refresh", refresh));
        }
    }

    interface ProfileWriter {
        void write(Profile profile) throws IOException;
    }

    static final class Session {
        Profile profile;
        String access;
        private boolean pending;

        Session(Profile profile) { this.profile = profile; }

        void rotate(Profile next, String nextAccess) {
            profile = next;
            access = nextAccess;
            pending = true;
        }

        void persist(ProfileWriter writer) throws IOException {
            if (!pending) return;
            writer.write(profile);
            pending = false;
        }
    }

    private final SharedPreferences settings;
    private final String importId;
    final String controllerSerial;
    private final Session session;
    private CookieManager serviceCookies;

    WbCloud(SharedPreferences settings) throws GeneralSecurityException, IOException, JSONException {
        this.settings = settings;
        this.importId = settings.getString(IMPORT_ID, "");
        session = new Session(parse(new JSONObject(RemoteSettings.unseal(PROFILE, settings.getString(PROFILE, "")))));
        controllerSerial = session.profile.serial();
        if (!controllerSerial.equals(settings.getString(SERIAL, ""))) settings.edit().putString(SERIAL, controllerSerial).apply();
    }

    boolean isCurrent(SharedPreferences preferences) {
        return preferences.contains(PROFILE) && importId.equals(preferences.getString(IMPORT_ID, ""));
    }

    static String importProfile(Context context, SharedPreferences settings) {
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
        File pending = new File(context.getNoBackupFilesDir(), PENDING_PROFILE);
        if (!pending.isFile()) return null;
        String error = null;
        try (FileInputStream input = new FileInputStream(pending)) {
            JSONObject object = new JSONObject(RemoteRoom.readResponse(input, MAX_PROFILE_BYTES));
            String id = object.getString("installId");
            if (!validImportId(id)) throw new IOException();
            if (!id.equals(settings.getString(IMPORT_ID, ""))) saveImportedProfile(settings, parse(object), id);
        } catch (IllegalArgumentException | IOException | GeneralSecurityException | JSONException failure) {
            error = "Настройте WB Cloud с компьютера";
        }
        try {
            Files.deleteIfExists(pending.toPath());
        } catch (IOException failure) {
            return "Не удалось завершить привязку";
        }
        return error;
    }

    private static void saveImportedProfile(SharedPreferences settings, Profile profile, String id)
        throws GeneralSecurityException, IOException {
        String sealed = seal(profile);
        synchronized (settings) {
            if (id.equals(settings.getString(IMPORT_ID, ""))) return;
            SharedPreferences.Editor editor = settings.edit();
            if (settings.contains(PROFILE) && !settings.contains(PREVIOUS_PROFILE)) {
                editor.putString(PREVIOUS_PROFILE, settings.getString(PROFILE, ""));
                editor.putString(PREVIOUS_IMPORT_ID, settings.getString(IMPORT_ID, ""));
            }
            if (!settings.contains(RoutePlan.AUTOMATIC) && !settings.getBoolean(RemoteSettings.ENABLED, false)) {
                editor.putBoolean(ACTIVE, true);
            }
            if (!editor.putString(PROFILE, sealed).putString(IMPORT_ID, id).putString(SERIAL, profile.serial())
                .putString(CONFIG_REVISION, UUID.randomUUID().toString())
                .putBoolean(VERIFY, true).putBoolean("demo", false).commit()) throw new IOException();
        }
    }

    static void restorePrevious(SharedPreferences settings) {
        synchronized (settings) {
            settings.edit().putString(PROFILE, settings.getString(PREVIOUS_PROFILE, ""))
                .putString(IMPORT_ID, settings.getString(PREVIOUS_IMPORT_ID, "")).remove(SERIAL)
                .putString(CONFIG_REVISION, UUID.randomUUID().toString())
                .putBoolean(VERIFY, true).putBoolean("demo", false)
                .remove(PREVIOUS_PROFILE).remove(PREVIOUS_IMPORT_ID).apply();
        }
    }

    static void confirmRead(Context context, SharedPreferences settings) {
        synchronized (settings) {
            String id = settings.getString(IMPORT_ID, "");
            if (!validImportId(id)) return;
            try {
                new File(context.getNoBackupFilesDir(), ONLINE_PREFIX + id).createNewFile();
                if (settings.contains(VERIFY) || settings.contains(PREVIOUS_PROFILE)) {
                    settings.edit().remove(VERIFY).remove(PREVIOUS_PROFILE).remove(PREVIOUS_IMPORT_ID).apply();
                }
            } catch (IOException failure) {
                // Without a receipt the PC must keep the prepared profile for a retry.
            }
        }
    }

    synchronized String gatewayKey() { return session.profile.gatewayKey(); }

    synchronized HttpsURLConnection open(String path, boolean reading, BooleanSupplier cancelled) throws IOException, JSONException {
        checkCancelled(cancelled);
        if (!isCurrent(settings)) throw new IOException("WB Cloud profile changed");
        session.persist(this::storeProfile);
        URI uri = URI.create(session.profile.origin() + path);
        if (serviceCookies == null || cookieValues(serviceCookies, uri).isEmpty()) {
            serviceCookies = null;
            if (!reading) throw new IOException();
            authorizeGateway(path, cancelled);
        }
        HttpsURLConnection connection = RemoteRoom.open(uri.toString(), "");
        applyCookies(serviceCookies, uri, connection);
        return connection;
    }

    synchronized IOException gatewayRejected(RemoteRoom.HttpFailure failure) {
        int status = failure.status;
        if ((status >= 300 && status < 400) || status == 401 || status == 403) {
            serviceCookies = null;
            return new RejectedTunnel();
        }
        return failure;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("Connection closed");
    }

    private void authorizeGateway(String path, BooleanSupplier cancelled) throws IOException, JSONException {
        if (session.access == null) refreshAccess();
        checkCancelled(cancelled);
        JSONObject link;
        try {
            link = issueTunnel();
        } catch (RemoteRoom.HttpFailure failure) {
            if (failure.status != 401) throw tunnelFailure(failure);
            checkCancelled(cancelled);
            refreshAccess();
            checkCancelled(cancelled);
            try {
                link = issueTunnel();
            } catch (RemoteRoom.HttpFailure retryFailure) {
                throw tunnelFailure(retryFailure);
            }
        }

        CookieManager cookies = serviceCookieJar();
        String origin = session.profile.origin();
        URI url = tunnelUri(origin, link.getString("tunnelRedirectUrl"));
        for (int step = 0; step < MAX_REDIRECTS; step++) {
            checkCancelled(cancelled);
            HttpsURLConnection connection = RemoteRoom.open(url.toString(), "");
            try {
                applyCookies(cookies, url, connection);
                int status = connection.getResponseCode();
                cookies.put(url, connection.getHeaderFields());
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException();
                    url = tunnelUri(origin, safeResolve(url, location).toString());
                } else {
                    URI readUrl = URI.create(origin + path);
                    boolean gatewayResponse = status == 200 && isJson(connection);
                    if (cookieValues(cookies, readUrl).isEmpty() || (!gatewayResponse
                        && !isGatewayChallenge(connection, status) && !isGatewayNotFound(connection, status))) throw new IOException();
                    serviceCookies = cookies;
                    return;
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException();
    }

    static boolean isGatewayChallenge(HttpsURLConnection connection, int status) throws IOException {
        return status == 401 && isGatewayError(connection, "unauthorized");
    }

    static boolean isGatewayNotFound(HttpsURLConnection connection, int status) throws IOException {
        return status == 404 && isGatewayError(connection, "not_found");
    }

    private static boolean isGatewayError(HttpsURLConnection connection, String code) throws IOException {
        if (!isJson(connection)) return false;
        try (InputStream error = connection.getErrorStream()) {
            if (error == null) return false;
            JSONObject body = new JSONObject(RemoteRoom.readResponse(error, 512));
            return body.length() == 1 && code.equals(body.opt("error"));
        } catch (JSONException invalid) {
            return false;
        }
    }

    private static boolean isJson(HttpsURLConnection connection) {
        String type = connection.getContentType();
        return type != null && "application/json".equalsIgnoreCase(type.split(";", 2)[0].trim());
    }

    private JSONObject issueTunnel() throws IOException, JSONException {
        Profile profile = session.profile;
        HttpsURLConnection request = RemoteRoom.open(CLOUD_API,
            "controllers/" + profile.serial() + "/tcp-tunnels/" + profile.port() + "/");
        try {
            request.setRequestProperty("Authorization", "Bearer " + session.access);
            return RemoteRoom.exchange(request, new JSONObject());
        } finally {
            request.disconnect();
        }
    }

    private void refreshAccess() throws IOException, JSONException {
        HttpsURLConnection request = RemoteRoom.open(CLOUD_API, "auth/token/refresh/");
        try {
            Profile profile = session.profile;
            JSONObject tokens = RemoteRoom.exchange(request, new JSONObject(Map.of("refresh", profile.refresh())));
            String nextAccess = tokens.getString("access");
            if (!validJwt(nextAccess)) throw new IOException();
            Profile next = new Profile(profile.serial(), profile.port(), profile.gatewayKey(),
                tokens.optString("refresh", profile.refresh()));
            session.rotate(next, nextAccess);
            session.persist(this::storeProfile);
        } catch (RemoteRoom.HttpFailure failure) {
            int status = failure.status;
            if (status == 400 || status == 401 || status == 403) throw new LoginRequired();
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new LoginRequired();
        } finally {
            request.disconnect();
        }
    }

    private void storeProfile(Profile profile) throws StorageUnavailable {
        try {
            String sealed = seal(profile);
            synchronized (settings) {
                String key = null;
                if (isCurrent(settings)) key = PROFILE;
                else if (importId.equals(settings.getString(PREVIOUS_IMPORT_ID, null))) key = PREVIOUS_PROFILE;
                if (key != null && settings.edit().putString(key, sealed).commit()) return;
            }
        } catch (GeneralSecurityException | IOException | IllegalArgumentException failure) {
            throw new StorageUnavailable();
        }
        throw new StorageUnavailable();
    }

    private static IOException tunnelFailure(RemoteRoom.HttpFailure failure) {
        return failure.status == 401 || failure.status == 403 ? new LoginRequired() : failure;
    }

    static CookieManager serviceCookieJar() {
        return new CookieManager(null, (uri, cookie) -> {
            if (!CookiePolicy.ACCEPT_ORIGINAL_SERVER.shouldAccept(uri, cookie)) return false;
            // Android treats Max-Age as version 1. WB Cloud expects plain name=value pairs.
            cookie.setVersion(0);
            return true;
        });
    }

    private static void applyCookies(CookieManager cookies, URI uri, HttpsURLConnection request) throws IOException {
        String header = String.join("; ", cookieValues(cookies, uri));
        if (!header.isEmpty()) request.setRequestProperty("Cookie", header);
    }

    private static List<String> cookieValues(CookieManager cookies, URI uri) throws IOException {
        return cookies.get(uri, Map.of()).getOrDefault("Cookie", List.of());
    }

    private static URI safeResolve(URI url, String value) throws IOException {
        try {
            return url.resolve(value);
        } catch (IllegalArgumentException malformed) {
            throw new IOException();
        }
    }

    static URI tunnelUri(String origin, String value) throws IOException {
        URI expected = URI.create(origin);
        URI actual = safeResolve(expected, value);
        if (!"https".equals(actual.getScheme()) || !expected.getHost().equalsIgnoreCase(actual.getHost())
            || (actual.getPort() != -1 && actual.getPort() != 443) || actual.getRawUserInfo() != null
            || actual.getRawFragment() != null) throw new IOException();
        return actual;
    }

    private static boolean validJwt(String value) {
        return value.length() <= 4096 && value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    }

    private static boolean validImportId(String value) {
        return value.matches("[a-f0-9]{32}");
    }

    private static Profile parse(JSONObject object) throws JSONException {
        return new Profile(object.getString("serial"), object.getInt("port"), object.getString("gatewayKey"),
            object.getString("refresh"));
    }

    private static String seal(Profile profile) throws GeneralSecurityException, IOException {
        return RemoteSettings.seal(PROFILE, profile.json().toString());
    }
}
