package com.galaxywatch.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.galaxywatch.home.RoutePlan.Route;
import androidx.wear.widget.SwipeDismissFrameLayout;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.json.JSONException;

public final class HomeActivity extends Activity {
    private static final String FROM_TILE = "from_tile";
    private static final String HOME_FROM_TILE = "home_from_tile";
    static final String LEGACY_SETTINGS = "legacy_connection_settings";
    private static final String CHECK_CLOUD = "verify_wb_cloud";
    private static final int BG = 0xFF171118;
    private static final int SURFACE = 0xFF302631;
    private static final int TEXT = 0xFFFFF1F2;
    private static final int MUTED = 0xFFCEBFCA;
    private static final int PETAL = 0xFFFFD9E5;
    private static final int LIT_SURFACE = 0xFF674423;
    private static final int LIT_OUTLINE = 0xFFFFD06B;
    private static final int LIT_TEXT = 0xFFFFF3DC;
    private static final int ROSE = 0xFFEDBED0;
    private static final int AMBER = 0xFFFFD3A1;
    private static final long FIRST_RETRY_MS = 1000;
    private static final long MAX_RETRY_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reconnect = this::connectRoom;
    private final Runnable checkHome = this::probeHome;
    private final Runnable verificationTimeout = this::expireVerification;
    private RoutePlan routes = new RoutePlan();
    private final Map<Route, Attempt> attempts = new EnumMap<>(Route.class);
    private Route route;
    private final Map<Rooms.Light, Boolean> states = new HashMap<>();
    private record Command(boolean target, View feedback) {}

    private final Map<Rooms.Light, Command> pending = new HashMap<>();
    private final Map<Rooms.Light, Button> controls = new HashMap<>();

    private SharedPreferences settings;
    private Rooms.Room room;
    private HomeLink connection;
    private String sessionScope;
    private final Map<Rooms.Light, Boolean> readingsToSave = new HashMap<>();
    private final Runnable saveReadings = this::flushReadings;
    private boolean onlyLit;
    private RoomGrid roomGrid;
    private Bitmap homePreview;
    private int homeScroll;
    private Runnable settingsBack;
    private Boolean vacation;
    private boolean online;
    private boolean connecting;
    private boolean screenActive;
    private boolean demo;
    private boolean installerCheck;
    private long verifyUntil;
    private boolean checkingCloud;
    private boolean fromTile;
    private boolean homeFromTile;
    private boolean settingsVisible;
    private boolean remoteSettingsVisible;
    private boolean pairing;
    private boolean confirming;
    private boolean refreshNeeded;
    private long retryDelay = FIRST_RETRY_MS;
    private int session;
    private String notice;
    private LinearLayout body;
    private TextView status;
    private TextView routeLabel;
    private Button groupOff;
    private Command groupCommand;
    private ScrollView scroll;
    private int confirmScroll;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = getSharedPreferences("home", MODE_PRIVATE);
        onlyLit = saved != null && saved.getBoolean("only_lit");
        String setupError = WbCloud.importProfile(this, settings);
        installerCheck = getIntent().getBooleanExtra(CHECK_CLOUD, false) || settings.getBoolean(WbCloud.VERIFY, false);
        demo = settings.getBoolean("demo", HomeConnections.DEFAULT_DEMO);
        CatalogLink.configureBundledHome(this);
        if (routeToCatalog(getIntent())) return;
        String roomId = saved == null ? getIntent().getStringExtra(Rooms.EXTRA) : saved.getString(Rooms.EXTRA);
        Rooms.Room selected = Rooms.find(roomId);
        fromTile = selected != null && (saved == null || saved.getBoolean(FROM_TILE));
        homeFromTile = saved == null ? Rooms.TILE_HOME.equals(roomId) : saved.getBoolean(HOME_FROM_TILE);
        if (getIntent().getBooleanExtra(LEGACY_SETTINGS, false)) {
            installerCheck = false;
            showSettings();
        }
        else if (selected == null) showHome();
        else showRoom(selected);
        if (setupError != null) {
            notice = setupError;
            updateRoom();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        homePreview = null;
        homeScroll = 0;
        onlyLit = false;
        installerCheck = intent.getBooleanExtra(CHECK_CLOUD, false);
        if (routeToCatalog(intent)) return;
        String entry = intent.getStringExtra(Rooms.EXTRA);
        Rooms.Room selected = Rooms.find(entry);
        fromTile = selected != null;
        homeFromTile = Rooms.TILE_HOME.equals(entry);
        if (selected == null) showHome();
        else showRoom(selected);
    }

    private boolean routeToCatalog(Intent intent) {
        if (intent.getBooleanExtra(LEGACY_SETTINGS, false)) return false;
        boolean firstUse = HomeConnections.DEFAULT_DEMO && !settings.contains("demo") && address().isEmpty()
            && !settings.contains(WbCloud.PROFILE);
        if (!CatalogLink.paired(this) && !firstUse && !installerCheck) return false;
        if (!CatalogLink.paired(this) && intent.getBooleanExtra("legacy_verify", false)) return false;
        Intent catalog = new Intent(this, CatalogActivity.class);
        catalog.putExtra(Rooms.EXTRA, intent.getStringExtra(Rooms.EXTRA));
        catalog.putExtra("verify_cloud", installerCheck);
        startActivity(catalog);
        finish();
        return true;
    }

