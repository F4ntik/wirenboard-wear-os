package com.galaxywatch.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.json.JSONException;

public final class HomeActivity extends Activity {
    private static final String FROM_TILE = "from_tile";
    private static final String HOME_FROM_TILE = "home_from_tile";
    private static final String CHECK_CLOUD = "verify_wb_cloud";
    private static final int BG = 0xFF171118;
    private static final int SURFACE = 0xFF302631;
    private static final int TEXT = 0xFFFFF1F2;
    private static final int MUTED = 0xFFCEBFCA;
    private static final int PETAL = 0xFFFFD9E5;
    private static final int PLUM = 0xFF593448;
    private static final int ROSE = 0xFFEDBED0;
    private static final int AMBER = 0xFFFFD3A1;
    private static final long FIRST_RETRY_MS = 1000;
    private static final long MAX_RETRY_MS = 30_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reconnect = this::connectRoom;
    private final Runnable checkHome = this::probeHome;
    private final Runnable verificationTimeout = this::expireVerification;
    private RoutePlan routes;
    private HomeProbe homeProbe;
    private Route route;
    private final Map<Rooms.Light, Boolean> states = new HashMap<>();
    private final Map<Rooms.Light, Boolean> demoStates = new HashMap<>();
    private record Command(boolean target, View feedback) {}

    private final Map<Rooms.Light, Command> pending = new HashMap<>();
    private final Map<Rooms.Light, Button> controls = new HashMap<>();

    private SharedPreferences settings;
    private Rooms.Room room;
    private HomeLink connection;
    private static WbCloud cloud;
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
        routes = new RoutePlan(settings.getBoolean(RoutePlan.LAST_CLOUD, false));
        String setupError = WbCloud.importProfile(this, settings);
        installerCheck = getIntent().getBooleanExtra(CHECK_CLOUD, false) || settings.getBoolean(WbCloud.VERIFY, false);
        demo = settings.getBoolean("demo", true);
        String roomId = saved == null ? getIntent().getStringExtra(Rooms.EXTRA) : saved.getString(Rooms.EXTRA);
        Rooms.Room selected = Rooms.find(roomId);
        fromTile = selected != null && (saved == null || saved.getBoolean(FROM_TILE));
        homeFromTile = saved == null ? Rooms.TILE_HOME.equals(roomId) : saved.getBoolean(HOME_FROM_TILE);
        if (selected == null) showHome();
        else showRoom(selected);
        if (setupError != null) {
            notice = setupError;
            updateRoom();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        installerCheck = intent.getBooleanExtra(CHECK_CLOUD, false);
        String entry = intent.getStringExtra(Rooms.EXTRA);
        Rooms.Room selected = Rooms.find(entry);
        fromTile = selected != null;
        homeFromTile = Rooms.TILE_HOME.equals(entry);
        if (selected == null) showHome();
        else showRoom(selected);
    }

    @Override protected void onStart() {
        super.onStart();
        screenActive = true;
        if (remoteSettingsVisible && pairing) showRemoteSettings();
        else connectRoom();
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
        out.putBoolean(FROM_TILE, fromTile);
        out.putBoolean(HOME_FROM_TILE, homeFromTile);
    }

    @Override public void onBackPressed() {
        if (room != null) backToRooms();
        else if (remoteSettingsVisible) showSettings();
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
        stopRoom();
        room = null;
        fromTile = false;
        renderHome();
        connectRoom();
    }

    private void renderHome() {
        page("Дом", true);
        controls.clear();
        addStatus();
        for (int first = 0; first < Rooms.ALL.size(); first += 2) addRoomRow(first);
        addButton("Подключение", this::showSettings, R.drawable.ic_connection);
        groupOff = addEdgeAction(this::confirmAllOff);
        updateRoom();
    }

