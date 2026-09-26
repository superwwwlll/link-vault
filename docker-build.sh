#!/usr/bin/env bash
# 构建镜像并跑一次完整构建（测试 + lint + 打包）。
#
# 日常发布建议直接用 ./release.sh：它还会校验签名指纹、生成更新清单并发布。
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${IMAGE:-link-vault-android-builder:local}"
PLATFORM="${PLATFORM:-linux/amd64}"

docker volume inspect link-vault-sdk >/dev/null 2>&1 || {
    cat >&2 <<'EOF'
缺少 SDK 卷 link-vault-sdk。

镜像里不再内置 Android SDK（dl.google.com 在受限网络下不可达），SDK 由这个卷提供。
在新机器上准备它：从已有机器导出整套环境再导入 ——
    ./env-transfer.sh export        # 在已有机器上
    ./env-transfer.sh import        # 在新机器上
EOF
    exit 1
}

docker build --platform "$PLATFORM" -t "$IMAGE" .

docker run --rm --platform "$PLATFORM" --name link-vault-rebuild --cpus 2 --memory 5g \
  -v "$PWD:/project" -v link-vault-gradle:/root/.gradle \
  -v link-vault-sdk:/opt/android-sdk \
  -v link-vault-debug-signing:/root/.android -v link-vault-robolectric:/root/.m2 \
  "$IMAGE"
