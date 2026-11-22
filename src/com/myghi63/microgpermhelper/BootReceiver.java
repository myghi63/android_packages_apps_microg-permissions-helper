package com.myghi63.microgpermhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** One-time core pass per user (boot or app-data wipe). To re-run after editing
 *  assets/config.json, bump KEY_CORE_DONE (e.g. to core_done_v2) and rebuild. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "helper_state";
    private static final String KEY_CORE_DONE = "core_done_v1";

    @Override
    public void onReceive(Context context, Intent intent) {
        final SharedPreferences state =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (state.getBoolean(KEY_CORE_DONE, false)) {
            return;
        }
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Helper.applyCore(context);
                state.edit().putBoolean(KEY_CORE_DONE, true).apply();
            } finally {
                result.finish();
            }
        }).start();
    }
}