    private void addRoomRow(int first) {
        LinearLayout row = new LinearLayout(this);
        for (Rooms.Room item : Rooms.ALL.subList(first, Math.min(first + 2, Rooms.ALL.size()))) {
            Button card = button(item.label().replace("-", "-\n"), () -> showRoom(item));
            card.setContentDescription(item.label());
            card.setTextSize(14);
            card.setPadding(dp(4), dp(4), dp(4), dp(4));
            card.setTextColor(PETAL);
            RoomIcons style = RoomIcons.forRoom(item.id());
            card.setBackground(buttonBackground(style.surface()));
            setIcon(card, style.icon(), true);
            LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
            if (row.getChildCount() > 0) cardLayout.leftMargin = dp(6);
            row.addView(card, cardLayout);
        }
        LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() == 1) {
            int pageWidth = dp(getResources().getConfiguration().screenWidthDp);
            rowLayout.width = (pageWidth - body.getPaddingLeft() - body.getPaddingRight() - dp(6)) / 2;
        }
        rowLayout.topMargin = dp(6);
        body.addView(row, rowLayout);
    }

    private void showRoom(Rooms.Room selected) {
        stopRoom();
        room = selected;
        page(selected.label(), true);
        addStatus();
        controls.clear();
        for (Rooms.Light light : selected.lights()) {
            Button button = addButton(light.label(), () -> changeLight(light));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setClipToOutline(light.artwork() != 0);
            button.setPaddingRelative(dp(12), dp(4), dp(12), dp(4));
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

    private void showSettings() {
        verifyUntil = 0;
        main.removeCallbacks(verificationTimeout);
        installerCheck = false;
        stopRoom();
        room = null;
        page("Подключение");
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
            routes.reset();
            showSettings();
        });
        body.addView(mode);
        if (auto) body.addView(description(hasCloud ? "Сначала дома, затем WB Cloud"
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
                cloud = null;
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
            routes.reset();
            demo = false;
            showHome();
        }, R.drawable.ic_connection);
        if (!auto) addButton(manualRoute() == Route.HTTPS ? "✓ Свой HTTPS" : "Свой HTTPS",
            this::showRemoteSettings, R.drawable.ic_action_internet);
        if (!demo || !address().isEmpty() || settings.getBoolean(RemoteSettings.ENABLED, false)) {
            addButton(demo ? "Выйти из демо" : "Открыть демо", () -> {
                demo = !demo;
                settings.edit().putBoolean("demo", demo).apply();
                showHome();
            }, R.drawable.ic_action_demo);
        }
        addButton("Назад", this::showHome, R.drawable.ic_action_back);
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

    private String address() {
        return settings.getString("address", "");
    }

    private boolean automatic() {
        return settings.getBoolean(RoutePlan.AUTOMATIC, !settings.getBoolean(RemoteSettings.ENABLED, false));
    }

    private Route manualRoute() {
        if (settings.getBoolean(WbCloud.ACTIVE, false)) return Route.CLOUD;
        return settings.getBoolean(RemoteSettings.ENABLED, false) ? Route.HTTPS : Route.HOME;
    }

    private WbCloud cloudProfile() throws GeneralSecurityException, IOException, JSONException {
        if (cloud == null || !cloud.isCurrent(settings)) cloud = new WbCloud(settings);
        return cloud;
    }

    private void connectRoom() {
        if (!screenActive || settingsVisible || isFinishing() || connection != null) return;
        boolean hasCloud = settings.contains(WbCloud.PROFILE);
        long now = SystemClock.elapsedRealtime();
        if (installerCheck) verifyUntil = now + 20_000;
        checkingCloud = hasCloud && now < verifyUntil;
        main.removeCallbacks(verificationTimeout);
        if (checkingCloud) main.postDelayed(verificationTimeout, verifyUntil - now);
        installerCheck = false;
        if (demo && !checkingCloud) {
            for (Rooms.Light light : lights()) states.put(light, demoStates.getOrDefault(light, false));
            updateRoom();
            return;
        }

        Route selected = routes.select(automatic(), manualRoute(), !address().isEmpty(), hasCloud, checkingCloud);
        if (automatic() && selected == Route.HOME && hasCloud) routes.attemptingHome();
        route = selected;
        int current = session;
        connecting = true;
        HomeLink.Events events = new HomeLink.Events() {
            @Override public void connected() {
                post(current, () -> {
                    if (checkingCloud && SystemClock.elapsedRealtime() >= verifyUntil) {
                        expireVerification();
                        return;
                    }
                    if (connection instanceof MqttRoom mqtt && !mqtt.isReady()) return;
                    connecting = false;
                    online = true;
                    retryDelay = FIRST_RETRY_MS;
                    rememberRoute(selected);
                    if (!"Нет подтверждения".equals(notice)) notice = null;
                    if (selected == Route.CLOUD) {
                        verifyUntil = 0;
                        main.removeCallbacks(verificationTimeout);
                        WbCloud.confirmRead(HomeActivity.this, settings);
                        if (resumeManualAfterCheck()) return;
                        checkingCloud = false;
                        if (automatic() && !address().isEmpty()) scheduleProbe(routes.homeDelay(SystemClock.elapsedRealtime()));
                    }
                    updateRoom();
                });
            }

            @Override public void state(String topic, Boolean value, boolean retained) {
                post(current, () -> receive(topic, value, retained));
            }

            @Override public void disconnected() {
                post(current, HomeActivity.this::connectionFailed);
            }

            @Override public void storageUnavailable() {
                post(current, () -> {
                    notice = WbCloud.STORAGE_NOTICE;
                    connectionFailed();
                });
            }

            @Override public void authenticationFailed() {
                post(current, () -> {
                    verifyUntil = 0;
                    main.removeCallbacks(verificationTimeout);
                    notice = selected == Route.CLOUD
                        ? "Проверьте вход в WB Cloud с компьютера" : "Сервер не принял ключ";
                    if (resumeManualAfterCheck()) return;
                    if (automatic()) connectionFailed();
                    else {
                        resetConnection();
                        updateRoom();
                    }
                });
            }

            @Override public void commandUnconfirmed() {
                post(current, () -> {
                    notice = "Нет подтверждения";
                    resetConnection();
                    connectRoom();
                });
            }
        };
        try {
            connection = switch (selected) {
                case HOME -> automatic()
                    ? new MqttRoom(address(), lights(), events, hasCloud ? localSerial() : null, routes.homeTimeout(hasCloud, now))
                    : new MqttRoom(address(), lights(), events);
                case CLOUD -> new RemoteRoom(cloudProfile(), lights(), events);
                case HTTPS -> new RemoteRoom(RemoteSettings.load(settings), lights(), events);
            };
        } catch (IllegalArgumentException | GeneralSecurityException | IOException | JSONException failure) {
            notice = switch (selected) {
                case HOME -> "Проверьте привязку или выберите «Вручную»";
                case CLOUD -> "Проверьте привязку WB Cloud";
                case HTTPS -> "Проверьте удалённый доступ";
            };
            if (resumeManualAfterCheck()) return;
            if (automatic()) connectionFailed();
            else {
                resetConnection();
                updateRoom();
            }
            return;
        }
        new Thread(connection::connect, "home-connect").start();
        if (selected == Route.CLOUD && automatic() && !checkingCloud && !address().isEmpty()) {
            scheduleProbe(routes.homeDelay(now));
        }
        updateRoom();
    }

    private void rememberRoute(Route verified) {
        if (!automatic()) return;
        routes.confirmed(verified);
        boolean usedCloud = verified == Route.CLOUD;
        if (settings.getBoolean(RoutePlan.LAST_CLOUD, false) != usedCloud) {
            settings.edit().putBoolean(RoutePlan.LAST_CLOUD, usedCloud).apply();
        }
    }

    private String localSerial() throws IOException, GeneralSecurityException, JSONException {
        String saved = settings.getString(WbCloud.SERIAL, "");
        return saved.isEmpty() ? cloudProfile().controllerSerial : saved;
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
        main.removeCallbacks(reconnect);
        retryDelay = FIRST_RETRY_MS;
        notice = null;
        resetConnection();
    }

    private void resetConnection() {
        session++;
        main.removeCallbacks(reconnect);
        main.removeCallbacks(checkHome);
        closeProbe();
        HomeLink old = connection;
        connection = null;
        route = null;
        checkingCloud = false;
        if (old != null) old.close();
        connecting = false;
        online = false;
        vacation = null;
        refreshNeeded = false;
        pending.clear();
        groupCommand = null;
        states.clear();
    }

    private void connectionFailed() {
        long now = SystemClock.elapsedRealtime();
        boolean retryVerification = checkingCloud && now < verifyUntil;
        if (!retryVerification && resumeManualAfterCheck()) return;
        if (groupCommand != null || !pending.isEmpty()) notice = "Нет подтверждения";
        long delay = retryDelay;
        if (automatic() && route == Route.HOME) {
            routes.homeFailed(now);
            if (settings.contains(WbCloud.PROFILE)) delay = 0;
        } else if (automatic() && route == Route.CLOUD && !retryVerification) {
            routes.cloudFailed(now);
        }
        resetConnection();
        updateRoom();
        main.postDelayed(reconnect, delay);
        retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
    }

    private void scheduleProbe(long delay) {
        main.removeCallbacks(checkHome);
        main.postDelayed(checkHome, delay);
    }

    private void closeProbe() {
        if (homeProbe != null) homeProbe.client.close();
        homeProbe = null;
    }

    private void probeHome() {
        if (!screenActive || checkingCloud || route != Route.CLOUD || !automatic() || homeProbe != null) return;
        homeProbe = new HomeProbe();
        new Thread(homeProbe.client::connect, "home-mqtt-probe").start();
    }

    private final class HomeProbe implements HomeLink.Events {
        final MqttRoom client = new MqttRoom(address(), lights(), this, cloud.controllerSerial);
        final Map<String, Boolean> snapshot = new HashMap<>();

        @Override public void connected() {
            main.post(() -> {
                if (homeProbe != this) return;
                if (!client.isReady()) return;
                if (!pending.isEmpty() || groupCommand != null) {
                    closeProbe();
                    scheduleProbe(FIRST_RETRY_MS);
                    return;
                }
                // Hand over the already verified MQTT session and its states together.
                homeProbe = null;
                resetConnection();
                connection = client;
                route = Route.HOME;
                vacation = snapshot.get(Rooms.VACATION);
                for (Rooms.Light light : lights()) states.put(light, snapshot.get(light.stateTopic()));
                online = true;
                retryDelay = FIRST_RETRY_MS;
                if (!"Нет подтверждения".equals(notice)) notice = null;
                rememberRoute(Route.HOME);
                updateRoom();
            });
        }

        @Override public void disconnected() {
            main.post(() -> {
                if (connection == client) connectionFailed();
                else if (homeProbe == this) {
                    closeProbe();
                    routes.homeFailed(SystemClock.elapsedRealtime());
                    scheduleProbe(RoutePlan.RECHECK_MS);
                }
            });
        }
        public void authenticationFailed() { disconnected(); }
        public void commandUnconfirmed() { disconnected(); }
        public void state(String topic, Boolean value, boolean retained) {
            main.post(() -> {
                if (connection == client) receive(topic, value, retained);
                else if (homeProbe == this) snapshot.put(topic, value);
            });
        }
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
                    if (command != groupCommand) {
                        command.feedback().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    }
                }
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
            demoStates.put(light, next);
            receive(light.stateTopic(), next, false);
            controls.get(light).performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            return;
        }

        notice = null;
        Command command = new Command(next, controls.get(light));
        pending.put(light, command);
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
                demoStates.put(light, false);
                states.put(light, false);
            }
            groupOff.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            updateRoom();
            return;
        }

        groupCommand = new Command(false, groupOff);
        for (Rooms.Light light : lights()) {
            if (!Boolean.FALSE.equals(states.get(light))) pending.put(light, groupCommand);
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
                return true;
            });
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
        String channel = route == null || demo ? "" : online ? route.label
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
                icon = lit ? R.drawable.ic_light_on : R.drawable.ic_light;
                state = lit ? R.string.light_state_on : R.string.light_state_off;
            }
            button.setEnabled(canChangeLight(light));
            CharSequence description = getText(state);
            if (description.equals(button.getStateDescription())) continue;
            int surface = lit ? PLUM : SURFACE;
            button.setBackground(light.artwork() == 0 ? buttonBackground(surface) : illustratedBackground(surface, light.artwork()));
            button.setTextColor(lit ? PETAL : TEXT);
            setIcon(button, icon, false);
            button.setCompoundDrawableTintList(ColorStateList.valueOf(lit ? PETAL : MUTED));
            button.setStateDescription(description);
        }
        String offLabel = "Погасить всё";
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
        settingsVisible = false;
        remoteSettingsVisible = false;
        pairing = false;
        confirming = false;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);
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
        SwipeDismissFrameLayout frame = new SwipeDismissFrameLayout(this);
        frame.setSwipeable(true);
        frame.addView(scroll);
        frame.addCallback(new SwipeDismissFrameLayout.Callback() {
            @Override public void onDismissed(SwipeDismissFrameLayout layout) {
                if (layout == scroll.getParent()) onBackPressed();
            }
        });
        setContentView(frame);
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
        Button button = addButton("Погасить всё", action);
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

    private RippleDrawable illustratedBackground(int surface, int artwork) {
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(surface);
        pill.setCornerRadius(dp(24));
        int translucent = (surface & 0x00FFFFFF) | 0x20000000;
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{surface, surface, translucent});
        LayerDrawable layers = new LayerDrawable(new Drawable[]{pill, getDrawable(artwork), shade});
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
