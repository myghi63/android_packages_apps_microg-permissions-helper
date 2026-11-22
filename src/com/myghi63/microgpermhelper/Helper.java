package com.myghi63.microgpermhelper;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainOwner;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.util.ArraySet;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the permission defaults described in assets/config.json:
 *  - "core": AppOps modes + approved web links for the fixed microG-stack apps;
 *  - "mass": allowlisted apps whose declared-but-unverified web domains ("supported
 *            links") are approved automatically, never taking a domain away from a
 *            non-overrideable (verified) owner.
 */
public final class Helper {
    private static final String TAG = "MicrogPermHelper";
    private static final String CONFIG_ASSET = "config.json";

    private Helper() {}

    public static void applyCore(Context ctx) {
        try {
            JSONObject cfg = loadConfig(ctx);
            AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
            DomainVerificationManager dvm =
                    ctx.getSystemService(DomainVerificationManager.class);
            JSONArray core = cfg.optJSONArray("core");
            for (int i = 0; core != null && i < core.length(); i++) {
                JSONObject entry = core.getJSONObject(i);
                applyEntry(ctx, ops, dvm, entry.getString("package"),
                        strList(entry.optJSONArray("appops")),
                        strList(entry.optJSONArray("domains")));
            }
            // Apps installed before this helper existed still get one mass pass.
            for (String pkg : strList(cfg.optJSONArray("mass"))) {
                enableLinks(dvm, pkg, null);
            }
        } catch (Exception ex) {
            Log.e(TAG, "applyCore failed", ex);
        }
    }

    public static void applyMassForPackage(Context ctx, String pkg) {
        try {
            JSONObject cfg = loadConfig(ctx);
            if (!strList(cfg.optJSONArray("mass")).contains(pkg)) {
                return;
            }
            DomainVerificationManager dvm =
                    ctx.getSystemService(DomainVerificationManager.class);
            enableLinks(dvm, pkg, null);
        } catch (Exception ex) {
            Log.e(TAG, "applyMassForPackage failed for " + pkg, ex);
        }
    }

    private static void applyEntry(Context ctx, AppOpsManager ops, DomainVerificationManager dvm,
            String pkg, List<String> appops, List<String> domains) {
        for (String op : appops) {
            try {
                int uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
                ops.setMode(op, uid, pkg, AppOpsManager.MODE_ALLOWED);
                Log.i(TAG, "appop " + op + " allowed for " + pkg);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "skip appop " + op + ": " + pkg + " not installed");
            } catch (Exception e) {
                Log.w(TAG, "appop " + op + " for " + pkg + " failed: " + e);
            }
        }
        if (!domains.isEmpty()) {
            enableLinks(dvm, pkg, domains);
        }
    }

    /**
     * Approve web domains of pkg still at DOMAIN_STATE_NONE. desired == null means
     * every declared domain (mass mode). Verified domains need nothing: the
     * platform approves them on its own.
     */
    private static void enableLinks(DomainVerificationManager dvm, String pkg,
            List<String> desired) {
        try {
            DomainVerificationUserState state = dvm.getDomainVerificationUserState(pkg);
            if (state == null) {
                Log.d(TAG, "no web-intent state for " + pkg);
                return;
            }
            Set<String> enable = new ArraySet<>();
            for (Map.Entry<String, Integer> host : state.getHostToStateMap().entrySet()) {
                if (host.getValue() != DomainVerificationUserState.DOMAIN_STATE_NONE) {
                    continue;
                }
                if (desired != null && !matches(desired, host.getKey())) {
                    continue;
                }
                if (blockedByOther(dvm, pkg, host.getKey())) {
                    continue;
                }
                enable.add(host.getKey());
            }
            if (enable.isEmpty()) {
                return;
            }
            int rc = dvm.setDomainVerificationUserSelection(state.getIdentifier(), enable, true);
            if (rc == DomainVerificationManager.STATUS_OK) {
                Log.i(TAG, "approved " + enable.size() + " link domains for " + pkg);
            } else {
                Log.w(TAG, "user selection for " + pkg + " returned error " + rc);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "package not installed: " + pkg);
        } catch (Exception e) {
            Log.w(TAG, "enableLinks failed for " + pkg + ": " + e);
        }
    }

    /** Exact host match, or suffix match for subdomains ("app.goo.gl" also matches
     *  "page.app.goo.gl"). */
    private static boolean matches(List<String> desired, String host) {
        for (String d : desired) {
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blockedByOther(DomainVerificationManager dvm, String pkg, String host) {
        for (DomainOwner owner : dvm.getOwnersForDomain(host)) {
            if (!owner.getPackageName().equals(pkg) && !owner.isOverrideable()) {
                Log.i(TAG, "domain " + host + " stays with " + owner.getPackageName()
                        + " (non-overrideable owner)");
                return true;
            }
        }
        return false;
    }

    private static JSONObject loadConfig(Context ctx) throws Exception {
        try (InputStream in = ctx.getAssets().open(CONFIG_ASSET)) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> strList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            out.add(arr.optString(i));
        }
        return out;
    }
}
