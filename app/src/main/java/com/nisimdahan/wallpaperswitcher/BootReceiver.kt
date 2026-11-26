package com.nisimdahan.wallpaperswitcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** מפעיל את השירות מחדש אחרי הפעלה מחדש של הטלפון או עדכון האפליקציה */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.isEnabled(context) && Library.hasAny(context)) {
            SwitcherService.start(context)
        }
    }
}
