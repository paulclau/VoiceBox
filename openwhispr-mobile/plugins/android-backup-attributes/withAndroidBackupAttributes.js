/**
 * Expo config plugin: declare `tools:replace` for the backup attributes the app
 * sets, so a library manifest cannot win the merge.
 *
 * Why: `expo-secure-store` puts `android:dataExtractionRules` and
 * `android:fullBackupContent` on `<application>` (its rules keep the
 * Keystore-backed store out of cloud backups), and `com.appsflyer:af-android-sdk`
 * declares both too. Without `tools:replace` the manifest merger fails:
 *
 *   Attribute application@dataExtractionRules value=(@xml/secure_store_data_extraction_rules)
 *   is also present at [com.appsflyer:af-android-sdk:x] AndroidManifest.xml value=(@xml/appsflyer_data_extraction_rules).
 *
 * AppsFlyer's own plugin can be told to prefer its rules
 * (`preferAppsFlyerBackupRules: true`), which makes it *delete* ours — the wrong
 * way round: the app's rules are what keep secure-store values out of backups.
 *
 * Registered last in `app.base.json` so it runs after the plugins that write
 * these attributes. Written as a prebuild-time mod because `android/` is
 * git-ignored and regenerated on every prebuild.
 */

const { withAndroidManifest } = require('expo/config-plugins');

/** App-owned backup attributes; each is only replaced when the app declares it. */
const APP_OWNED_ATTRIBUTES = ['android:dataExtractionRules', 'android:fullBackupContent'];

module.exports = function withAndroidBackupAttributes(config) {
  return withAndroidManifest(config, (cfg) => {
    const application = cfg.modResults?.manifest?.application?.[0];
    if (!application) {
      console.warn('[android-backup-attributes] No <application> element; nothing replaced');
      return cfg;
    }

    const attrs = application.$ ?? (application.$ = {});
    const owned = APP_OWNED_ATTRIBUTES.filter((name) => attrs[name] !== undefined);
    if (owned.length === 0) return cfg;

    // Preserve any tools:replace another manifest/mod already asked for.
    const existing = String(attrs['tools:replace'] ?? '')
      .split(',')
      .map((value) => value.trim())
      .filter(Boolean);
    const merged = [...new Set([...existing, ...owned])];
    attrs['tools:replace'] = merged.join(',');

    console.log(`[android-backup-attributes] tools:replace=${merged.join(',')}`);
    return cfg;
  });
};
