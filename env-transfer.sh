#!/usr/bin/env bash
#
# 把整套构建环境搬到另一台机器（例如那台闲置的 Mac Mini M4）。
#
# 链藏的构建依赖三个 Docker 卷，其中两个在别的机器上无法重新获取：
#   link-vault-sdk              Android SDK。dl.google.com 在受限网络下不可达，下不回来。
#   link-vault-debug-signing    签名密钥。丢了就永远无法覆盖升级已有安装。
#   link-vault-gradle           Gradle 依赖缓存。可以重新下载，带着只是省时间。
#
#   ./env-transfer.sh export [目录]    在本机导出（默认 ./env-bundle）
#   ./env-transfer.sh import [目录]    在目标机器导入
#
# 目标机器上同样需要 Docker，并且要先有构建镜像（见 README）。
#
set -euo pipefail
cd "$(dirname "$0")"

JDK_IMAGE="${JDK_IMAGE:-mcr.microsoft.com/openjdk/jdk:17-ubuntu}"
PLATFORM="${PLATFORM:-linux/amd64}"
ALL_VOLUMES=(link-vault-debug-signing link-vault-sdk link-vault-gradle)
# 便于先只拿最小的那个卷跑一遍验证：VOLUMES_OVERRIDE=link-vault-debug-signing
IFS=' ' read -r -a VOLUMES <<< "${VOLUMES_OVERRIDE:-${ALL_VOLUMES[*]}}"
DEFAULT_DIR="$PWD/../link-vault-env-bundle"

die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok()  { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
step(){ printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }

case "${1:-}" in

export)
    OUT="${2:-$DEFAULT_DIR}"
    mkdir -p "$OUT"
    for v in "${VOLUMES[@]}"; do
        step "导出 $v"
        docker volume inspect "$v" >/dev/null 2>&1 || die "找不到卷 $v"
        docker run --rm --platform "$PLATFORM" -v "$v:/src:ro" -v "$OUT:/out" "$JDK_IMAGE" \
            bash -c "cd /src && tar -czf /out/$v.tar.gz ." >/dev/null 2>&1
        ok "$(du -h "$OUT/$v.tar.gz" | cut -f1)  $v.tar.gz"
    done
    ( cd "$OUT" && shasum -a 256 *.tar.gz > SHA256SUMS.txt )
    step "结果"
    ls -lh "$OUT"
    printf '\n整包目录：%s\n把它复制到目标机器，然后在那边执行：\n    ./env-transfer.sh import %s\n' "$OUT" "$OUT"
    printf '\n\033[1;33m这个包里含签名私钥（口令是固定的 android），不要放到公开位置。\033[0m\n'
    ;;

import)
    SRC="${2:-$DEFAULT_DIR}"
    [ -d "$SRC" ] || die "找不到目录 $SRC"
    if [ -f "$SRC/SHA256SUMS.txt" ]; then
        step "校验完整性"
        ( cd "$SRC" && shasum -a 256 -c SHA256SUMS.txt ) || die "校验失败，压缩包可能损坏"
        ok "校验通过"
    fi
    for v in "${VOLUMES[@]}"; do
        f="$SRC/$v.tar.gz"
        [ -f "$f" ] || { printf '跳过（无 %s）\n' "$v.tar.gz"; continue; }
        step "导入 $v"
        if docker volume inspect "$v" >/dev/null 2>&1; then
            printf '\033[1;33m卷 %s 已存在，确认覆盖？[y/N] \033[0m' "$v"
            read -r reply
            [ "$reply" = "y" ] || [ "$reply" = "Y" ] || { printf '跳过 %s\n' "$v"; continue; }
        else
            docker volume create "$v" >/dev/null
        fi
        docker run --rm --platform "$PLATFORM" -v "$v:/dst" -v "$SRC:/src:ro" "$JDK_IMAGE" \
            bash -c "cd /dst && tar -xzf /src/$v.tar.gz" >/dev/null 2>&1
        ok "已导入 $v"
    done
    step "核对签名"
    bash ./signing-key.sh verify
    ;;

*)
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