    @Override protected void onStart() {
        super.onStart();
        screenActive = true;
        if (remoteSettingsVisible && pairing) showRemoteSettings();
        else connectRoom();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        for (Button button : controls.values()) {
            if (hasFocus) startLightAnimation(button);
            else stopLightAnimation(button);
        }
    }

    @Override protected void onStop() {
        screenActive = false;
        main.removeCallbacks(verificationTimeout);
        stopRoom();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (room != null) out.putString(Rooms.EXTRA, room.id());
        out.putBoolean("only_lit", onlyLit);
        out.putBoolean(FROM_TILE, fromTile);
        out.putBoolean(HOME_FROM_TILE, homeFromTile);
    }

    @Override public void onBackPressed() {
        if (room != null) backToRooms();
        else if (remoteSettingsVisible) showSettings();
        else if (settingsVisible && settingsBack != null) settingsBack.run();
        else if (settingsVisible) showHome();
        else if (confirming) closeConfirmation();
        else if (homeFromTile) finish();
        else super.onBackPressed();
    }

    private void backToRooms() {
        if (fromTile) finish();
        else showHome();
    }

    private void showHome() {
        if (getIntent().getBooleanExtra(LEGACY_SETTINGS, false) && CatalogLink.paired(this)) {
            finish();
            return;
        }
        if (CatalogLink.paired(this) && !installerCheck) {
            startActivity(new Intent(this, CatalogActivity.class));
            finish();
            return;
        }
        stopRoom();
        RoomCache.requestUpdate(this);
        room = null;
        fromTile = false;
        renderHome();
        connectRoom();
    }

