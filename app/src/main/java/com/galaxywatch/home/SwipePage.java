package com.galaxywatch.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

final class SwipePage {
    private SwipePage() {}

    static Bitmap capture(ScrollView page) {
        int width = page.getWidth();
        int height = page.getHeight();
        if (width == 0 || height == 0) return null;
        Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(preview);
        canvas.translate(-page.getScrollX(), -page.getScrollY());
        page.draw(canvas);
        return preview;
    }

    static View withPreview(View page, Bitmap preview) {
        if (preview == null) return page;
        FrameLayout viewport = new FrameLayout(page.getContext());
        // Wear translates the child and draws its scrim over the parent's background.
        viewport.setBackground(new BitmapDrawable(page.getResources(), preview));
        viewport.addView(page, MATCH_PARENT, MATCH_PARENT);
        return viewport;
    }

    static void restoreScroll(ScrollView page, int scrollY) {
        if (scrollY == 0) return;
        page.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                int oldLeft, int oldTop, int oldRight, int oldBottom) {
                page.removeOnLayoutChangeListener(this);
                page.scrollTo(0, scrollY);
            }
        });
    }

    @SuppressWarnings("deprecation")
    static void finish(Activity activity, View page) {
        // AndroidX resets the translation before the dismissal callback.
        page.setVisibility(View.INVISIBLE);
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
        activity.finish();
        if (Build.VERSION.SDK_INT < 34) activity.overridePendingTransition(0, 0);
    }
}
