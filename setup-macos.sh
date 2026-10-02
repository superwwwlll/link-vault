#!/usr/bin/env bash
# Apple Silicon 的隔离构建环境；不改系统 Java/PATH，也不接触正式签名。
set -euo pipefail
[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] || {
  echo "此脚本仅支持 Apple Silicon macOS；其他机器请参考 README 的 Docker 构建流程。" >&2; exit 1;
}
BASE="${VAULT_ANDROID_ENV:-$HOME/.local/share/link-vault-android}"
mkdir -p "$BASE/downloads" "$BASE/jdk" "$BASE/home" "$BASE/android-user"
download() {
  local url="$1" target="$2" checksum="$3" algorithm="$4"
  if ! [ -f "$target" ] || ! printf '%s  %s\n' "$checksum" "$target" | shasum -a "$algorithm" -c - >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 --max-time 900 "$url" -o "$target"
  fi
  printf '%s  %s\n' "$checksum" "$target" | shasum -a "$algorithm" -c -
}
if [ ! -x "$BASE/jdk/Contents/Home/bin/java" ]; then
  download https://cdn.azul.com/zulu/bin/zulu17.66.19-ca-jdk17.0.19-macosx_aarch64.tar.gz \
    "$BASE/downloads/jdk.tar.gz" f2bd5afaaaa4c23eb4bf2c78913c7eb7d3d228e44209ffec652fb72388a2f25c 256
  tar -xzf "$BASE/downloads/jdk.tar.gz" -C "$BASE/jdk" --strip-components=1
fi
export JAVA_HOME="$BASE/jdk/Contents/Home"
export ANDROID_HOME="$BASE/sdk"
export ANDROID_USER_HOME="$BASE/android-user"
export GRADLE_USER_HOME="$BASE/gradle-cache"
export PATH="$JAVA_HOME/bin:$PATH"
if [ ! -x "$BASE/gradle-8.9/bin/gradle" ]; then
  download https://downloads.gradle.org/distributions/gradle-8.9-bin.zip \
    "$BASE/downloads/gradle.zip" d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab 256
  unzip -q "$BASE/downloads/gradle.zip" -d "$BASE"
fi
MANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$MANAGER" ]; then
  download https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip \
    "$BASE/downloads/tools.zip" 37fb7dd41005b3b4ca6ea48ac27074b6fc4e3236 1
  mkdir -p "$ANDROID_HOME/cmdline-tools" "$BASE/downloads/tools"
  unzip -q "$BASE/downloads/tools.zip" -d "$BASE/downloads/tools"
  cp -a "$BASE/downloads/tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
(set +o pipefail; yes | "$MANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null)
"$MANAGER" --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;34.0.0" "platform-tools"
java -version
"$BASE/gradle-8.9/bin/gradle" --version
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
"$ANDROID_HOME/build-tools/34.0.0/aapt2" version
