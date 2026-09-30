#!/bin/bash
set -e

TOOLCHAIN="/home/lunarphoton/.local/share/android-toolchain"
JAVA_HOME="$TOOLCHAIN/jdk-17.0.10+7"
AAPT2="$TOOLCHAIN/aapt2"
ANDROID_JAR="$TOOLCHAIN/android.jar"
R8_JAR="$TOOLCHAIN/r8.jar"
SIGNER_JAR="$TOOLCHAIN/uber-apk-signer.jar"

PROJECT="/home/lunarphoton/.local/share/pc-authenticator-android"
BUILD_DIR="$PROJECT/build"
DIST_DIR="$PROJECT/dist"

echo "[1/6] Cleaning previous build..."
rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/compiled_res" "$BUILD_DIR/dex" "$DIST_DIR"

echo "[2/6] Compiling resources with AAPT2..."
"$AAPT2" compile --dir "$PROJECT/res" -o "$BUILD_DIR/compiled_res/res.zip"

echo "[3/6] Linking resources and generating R.java..."
"$AAPT2" link -I "$ANDROID_JAR" \
    --manifest "$PROJECT/AndroidManifest.xml" \
    -o "$BUILD_DIR/unaligned.apk" \
    --java "$BUILD_DIR/gen" \
    "$BUILD_DIR/compiled_res/res.zip" \
    --auto-add-overlay

echo "[4/6] Compiling Java source code..."
SOURCES=$(find "$PROJECT/src" "$BUILD_DIR/gen" -name "*.java")
"$JAVA_HOME/bin/javac" -encoding UTF-8 \
    -cp "$ANDROID_JAR" \
    -sourcepath "$PROJECT/src:$BUILD_DIR/gen" \
    -d "$BUILD_DIR/obj" \
    $SOURCES

echo "[5/6] Dexing bytecode with D8 (R8)..."
CLASSES=$(find "$BUILD_DIR/obj" -name "*.class")
"$JAVA_HOME/bin/java" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" \
    --release \
    --min-api 24 \
    --output "$BUILD_DIR/dex" \
    $CLASSES

# Add classes.dex into unaligned.apk
python3 -c "
import zipfile
with zipfile.ZipFile('$BUILD_DIR/unaligned.apk', 'a') as z:
    z.write('$BUILD_DIR/dex/classes.dex', 'classes.dex')
"

echo "[6/6] Zipaligning and Signing APK with Uber-APK-Signer..."
"$JAVA_HOME/bin/java" -jar "$SIGNER_JAR" \
    --apks "$BUILD_DIR/unaligned.apk" \
    --out "$DIST_DIR" \
    --allowResign

# Find the generated signed APK
SIGNED_APK=$(find "$DIST_DIR" -name "*aligned-debugSigned.apk" -o -name "*aligned-signed.apk" | head -n 1)

if [ -f "$SIGNED_APK" ]; then
    echo "=================================================="
    echo "✅ Custom APK built and signed successfully!"
    echo "Source APK: $SIGNED_APK"
    
    # Deploy only PCAuthenticator.apk
    cp -f "$SIGNED_APK" "/home/lunarphoton/Downloads/PCAuthenticator.apk"
    rm -f "/home/lunarphoton/Downloads/authenticator.apk" "/home/lunarphoton/Downloads/PCConnect.apk"
    
    echo "Deployed to:"
    echo "📁 /home/lunarphoton/Downloads/PCAuthenticator.apk"
    echo "=================================================="
    ls -lh "/home/lunarphoton/Downloads/PCAuthenticator.apk"
else
    echo "❌ Signing failed, output APK not found in $DIST_DIR"
    exit 1
fi
