#!/usr/bin/env bash
# Reproducible Android/JVM toolchain install for this project, entirely under $HOME
# (the sandbox has no root, so nothing goes to /usr or /opt).
#
#   ./tools/setup-toolchain.sh          # install everything
#   source tools/env.sh                 # put it on PATH for a shell
#
# Pins: Temurin JDK 17, Gradle 8.9, Android cmdline-tools (platform 35, build-tools 35.0.0), the
# NDK and CMake named in app/build.gradle.kts (needed for libbark_asr.so).
set -euo pipefail

TOOLCHAIN="${TOOLCHAIN:-$HOME/.toolchain}"
JDK_VERSION="${JDK_VERSION:-17}"
GRADLE_VERSION="${GRADLE_VERSION:-8.9}"
SDK_PLATFORM="${SDK_PLATFORM:-35}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"
CMDLINE_TOOLS_V="${CMDLINE_TOOLS_V:-11076708}" # release id, not the version string
NDK_VERSION="${NDK_VERSION:-29.0.14206865}" # must match ndkVersion in app/build.gradle.kts
CMAKE_VERSION="${CMAKE_VERSION:-3.22.1}"     # must match the cmake version there

mkdir -p "$TOOLCHAIN"
cd "$TOOLCHAIN"

log() { printf '\n=== %s\n' "$*"; }

# --- JDK -------------------------------------------------------------------
if [ ! -x "$TOOLCHAIN/jdk-$JDK_VERSION/bin/java" ]; then
  log "Installing Temurin JDK $JDK_VERSION"
  curl -fsSL -o jdk.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/$JDK_VERSION/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "jdk-$JDK_VERSION"
  tar -xzf jdk.tar.gz -C "jdk-$JDK_VERSION" --strip-components=1
  rm -f jdk.tar.gz
fi
export JAVA_HOME="$TOOLCHAIN/jdk-$JDK_VERSION"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

# --- Gradle ----------------------------------------------------------------
if [ ! -x "$TOOLCHAIN/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  log "Installing Gradle $GRADLE_VERSION"
  curl -fsSL -o gradle.zip \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q gradle.zip
  rm -f gradle.zip
fi

# --- Android SDK -----------------------------------------------------------
export ANDROID_HOME="$TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Installing Android cmdline-tools"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o cmdtools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_V}_latest.zip"
  unzip -q cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f cmdtools.zip
fi

log "Accepting SDK licences"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true

log "Installing platform $SDK_PLATFORM, build-tools $BUILD_TOOLS, NDK $NDK_VERSION, CMake $CMAKE_VERSION"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install \
  "platforms;android-$SDK_PLATFORM" "build-tools;$BUILD_TOOLS" "platform-tools" \
  "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION"

log "Done"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "gradle=$TOOLCHAIN/gradle-$GRADLE_VERSION/bin/gradle"
