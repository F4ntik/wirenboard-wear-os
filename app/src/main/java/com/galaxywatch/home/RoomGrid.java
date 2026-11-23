package com.galaxywatch.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The room buttons keep their positions and accessibility focus across state updates. */
final class RoomGrid extends LinearLayout {
    private final List<Rooms.Room> order;
    private final Consumer<Rooms.Room> open;
    private final Map<Rooms.Room, Card> cards = new HashMap<>();
    private List<Rooms.Room> shown, unknown;
    private String message;

    RoomGrid(Context context, List<Rooms.Room> favorites, Consumer<Rooms.Room> open) {
        super(context);
        this.open = open;
        order = new ArrayList<>(favorites);
        for (Rooms.Room room : Rooms.ALL) if (!order.contains(room)) order.add(room);
        setOrientation(VERTICAL);
    }

    void display(Map<Rooms.Light, Boolean> values, boolean onlyLit) {
        List<Rooms.Room> first = new ArrayList<>(), missing = new ArrayList<>();
        Map<Rooms.Room, RoomOverview.Status> statuses = new HashMap<>();
        for (Rooms.Room room : order) {
            var status = RoomOverview.status(room, values);
            statuses.put(room, status);
            if (!onlyLit || status.on() > 0) first.add(room);
            else if (status.unknown() > 0) missing.add(room);
        }
        String empty = !onlyLit || !first.isEmpty() ? "" : missing.isEmpty()
            ? "В комнатах свет погашен" : "Пока нет подтверждённого света";
        if (!first.equals(shown) || !missing.equals(unknown) || !empty.equals(message)) {
            removeAllViews();
            cards.clear();
            if (!empty.isEmpty()) heading(empty);
            rows(first);
            if (!missing.isEmpty()) { heading("Нет полных данных"); rows(missing); }
            shown = first;
            unknown = missing;
            message = empty;
        }
        cards.forEach((room, card) -> card.update(statuses.get(room)));
    }

    private void heading(String text) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(13);
        label.setTextColor(0xFFE4D7DF);
        label.setPadding(0, dp(6), 0, dp(2));
        addView(label);
    }

    private void rows(List<Rooms.Room> rooms) {
        for (int i = 0; i < rooms.size(); i += 2) {
            LinearLayout row = new LinearLayout(getContext());
            row.setBaselineAligned(false);
            for (int j = 0; j < 2; j++) {
                LayoutParams cell = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
                if (j == 1) cell.leftMargin = dp(5);
                if (i + j >= rooms.size()) { row.addView(new View(getContext()), cell); break; }
                Rooms.Room room = rooms.get(i + j);
                Card card = new Card(room);
                cards.put(room, card);
                row.addView(card, cell);
            }
            LayoutParams line = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            line.topMargin = dp(4);
            addView(row, line);
        }
    }

    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    private final class Card extends LinearLayout {
        final Rooms.Room room;
        final TextView count;
        final ImageView bulb;
        RoomOverview.Status last;
        Card(Rooms.Room room) {
            super(RoomGrid.this.getContext());
            this.room = room;
            setGravity(Gravity.CENTER);
            setOrientation(VERTICAL);
            setMinimumHeight(dp(48));
            setPadding(dp(3), dp(3), dp(3), dp(3));
            LinearLayout symbols = new LinearLayout(getContext());
            symbols.setGravity(Gravity.CENTER);
            ImageView image = image(RoomIcons.forRoom(room.id()).icon());
            symbols.addView(image, new LayoutParams(dp(18), dp(18)));
            bulb = image(R.drawable.ic_light_on);
            LayoutParams icon = new LayoutParams(dp(14), dp(14));
            icon.leftMargin = dp(3);
            symbols.addView(bulb, icon);
            count = text(14);
            count.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            symbols.addView(count);
            addView(symbols);
            TextView title = text(13);
            title.setText(room.label().replace("-", "-\n"));
            title.setTextColor(0xFFFFF1F2);
            addView(title);
            setFocusable(true);
            setOnClickListener(v -> open.accept(room));
        }
        private TextView text(float size) {
            TextView t = new TextView(getContext());
            t.setGravity(Gravity.CENTER);
            t.setTextSize(size);
            t.setIncludeFontPadding(false);
            t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return t;
        }
        private ImageView image(int id) {
            ImageView image = new ImageView(getContext());
            image.setImageResource(id);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return image;
        }
        @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }

        void update(RoomOverview.Status status) {
            if (status.equals(last)) return;
            last = status;
            boolean on = status.on() > 0;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(on ? 0xFF674423 : RoomIcons.forRoom(room.id()).surface());
            if (on) bg.setStroke(dp(2), 0xFFFFD06B);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), bg, null));
            bulb.setVisibility(on ? VISIBLE : GONE);
            count.setText(status.mark());
            count.setPadding(status.unknown() > 0 && !on ? dp(3) : 0, 0, 0, 0);
            count.setTextColor(on ? 0xFFFFDB73 : 0xFFF0E6ED);
            setContentDescription(room.label() + ". " + status.description());
        }
    }
}
