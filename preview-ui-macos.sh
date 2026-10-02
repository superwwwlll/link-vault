#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
BASE="${VAULT_ANDROID_ENV:-$HOME/.local/share/link-vault-android}"
export JAVA_HOME="$BASE/jdk/Contents/Home"
export ANDROID_HOME="$BASE/sdk"
export ANDROID_USER_HOME="$BASE/android-user"
export GRADLE_USER_HOME="$BASE/gradle-cache"
export PATH="$JAVA_HOME/bin:$PATH"
"$BASE/gradle-8.9/bin/gradle" --no-daemon --console=plain --max-workers=2 \
  "-Duser.home=$BASE/home" "-PvaultScreenshots=$PWD/deliverables/screenshots-manus" \
  testDebugUnitTest lintDebug assembleDebug "$@"