    private void renderHome() {
        homePreview = null;
        page("Дом", true);
        SwipePage.restoreScroll(scroll, homeScroll);
        homeScroll = 0;
        controls.clear();
        addStatus();
        RoomFilter filter = new RoomFilter(this, onlyLit, selected -> {
            onlyLit = selected;
            updateRoom();
        });
        body.addView(filter, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        roomGrid = new RoomGrid(this, RoomOverview.favorites(settings), this::showRoom);
        body.addView(roomGrid);
        BezelHelp.showOnce(scroll);
        addButton("Настройки", this::showPreferences, R.drawable.ic_connection);
        groupOff = addEdgeAction(this::confirmAllOff);
        updateRoom();
    }

    private void showRoom(Rooms.Room selected) {
        if (!fromTile && roomGrid != null) {
            homePreview = SwipePage.capture(scroll);
            homeScroll = scroll.getScrollY();
        }
        stopRoom();
        room = selected;
        page(selected.label(), true);
        addStatus();
        controls.clear();
        for (Rooms.Light light : selected.lights()) {
            Button button = addButton(light.label(), () -> changeLight(light));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setClipToOutline(true);
            button.setPaddingRelative(dp(10), dp(4), dp(10), dp(4));
            controls.put(light, button);
        }
        groupOff = addEdgeAction(this::switchOff);
        updateRoom();
        connectRoom();
    }

    private void confirmAllOff() {
        if (!canSwitchOff()) return;
        confirmScroll = scroll.getScrollY();
        page("Выключить\nвесь свет?").setTextSize(20);
        body.setPadding(body.getPaddingLeft(), dp(17), body.getPaddingRight(), dp(17));
        confirming = true;
        addStatus();
        groupOff = addButton("Выключить", () -> {
            closeConfirmation();
            switchOff();
        }, R.drawable.ic_power);
        addButton("Отмена", this::closeConfirmation, R.drawable.ic_action_close);
        updateRoom();
    }

    private void closeConfirmation() {
        renderHome();
        ScrollView home = scroll;
        home.post(() -> home.scrollTo(0, confirmScroll));
    }

    private void showPreferences() {
        verifyUntil = 0;
        main.removeCallbacks(verificationTimeout);
        installerCheck = false;
        stopRoom();
        room = null;
        page("Настройки");
        settingsVisible = true;
        settingsBack = this::showHome;
        status = null;
        groupOff = null;
        addButton("Подключить дом", () -> startActivity(new Intent(this, CatalogActivity.class)), R.drawable.ic_action_link);
        addButton("Избранные комнаты", this::showFavorites, R.drawable.ic_room_grid);
        if (!HomeConnections.DEFAULT_DEMO || !demo || !address().isEmpty() || settings.contains(WbCloud.PROFILE)) {
            addButton("Подключение", this::showSettings, R.drawable.ic_connection);
        }
        body.addView(description(BezelHelp.GUIDE, Gravity.CENTER));
        addButton("Назад", settingsBack, R.drawable.ic_action_back);
    }

    private void showFavorites() {
        page("На плитке");
        settingsVisible = true;
        settingsBack = this::showPreferences;
        List<Rooms.Room> favorites = RoomOverview.favorites(settings);
        for (int i = 0; i < favorites.size(); i++) {
            int slot = i;
            Rooms.Room favorite = favorites.get(i);
            addButton((i + 1) + ". " + favorite.label(), () -> chooseFavorite(slot), RoomIcons.forRoom(favorite.id()).icon());
        }
        addButton("Готово", this::showHome);
    }

    private void chooseFavorite(int slot) {
        page("Место " + (slot + 1));
        settingsVisible = true;
        settingsBack = this::showFavorites;
        List<Rooms.Room> favorites = RoomOverview.favorites(settings);
        for (Rooms.Room choice : Rooms.ALL) {
            String label = (favorites.get(slot).equals(choice) ? "✓ " : "") + choice.label();
            addButton(label, () -> {
                RoomOverview.saveFavorites(settings, RoomOverview.replaceSlot(favorites, slot, choice));
                RoomCache.requestUpdate(this);
                showFavorites();
            }, RoomIcons.forRoom(choice.id()).icon());
        }
        addButton("Назад", settingsBack, R.drawable.ic_action_back);
    }

    private void showSettings() {
        verifyUntil = 0;
        main.removeCallbacks(verificationTimeout);
        installerCheck = false;
        stopRoom();
        room = null;
        page("Подключение");
        settingsBack = getIntent().getBooleanExtra(LEGACY_SETTINGS, false) ? this::finish : this::showPreferences;
        status = null;
        groupOff = null;
        settingsVisible = true;
        boolean auto = automatic();
        boolean hasCloud = settings.contains(WbCloud.PROFILE);
        Switch mode = new Switch(this);
        mode.setText(auto ? "Автоматически" : "Вручную");
        mode.setTextColor(TEXT);
        mode.setTextSize(15);
        mode.setMinHeight(dp(48));
        mode.setChecked(auto);
        mode.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean(RoutePlan.AUTOMATIC, checked).apply();
            showSettings();
        });
        body.addView(mode);
        if (auto) body.addView(description(hasCloud ? "Домашняя сеть и WB Cloud"
            : "Для связи вне дома привяжите WB Cloud с компьютера", Gravity.CENTER));
        if (!auto && hasCloud) {
            addButton(manualRoute() == Route.CLOUD ? "✓ WB Cloud" : "WB Cloud", () -> {
                settings.edit().putBoolean(WbCloud.ACTIVE, true).putBoolean(RemoteSettings.ENABLED, false)
                    .putBoolean("demo", false).apply();
                demo = false;
                showHome();
            }, R.drawable.ic_action_internet);
        }
        if (settings.contains(WbCloud.PREVIOUS_PROFILE)) {
            addButton("Прежний доступ", () -> {
                WbCloud.restorePrevious(settings);
                installerCheck = true;
                demo = false;
                showHome();
            }, R.drawable.ic_action_back);
        }
        body.addView(description("IP или IP:порт Wiren Board", Gravity.CENTER));
        EditText address = new EditText(this);
        address.setSingleLine();
        address.setTextSize(15);
        address.setTextColor(TEXT);
        address.setInputType(InputType.TYPE_CLASS_PHONE);
        address.setKeyListener(DigitsKeyListener.getInstance("0123456789.:"));
        address.setText(address());
        address.setContentDescription("Локальный IP Wiren Board");
        body.addView(address);
        addButton(auto ? "Готово" : manualRoute() == Route.HOME ? "✓ Домашняя сеть" : "Домашняя сеть", () -> {
            String value = address.getText().toString().trim();
            try {
                if (!auto || !hasCloud || !value.isEmpty()) MqttRoom.brokerUri(value);
            } catch (IllegalArgumentException invalid) {
                address.setError("Укажите локальный IPv4");
                return;
            }
            SharedPreferences.Editor editor = settings.edit().putString("address", value).putBoolean("demo", false);
            if (!auto) editor.putBoolean(RemoteSettings.ENABLED, false).putBoolean(WbCloud.ACTIVE, false);
            editor.apply();
            demo = false;
            showHome();
        }, R.drawable.ic_connection);
        if (!auto) addButton(manualRoute() == Route.HTTPS ? "✓ Свой HTTPS" : "Свой HTTPS",
            this::showRemoteSettings, R.drawable.ic_action_internet);
        if (!CatalogLink.paired(this) && (!HomeConnections.DEFAULT_DEMO || !demo || !address().isEmpty() || settings.getBoolean(RemoteSettings.ENABLED, false))) {
            addButton(demo ? "Выйти из демо" : "Открыть демо", () -> {
                demo = !demo;
                settings.edit().putBoolean("demo", demo).apply();
                showHome();
            }, R.drawable.ic_action_demo);
        }
        addButton("Назад", this::showPreferences, R.drawable.ic_action_back);
    }

    private void showRemoteSettings() {
        page("Из интернета");
        settingsVisible = true;
        remoteSettingsVisible = true;
        EditText server = remoteField("Адрес HTTPS", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(settings.getString(RemoteSettings.ORIGIN, ""));
        boolean savedKey = settings.contains(RemoteSettings.ORIGIN);
        EditText code = remoteField(savedKey ? "Код для нового подключения" : "8 цифр со шлюза", InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        code.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        TextView feedback = description("", Gravity.CENTER);
        feedback.setVisibility(View.GONE);
        body.addView(feedback);
        addButton("Подключиться", () -> pairRemote(server, code, feedback), R.drawable.ic_action_link);
        addButton("Назад", this::showSettings, R.drawable.ic_action_back);
    }

    private void pairRemote(EditText server, EditText code, TextView feedback) {
        if (!code.isEnabled()) return;
        String origin;
        try {
            origin = RemoteSettings.origin(server.getText().toString());
        } catch (IllegalArgumentException invalid) {
            server.setError("Нужен HTTPS-адрес сервера");
            return;
        }
        String digits = code.getText().toString();
        if (digits.isEmpty()) {
            try {
                if (origin.equals(RemoteSettings.load(settings).origin())) {
                    useRemote();
                    return;
                }
            } catch (IllegalArgumentException | GeneralSecurityException | IOException unavailable) {
                // A new pairing code is required when no credential can be read.
            }
        }
        if (!digits.matches("[0-9]{8}")) {
            code.setError("Введите 8 цифр кода");
            return;
        }
        server.setEnabled(false);
        code.setEnabled(false);
        pairing = true;
        feedback.setText("Подключаю часы…");
        feedback.setVisibility(View.VISIBLE);
        int current = session;
        new Thread(() -> {
            try {
                String key = RemoteRoom.pair(origin, digits);
                post(current, () -> {
                    try {
                        RemoteSettings.save(settings, origin, key);
                        useRemote();
                    } catch (IllegalArgumentException | GeneralSecurityException | IOException failure) {
                        pairingFailed(server, code, feedback, "Не удалось сохранить доступ");
                    }
                });
            } catch (RemoteRoom.HttpFailure rejected) {
                String message = switch (rejected.status) {
                    case 403 -> "Неверный код";
                    case 410, 429 -> "Нужен новый одноразовый код";
                    default -> "Сервер не принял подключение";
                };
                post(current, () -> pairingFailed(server, code, feedback, message));
            } catch (IOException | JSONException failure) {
                post(current, () -> pairingFailed(server, code, feedback, "Сервер недоступен"));
            }
        }, "home-pair").start();
    }

    private void useRemote() {
        settings.edit().putBoolean(RoutePlan.AUTOMATIC, false).putBoolean(RemoteSettings.ENABLED, true).putBoolean(WbCloud.ACTIVE, false)
            .putBoolean("demo", false).apply();
        demo = false;
        showHome();
    }

    private void pairingFailed(EditText server, EditText code, TextView feedback, String message) {
        pairing = false;
        server.setEnabled(true);
        code.setEnabled(true);
        feedback.setText(message);
    }

    private EditText remoteField(String label, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setTextColor(TEXT);
        field.setTextSize(15);
        field.setHint(label);
        field.setHintTextColor(MUTED);
        field.setInputType(inputType);
        body.addView(field, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return field;
    }

    private String address() { return HomeConnections.address(settings); }

    private boolean automatic() { return HomeConnections.automatic(settings); }

    private Route manualRoute() { return HomeConnections.manualRoute(settings); }

    private void connectRoom() {
        if (!screenActive || settingsVisible || isFinishing() || connection != null || !attempts.isEmpty()) return;
        boolean hasCloud = settings.contains(WbCloud.PROFILE);
        long now = SystemClock.elapsedRealtime();
        if (installerCheck) verifyUntil = now + 20_000;
        checkingCloud = hasCloud && now < verifyUntil;
        main.removeCallbacks(verificationTimeout);
        if (checkingCloud) main.postDelayed(verificationTimeout, verifyUntil - now);
        installerCheck = false;
        if (demo && !checkingCloud) {
            Map<Rooms.Light, Boolean> saved = RoomCache.read(this).statesAt(System.currentTimeMillis());
            for (Rooms.Light light : lights()) states.put(light, saved.getOrDefault(light, false));
            updateRoom();
            return;
        }

        List<Route> choices = RoutePlan.candidates(automatic(), manualRoute(), !address().isEmpty(), hasCloud, checkingCloud);
        route = choices.size() == 1 ? choices.get(0) : null;
        connecting = true;
        for (Route choice : choices) {
            Attempt attempt = new Attempt(choice);
            attempts.put(choice, attempt);
            long delay = choices.size() > 1 ? routes.retryIn(choice, now) : 0;
            if (delay == 0) attempt.run();
            else main.postDelayed(attempt, delay);
        }
        updateRoom();
    }

    // Callbacks from both transports are arbitrated on the main thread. Only the winner can send commands.
    private final class Attempt implements HomeLink.Events, Runnable {
        final Route channel;
        final String source = RoomCache.scope(HomeActivity.this);
        final Map<String, Boolean> snapshot = new HashMap<>();
        long backoff = FIRST_RETRY_MS;
        HomeLink client;
        boolean ready;
        boolean waitingForSnapshot;
        final Runnable snapshotTimeout = () -> failed(snapshot.get(Rooms.VACATION) == null
            ? "Нет данных о режиме «Отпуск»" : "Нет данных о лампах", false);

        Attempt(Route channel) { this.channel = channel; }

        @Override public void run() {
            if (attempts.get(channel) != this) return;
            try {
                client = HomeConnections.open(settings, channel, lights(), this);
                new Thread(client::connect, "home-connect-" + channel.name()).start();
                if (!online) connecting = true;
                updateRoom();
            } catch (IllegalArgumentException | GeneralSecurityException | IOException | JSONException failure) {
                String message = switch (channel) {
                    case HOME -> "Проверьте привязку или выберите «Вручную»";
                    case CLOUD -> "Проверьте привязку WB Cloud";
                    case HTTPS -> "Проверьте удалённый доступ";
                };
                main.post(() -> failed(message, true));
            }
        }

        void close() {
            main.removeCallbacks(this);
            main.removeCallbacks(snapshotTimeout);
            if (client != null) client.close();
        }

        @Override public void state(String topic, Boolean value, boolean retained) {
            main.post(() -> {
                if (client != null && connection == client) receive(topic, value, retained);
                else if (attempts.get(channel) == this) {
                    // RemoteRoom emits vacation first in each complete cloud snapshot.
                    if (channel == Route.CLOUD && Rooms.VACATION.equals(topic)) snapshot.clear();
                    snapshot.put(topic, value);
                    if (ready) accept();
                }
            });
        }

        @Override public void connected() {
            main.post(() -> {
                ready = true;
                accept();
            });
        }

        private void accept() {
            if (attempts.get(channel) != this || !client.isReady()) return;
            if (checkingCloud && SystemClock.elapsedRealtime() >= verifyUntil) {
                expireVerification();
                return;
            }
            if (channel == Route.CLOUD && attempts.size() > 1
                && (snapshot.get(Rooms.VACATION) == null || lights().stream().noneMatch(light -> snapshot.get(light.stateTopic()) != null))) {
                if (!waitingForSnapshot) {
                    waitingForSnapshot = true;
                    main.postDelayed(snapshotTimeout, 5000);
                }
                return;
            }
            main.removeCallbacks(snapshotTimeout);
            if (online && (!pending.isEmpty() || groupCommand != null)) {
                attempts.remove(channel);
                close();
                scheduleProbe(FIRST_RETRY_MS);
                return;
            }
            attempts.remove(channel);
            closeAttempts();
            if (connection != null) {
                session++;
                connection.close();
            }
            flushReadings();
            sessionScope = source;
            connection = client;
            route = channel;
            states.clear();
            vacation = snapshot.get(Rooms.VACATION);
            for (Rooms.Light light : lights()) {
                if (snapshot.containsKey(light.stateTopic())) states.put(light, snapshot.get(light.stateTopic()));
            }
            snapshot.clear();
            if (!demo) RoomCache.save(HomeActivity.this, sessionScope, states, System.currentTimeMillis());
            connecting = false;
            online = true;
            routes.connected(SystemClock.elapsedRealtime());
            retryDelay = FIRST_RETRY_MS;
            if (!"Нет подтверждения".equals(notice)) notice = null;
            if (channel == Route.CLOUD) {
                verifyUntil = 0;
                main.removeCallbacks(verificationTimeout);
                WbCloud.confirmRead(HomeActivity.this, settings);
                if (resumeManualAfterCheck()) return;
                checkingCloud = false;
                if (automatic() && !address().isEmpty()) scheduleProbe(RoutePlan.RECHECK_MS);
            }
            updateRoom();
        }

        @Override public void disconnected() { main.post(() -> failed(null, false)); }
        @Override public void storageUnavailable() { main.post(() -> failed(WbCloud.STORAGE_NOTICE, false)); }
        @Override public void authenticationFailed() {
            main.post(() -> failed(channel == Route.CLOUD ? "Проверьте вход в WB Cloud с компьютера" : "Сервер не принял ключ", true));
        }

        private void failed(String message, boolean rejected) {
            boolean active = client != null && connection == client;
            if (!active && attempts.get(channel) != this) return;
            if (!active && online) {
                // The return-home probe failed. The working cloud connection stays open.
                attempts.remove(channel);
                close();
                scheduleProbe(RoutePlan.RECHECK_MS);
                return;
            }
            if (message != null && !"Нет подтверждения".equals(notice)) notice = message;
            if (!active && attempts.size() > 1) {
                close();
                Attempt next = new Attempt(channel);
                next.backoff = Math.min(backoff * 2, MAX_RETRY_MS);
                attempts.put(channel, next);
                main.postDelayed(next, backoff);
                connecting = attempts.values().stream().anyMatch(attempt -> attempt.client != null);
                updateRoom();
                return;
            }
            if (rejected) {
                verifyUntil = 0;
                main.removeCallbacks(verificationTimeout);
                if (resumeManualAfterCheck()) return;
                if (!automatic()) {
                    resetConnection();
                    updateRoom();
                    return;
                }
            }
            connectionFailed();
        }

        @Override public void commandUnconfirmed() {
            main.post(() -> {
                if (client == null || connection != client) return;
                notice = "Нет подтверждения";
                resetConnection();
                connectRoom();
            });
        }
    }

    private void flushReadings() {
        main.removeCallbacks(saveReadings);
        if (!readingsToSave.isEmpty() && sessionScope != null) {
            RoomCache.save(this, sessionScope, readingsToSave, System.currentTimeMillis());
        }
        readingsToSave.clear();
    }

    private void cacheReading(Rooms.Light light, Boolean value) {
        if (sessionScope == null || demo) return;
        boolean first = readingsToSave.isEmpty();
        readingsToSave.put(light, value);
        if (first) main.post(saveReadings);
    }

    private boolean resumeManualAfterCheck() {
        if (!checkingCloud || (!demo && (automatic() || manualRoute() == Route.CLOUD))) return false;
        verifyUntil = 0;
        main.removeCallbacks(verificationTimeout);
        resetConnection();
        connectRoom();
        return true;
    }

    private void expireVerification() {
        if (verifyUntil == 0 || !screenActive || settingsVisible) return;
        verifyUntil = 0;
        resetConnection();
        connectRoom();
    }

    private void stopRoom() {
        for (Button button : controls.values()) stopLightAnimation(button);
        routes = new RoutePlan();
        main.removeCallbacks(reconnect);
        retryDelay = FIRST_RETRY_MS;
        notice = null;
        resetConnection();
    }

    private void resetConnection() {
        flushReadings();
        sessionScope = null;
        session++;
        main.removeCallbacks(reconnect);
        main.removeCallbacks(checkHome);
        closeAttempts();
        HomeLink old = connection;
        connection = null;
        route = null;
        checkingCloud = false;
        if (old != null) old.close();
        connecting = false;
        online = false;
        vacation = null;
        refreshNeeded = false;
        if (!pending.isEmpty()) TileRead.commandsPending(false);
        pending.clear();
        groupCommand = null;
        states.clear();
    }

    private void connectionFailed() {
        if (checkingCloud && SystemClock.elapsedRealtime() >= verifyUntil && resumeManualAfterCheck()) return;
        if (groupCommand != null || !pending.isEmpty()) notice = "Нет подтверждения";
        boolean raceAgain = online && automatic() && settings.contains(WbCloud.PROFILE) && !address().isEmpty();
        if (raceAgain) routes.lost(route, SystemClock.elapsedRealtime());
        resetConnection();
        updateRoom();
        main.postDelayed(reconnect, raceAgain ? 0 : retryDelay);
        retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
    }

    private void scheduleProbe(long delay) {
        main.removeCallbacks(checkHome);
        main.postDelayed(checkHome, delay);
    }

    private void closeAttempts() {
        for (Attempt attempt : attempts.values()) attempt.close();
        attempts.clear();
    }

    private void probeHome() {
        if (!screenActive || !online || route != Route.CLOUD || !automatic() || !attempts.isEmpty()) return;
        Attempt attempt = new Attempt(Route.HOME);
        attempts.put(Route.HOME, attempt);
        attempt.run();
    }

    private void refreshWhenIdle() {
        if (!refreshNeeded || !pending.isEmpty()) return;
        resetConnection();
        connectRoom();
    }

    private void post(int current, Runnable action) {
        main.post(() -> {
            if (session == current) action.run();
        });
    }

    private void receive(String topic, Boolean value, boolean retained) {
        if (Rooms.VACATION.equals(topic)) {
            vacation = value;
        } else {
            for (Rooms.Light light : lights()) {
                if (!light.stateTopic().equals(topic)) continue;
                states.put(light, value);
                Command command = pending.get(light);
                if (!retained && value != null && command != null && value == command.target()) {
                    pending.remove(light);
                    if (pending.isEmpty()) TileRead.commandsPending(false);
                    if (command != groupCommand) {
                        command.feedback().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    }
                }
                if (!pending.containsKey(light)) cacheReading(light, value);
                break;
            }
        }
        if (groupCommand != null && pending.isEmpty() && allOff()) {
            groupCommand.feedback().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            groupCommand = null;
        }
        updateRoom();
        refreshWhenIdle();
    }

    private boolean canSend() {
        return demo || online && Boolean.FALSE.equals(vacation);
    }

    private List<Rooms.Light> lights() {
        return room == null ? Rooms.MASTER_LIGHTS : room.lights();
    }

    private boolean canSwitchOff() {
        return canSend() && groupCommand == null && pending.isEmpty() && !allOff();
    }

    private boolean canChangeLight(Rooms.Light light) {
        return canSend() && groupCommand == null && states.get(light) != null && !pending.containsKey(light)
            && (!(connection instanceof RemoteRoom) || pending.isEmpty());
    }

    private boolean allOff() {
        return lights().stream().allMatch(light -> Boolean.FALSE.equals(states.get(light)));
    }

    private void changeLight(Rooms.Light light) {
        Boolean state = states.get(light);
        if (!canChangeLight(light)) return;
        boolean next = !state;
        if (demo) {
            RoomCache.saveDemo(this, java.util.Collections.singletonMap(light, next));
            receive(light.stateTopic(), next, false);
            controls.get(light).performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            return;
        }

        notice = null;
        Command command = new Command(next, controls.get(light));
        TileRead.commandsPending(true);
        pending.put(light, command);
        cacheReading(light, null);
        try {
            connection.send(light, next);
            awaitConfirmation(command);
        } catch (MqttException failure) {
            connectionFailed();
            return;
        }
        updateRoom();
    }

    private void switchOff() {
        if (!canSwitchOff()) return;
        notice = null;
        if (demo) {
            for (Rooms.Light light : lights()) {
                states.put(light, false);
            }
            RoomCache.saveDemo(this, states);
            groupOff.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            updateRoom();
            return;
        }

        TileRead.commandsPending(true);
        groupCommand = new Command(false, groupOff);
        for (Rooms.Light light : lights()) {
            if (!Boolean.FALSE.equals(states.get(light))) {
                pending.put(light, groupCommand);
                cacheReading(light, null);
            }
        }
        try {
            if (room == null) connection.sendAllOff();
            else connection.sendOff(pending.keySet());
            awaitConfirmation(groupCommand);
        } catch (MqttException failure) {
            connectionFailed();
            return;
        }
        updateRoom();
    }

    private void awaitConfirmation(Command command) {
        int current = session;
        int timeout = connection.confirmationTimeoutMs();
        main.postDelayed(() -> {
            if (current != session) return;
            boolean timedOut = pending.entrySet().removeIf(item -> {
                if (item.getValue() != command) return false;
                states.remove(item.getKey());
                cacheReading(item.getKey(), null);
                return true;
            });
            if (pending.isEmpty()) TileRead.commandsPending(false);
            if (command == groupCommand) groupCommand = null;
            else if (!timedOut) return;
            notice = "Нет подтверждения";
            refreshNeeded = true;
            updateRoom();
            refreshWhenIdle();
        }, timeout);
    }

    private void updateRoom() {
        if (status == null) return;
        String text;
        if (demo) text = "";
        else if (Boolean.TRUE.equals(vacation)) text = "Включён режим «Отпуск»";
        else if (notice != null) text = notice;
        else if (connecting) text = "Подключение…";
        else if (!online) text = "Нет связи с домом";
        else if (vacation == null) text = "Статус «Отпуска» неизвестен";
        else text = "";
        String channel = demo ? "" : route == null ? (connecting ? "Проверяем связь…" : "") : online ? route.label
            : route == Route.HOME ? "Ищем дом…" : "Подключаемся · " + route.label;
        if (!channel.contentEquals(routeLabel.getText())) routeLabel.setText(channel);
        routeLabel.setVisibility(channel.isEmpty() ? View.GONE : View.VISIBLE);
        if (connecting && !channel.isEmpty() && notice == null) text = "";
        if (!text.contentEquals(status.getText())) status.setText(text);
        status.setTextColor(demo || connecting && notice == null ? MUTED : AMBER);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);

        for (Map.Entry<Rooms.Light, Button> control : controls.entrySet()) {
            Rooms.Light light = control.getKey();
            Button button = control.getValue();
            Boolean value = states.get(light);
            boolean waiting = pending.containsKey(light);
            boolean lit = !waiting && Boolean.TRUE.equals(value);
            int icon;
            int state;
            if (waiting) {
                icon = R.drawable.ic_light_waiting;
                state = R.string.light_state_pending;
            } else if (value == null) {
                icon = R.drawable.ic_light_unknown;
                state = R.string.light_state_unknown;
            } else {
                icon = lit ? R.drawable.ic_light_glow : R.drawable.ic_light;
                state = lit ? R.string.light_state_on : R.string.light_state_off;
            }
            button.setEnabled(canChangeLight(light));
            CharSequence description = getText(state);
            if (description.equals(button.getStateDescription())) continue;
            button.setBackground(lightBackground(light.artwork(), lit));
            button.setTextColor(lit ? LIT_TEXT : TEXT);
            setLightIcon(button, icon, lit);
            button.setStateDescription(description);
        }
        if (roomGrid != null) {
            Map<Rooms.Light, Boolean> confirmed = states;
            if (!pending.isEmpty()) {
                confirmed = new HashMap<>(states);
                for (Rooms.Light light : pending.keySet()) confirmed.remove(light);
            }
            roomGrid.display(confirmed, onlyLit);
        }
        String offLabel = room == null ? "Погасить весь дом" : "Погасить здесь";
        boolean off = allOff();
        if (confirming) offLabel = "Выключить";
        else if (groupCommand != null) offLabel = room == null ? "Выключаю свет…" : "Выключаю…";
        else if (off) offLabel = "Всё выключено";
        groupOff.setText(offLabel);
        boolean action = confirming || !off;
        int offColor = action ? BG : MUTED;
        int fill = action ? ROSE : SURFACE;
        if (!confirming && getResources().getConfiguration().isScreenRound()) {
            Drawable edge = getDrawable(R.drawable.edge_action).mutate();
            edge.setTint(fill);
            groupOff.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), edge, null));
        } else groupOff.setBackground(buttonBackground(fill));
        groupOff.setTextColor(offColor);
        groupOff.setCompoundDrawableTintList(ColorStateList.valueOf(offColor));
        groupOff.setEnabled(canSwitchOff());
        groupOff.setAlpha(groupOff.isEnabled() ? 1f : 0.68f);
    }

    private TextView page(String title) {
        return page(title, false);
    }

    private TextView page(String title, boolean floral) {
        roomGrid = null;
        settingsBack = null;
        settingsVisible = false;
        remoteSettingsVisible = false;
        pairing = false;
        confirming = false;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(floral);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(floral);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int width = getResources().getConfiguration().screenWidthDp;
        int side = floral ? width * 8 / 100 : Math.max(20, width * 12 / 100);
        int vertical = Math.max(24, width * 14 / 100);
        body.setPadding(dp(side), dp(floral ? width * 3 / 100 : vertical), dp(side), dp(floral ? 3 : vertical));
        scroll.addView(body);
        scroll.setFocusable(true);
        if (floral) scroll.setFocusableInTouchMode(true);
        SwipeDismissFrameLayout frame = new SwipeDismissFrameLayout(this);
        frame.setSwipeable(true);
        frame.addView(scroll);
        frame.addCallback(new SwipeDismissFrameLayout.Callback() {
            @Override public void onDismissed(SwipeDismissFrameLayout layout) {
                if (!isFinishing() && layout == scroll.getParent()) {
                    if (room != null && fromTile) SwipePage.finish(HomeActivity.this, layout);
                    else onBackPressed();
                }
            }
        });
        setContentView(SwipePage.withPreview(frame, room != null ? homePreview : null));
        scroll.requestFocus();
        TextView header = description(title, Gravity.CENTER);
        header.setTextColor(TEXT);
        header.setTextSize(22);
        header.setTypeface(floral ? Typeface.SERIF : Typeface.DEFAULT, floral ? Typeface.NORMAL : Typeface.BOLD);
        header.setAccessibilityHeading(true);
        if (floral) {
            ImageView flowers = new ImageView(this);
            flowers.setImageResource(room == null ? R.drawable.blossoms : RoomIcons.forRoom(room.id()).crown());
            flowers.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            body.addView(flowers, new LinearLayout.LayoutParams(dp(64), dp(15)));
            boolean longTitle = room != null && title.contains("-");
            header.setTextSize(longTitle ? 17 : 21);
            if (longTitle && (width < 200 || getResources().getConfiguration().fontScale > 1.1f)) {
                header.setText(title.replace("-", "-\n"));
                header.setContentDescription(title);
            }
            header.setPadding(0, 0, 0, 0);
            header.setIncludeFontPadding(false);
        }
        body.addView(header);
        return header;
    }

    private Button addEdgeAction(Runnable action) {
        body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        Button button = addButton(room == null ? "Погасить весь дом" : "Погасить здесь", action);
        button.setTextSize(14);
        button.setPadding(dp(15), dp(2), dp(15), dp(12));
        return button;
    }

    private void addStatus() {
        routeLabel = description("", Gravity.CENTER);
        routeLabel.setTextSize(12);
        body.addView(routeLabel);
        status = description("", Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
    }

    private TextView description(String value, int gravity) {
        TextView view = new TextView(this);
        view.setGravity(gravity);
        view.setText(value);
        view.setTextColor(MUTED);
        view.setTextSize(13);
        view.setPadding(0, dp(1), 0, dp(5));
        return view;
    }

    private Button addButton(String label, Runnable action) {
        Button button = button(label, action);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(room == null ? 5 : 3);
        body.addView(button, layout);
        return button;
    }

    private Button addButton(String label, Runnable action, int iconId) {
        Button button = addButton(label, action);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setIcon(button, iconId, false);
        return button;
    }

    private void setIcon(Button button, int iconId, boolean above) {
        Drawable icon = getDrawable(iconId).mutate();
        int size = dp(above ? 21 : 18);
        icon.setBounds(0, 0, size, size);
        button.setCompoundDrawablesRelative(above ? null : icon, above ? icon : null, null, null);
        button.setCompoundDrawablePadding(dp(above ? 2 : 6));
        button.setCompoundDrawableTintList(above ? null : button.getTextColors());
    }

    private void startLightAnimation(Button button) {
        if (screenActive && hasWindowFocus() && button.isShown() && ValueAnimator.areAnimatorsEnabled()
            && button.getCompoundDrawablesRelative()[0] instanceof Animatable animation && !animation.isRunning()) {
            animation.start();
        }
    }

    private void stopLightAnimation(Button button) {
        if (button.getCompoundDrawablesRelative()[0] instanceof Animatable animation) animation.stop();
    }

    private void setLightIcon(Button button, int iconId, boolean lit) {
        stopLightAnimation(button);
        Drawable icon = getDrawable(iconId).mutate();
        if (!lit) icon = new InsetDrawable(icon, dp(3));
        button.setCompoundDrawableTintList(lit ? null : ColorStateList.valueOf(MUTED));
        int size = dp(27);
        icon.setBounds(0, 0, size, size);
        button.setCompoundDrawablesRelative(icon, null, null, null);
        button.setCompoundDrawablePadding(dp(5));
        if (lit) startLightAnimation(button);
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setBackground(buttonBackground(SURFACE));
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private RippleDrawable lightBackground(int artwork, boolean lit) {
        int surface = lit ? LIT_SURFACE : SURFACE;
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(surface);
        pill.setCornerRadius(dp(24));
        List<Drawable> content = new ArrayList<>();
        content.add(pill);
        if (artwork != 0) {
            content.add(getDrawable(artwork));
            int translucent = (surface & 0x00FFFFFF) | 0x20000000;
            content.add(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{surface, surface, translucent}));
        }
        if (lit) {
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(android.graphics.Color.TRANSPARENT);
            outline.setCornerRadius(dp(24));
            outline.setStroke(dp(2.25f), LIT_OUTLINE);
            content.add(outline);
        }
        LayerDrawable layers = new LayerDrawable(content.toArray(new Drawable[0]));
        layers.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
        return new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), layers, null);
    }

    private RippleDrawable buttonBackground(int color) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(24));
        return new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), fill, null);
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
