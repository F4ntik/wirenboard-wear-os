package com.galaxywatch.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;

/** Draws command feedback inside the existing touch target without remeasuring its label. */
class CommandButton extends Button {
    private static final long DELAY_MS = 180;
    private static final long LAP_MS = 1050;
    private final float density;
    private Paint indicator;
    private boolean waiting;
    private long showAt;

    CommandButton(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
    }

    void commandState(boolean pending, CharSequence confirmedDescription) {
        CharSequence description = pending ? getResources().getText(R.string.light_state_pending) : confirmedDescription;
        if (!TextUtils.equals(getStateDescription(), description)) setStateDescription(description);
        if (waiting == pending) return;
        waiting = pending;
        if (pending) {
            showAt = SystemClock.uptimeMillis() + DELAY_MS;
            postInvalidateDelayed(DELAY_MS);
        } else {
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!waiting) return;
        long now = SystemClock.uptimeMillis();
        if (now < showAt) {
            postInvalidateDelayed(showAt - now);
            return;
        }
        if (indicator == null) {
            indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
            indicator.setStrokeWidth(2 * density);
            indicator.setStrokeCap(Paint.Cap.ROUND);
            indicator.setStyle(Paint.Style.STROKE);
        }
        boolean moving = hasWindowFocus() && ValueAnimator.areAnimatorsEnabled();
        float progress = moving ? ((now - showAt) % LAP_MS) / (float) LAP_MS : 0.25f;
        int ink = Color.luminance(getCurrentTextColor()) > .5 ? 0xFFFFD9E5 : 0xFF452331;
        Drawable icon = getCompoundDrawablesRelative()[0];
        canvas.save();
        canvas.translate(getScrollX(), getScrollY());
        if (icon != null) {
            float halfIcon = icon.getBounds().width() * .5f;
            float x = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? getWidth() - getPaddingRight() - halfIcon : getPaddingLeft() + halfIcon;
            float y = (getHeight() + getPaddingTop() - getPaddingBottom()) * .5f;
            float radius = halfIcon + density;
            indicator.setColor(ink);
            indicator.setAlpha(70);
            canvas.drawCircle(x, y, radius, indicator);
            indicator.setAlpha(255);
            canvas.drawArc(x - radius, y - radius, x + radius, y + radius, progress * 360 - 90, 105, false, indicator);
        } else {
            // Text-only actions keep their label, even on the narrow, curved bottom button.
            float half = Math.min(17 * density, getWidth() * .18f);
            float left = getWidth() * .5f - half;
            float y = 4 * density;
            indicator.setColor(ink);
            indicator.setAlpha(75);
            canvas.drawLine(left, y, left + 2 * half, y, indicator);
            indicator.setAlpha(255);
            float offset = half * .65f * (1 - (float) Math.cos(progress * Math.PI * 2));
            canvas.drawLine(left + offset, y, left + offset + half * .7f, y, indicator);
        }
        canvas.restore();
        if (moving) postInvalidateDelayed(33);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && waiting) invalidate();
    }
}
