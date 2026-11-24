package com.galaxywatch.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ScrollView;
import android.widget.Toast;

final class BezelHelp {
    static final String GUIDE = "Ведите пальцем по краю экрана или вращайте кольцо, чтобы листать список.";
    private static final String SEEN = "bezel_scroll_hint_seen";

    static void showOnce(ScrollView list) {
        list.postDelayed(() -> {
            if (!list.isShown() || !list.hasWindowFocus()
                || !(list.canScrollVertically(1) || list.canScrollVertically(-1))) return;
            Context context = list.getContext();
            SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
            if (prefs.getBoolean(SEEN, false)) return;
            Toast.makeText(context, "Листайте по краю экрана", Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean(SEEN, true).apply();
        }, 500);
    }

    private BezelHelp() {}
}
