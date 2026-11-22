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

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.verify.domain.DomainOwner;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.os.UserHandle;
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
 *  - "core": AppOps modes, approved web links and (opt-in "permissions") runtime
 *    permission auto-grant for the fixed microG-stack apps;
 *  - blanket link pass: every installed app's declared-but-unverified web domains
 *    ("supported links") are approved automatically, except packages in "deny";
 *    a domain is never taken away from a non-overrideable (verified) owner.
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
                        strList(entry.optJSONArray("domains")),
                        entry.opt("permissions"));
            }
            // Blanket pass over everything already installed.
            List<String> denied = strList(cfg.optJSONArray("deny"));
            for (PackageInfo pi : ctx.getPackageManager().getInstalledPackages(0)) {
                if (denied.contains(pi.packageName)) {
                    continue;
                }
                enableLinks(dvm, pi.packageName, null);
            }
        } catch (Exception ex) {
            Log.e(TAG, "applyCore failed", ex);
        }
    }

    /** Install/update pass: blanket link approval unless denied; runtime permission
     *  auto-grant only for core-listed packages that opted in. */
    public static void applyInstallPass(Context ctx, String pkg) {
        try {
            JSONObject cfg = loadConfig(ctx);
            if (strList(cfg.optJSONArray("deny")).contains(pkg)) {
                return;
            }
            DomainVerificationManager dvm =
                    ctx.getSystemService(DomainVerificationManager.class);
            enableLinks(dvm, pkg, null);
            JSONArray core = cfg.optJSONArray("core");
            for (int i = 0; core != null && i < core.length(); i++) {
                JSONObject entry = core.getJSONObject(i);
                if (pkg.equals(entry.getString("package")) && entry.opt("permissions") != null) {
                    grantPermissions(ctx, pkg, entry.opt("permissions"));
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "applyInstallPass failed for " + pkg, ex);
        }
    }

    private static void applyEntry(Context ctx, AppOpsManager ops, DomainVerificationManager dvm,
            String pkg, List<String> appops, List<String> domains, Object permissions) {
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
        if (permissions != null) {
            grantPermissions(ctx, pkg, permissions);
        }
    }

    /**
     * Opt-in per core entry: "permissions": "all" grants every runtime permission
     * the package requests but does not hold; a JSON array restricts the grant to
     * those names. Only PROTECTION_DANGEROUS permissions are touched (runtime ones);
     * grantRuntimePermission needs GRANT_RUNTIME_PERMISSIONS, which platform signing
     * gives us automatically.
     */
    private static void grantPermissions(Context ctx, String pkg, Object permissions) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            String[] requested = pi.requestedPermissions != null
                    ? pi.requestedPermissions : new String[0];
            List<String> explicit = permissions instanceof JSONArray
                    ? strList((JSONArray) permissions) : null;
            UserHandle user = ctx.getUser();
            int count = 0;
            for (String perm : requested) {
                if (pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                if (explicit != null && !explicit.contains(perm)) {
                    continue;
                }
                if (!isRuntimePermission(pm, perm)) {
                    continue;
                }
                pm.grantRuntimePermission(pkg, perm, user);
                count++;
            }
            if (count > 0) {
                Log.i(TAG, "granted " + count + " runtime permissions for " + pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "runtime permission grant for " + pkg + " failed: " + e);
        }
    }

    private static boolean isRuntimePermission(PackageManager pm, String perm) {
        try {
            return pm.getPermissionInfo(perm, 0).getProtection()
                    == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Approve web domains of pkg still at DOMAIN_STATE_NONE. desired == null means
     * every declared domain (blanket pass). Verified domains need nothing: the
     * platform approves them on its own.
     */
    private static void enableLinks(DomainVerificationManager dvm, String pkg,
            List<String> desired) {
        try {
            DomainVerificationUserState state = dvm.getDomainVerificationUserState(pkg);
            if (state == null) {
                if (desired != null) {
                    Log.d(TAG, "no web-intent state for " + pkg);
                }
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
