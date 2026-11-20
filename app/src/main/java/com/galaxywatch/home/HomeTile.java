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
import androidx.wear.protolayout.ModifiersBuilders.Background;
import androidx.wear.protolayout.ModifiersBuilders.Clickable;
import androidx.wear.protolayout.ModifiersBuilders.Corner;
import androidx.wear.protolayout.ModifiersBuilders.Modifiers;
import androidx.wear.protolayout.ModifiersBuilders.Semantics;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId;
import androidx.wear.protolayout.ResourceBuilders.ImageResource;
import androidx.wear.protolayout.ResourceBuilders.Resources;
import androidx.wear.protolayout.TimelineBuilders.Timeline;
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry;
import androidx.wear.tiles.RequestBuilders.ResourcesRequest;
import androidx.wear.tiles.RequestBuilders.TileRequest;
import androidx.wear.tiles.TileBuilders.Tile;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.ListenableFuture;

public final class HomeTile extends TileService {
    private static final String RESOURCES = "5";
    private static final int TARGET_DP = 48;

    @Override protected ListenableFuture<Tile> onTileRequest(TileRequest request) {
        var device = request.getDeviceConfiguration();
        float screen = device.getScreenWidthDp();
        boolean showIcons = device.getFontScale() <= 1.15f;
        float roomWidth = Math.max(TARGET_DP, Math.min(91, screen * (showIcons ? 0.37f : 0.41f)));
        Column.Builder content = new Column.Builder().setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER);
        content.addContent(new Spacer.Builder().setHeight(dp(6)).build());
        content.addContent(new Image.Builder().setResourceId("blossoms").setWidth(dp(64)).setHeight(dp(18)).build());
        if (screen >= 225) {
            content.addContent(text("Дом", 19, 0xFFFFF1F2, 1));
        }
        content.addContent(new Spacer.Builder().setHeight(expand()).build());
        content.addContent(row(0, roomWidth, showIcons))
            .addContent(new Spacer.Builder().setHeight(dp(4)).build())
            .addContent(row(2, roomWidth, showIcons))
            .addContent(new Spacer.Builder().setHeight(expand()).build())
            .addContent(launchButton(Rooms.TILE_HOME, "Все комнаты", screen * 0.84f, false))
            .addContent(new Spacer.Builder().setHeight(dp(3)).build());
        Box root = new Box.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(new Modifiers.Builder().setBackground(new Background.Builder().setColor(argb(0xFF171118)).build()).build())
            .addContent(content.build()).build();
        Timeline timeline = new Timeline.Builder().addTimelineEntry(new TimelineEntry.Builder()
            .setLayout(new Layout.Builder().setRoot(root).build()).build()).build();
        return completed(new Tile.Builder().setResourcesVersion(RESOURCES).setTileTimeline(timeline).build());
    }

    @Override protected ListenableFuture<Resources> onTileResourcesRequest(ResourcesRequest request) {
        Resources.Builder resources = new Resources.Builder().setVersion(RESOURCES);
        for (Rooms.Room room : Rooms.ALL.subList(0, 4)) {
            addIcon(resources, room.id(), RoomIcons.forRoom(room.id()).icon());
        }
        addIcon(resources, "blossoms", R.drawable.blossoms);
        addIcon(resources, "edge", R.drawable.edge_action);
        return completed(resources.build());
    }

    private void addIcon(Resources.Builder resources, String id, int drawable) {
        resources.addIdToImageMapping(id, new ImageResource.Builder()
            .setAndroidResourceByResId(new AndroidImageResourceByResId.Builder()
                .setResourceId(drawable).build())
            .build());
    }

    private Row row(int index, float width, boolean showIcons) {
        return new Row.Builder().addContent(roomButton(Rooms.ALL.get(index), width, showIcons))
            .addContent(new Spacer.Builder().setWidth(dp(6)).build())
            .addContent(roomButton(Rooms.ALL.get(index + 1), width, showIcons)).build();
    }

    private Box roomButton(Rooms.Room room, float width, boolean showIcons) {
        return launchButton(room.id(), room.label(), width, showIcons);
    }

    private Box launchButton(String id, String label, float width, boolean showIcons) {
        boolean allRooms = Rooms.TILE_HOME.equals(id);
        int foreground = allRooms ? 0xFF171118 : 0xFFFFD9E5;
        AndroidActivity target = new AndroidActivity.Builder().setPackageName(getPackageName())
            .setClassName(HomeActivity.class.getName())
            .addKeyToExtraMapping(Rooms.EXTRA, new AndroidStringExtra.Builder().setValue(id).build())
            .build();
        Clickable action = new Clickable.Builder().setId(id)
            .setOnClick(new LaunchAction.Builder().setAndroidActivity(target).build()).build();
        Column.Builder content = new Column.Builder().setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER);
        boolean twoLines = !allRooms && label.contains("-");
        if (showIcons) {
            int iconSize = twoLines ? 13 : 20;
            content.addContent(new Image.Builder().setResourceId(id).setWidth(dp(iconSize)).setHeight(dp(iconSize)).build());
            content.addContent(new Spacer.Builder().setHeight(dp(2)).build());
        }
        String caption = twoLines ? label.replace("-", "-\n") : label;
        int labelSize = allRooms ? 14 : showIcons && !twoLines ? 13 : 12;
        content.addContent(text(caption, labelSize,
            foreground, twoLines || !showIcons ? 2 : 1));
        Modifiers.Builder modifiers = new Modifiers.Builder().setClickable(action)
            .setSemantics(new Semantics.Builder().setContentDescription(label)
                .setRole(ModifiersBuilders.SEMANTICS_ROLE_BUTTON).build());
        Box.Builder box = new Box.Builder().setWidth(dp(width)).setHeight(dp(TARGET_DP))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER);
        if (allRooms) {
            box.addContent(new Image.Builder().setResourceId("edge").setWidth(dp(width)).setHeight(dp(TARGET_DP))
                .setContentScaleMode(CONTENT_SCALE_MODE_FILL_BOUNDS).build());
            content.addContent(new Spacer.Builder().setHeight(dp(10)).build());
        } else {
            modifiers.setBackground(new Background.Builder().setColor(argb(RoomIcons.forRoom(id).surface()))
                .setCorner(new Corner.Builder().setRadius(dp(17)).build()).build());
        }
        return box.setModifiers(modifiers.build()).addContent(content.build()).build();
    }

    private static Text text(String label, int size, int color, int maxLines) {
        return new Text.Builder().setText(label).setMaxLines(maxLines).setMultilineAlignment(TEXT_ALIGN_CENTER)
            .setFontStyle(new FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build()).build();
    }

    private static <T> ListenableFuture<T> completed(T value) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(value);
            return "Home tile";
        });
    }
}
