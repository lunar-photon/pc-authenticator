#!/bin/bash
set -e

TOOLCHAIN="/home/lunarphoton/.local/share/android-toolchain"
JAVA_HOME="$TOOLCHAIN/jdk-17.0.10+7"
AAPT2="$TOOLCHAIN/aapt2"
ANDROID_JAR="$TOOLCHAIN/android.jar"
R8_JAR="$TOOLCHAIN/r8.jar"
SIGNER_JAR="$TOOLCHAIN/uber-apk-signer.jar"

REPO_DIR="/home/lunarphoton/Downloads/All/pc-authenticator/android"
PROJECT="/home/lunarphoton/.local/share/pc-authenticator-android"
BUILD_DIR="$PROJECT/build"
DIST_DIR="$PROJECT/dist"

# If repo source exists, sync latest source & res to toolchain build project
if [ -d "$REPO_DIR/src" ] && [ -d "$REPO_DIR/res" ]; then
    echo "[0/6] Syncing latest code and resources from repository..."
    mkdir -p "$PROJECT/src" "$PROJECT/res"
    cp -r "$REPO_DIR/src/"* "$PROJECT/src/"
    cp -r "$REPO_DIR/res/"* "$PROJECT/res/"
    cp "$REPO_DIR/AndroidManifest.xml" "$PROJECT/AndroidManifest.xml"
fi

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
    
    # Deploy PCAuthenticator.apk to all distribution locations
    cp -f "$SIGNED_APK" "/home/lunarphoton/Downloads/PCAuthenticator.apk"
    cp -f "$SIGNED_APK" "/home/lunarphoton/Downloads/All/pc-authenticator/android/PCAuthenticator.apk" 2>/dev/null || true
    cp -f "$SIGNED_APK" "/home/lunarphoton/Downloads/All/pc-authenticator/linux/static/PCAuthenticator.apk" 2>/dev/null || true
    if [ -d "/home/lunarphoton/.config/pc-authenticator/static" ]; then
        cp -f "$SIGNED_APK" "/home/lunarphoton/.config/pc-authenticator/static/PCAuthenticator.apk"
    fi
    rm -f "/home/lunarphoton/Downloads/authenticator.apk" "/home/lunarphoton/Downloads/PCConnect.apk"
    
    echo "Deployed to:"
    echo "📁 /home/lunarphoton/Downloads/PCAuthenticator.apk"
    echo "📁 /home/lunarphoton/Downloads/All/pc-authenticator/android/PCAuthenticator.apk"
    echo "📁 /home/lunarphoton/Downloads/All/pc-authenticator/linux/static/PCAuthenticator.apk"
    if [ -d "/home/lunarphoton/.config/pc-authenticator/static" ]; then
        echo "📁 /home/lunarphoton/.config/pc-authenticator/static/PCAuthenticator.apk"
    fi
    echo "=================================================="
    ls -lh "/home/lunarphoton/Downloads/PCAuthenticator.apk"
else
    echo "❌ Signing failed, output APK not found in $DIST_DIR"
    exit 1
fi
