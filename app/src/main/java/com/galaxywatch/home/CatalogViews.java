package com.galaxywatch.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class CatalogViews {
    static final int BG = 0xFF171118;
    static final int SURFACE = 0xFF302631;
    static final int TEXT = 0xFFFFF1F2;
    static final int MUTED = 0xFFCEBFCA;
    static final int ROSE = 0xFFFFD9E5;
    static final int AMBER = 0xFFFFD06B;
    private static final int EDGE_ROSE = 0xFFEDBED0;
    private static final int LIT = 0xFF674423;
    private static final int LIT_TEXT = 0xFFFFF3DC;

    private final Context context;

    CatalogViews(Context context) { this.context = context; }

    int dp(float value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }

    TextView text(String label, int size, int color) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(size);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(color);
        return text;
    }

    Button button(String label, Runnable action, int icon) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setStateListAnimator(null);
        button.setBackground(pill(SURFACE, 0, 0));
        if (icon != 0) {
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setCompoundDrawablePadding(dp(6));
            button.setCompoundDrawablesRelative(drawable(icon, 18, ROSE), null, null, null);
        }
        button.setOnClickListener(view -> action.run());
        return button;
    }

    void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .62f);
    }

    void edge(Button button, boolean allOff) {
        int fill = allOff ? SURFACE : EDGE_ROSE;
        if (context.getResources().getConfiguration().isScreenRound()) {
            Drawable edge = context.getDrawable(R.drawable.edge_action).mutate();
            edge.setTint(fill);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), edge, null));
        } else {
            button.setBackground(pill(fill, 0, 0));
        }
        button.setTextColor(allOff ? MUTED : BG);
    }

    Drawable drawable(int resource, int size, Integer tint) {
        Drawable drawable = context.getDrawable(resource).mutate();
        if (tint != null) drawable.setTint(tint);
        drawable.setBounds(0, 0, dp(size), dp(size));
        return drawable;
    }

    RippleDrawable pill(int surface, int outline, int fixture) {
        GradientDrawable fill = shape(surface, 0);
        Drawable content = fill;
        if (fixture != 0 || outline != 0) {
            LayerDrawable layers;
            if (fixture == 0) {
                layers = new LayerDrawable(new Drawable[]{fill, shape(Color.TRANSPARENT, outline)});
            } else {
                GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{surface, surface, (surface & 0xFFFFFF) | 0x20000000});
                layers = new LayerDrawable(new Drawable[]{fill, context.getDrawable(fixture), fade,
                    shape(Color.TRANSPARENT, outline)});
            }
            layers.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
            content = layers;
        }
        return new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), content, shape(Color.WHITE, 0));
    }

    private GradientDrawable shape(int color, int outline) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(21));
        if (outline != 0) shape.setStroke(dp(2), outline);
        return shape;
    }

    final class RoomCard extends LinearLayout {
        private final RoomIcons theme;
        private final TextView mark;
        private final ImageView bulb;
        private RoomOverview.Status last;

        RoomCard(WatchCatalog.Room room, Runnable open) {
            super(context);
            theme = RoomIcons.forRoom(room.theme);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setMinimumHeight(dp(48));
            setPadding(dp(3), dp(3), dp(3), dp(3));
            setFocusable(true);
            setOnClickListener(view -> open.run());

            LinearLayout symbols = new LinearLayout(context);
            symbols.setGravity(Gravity.CENTER);
            ImageView icon = symbol(theme.icon());
            symbols.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
            bulb = symbol(R.drawable.ic_light_on);
            LinearLayout.LayoutParams bulbLayout = new LinearLayout.LayoutParams(dp(14), dp(14));
            bulbLayout.leftMargin = dp(3);
            symbols.addView(bulb, bulbLayout);
            mark = text("", 14, AMBER);
            mark.setIncludeFontPadding(false);
            mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            symbols.addView(mark);
            addView(symbols);

            TextView name = text(room.label.replace("-", "-\n"), 13, TEXT);
            name.setIncludeFontPadding(false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(name);
            setContentDescription(room.label);
        }

        private ImageView symbol(int resource) {
            ImageView image = new ImageView(context);
            image.setImageResource(resource);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return image;
        }

        @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }

        void display(RoomOverview.Status status) {
            if (status.equals(last)) return;
            last = status;
            mark.setText(status.mark());
            setStateDescription(status.description());
            boolean lit = status.on() > 0;
            bulb.setVisibility(lit ? VISIBLE : GONE);
            mark.setTextColor(lit ? 0xFFFFDB73 : 0xFFF0E6ED);
            mark.setPadding(status.unknown() > 0 && !lit ? dp(3) : 0, 0, 0, 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(lit ? LIT : theme.surface());
            if (lit) bg.setStroke(dp(2), AMBER);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF), bg, null));
        }
    }

    final class LightButton extends Button {
        private final int artwork;
        private int icon;

        LightButton(WatchCatalog.Light light, Runnable toggle) {
            super(context);
            artwork = switch (light.theme) {
                case "pendant" -> R.drawable.fixture_pendant;
                case "wardrobe" -> R.drawable.fixture_wardrobe;
                case "sconce" -> R.drawable.fixture_sconce;
                case "kitchen" -> R.drawable.fixture_kitchen;
                case "entry" -> R.drawable.fixture_entry;
                case "track" -> R.drawable.fixture_track;
                case "mirror" -> R.drawable.fixture_mirror;
                case "shower" -> R.drawable.fixture_shower;
                default -> 0;
            };
            setText(light.label);
            setAllCaps(false);
            setTextColor(TEXT);
            setTextSize(15);
            setMinimumHeight(dp(48));
            setMinHeight(dp(48));
            setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            setPadding(dp(10), dp(4), dp(10), dp(4));
            setCompoundDrawablePadding(dp(5));
            setClipToOutline(true);
            setStateListAnimator(null);
            setOnClickListener(view -> toggle.run());
        }

        void display(Boolean power, boolean pending, boolean canToggle, boolean animate) {
            boolean lit = Boolean.TRUE.equals(power);
            int next = pending ? R.drawable.ic_light_waiting : power == null ? R.drawable.ic_light_unknown
                : lit ? R.drawable.ic_light_on : R.drawable.ic_light;
            if (icon != next) {
                animation(false);
                icon = next;
                setCompoundDrawablesRelative(drawable(next, lit ? 27 : 23, lit && !pending ? null : MUTED), null, null, null);
                setBackground(pill(lit ? LIT : SURFACE, lit ? AMBER : 0, artwork));
                setTextColor(lit ? LIT_TEXT : TEXT);
                setStateDescription(pending ? "Ожидаем ответ" : power == null ? "Состояние неизвестно"
                    : lit ? "Включено" : "Выключено");
            }
            setEnabled(canToggle);
            setAlpha(canToggle || pending || lit ? 1f : .62f);
            animation(animate);
        }

        void animation(boolean active) {
            if (getCompoundDrawablesRelative()[0] instanceof Animatable drawable) {
                if (active && ValueAnimator.areAnimatorsEnabled() && isShown()) {
                    if (!drawable.isRunning()) drawable.start();
                } else if (drawable.isRunning()) drawable.stop();
            }
        }
    }
}
