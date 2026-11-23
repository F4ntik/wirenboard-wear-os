package com.galaxywatch.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** A tile tap always delivers its room, even when the watch has an existing app task. */
public final class CatalogTileEntryActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String entry = getIntent().getStringExtra(Rooms.EXTRA);
        if (CatalogLink.paired(this) && entry != null
                && (Rooms.TILE_HOME.equals(entry) || entry.startsWith(CatalogPresentation.ENTRY))) {
            startActivity(new Intent(this, CatalogActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Rooms.EXTRA, entry));
        }
        finish();
    }
}
