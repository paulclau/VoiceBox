#!/usr/bin/env bash
# Put the project toolchain on PATH:  source tools/env.sh
TOOLCHAIN="${TOOLCHAIN:-$HOME/.toolchain}"
export JAVA_HOME="$TOOLCHAIN/jdk-17"
export ANDROID_HOME="$TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$TOOLCHAIN/gradle-8.9/bin:$ANDROID_HOME/platform-tools:$PATH"
