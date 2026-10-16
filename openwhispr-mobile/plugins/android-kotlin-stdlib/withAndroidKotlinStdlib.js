/**
 * Expo config plugin: pin `org.jetbrains.kotlin:kotlin-stdlib*` to the Kotlin
 * version the app itself compiles with (Expo's version catalog, exposed as
 * `rootProject.ext.kotlinVersion` by the `expo-root-project` plugin).
 *
 * Why: `react-native-appsflyer` declares
 * `kotlin-stdlib:${safeExtGet('kotlin_stdlib_version', '2.4.10')}` — a stdlib
 * newer than Expo's Kotlin (2.1.x). The declared version wins Gradle's
 * conflict resolution, and then `:react-native-appsflyer:compileDebugKotlin`
 * dies with an internal compiler error:
 *
 *   e: .../kotlin-stdlib-2.4.10.jar!/META-INF/kotlin-stdlib-jdk7.kotlin_module
 *      Module was compiled with an incompatible version of Kotlin.
 *      The binary version of its metadata is 2.4.0, expected version is 2.1.0.
 *   e: ... Exception in type checkers
 *   Caused by: java.lang.IllegalArgumentException: source must not be null
 *
 * (Kotlin reports the metadata mismatch as a *type-check* crash, so the raw
 * error reads as an ICE rather than a version conflict.)
 *
 * Forcing the stdlib to the compiler's own version is the fix Gradle expects;
 * the alternative — moving the whole project to Kotlin 2.4.x — is gated on
 * Expo's KSP lookup supporting it. The force is applied to every subproject and
 * every configuration, because a second dependency may also request a newer
 * stdlib transitively and the highest request would win again.
 *
 * Written as a prebuild-time edit (like the other `plugins/` entries) because
 * `android/` is git-ignored and regenerated on every prebuild.
 */

const { withDangerousMod } = require('expo/config-plugins');
const fs = require('fs');
const path = require('path');

const MARKER = '// [android-kotlin-stdlib] pin kotlin-stdlib to the project Kotlin version';

const BLOCK = `
${MARKER}
// See plugins/android-kotlin-stdlib/withAndroidKotlinStdlib.js — a dependency
// (react-native-appsflyer) defaults its stdlib to a Kotlin newer than the one
// Expo compiles with, which makes that module fail to compile with an
// internal compiler error. Force the compiler's own stdlib everywhere.
allprojects {
  configurations.all {
    resolutionStrategy {
      force "org.jetbrains.kotlin:kotlin-stdlib:\${rootProject.ext.kotlinVersion}"
      force "org.jetbrains.kotlin:kotlin-stdlib-jdk7:\${rootProject.ext.kotlinVersion}"
      force "org.jetbrains.kotlin:kotlin-stdlib-jdk8:\${rootProject.ext.kotlinVersion}"
    }
  }
}
`;

function addKotlinStdlibPin(contents) {
  if (contents.includes(MARKER)) return contents; // idempotent across prebuilds
  return `${contents}\n${BLOCK}`;
}

module.exports = function withAndroidKotlinStdlib(config) {
  return withDangerousMod(config, [
    'android',
    async (cfg) => {
      const buildGradle = path.join(cfg.modRequest.platformProjectRoot, 'build.gradle');
      if (!fs.existsSync(buildGradle)) {
        console.warn('[android-kotlin-stdlib] android/build.gradle not found; nothing pinned');
        return cfg;
      }
      const contents = fs.readFileSync(buildGradle, 'utf8');
      const next = addKotlinStdlibPin(contents);
      if (next !== contents) {
        fs.writeFileSync(buildGradle, next);
        console.log(
          '[android-kotlin-stdlib] Pinned kotlin-stdlib to rootProject.ext.kotlinVersion',
        );
      }
      return cfg;
    },
  ]);
};
