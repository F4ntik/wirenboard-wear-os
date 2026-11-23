package com.galaxywatch.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.wear.widget.SwipeDismissFrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.galaxywatch.home.CatalogViews.BG;
import static com.galaxywatch.home.CatalogViews.MUTED;
import static com.galaxywatch.home.CatalogViews.ROSE;
import static com.galaxywatch.home.CatalogViews.TEXT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Wear controls for the controller pinned by the owner. All command state comes from readback. */
public final class CatalogActivity extends Activity {
    private static final long POLL_MS = 5_000;
    private static final long RETRY_MS = 9_000;
    private static final long FRESH_MS = 22_000;
    private static final long NOTICE_MS = 15_000;
    private static final String SETUP_HINT = "На Wiren Board откройте «Интеграции → Пульт света Wear OS» и добавьте комнаты и лампы.";
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "wear-catalog-app"));
    // Accessed on the main thread. A command still occupies its slot if the screen is dismissed.
    private static boolean commanding;
    private static final int[] PRESETS = {0xFFFFF4DF, 0xFFFFA53B, 0xFFFF3737, 0xFF46DC72, 0xFF417BFF, 0xFFC968FF};
    private static final String[] COLOR_NAMES = {"Белый", "Янтарный", "Красный", "Зелёный", "Синий", "Сиреневый"};

    private enum Page { SETUP, APPROVE, HOME, ROOM, BRIGHTNESS, COLOR, SETTINGS, FAVORITES, PICK_FAVORITE, OFF, FORGET }
    private record RoomRow(String title, List<WatchCatalog.Room> rooms) {}
    private record LightControls(CatalogViews.LightButton power, Button brightness, Button color) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::read;
    private final CatalogViews views = new CatalogViews(this);
    private final Map<String, CatalogViews.RoomCard> cards = new HashMap<>();
    private final Map<String, LightControls> lights = new HashMap<>();
    private List<RoomRow> shownRows = List.of();
    private SharedPreferences prefs;
    private WatchCatalog home;
    private WatchCatalog discovered;
    private CatalogLink.Result latest;
    private Future<?> readFuture;
    private long readGeneration;
    private long observed;
    private long noticeUntil;
    private Set<String> pending = Set.of();
    private boolean foreground, onlyLit, direct, verify, setupBusy, customColor;
    private String roomId, lightId, commandNotice, setupNotice, address, confirmRevision;
    private int favoriteSlot, editorValue, homeScroll, roomScroll;
    private Page screen;
    private ScrollView scroll;
    private LinearLayout body, roomRows;
    private TextView status, route, editorPreview, editorLight;
    private Button off, apply, connection, forget, customColorButton;
    private Boolean edgeAllOff;
    private EditText addressField;
    private SeekBar[] colorSliders;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        home = CatalogLink.cached(this);
        address = prefs.getString("address", "");
        Page start = Page.HOME;
        if (saved == null) {
            verify = getIntent().getBooleanExtra("verify_cloud", false);
            start = entryPage(getIntent());
        } else {
            roomId = saved.getString("room");
            lightId = saved.getString("light");
            direct = saved.getBoolean("direct");
            onlyLit = saved.getBoolean("only_lit");
            verify = saved.getBoolean("verify");
            address = saved.getString("address", address);
            setupNotice = saved.getString("setup_notice");
            customColor = saved.getBoolean("custom_color");
            editorValue = saved.getInt("value");
            homeScroll = saved.getInt("home_scroll");
            roomScroll = saved.getInt("room_scroll");
            start = switch (saved.getString("screen", "HOME")) {
                case "ROOM", "OFF" -> findRoom() == null ? Page.HOME : Page.ROOM;
                case "BRIGHTNESS" -> restoreEditor(Page.BRIGHTNESS);
                case "COLOR" -> restoreEditor(Page.COLOR);
                case "SETTINGS", "FORGET" -> Page.SETTINGS;
                case "FAVORITES", "PICK_FAVORITE" -> Page.FAVORITES;
                default -> Page.HOME;
            };
        }
        if (home != null && findRoom() == null) roomId = null;
        render(CatalogLink.paired(this) ? start : Page.SETUP);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        verify |= intent.getBooleanExtra("verify_cloud", false);
        if (CatalogLink.paired(this)) {
            cancelRead();
            latest = null;
            home = CatalogLink.cached(this);
            homeScroll = roomScroll = 0;
            onlyLit = false;
            lightId = null;
            render(entryPage(intent));
        }
        if (foreground) {
            if (verify) verifyCloud();
            else read();
        }
    }

    private Page entryPage(Intent intent) {
        String entry = intent.getStringExtra(Rooms.EXTRA);
        direct = entry != null && entry.startsWith(CatalogPresentation.ENTRY);
        roomId = direct ? entry.substring(CatalogPresentation.ENTRY.length()) : null;
        if (findRoom() != null) return Page.ROOM;
        if (home != null) roomId = null;
        return Page.HOME;
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        update();
        if (verify) verifyCloud();
        else read();
    }

    @Override protected void onStop() {
        foreground = false;
        cancelRead();
        latest = null;
        for (LightControls controls : lights.values()) controls.power.animation(false);
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        for (LightControls controls : lights.values()) controls.power.animation(foreground && focused);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        rememberScroll();
        out.putString("screen", screen.name());
        out.putString("room", roomId);
        out.putString("light", lightId);
        out.putString("address", addressField == null ? address : addressField.getText().toString());
        out.putString("setup_notice", setupBusy ? null : setupNotice);
        out.putBoolean("direct", direct);
        out.putBoolean("only_lit", onlyLit);
        out.putBoolean("verify", verify);
        out.putBoolean("custom_color", customColor);
        out.putInt("value", editorValue);
        out.putInt("home_scroll", homeScroll);
        out.putInt("room_scroll", roomScroll);
    }

    @Override public void onBackPressed() {
        switch (screen) {
            case ROOM -> { if (direct) finish(); else go(Page.HOME); }
            case BRIGHTNESS, COLOR -> go(Page.ROOM);
            case OFF -> go(roomId == null ? Page.HOME : Page.ROOM);
            case SETTINGS -> go(Page.HOME);
            case FORGET, FAVORITES -> go(Page.SETTINGS);
            case PICK_FAVORITE -> go(Page.FAVORITES);
            case APPROVE -> { if (!setupBusy) go(Page.SETUP); }
            default -> finish();
        }
    }

    private void rememberScroll() {
        if (screen == Page.HOME) homeScroll = scroll.getScrollY();
        if (screen == Page.ROOM) roomScroll = scroll.getScrollY();
    }

    private void go(Page next) {
        rememberScroll();
        if (next == Page.HOME) roomId = null;
        if (next != Page.BRIGHTNESS && next != Page.COLOR) lightId = null;
        render(next);
    }

    private void render(Page next) {
        for (LightControls controls : lights.values()) controls.power.animation(false);
        screen = next;
        cards.clear();
        lights.clear();
        shownRows = List.of();
        roomRows = null;
        status = route = editorPreview = editorLight = null;
        off = apply = connection = forget = customColorButton = null;
        edgeAllOff = null;
        addressField = null;
        colorSliders = null;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        boolean floral = next == Page.HOME || next == Page.ROOM;
        scroll.setFillViewport(floral);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setFocusableInTouchMode(true);
        int width = getResources().getConfiguration().screenWidthDp;
        int side = floral ? width * 8 / 100 : Math.max(20, width * 12 / 100);
        body.setPadding(views.dp(side), views.dp(floral ? width * 3 / 100 : Math.max(12, width / 12)),
            views.dp(side), views.dp(floral ? 3 : 24));
        scroll.addView(body, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        SwipeDismissFrameLayout swipe = new SwipeDismissFrameLayout(this);
        swipe.setSwipeable(true);
        swipe.addView(scroll, MATCH_PARENT, MATCH_PARENT);
        swipe.addCallback(new SwipeDismissFrameLayout.Callback() {
            @Override public void onDismissed(SwipeDismissFrameLayout frame) {
                if (scroll.getParent() == frame) {
                    if (screen == Page.APPROVE && setupBusy) render(Page.APPROVE);
                    else onBackPressed();
                }
            }
        });
        setContentView(swipe);
        switch (next) {
            case SETUP -> showSetup();
            case APPROVE -> showApproval();
            case HOME -> showHome();
            case ROOM -> showRoom();
            case BRIGHTNESS -> showBrightness();
            case COLOR -> showColor();
            case SETTINGS -> showSettings();
            case FAVORITES -> showFavorites();
            case PICK_FAVORITE -> showFavoriteChoice();
            case OFF -> showOffConfirmation();
            case FORGET -> showForgetConfirmation();
        }
        update();
        int restore = next == Page.HOME ? homeScroll : next == Page.ROOM ? roomScroll : 0;
        ScrollView current = scroll;
        if (restore > 0) current.post(() -> current.scrollTo(0, restore));
    }

    private TextView title(String label, int artwork) {
        boolean floral = screen == Page.HOME || screen == Page.ROOM;
        if (artwork != 0) {
            body.addView(flowers(artwork), new LinearLayout.LayoutParams(views.dp(64), views.dp(floral ? 15 : 22)));
        }
        boolean compoundRoom = screen == Page.ROOM && label.contains("-");
        var config = getResources().getConfiguration();
        String displayed = compoundRoom && (config.screenWidthDp < 200 || config.fontScale > 1.1f)
            ? label.replace("-", "-\n") : label;
        TextView title = views.text(displayed, compoundRoom ? 17 : floral ? 21 : 22, floral ? TEXT : ROSE);
        title.setTypeface(floral ? Typeface.SERIF : Typeface.DEFAULT, floral ? Typeface.NORMAL : Typeface.BOLD);
        title.setIncludeFontPadding(!floral);
        if (!displayed.equals(label)) title.setContentDescription(label);
        title.setAccessibilityHeading(true);
        add(title, floral ? 0 : 1);
        return title;
    }

    private ImageView flowers(int artwork) {
        ImageView image = new ImageView(this);
        image.setImageResource(artwork);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private TextView note(String message) {
        TextView note = views.text(message, 13, MUTED);
        add(note, 4);
        return note;
    }

    private void add(View view, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.topMargin = views.dp(top);
        body.addView(view, lp);
    }

    private Button button(String label, Runnable action, int icon) {
        Button button = views.button(label, action, icon);
        add(button, 5);
        return button;
    }

    private void addStatus() {
        route = note("");
        route.setTextSize(12);
        addStatusText();
    }

    private void addStatusText() {
        status = note("");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void showSetup() {
        title("Подключить дом", R.drawable.blossoms).setTextSize(18);
        note("Локальный адрес Wiren Board");
        addressField = new EditText(this);
        addressField.setSingleLine();
        addressField.setTextColor(TEXT);
        addressField.setHintTextColor(MUTED);
        addressField.setTextSize(15);
        addressField.setGravity(Gravity.CENTER);
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        addressField.setHint("IP или IP:порт");
        addressField.setText(address);
        addressField.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            discover();
            return true;
        });
        add(addressField, 3);
        apply = button("Найти дом", this::discover, R.drawable.ic_action_link);
        status = note("");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button("Посмотреть демо", this::demo, R.drawable.ic_action_demo);
        note(SETUP_HINT);
    }

    private void discover() {
        if (setupBusy) return;
        String candidate = addressField.getText().toString().trim();
        try {
            MqttRoom.brokerUri(candidate);
        } catch (IllegalArgumentException invalid) {
            addressField.setError("Введите локальный IPv4 или IPv4:порт");
            return;
        }
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(addressField.getWindowToken(), 0);
        address = candidate;
        setupBusy = true;
        setupNotice = "Ищем контроллер…";
        update();
        NETWORK.execute(() -> {
            CatalogLink.Result result = CatalogLink.discover(getApplicationContext(), candidate);
            main.post(() -> {
                setupBusy = false;
                if (!alive() || screen != Page.SETUP) return;
                if (result.error == null && result.catalog != null) {
                    discovered = result.catalog;
                    setupNotice = null;
                    go(Page.APPROVE);
                } else {
                    setupNotice = result.error == null ? "Каталог дома не найден" : result.error;
                    update();
                }
            });
        });
    }

    private void showApproval() {
        title("Это ваш дом?", R.drawable.blossoms);
        note("Контроллер " + discovered.controller);
        if (discovered.rooms.isEmpty()) note("Комнаты пока не настроены");
        else {
            List<String> names = new ArrayList<>();
            int shown = Math.min(5, discovered.rooms.size());
            for (int i = 0; i < shown; i++) names.add(discovered.rooms.get(i).label);
            int other = discovered.rooms.size() - shown;
            note(String.join(" · ", names) + (other > 0 ? " · ещё " + other : ""));
        }
        apply = button("Подключить", this::approve, R.drawable.ic_action_link);
        status = note("");
        button("Другой адрес", this::onBackPressed, R.drawable.ic_action_back);
    }

    private void approve() {
        if (setupBusy) return;
        WatchCatalog candidate = discovered;
        String approvedAddress = address;
        setupBusy = true;
        setupNotice = "Сохраняем дом…";
        update();
        NETWORK.execute(() -> {
            boolean approved = CatalogLink.approve(getApplicationContext(), candidate);
            if (approved) {
                prefs.edit().putString("address", approvedAddress).putBoolean("demo", false).apply();
                RoomCache.requestUpdate(getApplicationContext());
            }
            main.post(() -> {
                setupBusy = false;
                if (!alive()) return;
                if (approved) {
                    setupNotice = null;
                    home = CatalogLink.cached(this);
                    go(Page.HOME);
                    read();
                } else {
                    setupNotice = "Подтверждение устарело. Найдите дом снова.";
                    go(Page.SETUP);
                }
            });
        });
    }

    private void verifyCloud() {
        if (setupBusy) return;
        setupBusy = true;
        setupNotice = "Проверяем WB Cloud…";
        update();
        NETWORK.execute(() -> {
            CatalogLink.Result result = CatalogLink.verifyCloud(getApplicationContext());
            main.post(() -> {
                setupBusy = false;
                verify = false;
                if (!alive()) return;
                if (CatalogLink.paired(this)) {
                    commandNotice(result.error);
                    if (foreground) read();
                } else if (result.error == null && result.state != null) {
                    setupNotice = "WB Cloud работает. Укажите локальный IP, чтобы подтвердить дом.";
                    update();
                } else if (result.legacyGateway) {
                    startActivity(new Intent(this, HomeActivity.class)
                        .putExtra("legacy_verify", true).putExtra("verify_wb_cloud", true));
                    finish();
                } else {
                    setupNotice = result.error;
                    update();
                }
            });
        });
    }

    private void demo() {
        if (setupBusy && verify) return;
        prefs.edit().putBoolean("demo", true).apply();
        RoomCache.requestUpdate(this);
        startActivity(new Intent(this, HomeActivity.class).putExtra("legacy_verify", true));
        finish();
    }

    private void showHome() {
        title("Дом", R.drawable.blossoms);
        addStatus();
        if (home == null) {
            note("Схема дома загрузится при подключении.");
        } else if (home.rooms.isEmpty() || home.lights.isEmpty()) {
            note(SETUP_HINT);
        } else {
            Button filter = button(onlyLit ? "Все комнаты" : "Где горит", () -> {
                onlyLit = !onlyLit;
                homeScroll = 0;
                render(Page.HOME);
            }, R.drawable.ic_light);
            filter.setSelected(onlyLit);
            roomRows = new LinearLayout(this);
            roomRows.setOrientation(LinearLayout.VERTICAL);
            add(roomRows, 3);
        }
        button("Настройки", () -> go(Page.SETTINGS), R.drawable.ic_connection);
        if (home != null && !home.lights.isEmpty()) {
            edgeOff("Погасить весь дом", this::askOff);
        }
    }

    private void showRoom() {
        WatchCatalog.Room room = findRoom();
        roomHeader(room);
        for (WatchCatalog.Light light : room.lights) {
            CatalogViews.LightButton power = views.new LightButton(light, () -> toggle(light.id));
            add(power, 3);
            Button brightness = light.brightness == null ? null : views.button("Яркость", () -> edit(light.id, Page.BRIGHTNESS), 0);
            Button color = light.color == null ? null : views.button("Цвет", () -> edit(light.id, Page.COLOR), 0);
            if (brightness != null && color != null) {
                LinearLayout extras = new LinearLayout(this);
                extras.setBaselineAligned(false);
                extras.addView(brightness, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
                lp.leftMargin = views.dp(4);
                extras.addView(color, lp);
                add(extras, 4);
            } else if (brightness != null || color != null) {
                add(brightness == null ? color : brightness, 4);
            }
            lights.put(light.id, new LightControls(power, brightness, color));
        }
        if (room.lights.isEmpty()) note(SETUP_HINT);
        else edgeOff("Погасить здесь", () -> switchOff(false));
    }

    private void roomHeader(WatchCatalog.Room room) {
        int crown = RoomIcons.forRoom(room.theme).crown();
        var config = getResources().getConfiguration();
        boolean compact = config.isScreenRound() && config.fontScale <= 1.1f && room.lights.size() == 2
            && room.lights.stream().allMatch(light -> light.brightness == null && light.color == null);
        if (!compact) {
            title(room.label, crown);
            addStatus();
            return;
        }

        LinearLayout banner = new LinearLayout(this);
        banner.setGravity(Gravity.CENTER);
        banner.setBaselineAligned(false);
        banner.setPadding(0, views.dp(6), 0, 0);
        banner.addView(flowers(crown), new LinearLayout.LayoutParams(views.dp(30), views.dp(11)));
        route = views.text("", 10, MUTED);
        route.setSingleLine();
        route.setIncludeFontPadding(false);
        LinearLayout.LayoutParams routeSize = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        routeSize.leftMargin = views.dp(3);
        banner.addView(route, routeSize);
        add(banner, 0);
        title(room.label, 0);
        addStatusText();
    }

    private void edgeOff(String label, Runnable action) {
        body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        off = button(label, action, 0);
        off.setTextSize(14);
        off.setPadding(views.dp(15), views.dp(2), views.dp(15), views.dp(12));
    }

    private void showSettings() {
        title("Настройки", 0);
        if (home != null && !home.rooms.isEmpty()) button("Избранные комнаты", () -> go(Page.FAVORITES), R.drawable.ic_room_grid);
        connection = button("Подключение", () -> startActivity(new Intent(this, HomeActivity.class)
            .putExtra(HomeActivity.LEGACY_SETTINGS, true)), R.drawable.ic_connection);
        forget = button("Отключить дом", () -> go(Page.FORGET), R.drawable.ic_action_close);
        button("Назад", this::onBackPressed, R.drawable.ic_action_back);
    }

    private void showFavorites() {
        title("На плитке", 0);
        if (home != null) {
            List<WatchCatalog.Room> favorites = CatalogPresentation.favorites(this, home);
            for (int i = 0; i < favorites.size(); i++) {
                WatchCatalog.Room room = favorites.get(i);
                int slot = i;
                button((i + 1) + ". " + room.label, () -> {
                    favoriteSlot = slot;
                    go(Page.PICK_FAVORITE);
                }, RoomIcons.forRoom(room.theme).icon());
            }
        }
        button("Готово", () -> go(Page.HOME), R.drawable.ic_action_back);
    }

    private void showFavoriteChoice() {
        title("Место " + (favoriteSlot + 1), 0);
        if (home != null) for (WatchCatalog.Room room : home.rooms) {
            button(room.label, () -> {
                CatalogPresentation.setFavorite(this, home, favoriteSlot, room);
                go(Page.FAVORITES);
            }, RoomIcons.forRoom(room.theme).icon());
        }
        button("Назад", this::onBackPressed, R.drawable.ic_action_back);
    }

    private void showForgetConfirmation() {
        title("Отключить дом?", 0);
        note("Часы забудут подключённый контроллер.");
        forget = button("Отключить", () -> {
            if (commanding) return;
            cancelRead();
            CatalogLink.forget(this);
            demo();
        }, R.drawable.ic_action_close);
        button("Отмена", this::onBackPressed, R.drawable.ic_action_back);
    }

    private void showOffConfirmation() {
        title("Выключить\nвесь свет?", 0);
        addStatus();
        off = button("Выключить", () -> {
            if (!canSwitchOff() || !home.revision.equals(confirmRevision)) return;
            go(Page.HOME);
            switchOff(true);
        }, R.drawable.ic_power);
        button("Отмена", this::onBackPressed, R.drawable.ic_action_back);
    }

    private void switchOff(boolean whole) {
        if (!canSwitchOff()) return;
        Map<String, CatalogLink.Change> changes = new LinkedHashMap<>();
        for (WatchCatalog.Light light : offTargets()) changes.put(light.id, CatalogLink.Change.power(false));
        send(changes, whole);
    }

    private void askOff() {
        if (!canSwitchOff()) return;
        confirmRevision = home.revision;
        go(Page.OFF);
    }

    private void edit(String id, Page kind) {
        CatalogLink.LightState state = lightState(id);
        if (!canCommand() || state == null) return;
        if ((kind == Page.BRIGHTNESS && state.brightness == null) || (kind == Page.COLOR && state.color == null)) return;
        lightId = id;
        customColor = false;
        editorValue = kind == Page.BRIGHTNESS ? state.brightness : toColor(state.color);
        go(kind);
    }

    private void showBrightness() {
        title("Яркость", 0);
        editorLight = note(findLight().label);
        addStatus();
        editorPreview = note("");
        SeekBar brightness = slider("Яркость", 100, editorValue, value -> {
            editorValue = value;
            updateEditor();
        });
        add(brightness, 3);
        apply = button("Применить", () -> apply(CatalogLink.Change.brightness(editorValue)), 0);
        button("Назад", this::onBackPressed, R.drawable.ic_action_back);
    }

    private void showColor() {
        title("Цвет", 0);
        editorLight = note(findLight().label);
        addStatus();
        editorPreview = note("");
        for (int line = 0; line < 2; line++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < 3; col++) {
                int index = line * 3 + col;
                int color = PRESETS[index];
                Button swatch = views.button("", () -> pickColor(color), 0);
                swatch.setBackground(views.pill(color, 0, 0));
                swatch.setContentDescription(COLOR_NAMES[index]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, views.dp(48), 1f);
                if (col > 0) lp.leftMargin = views.dp(3);
                row.addView(swatch, lp);
            }
            add(row, 4);
        }
        apply = button("Применить", () -> apply(CatalogLink.Change.color(toPayload(editorValue))), 0);
        customColorButton = button("Свой цвет", this::showCustomColor, R.drawable.ic_light);
        if (customColor) showCustomColor();
        button("Назад", this::onBackPressed, R.drawable.ic_action_back);
    }

    private void showCustomColor() {
        if (colorSliders != null) return;
        customColor = true;
        customColorButton.setVisibility(View.GONE);
        colorSliders = new SeekBar[3];
        String[] labels = {"Красный", "Зелёный", "Синий"};
        for (int i = 0; i < labels.length; i++) {
            int channel = 2 - i;
            int shift = channel * 8;
            int position = body.indexOfChild(apply);
            body.addView(views.text(labels[i], 13, MUTED), position);
            colorSliders[i] = slider(labels[i], 255, (editorValue >> shift) & 255, value -> {
                editorValue = (editorValue & ~(255 << shift)) | (value << shift);
                updateEditor();
            });
            body.addView(colorSliders[i], position + 1);
        }
    }

    private interface ValueChanged { void changed(int value); }

    private SeekBar slider(String label, int max, int value, ValueChanged change) {
        SeekBar slider = new SeekBar(this);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setMinimumHeight(views.dp(48));
        slider.setContentDescription(label);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) change.changed(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        return slider;
    }

    private void pickColor(int color) {
        editorValue = color;
        if (colorSliders != null) {
            colorSliders[0].setProgress(Color.red(color));
            colorSliders[1].setProgress(Color.green(color));
            colorSliders[2].setProgress(Color.blue(color));
        }
        updateEditor();
    }

    private void updateEditor() {
        if (editorPreview == null) return;
        message(editorLight, findLight().label);
        CatalogLink.LightState confirmed = lightState(lightId);
        if (screen == Page.BRIGHTNESS) {
            message(editorPreview, editorValue + "%");
            views.enabled(apply, canCommand() && confirmed != null && confirmed.brightness != null
                && editorValue != confirmed.brightness);
        } else {
            String label = "RGB " + toPayload(editorValue).replace(';', ' ');
            if (!label.contentEquals(editorPreview.getText())) {
                editorPreview.setText(label);
                editorPreview.setBackground(views.pill(editorValue, 0, 0));
                editorPreview.setTextColor(Color.luminance(editorValue) > .34 ? Color.BLACK : Color.WHITE);
            }
            views.enabled(apply, canCommand() && confirmed != null && confirmed.color != null
                && !toPayload(editorValue).equals(confirmed.color));
        }
    }

    private static String toPayload(int color) {
        return Color.red(color) + ";" + Color.green(color) + ";" + Color.blue(color);
    }

    private static int toColor(String payload) {
        String[] components = payload.split(";");
        return Color.rgb(Integer.parseInt(components[0]), Integer.parseInt(components[1]), Integer.parseInt(components[2]));
    }

    private void apply(CatalogLink.Change change) {
        if (!apply.isEnabled() || editorTopic(screen) == null) return;
        String id = lightId;
        go(Page.ROOM);
        send(Map.of(id, change), false);
    }

    private void toggle(String id) {
        CatalogLink.LightState state = lightState(id);
        if (canCommand() && state != null && state.power != null) {
            send(Map.of(id, CatalogLink.Change.power(!state.power)), false);
        }
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    private void read() {
        main.removeCallbacks(poll);
        if (!foreground || !alive() || !CatalogLink.paired(this)) return;
        if (commanding || verify || setupBusy) {
            main.postDelayed(poll, 1_000);
            return;
        }
        if (readFuture != null) return;
        WatchCatalog baseline = home;
        long generation = ++readGeneration;
        long started = System.currentTimeMillis();
        update();
        readFuture = NETWORK.submit(() -> {
            CatalogLink.Result result = CatalogLink.read(getApplicationContext(), baseline);
            WatchCatalog fresh = CatalogLink.cached(getApplicationContext());
            if (!Thread.currentThread().isInterrupted()) CatalogPresentation.save(getApplicationContext(), result, started);
            main.post(() -> {
                if (generation != readGeneration) return;
                readFuture = null;
                if (!foreground || !alive()) return;
                accept(result, fresh);
                scheduleRead(result.error == null ? POLL_MS : RETRY_MS);
            });
        });
    }

    private void cancelRead() {
        main.removeCallbacks(poll);
        readGeneration++;
        if (readFuture != null) readFuture.cancel(true);
        readFuture = null;
    }

    private void scheduleRead(long delay) {
        main.removeCallbacks(poll);
        if (foreground && alive()) main.postDelayed(poll, delay);
    }

    private void send(Map<String, CatalogLink.Change> changes, boolean whole) {
        if (!canCommand()) return;
        CatalogLink.Result prior = latest;
        cancelRead();
        commanding = true;
        pending = Set.copyOf(changes.keySet());
        commandNotice(null);
        CatalogPresentation.commandPending(this, true);
        update();
        NETWORK.execute(() -> {
            CatalogLink.Result confirmation;
            long started = System.currentTimeMillis();
            try {
                confirmation = whole ? CatalogLink.allOff(getApplicationContext(), prior)
                    : CatalogLink.command(getApplicationContext(), prior, changes);
            } catch (RuntimeException unavailable) {
                confirmation = null;
            }
            WatchCatalog fresh = CatalogLink.cached(getApplicationContext());
            CatalogLink.Result result = confirmation;
            main.post(() -> finishCommand(result, fresh, started));
        });
    }

    private void finishCommand(CatalogLink.Result result, WatchCatalog fresh, long started) {
        commanding = false;
        pending = Set.of();
        CatalogPresentation.commandPending(this, false);
        if (result != null) CatalogPresentation.save(this, result, started);
        if (!alive()) return;
        commandNotice(result == null ? "Контроллер не подтвердил команду" : result.error);
        if (result == null) {
            latest = null;
            update();
        } else {
            accept(result, fresh);
        }
        scheduleRead(result == null || result.error != null ? 0 : POLL_MS);
    }

    private void commandNotice(String text) {
        commandNotice = text;
        noticeUntil = SystemClock.elapsedRealtime() + NOTICE_MS;
    }

    private void accept(CatalogLink.Result result, WatchCatalog fresh) {
        String oldTopic = editorTopic(screen);
        boolean sameController = home != null && fresh != null && home.controller.equals(fresh.controller);
        boolean changed = home != fresh && (!sameController || !home.revision.equals(fresh.revision));
        latest = result;
        observed = SystemClock.elapsedRealtime();
        if (changed) {
            home = fresh;
            RoomCache.requestUpdate(this);
            if (roomId != null && findRoom() == null) {
                go(Page.HOME);
                return;
            }
            if ((screen == Page.BRIGHTNESS || screen == Page.COLOR) && sameController && Objects.equals(oldTopic, editorTopic(screen))) {
                update();
                return;
            }
            if (screen == Page.OFF || screen == Page.BRIGHTNESS || screen == Page.COLOR) {
                go(roomId == null ? Page.HOME : Page.ROOM);
                return;
            }
            if (screen == Page.HOME && direct && findRoom() != null) {
                go(Page.ROOM);
                return;
            }
            rememberScroll();
            render(screen);
        } else {
            update();
        }
    }

    private WatchCatalog.Room findRoom() {
        if (home != null && roomId != null) for (WatchCatalog.Room room : home.rooms) if (room.id.equals(roomId)) return room;
        return null;
    }

    private WatchCatalog.Light findLight() {
        WatchCatalog.Room room = findRoom();
        if (room != null && lightId != null) for (WatchCatalog.Light light : room.lights) if (light.id.equals(lightId)) return light;
        return null;
    }

    private String editorTopic(Page kind) {
        if (kind != Page.BRIGHTNESS && kind != Page.COLOR) return null;
        WatchCatalog.Light light = findLight();
        if (light == null) return null;
        return kind == Page.BRIGHTNESS ? light.brightness : light.color;
    }

    private Page restoreEditor(Page kind) {
        if (editorTopic(kind) != null) return kind;
        return findRoom() == null ? Page.HOME : Page.ROOM;
    }

    private CatalogLink.State liveState() {
        if (latest == null || latest.state == null || home == null || latest.error != null
            || !latest.catalog.controller.equals(home.controller) || !latest.state.revision.equals(home.revision)
            || SystemClock.elapsedRealtime() - observed >= FRESH_MS) return null;
        return latest.state;
    }

    private CatalogLink.LightState lightState(String id) {
        CatalogLink.State state = liveState();
        return state == null ? null : state.lights.get(id);
    }

    private boolean canCommand() {
        CatalogLink.State state = liveState();
        return foreground && !commanding && state != null && Boolean.FALSE.equals(state.vacation);
    }

    private List<WatchCatalog.Light> offTargets() {
        if (home == null) return List.of();
        if (roomId == null) return new ArrayList<>(home.lights.values());
        WatchCatalog.Room room = findRoom();
        return room == null ? List.of() : room.lights;
    }

    private boolean canSwitchOff() {
        if (!canCommand()) return false;
        boolean anyOn = false;
        for (WatchCatalog.Light light : offTargets()) {
            CatalogLink.LightState state = lightState(light.id);
            if (state == null || state.power == null) return false;
            anyOn |= state.power;
        }
        return anyOn;
    }

    private boolean allOff() {
        List<WatchCatalog.Light> targets = offTargets();
        for (WatchCatalog.Light light : targets) {
            CatalogLink.LightState state = lightState(light.id);
            if (state == null || !Boolean.FALSE.equals(state.power)) return false;
        }
        return !targets.isEmpty();
    }

    private void update() {
        if (screen == Page.SETUP || screen == Page.APPROVE) {
            message(status, setupNotice);
            views.enabled(apply, !setupBusy);
            if (addressField != null) addressField.setEnabled(!setupBusy);
            return;
        }
        CatalogLink.State state = liveState();
        if (commandNotice != null && SystemClock.elapsedRealtime() >= noticeUntil) commandNotice = null;
        if (route != null) message(route, latest == null || latest.route == null ? null : latest.route.label);
        if (status != null) message(status, commanding ? "Ждём подтверждения…" : commandNotice != null ? commandNotice
            : verify ? "Проверяем WB Cloud…" : latest == null ? "Обновляем состояние…" : latest.error != null ? latest.error
            : state == null ? "Обновляем состояние…" : state.vacation == null ? "Проверяем режим отпуска…"
            : state.vacation ? "Включён режим отпуска" : null);
        if (roomRows != null) updateRooms();
        boolean ready = canCommand();
        for (Map.Entry<String, LightControls> entry : lights.entrySet()) {
            String id = entry.getKey();
            LightControls controls = entry.getValue();
            CatalogLink.LightState value = state == null ? null : state.lights.get(id);
            Boolean power = value == null ? null : value.power;
            controls.power.display(power, pending.contains(id), ready && power != null, foreground && hasWindowFocus());
            if (controls.brightness != null) {
                controls.brightness.setText(value == null || value.brightness == null ? "Яркость" : "Яркость · " + value.brightness + "%");
                views.enabled(controls.brightness, ready && value != null && value.brightness != null);
            }
            if (controls.color != null) views.enabled(controls.color, ready && value != null && value.color != null);
        }
        if (off != null) {
            views.enabled(off, canSwitchOff() && (screen != Page.OFF || home.revision.equals(confirmRevision)));
            if (screen == Page.HOME || screen == Page.ROOM) {
                boolean allOff = allOff();
                if (!Objects.equals(edgeAllOff, allOff)) {
                    edgeAllOff = allOff;
                    off.setText(allOff ? "Всё выключено" : screen == Page.HOME ? "Погасить весь дом" : "Погасить здесь");
                    views.edge(off, allOff);
                }
            }
        }
        if (connection != null) views.enabled(connection, !commanding);
        if (forget != null) views.enabled(forget, !commanding);
        updateEditor();
    }

    private void message(TextView label, String text) {
        if (!Objects.equals(label.getText().toString(), text == null ? "" : text)) label.setText(text);
        label.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void updateRooms() {
        Map<String, Boolean> powers = new HashMap<>();
        CatalogLink.State state = liveState();
        if (state != null) for (var item : state.lights.entrySet()) powers.put(item.getKey(), item.getValue().power);
        List<RoomRow> rows = new ArrayList<>();
        if (onlyLit) {
            List<WatchCatalog.Room> lit = new ArrayList<>(), unknown = new ArrayList<>();
            for (WatchCatalog.Room room : home.rooms) {
                RoomOverview.Status reading = CatalogPresentation.status(room, powers);
                if (reading.on() > 0) lit.add(room);
                else if (reading.unknown() > 0) unknown.add(room);
            }
            if (!lit.isEmpty()) rows.add(new RoomRow(null, lit));
            else rows.add(new RoomRow(unknown.isEmpty() ? "В комнатах свет погашен" : "Пока нет подтверждённого света", List.of()));
            if (!unknown.isEmpty()) rows.add(new RoomRow("Нет полных данных", unknown));
        } else {
            List<WatchCatalog.Room> ordered = new ArrayList<>(CatalogPresentation.favorites(this, home));
            for (WatchCatalog.Room room : home.rooms) if (!ordered.contains(room)) ordered.add(room);
            rows.add(new RoomRow(null, ordered));
        }
        if (!rows.equals(shownRows)) {
            shownRows = rows;
            roomRows.removeAllViews();
            cards.clear();
            for (RoomRow section : rows) addRoomRow(section);
        }
        for (WatchCatalog.Room room : home.rooms) {
            CatalogViews.RoomCard card = cards.get(room.id);
            if (card != null) card.display(CatalogPresentation.status(room, powers));
        }
    }

    private void addRoomRow(RoomRow section) {
        if (section.title != null) {
            TextView heading = views.text(section.title, 13, MUTED);
            heading.setPadding(0, views.dp(6), 0, views.dp(2));
            heading.setAccessibilityHeading(true);
            roomRows.addView(heading);
        }
        for (int i = 0; i < section.rooms.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false);
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
                if (j == 1) lp.leftMargin = views.dp(5);
                if (i + j >= section.rooms.size()) {
                    row.addView(new View(this), lp);
                    break;
                }
                WatchCatalog.Room room = section.rooms.get(i + j);
                CatalogViews.RoomCard card = views.new RoomCard(room, () -> {
                    roomId = room.id;
                    roomScroll = 0;
                    direct = false;
                    go(Page.ROOM);
                });
                row.addView(card, lp);
                cards.put(room.id, card);
            }
            LinearLayout.LayoutParams pair = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            pair.topMargin = views.dp(4);
            roomRows.addView(row, pair);
        }
    }
}
