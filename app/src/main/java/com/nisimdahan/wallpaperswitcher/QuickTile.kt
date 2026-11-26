package com.nisimdahan.wallpaperswitcher

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Context
import android.widget.Toast

/** כפתור בהגדרות המהירות (מגירת ההתראות): "החלף רקע" בלחיצה אחת */
class QuickTile : TileService() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onStartListening() {
        qsTile?.apply {
            state = if (Library.hasAny(this@QuickTile)) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        if (!Library.hasAny(this)) {
            Toast.makeText(this, getString(R.string.tile_need_images), Toast.LENGTH_SHORT).show()
            return
        }
        WallpaperChanger.changeAsync(this)
    }
}
