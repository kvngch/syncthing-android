package com.nutomic.syncthingandroid.service;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.nutomic.syncthingandroid.activities.PhotoShootActivity;


public class QuickSettingsTileCamera extends TileService {
    public QuickSettingsTileCamera() {
    }

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }
        super.onStartListening();
    }

    @Override
    public void onClick() {
        startActivity(
                    new Intent(getApplicationContext(), PhotoShootActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        super.onClick();
    }
}
