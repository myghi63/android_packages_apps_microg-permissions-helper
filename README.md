# microG permissions helper

Headless system app (platform-signed, `/product/priv-app`, no UI) that applies
the permission defaults for the microG stack that the platform cannot express
via `default-permissions.xml`. Built by the LineageOS tree; integrated by
`vendor/microG/common.mk`.

## Why an app at all

- `default-permissions.xml` (the `<exceptions>` reader, `DefaultPermissionGrantPolicy`)
  only calls `grantRuntimePermission()`. It cannot touch AppOps or web-link state.
- AppOps ("install unknown apps", "display over other apps") require
  `AppOpsManager.setMode` (`@SystemApi`, needs `MANAGE_APP_OPS_MODES`).
- "Open by default" web links are per-(app, domain) state in `DomainVerificationService`;
  enabling the unverified ones ("supported links") uses
  `DomainVerificationManager.setDomainVerificationUserSelection` (`@SystemApi`, needs
  `UPDATE_DOMAIN_VERIFICATION_USER_SELECTION`) — the exact APIs the Settings UI uses.
- Both permissions are `signature`-protected, so a platform-signed in-tree app is
  the clean carrier. An init script would need root + sepolicy and would not be
  portable; there is no `cmd domain_verification` shell implementation on this build.

## Phases

- **Core** — first `BOOT_COMPLETED` per user (also after a helper data wipe):
  sets the AppOps modes, enables the configured domains and (for entries with
  `"permissions": "all"`) grants every runtime permission the package requests but
  does not hold; plus one blanket pass enabling every installed app's supported links
  (except `deny`). Guarded by the `core_done_v2` marker in SharedPreferences
  (`helper_state`); bump the key in `BootReceiver.java` to force a re-run after a
  config change.
- **On install/update** — `PACKAGE_ADDED`/`PACKAGE_REPLACED` (3s delay, so the domain
  state is already collected): for any package not in `deny`, every declared-but-
  unverified web domain of that app ("supported links", often hundreds per app)
  is approved at once. Verified domains (autoVerify) need nothing — the platform
  approves them by itself.

## Config (`assets/config.json`)

```json
{
  "core": [
    { "package": "com.aurora.store",
      "appops": ["android:request_install_packages"],
      "permissions": "all",
      "domains": ["play.google.com", "market.android.com"] }
  ],
  "deny": []
}
```

- `appops`: AppOps long op names (`AppOpsManager.OPSTR_*`, the `android:` form that
  `strOpToOp` accepts — the short names `adb shell appops` maps internally are NOT
  accepted here), always set to `MODE_ALLOWED`.
- `domains`: exact hosts to enable. A plain entry also matches subdomains
  (`"app.goo.gl"` matches `"page.app.goo.gl"`). Only domains the app actually
  declares in its manifest web-intent filters can be approved — undeclared entries
  are silently skipped (they never appear in the app's domain state).
- `permissions` (optional, per `core` entry): `"all"` = grant every runtime permission
  the app requests and lacks; a JSON array of names = grant only those. Restricted to
  `PROTECTION_DANGEROUS` permissions (what the runtime-grant API can hold); runs on the
  core boot pass and on that package's install/update. Deliberately per-app opt-in —
  unlike links, dangerous permissions are never blanket-granted to unknown apps.
  Uses `PackageManager.grantRuntimePermission` (`@SystemApi`, in the System SDK) with
  `GRANT_RUNTIME_PERMISSIONS` (`signature|installer|verifier` — the platform signature
  grants it; no privapp-permissions entry needed).
- `deny`: packages exempt from the blanket link approval (link-hijack suspects, apps
  with links you open somewhere else). Blanket approval mirrors what Google Play does
  on stock devices; add an app here only if you want its links left unapproved.
  No code change required.

## Security note

Mass approval can transfer a domain that another app had only *user-selected*
(the platform hands overrideable owners over automatically, same as the Settings
UI does). Domains already held by a *verified* (non-overrideable) owner are never
taken — the helper detects them via `getOwnersForDomain`, skips the domain and
logs it. Blanket approval trusts whatever an app *declares*; the `deny` list is
the opt-out for apps you do not want claiming links.

## Build wiring

- Manifest entry (device local manifest):
  ```xml
  <project name="android_packages_apps_microg-permissions-helper"
           path="packages/apps/microg-permissions-helper"
           remote="myghi63" revision="main" />
  ```
- `vendor/microG/common.mk`: `PRODUCT_PACKAGES += microg-permissions-helper` plus
  the privapp allowlist `permissions/privapp-permissions-microg-helper.xml`
  (only the two signature permissions; normal perms need no entry).

## Verify on a running device

- `adb shell pm list packages | grep microgpermhelper`
- `adb logcat -s MicrogPermHelper` → `appop … allowed for …`,
  `approved N link domains for …`
- Settings → Apps → special access → Install unknown apps: Aurora / Companion /
  F-Droid already toggled on. Settings → Apps → Aurora / F-Droid / Google Services
  → "Open by default": the configured domains enabled.

## Out of scope

- Runtime permissions of apps NOT listed in `core`: left to the platform's normal
  flows (`vendor/microG/permissions/default-permissions-microg.xml` still covers the
  microG baseline; `"permissions": "all"` makes the helper able to subsume such XML
  defaults for its listed apps in other ROMs).
- Google sync adapters (calendar/contacts): GmsCore carries its own; if a separate
  adapter package ever needs defaults, add it to `core` — no code change needed.
