#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME=/opt/android-sdk
export PATH="/opt/gradle-8.9/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"
# 版本号从 app/build.gradle.kts 读取，保证产物名与真实版本一致
VERSION="${VERSION:-$(grep -oE 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | cut -d'"' -f2)}"
timeout 1800 gradle --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug
chmod 600 /root/.android/debug.keystore
mkdir -p deliverables
cp app/build/outputs/apk/debug/app-debug.apk "deliverables/lian-cang-${VERSION}-debug.apk"
# 按版本各留一份记录，避免升级构建把上一版的校验结果覆盖掉
apksigner verify --verbose --print-certs "deliverables/lian-cang-${VERSION}-debug.apk" > "deliverables/apk-verification-${VERSION}.txt"
cp "deliverables/apk-verification-${VERSION}.txt" deliverables/apk-verification.txt
sha256sum "deliverables/lian-cang-${VERSION}-debug.apk" > "deliverables/SHA256SUMS-${VERSION}.txt"
cp "deliverables/SHA256SUMS-${VERSION}.txt" deliverables/SHA256SUMS.txt
