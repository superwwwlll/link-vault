#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME=/opt/android-sdk
export PATH="/opt/gradle-8.9/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"
# 版本号从 app/build.gradle.kts 读取，保证产物名与真实版本一致
VERSION="${VERSION:-$(grep -oE 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | cut -d'"' -f2)}"
timeout 1800 gradle --no-daemon --console=plain testDebugUnitTest lintDebug assembleRelease
chmod 600 /root/.android/debug.keystore
mkdir -p deliverables
APK="deliverables/lian-cang-${VERSION}.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

# 发出去的包绝不能是 debuggable：那等于给连着的电脑留了一条读私有目录的通道。
# 这一条是硬闸门，宁可构建失败也不能让这种包进到发布目录。
if aapt2 dump xmltree --file AndroidManifest.xml "$APK" | grep -q 'android:debuggable'; then
    echo "构建出的 APK 带 android:debuggable，禁止发布：检查 app/build.gradle.kts 的 buildTypes.release" >&2
    exit 1
fi

# 按版本各留一份记录，避免升级构建把上一版的校验结果覆盖掉
apksigner verify --verbose --print-certs "$APK" > "deliverables/apk-verification-${VERSION}.txt"
cp "deliverables/apk-verification-${VERSION}.txt" deliverables/apk-verification.txt
sha256sum "$APK" > "deliverables/SHA256SUMS-${VERSION}.txt"
cp "deliverables/SHA256SUMS-${VERSION}.txt" deliverables/SHA256SUMS.txt
