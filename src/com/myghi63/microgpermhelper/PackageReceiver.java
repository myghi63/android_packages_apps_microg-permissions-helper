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
