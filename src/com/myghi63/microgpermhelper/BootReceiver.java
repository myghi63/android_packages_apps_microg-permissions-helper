/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Myghi63
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.myghi63.microgpermhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** One-time core pass per user (boot or app-data wipe). To re-run after editing
 *  assets/config.json, bump KEY_CORE_DONE (e.g. to core_done_v2) and rebuild. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "helper_state";
    private static final String KEY_CORE_DONE = "core_done_v2";

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
