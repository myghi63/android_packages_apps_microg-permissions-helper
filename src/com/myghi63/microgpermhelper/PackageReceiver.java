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
import android.os.Handler;
import android.os.Looper;

/** Install/update pass (links blanket + opt-in core permissions); skips config "deny". */
public final class PackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null || intent.getAction() == null) {
            return;
        }
        final String pkg = intent.getData().getSchemeSpecificPart();
        if (pkg == null || pkg.equals(context.getPackageName())) {
            return;
        }
        // DomainVerificationService collects a fresh package's web intents slightly
        // after PACKAGE_ADDED; run the approval pass after a short delay.
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> Helper.applyInstallPass(app, pkg), 3000);
    }
}
