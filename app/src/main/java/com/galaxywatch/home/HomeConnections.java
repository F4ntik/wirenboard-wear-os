package com.galaxywatch.home;

import android.content.SharedPreferences;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class HomeConnections {
    static final boolean DEFAULT_DEMO = true;
    static final String DEFAULT_ADDRESS = "";
    private static final Map<String, WbCloud> clouds = new HashMap<>();
    private static SharedPreferences cloudSettings;

    private HomeConnections() {}

    static String address(SharedPreferences settings) {
        return settings.getString("address", DEFAULT_ADDRESS);
    }

    static boolean automatic(SharedPreferences settings) {
        return settings.getBoolean(RoutePlan.AUTOMATIC, !settings.getBoolean(RemoteSettings.ENABLED, false));
    }

    static RoutePlan.Route manualRoute(SharedPreferences settings) {
        if (settings.getBoolean(WbCloud.ACTIVE, false)) return RoutePlan.Route.CLOUD;
        return settings.getBoolean(RemoteSettings.ENABLED, false) ? RoutePlan.Route.HTTPS : RoutePlan.Route.HOME;
    }

    static HomeLink open(SharedPreferences settings, RoutePlan.Route route, List<Rooms.Light> lights,
                         HomeLink.Events events) throws GeneralSecurityException, IOException, JSONException {
        return switch (route) {
            case HOME -> automatic(settings)
                ? new MqttRoom(address(settings), lights, events, localSerial(settings))
                : new MqttRoom(address(settings), lights, events);
            case CLOUD -> new RemoteRoom(cloudProfile(settings), lights, events);
            case HTTPS -> new RemoteRoom(RemoteSettings.load(settings), lights, events);
        };
    }

    private static String localSerial(SharedPreferences settings)
        throws GeneralSecurityException, IOException, JSONException {
        if (!settings.contains(WbCloud.PROFILE)) return null;
        String saved = settings.getString(WbCloud.SERIAL, "");
        return saved.isEmpty() ? cloudProfile(settings).controllerSerial : saved;
    }

    private static synchronized WbCloud cloudProfile(SharedPreferences settings)
        throws GeneralSecurityException, IOException, JSONException {
        synchronized (settings) {
            if (cloudSettings != settings) {
                clouds.clear();
                cloudSettings = settings;
            }
            String current = settings.getString(WbCloud.IMPORT_ID, "");
            String previous = settings.getString(WbCloud.PREVIOUS_IMPORT_ID, null);
            clouds.keySet().removeIf(id -> !id.equals(current) && !id.equals(previous));
            WbCloud cloud = clouds.get(current);
            if (cloud == null) {
                cloud = new WbCloud(settings);
                clouds.put(current, cloud);
            }
            return cloud;
        }
    }
}
