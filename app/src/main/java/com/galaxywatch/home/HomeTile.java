package com.galaxywatch.home;

import static androidx.wear.protolayout.ColorBuilders.argb;
import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;
import static androidx.wear.protolayout.DimensionBuilders.sp;
import static androidx.wear.protolayout.LayoutElementBuilders.*;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.wear.protolayout.ActionBuilders.AndroidActivity;
import androidx.wear.protolayout.ActionBuilders.AndroidStringExtra;
import androidx.wear.protolayout.ActionBuilders.LaunchAction;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ModifiersBuilders.Background;
import androidx.wear.protolayout.ModifiersBuilders.Border;
import androidx.wear.protolayout.ModifiersBuilders.Clickable;
import androidx.wear.protolayout.ModifiersBuilders.Corner;
import androidx.wear.protolayout.ModifiersBuilders.Modifiers;
import androidx.wear.protolayout.ModifiersBuilders.Padding;
import androidx.wear.protolayout.ModifiersBuilders.Semantics;
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId;
import androidx.wear.protolayout.ResourceBuilders.ImageResource;
import androidx.wear.protolayout.ResourceBuilders.Resources;
import androidx.wear.protolayout.TimelineBuilders.TimeInterval;
import androidx.wear.protolayout.TimelineBuilders.Timeline;
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry;
import androidx.wear.tiles.RequestBuilders.ResourcesRequest;
import androidx.wear.tiles.RequestBuilders.TileRequest;
import androidx.wear.tiles.TileBuilders.Tile;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class HomeTile extends TileService {
    private static final String RESOURCES = "8";
    private static final int TARGET_DP = 48;
    private static final int GAP_DP = 4;
    private static final int MAX_TIMED_ENTRIES = 12;
    private static final long REFRESH_INTERVAL_MS = 60_000;
    private static final int TILE_BACKGROUND = 0xFF171118;
    private static final int ROOM_TEXT = 0xFFFFF5F2;
    private static final int LIT_BACKGROUND = 0xFF593E2B;
    private static final int GOLD = 0xFFFFD06B;
    private static final String ALL_ROOMS = "Все комнаты";
    private static final String LAMP = "status-lamp";

    @Override protected void onTileEnterEvent(androidx.wear.tiles.EventBuilders.TileEnterEvent event) {
        if (CatalogLink.paired(this)) CatalogPresentation.refresh(this);
        else TileRead.refresh(this);
    }

    @Override protected ListenableFuture<Tile> onTileRequest(TileRequest request) {
        var device = request.getDeviceConfiguration();
        TileSize size = new TileSize(device.getScreenWidthDp(), device.getScreenHeightDp(),
            Math.max(1f, device.getFontScale()));
        CatalogLink.configureBundledHome(this);
        if (CatalogLink.paired(this)) return catalogTile(size);
        List<Rooms.Room> favorites = RoomOverview.favorites(getSharedPreferences("home", MODE_PRIVATE));
        TileRead.refresh(this);
        RoomCache.Snapshot cache = RoomCache.read(this);
        long now = System.currentTimeMillis();
        Timeline.Builder timeline = new Timeline.Builder();
        var expiries = new TreeSet<>(cache.expiriesAfter(now));
        long start = now;
        int entryCount = 0;
        for (long expiry : expiries) {
            if (entryCount == MAX_TIMED_ENTRIES) break;
            timeline.addTimelineEntry(new TimelineEntry.Builder()
                .setValidity(new TimeInterval.Builder().setStartMillis(start).setEndMillis(expiry).build())
                .setLayout(legacyLayout(size, favorites, cache.statesAt(start)))
                .build());
            start = expiry;
            entryCount++;
        }
        // Once the last reading expires, never return to a layout with stale light counts.
        long fallbackTime = expiries.isEmpty() ? now : expiries.last();
        timeline.addTimelineEntry(new TimelineEntry.Builder()
            .setLayout(legacyLayout(size, favorites, cache.statesAt(fallbackTime))).build());
        return completed(new Tile.Builder().setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS).setTileTimeline(timeline.build()).build());
    }

    private ListenableFuture<Tile> catalogTile(TileSize size) {
        CatalogPresentation.refresh(this);
        WatchCatalog home = CatalogLink.cached(this);
        var timeline = new Timeline.Builder();
        if (home == null || home.rooms.isEmpty()) {
            timeline.addTimelineEntry(new TimelineEntry.Builder().setLayout(
                layout(size, List.of(), home == null ? "Загрузка комнат" : "Комнат пока нет")).build());
        } else {
            var pins = CatalogPresentation.favorites(this, home);
            var snapshot = CatalogPresentation.readings(this, home);
            long now = System.currentTimeMillis();
            if (snapshot.fresh(now)) {
                timeline.addTimelineEntry(new TimelineEntry.Builder().setValidity(new TimeInterval.Builder()
                    .setStartMillis(now).setEndMillis(snapshot.observed() + RoomCache.FRESH_MS).build())
                    .setLayout(catalogLayout(size, home, pins, snapshot.powers())).build());
            }
            timeline.addTimelineEntry(new TimelineEntry.Builder()
                .setLayout(catalogLayout(size, home, pins, Map.of())).build());
        }
        return completed(new Tile.Builder().setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS).setTileTimeline(timeline.build()).build());
    }

    private record Card(String entry, String label, String theme, RoomOverview.Status status) {}

    private Layout catalogLayout(TileSize size, WatchCatalog home, List<WatchCatalog.Room> pins,
                                Map<String, Boolean> powers) {
        List<Card> cards = new ArrayList<>();
        for (WatchCatalog.Room room : pins) cards.add(new Card(CatalogPresentation.ENTRY + room.id, room.label,
            RoomIcons.knownTheme(room.theme), CatalogPresentation.status(room, powers)));
        return layout(size, cards, CatalogPresentation.outsideHint(home.rooms, pins, powers));
    }

    @Override protected ListenableFuture<Resources> onTileResourcesRequest(ResourcesRequest request) {
        Resources.Builder resources = new Resources.Builder().setVersion(RESOURCES);
        for (String theme : RoomIcons.THEMES) {
            addIcon(resources, theme, RoomIcons.forRoom(theme).icon());
        }
        addIcon(resources, "blossoms", R.drawable.blossoms);
        addIcon(resources, "edge", R.drawable.edge_action);
        addIcon(resources, LAMP, R.drawable.ic_light_on);
        return completed(resources.build());
    }

    private Layout legacyLayout(TileSize size, List<Rooms.Room> favorites, Map<Rooms.Light, Boolean> states) {
        List<Card> cards = new ArrayList<>();
        for (Rooms.Room room : favorites) cards.add(new Card(room.id(), room.label(), room.id(),
            RoomOverview.status(room, states)));
        return layout(size, cards, RoomOverview.outsideHint(favorites, states));
    }

    private Layout layout(TileSize size, List<Card> favorites, String hint) {
        Column.Builder content = new Column.Builder().setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER);
        float gridTop = size.gridTop((favorites.size() + 1) / 2);
        if (gridTop >= 27 && !(size.compact() && size.largeText())) {
            content.addContent(verticalSpace(5)).addContent(image("blossoms", 68, 19))
                .addContent(verticalSpace(gridTop - 24));
        } else {
            content.addContent(verticalSpace(gridTop));
        }
        for (int index = 0; index < favorites.size(); index += 2) {
            if (index > 0) content.addContent(verticalSpace(GAP_DP));
            Row.Builder row = new Row.Builder();
            row.addContent(roomButton(favorites.get(index), size));
            if (index + 1 < favorites.size()) {
                row.addContent(horizontalSpace(GAP_DP)).addContent(roomButton(favorites.get(index + 1), size));
            }
            content.addContent(row.build());
        }
        content.addContent(new Spacer.Builder().setHeight(expand()).build())
            .addContent(allRoomsButton(hint, size));

        Box root = centeredBox().setWidth(expand()).setHeight(expand())
            .setModifiers(new Modifiers.Builder().setBackground(
                new Background.Builder().setColor(argb(TILE_BACKGROUND)).build()).build())
            .addContent(content.build()).build();
        return new Layout.Builder().setRoot(root).build();
    }

    private Box roomButton(Card room, TileSize size) {
        var status = room.status();
        boolean lit = status.on() > 0;
        String caption = room.label().replaceFirst("-", "-\n");
        boolean twoLines = !caption.equals(room.label()) || caption.length() > 11;
        boolean showRoomIcon = !size.largeText();
        String mark = status.mark();
        Column.Builder content = new Column.Builder().setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER);
        if (showRoomIcon || !mark.isEmpty()) {
            Row.Builder statusRow = new Row.Builder().setVerticalAlignment(VERTICAL_ALIGN_CENTER);
            if (showRoomIcon) {
                int iconSize = twoLines ? 15 : 19;
                statusRow.addContent(image(room.theme(), iconSize, iconSize));
                if (!mark.isEmpty()) statusRow.addContent(horizontalSpace(4));
            }
            if (lit) statusRow.addContent(image(LAMP, 13, 13)).addContent(horizontalSpace(1));
            if (!mark.isEmpty()) {
                float markSize = Math.min(13, (size.compact() ? 14 : 16) / size.fontScale());
                statusRow.addContent(text(mark, markSize, lit ? GOLD : ROOM_TEXT, 1, FONT_WEIGHT_BOLD));
            }
            content.addContent(statusRow.build());
        }
        float labelSize = size.largeText()
            ? Math.min(13, (size.roomWidth() - 6) / (4.5f * size.fontScale()))
            : size.compact() ? 11 : 12;
        content.addContent(text(caption, labelSize, ROOM_TEXT, twoLines ? 2 : 1, FONT_WEIGHT_NORMAL));

        Modifiers.Builder modifiers = navigation(room.entry(), room.label() + ". " + status.description())
            .setPadding(new Padding.Builder().setStart(dp(3)).setEnd(dp(3)).build())
            .setBackground(new Background.Builder()
                .setColor(argb(lit ? LIT_BACKGROUND : RoomIcons.forRoom(room.theme()).surface()))
                .setCorner(new Corner.Builder().setRadius(dp(17)).build()).build());
        if (lit) modifiers.setBorder(new Border.Builder().setWidth(dp(2)).setColor(argb(GOLD)).build());
        return centeredBox().setWidth(dp(size.roomWidth())).setHeight(dp(size.roomHeight()))
            .setModifiers(modifiers.build()).addContent(content.build()).build();
    }

    private Box allRoomsButton(String hint, TileSize size) {
        float width = size.width() * 0.82f;
        float height = size.edgeHeight();
        float labelSize = Math.min(14, 17 / size.fontScale());
        Column.Builder captions = new Column.Builder().setWidth(dp(size.width() * 0.61f))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(text(ALL_ROOMS, labelSize, TILE_BACKGROUND, 1, FONT_WEIGHT_NORMAL));
        if (!hint.isEmpty()) {
            String visibleHint = size.compact() && size.largeText() && hint.equals("Ещё в кухне-гостиной")
                ? "Кухня-гостиная" : hint;
            captions.addContent(text(visibleHint, Math.min(9, 11 / size.fontScale()),
                TILE_BACKGROUND, 2, FONT_WEIGHT_NORMAL));
        }
        captions.addContent(verticalSpace(hint.isEmpty() ? 8 : 13));
        String description = hint.isEmpty() ? ALL_ROOMS : ALL_ROOMS + ". " + hint;
        return centeredBox().setWidth(dp(width)).setHeight(dp(height))
            .setModifiers(navigation(Rooms.TILE_HOME, description).build())
            .addContent(new Image.Builder().setResourceId("edge").setWidth(dp(width)).setHeight(dp(height))
                .setContentScaleMode(CONTENT_SCALE_MODE_FILL_BOUNDS).build())
            .addContent(captions.build()).build();
    }

    private Modifiers.Builder navigation(String id, String description) {
        boolean catalog = id.startsWith(CatalogPresentation.ENTRY)
            || Rooms.TILE_HOME.equals(id) && CatalogLink.paired(this);
        Class<?> activity = catalog ? CatalogTileEntryActivity.class : HomeActivity.class;
        AndroidActivity target = new AndroidActivity.Builder().setPackageName(getPackageName())
            .setClassName(activity.getName())
            .addKeyToExtraMapping(Rooms.EXTRA, new AndroidStringExtra.Builder().setValue(id).build())
            .build();
        return new Modifiers.Builder().setClickable(new Clickable.Builder().setId(id)
                .setOnClick(new LaunchAction.Builder().setAndroidActivity(target).build()).build())
            .setSemantics(new Semantics.Builder().setContentDescription(description)
                .setRole(ModifiersBuilders.SEMANTICS_ROLE_BUTTON).build());
    }

    private static void addIcon(Resources.Builder resources, String id, int drawable) {
        resources.addIdToImageMapping(id, new ImageResource.Builder()
            .setAndroidResourceByResId(new AndroidImageResourceByResId.Builder()
                .setResourceId(drawable).build()).build());
    }

    private static Image image(String id, float width, float height) {
        return new Image.Builder().setResourceId(id).setWidth(dp(width)).setHeight(dp(height)).build();
    }

    private static Box.Builder centeredBox() {
        return new Box.Builder().setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER);
    }

    private static Spacer verticalSpace(float height) {
        return new Spacer.Builder().setHeight(dp(height)).build();
    }

    private static Spacer horizontalSpace(float width) {
        return new Spacer.Builder().setWidth(dp(width)).build();
    }

    private static Text text(String label, float size, int color, int maxLines, int weight) {
        return new Text.Builder().setText(label).setMaxLines(maxLines)
            .setMultilineAlignment(TEXT_ALIGN_CENTER)
            .setFontStyle(new FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setWeight(weight).build()).build();
    }

    private static <T> ListenableFuture<T> completed(T value) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(value);
            return "Home tile";
        });
    }

    private record TileSize(float width, float height, float fontScale) {
        boolean compact() {
            return Math.min(width, height) < 200;
        }

        boolean largeText() {
            return fontScale > 1.15f;
        }

        float roomWidth() {
            return Math.max(TARGET_DP, Math.min(100, width * 0.38f));
        }

        float roomHeight() {
            float base = Math.max(TARGET_DP, Math.min(60, height * 0.244f));
            if (!largeText()) return base;
            return base + (compact() ? 4 : 7);
        }

        float edgeHeight() {
            float base = Math.max(TARGET_DP, Math.min(58, height * 0.223f));
            return base + (largeText() ? 4 : 0);
        }

        float gridTop(int rows) {
            float gridHeight = rows * roomHeight() + Math.max(0, rows - 1) * GAP_DP;
            float free = Math.max(0, height - gridHeight - edgeHeight());
            return Math.min(free, free / 2 + 9);
        }
    }
}
