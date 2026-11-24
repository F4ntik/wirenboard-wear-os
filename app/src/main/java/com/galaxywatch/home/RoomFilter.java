package com.galaxywatch.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.util.function.Consumer;

/** Both room lists use the same two mutually exclusive views. */
final class RoomFilter extends RadioGroup {
    RoomFilter(Context context, boolean onlyLit, Consumer<Boolean> onChange) {
        super(context);
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        setBackground(new InsetDrawable(pill(0xFF332A32), dp(8), dp(9), dp(8), dp(9)));
        setPadding(dp(9), 0, dp(9), 0);

        RadioButton all = option("Все комнаты", "Показать все комнаты", 1.1f);
        RadioButton lit = option("Свет горит", "Показать комнаты, где горит свет", .9f);
        check(onlyLit ? lit.getId() : all.getId());
        setOnCheckedChangeListener((group, checkedId) -> onChange.accept(checkedId == lit.getId()));
    }

    private RadioButton option(String label, String description, float weight) {
        RadioButton button = new RadioButton(getContext());
        button.setId(View.generateViewId());
        button.setButtonDrawable(null);
        button.setGravity(Gravity.CENTER);
        button.setText(label);
        button.setContentDescription(description);
        button.setTextSize(12);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));

        int[] checked = {android.R.attr.state_checked};
        StateListDrawable fill = new StateListDrawable();
        fill.addState(checked, pill(CatalogViews.ROSE));
        fill.addState(new int[0], pill(0x00000000));
        button.setBackgroundTintList(null);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x307F6775), fill, pill(0xFFFFFFFF));
        // The visible pill is smaller than the 48 dp touch target.
        button.setBackground(new InsetDrawable(ripple, 0, dp(11), 0, dp(11)));
        button.setPadding(dp(2), dp(11), dp(2), dp(11));
        button.setTextColor(new ColorStateList(new int[][]{checked, new int[0]},
            new int[]{CatalogViews.BG, CatalogViews.TEXT}));
        addView(button, new LayoutParams(0, LayoutParams.MATCH_PARENT, weight));
        return button;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(32));
        return shape;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
