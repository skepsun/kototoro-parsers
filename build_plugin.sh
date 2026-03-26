#!/bin/bash
set -e

echo "Building raw JAR..."
./gradlew jar --no-daemon

echo "Finding d8 path from Android SDK..."
if [ -z "$ANDROID_HOME" ]; then
    echo "ANDROID_HOME is not set. Falling back to default Mac Android Studio SDK location..."
    ANDROID_HOME="$HOME/Library/Android/sdk"
fi

# Find the latest build-tools dir
BUILD_TOOLS_DIR=$(ls -d $ANDROID_HOME/build-tools/* | tail -1)
D8_PATH="$BUILD_TOOLS_DIR/d8"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

if [ ! -f "$D8_PATH" ]; then
    echo "Could not find d8 at $D8_PATH. Please ensure ANDROID_HOME is set correctly."
    exit 1
fi

cd build/libs
RAW_JAR=$(ls kototoro-parsers-*.jar | head -n 1)

if [ -z "$RAW_JAR" ]; then
    echo "Raw jar not found in build/libs."
    exit 1
fi

echo "Running d8 Dex converter on $RAW_JAR..."
$D8_PATH --release --lib $ANDROID_JAR --output . $RAW_JAR

echo "Packaging classes.dex into final kototoro-parsers-plugin.jar..."
jar cvf kototoro-parsers-plugin.jar classes.dex
rm classes.dex

echo "Done! Final plugin is located at: build/libs/kototoro-parsers-plugin.jar"
echo "You can now import this file directy into Kototoro via Settings -> Remote Sources."
