#!/usr/bin/env bash
#
# 链藏的签名密钥目前只有一个副本，放在 Docker 卷 link-vault-debug-signing 里。
#
# 它一旦丢失，你就**再也无法覆盖升级**已经装在手机上的版本 —— 只能卸载重装，
# 也就是把所有收藏一起丢掉。Docker 卷可能因为 `docker volume prune`、
# Docker Desktop 重置/卸载、换机器或磁盘故障而消失，所以必须有卷外的副本。
#
#   ./signing-key.sh backup  [目录]   导出密钥与公开证书（默认 ../link-vault-signing-backup）
#   ./signing-key.sh restore [目录]   在任何机器上重建密钥卷
#   ./signing-key.sh verify           打印当前卷内密钥的证书指纹并与记录核对
#
set -euo pipefail
cd "$(dirname "$0")"

VOLUME="${VOLUME:-link-vault-debug-signing}"
KEYSTORE="${KEYSTORE:-debug.keystore}"
# 标准 Android 调试密钥库的口令，AGP 生成时就固定成这两个值。
STOREPASS="${STOREPASS:-android}"
ALIAS="${ALIAS:-androiddebugkey}"
JDK_IMAGE="${JDK_IMAGE:-mcr.microsoft.com/openjdk/jdk:17-ubuntu}"
PLATFORM="${PLATFORM:-linux/amd64}"
FINGERPRINT_FILE="$PWD/SIGNING-CERT.txt"
DEFAULT_DIR="$PWD/../link-vault-signing-backup"

die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok()  { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
step(){ printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }

fingerprint() {
    # 从 APK/密钥库打印的证书指纹里取出纯十六进制串
    tr 'A-Z' 'a-z' | tr -d ':' | tr -d ' '
}

expected() {
    grep -oE '[0-9a-fA-F]{64}' "$FINGERPRINT_FILE" | head -1 | tr 'A-Z' 'a-z'
}

case "${1:-}" in

backup)
    OUT="${2:-$DEFAULT_DIR}"
    step "从 Docker 卷 $VOLUME 导出签名密钥"
    docker volume inspect "$VOLUME" >/dev/null 2>&1 || die "找不到卷 ${VOLUME}，没有可导出的东西"
    mkdir -p "$OUT"
    chmod 700 "$OUT"

    docker run --rm --platform "$PLATFORM" -v "$VOLUME:/src:ro" -v "$OUT:/out" "$JDK_IMAGE" bash -c "
        set -e
        cp /src/$KEYSTORE /out/$KEYSTORE
        chmod 600 /out/$KEYSTORE
        keytool -exportcert -rfc -alias $ALIAS -keystore /src/$KEYSTORE -storepass $STOREPASS > /out/link-vault-debug.crt
        keytool -list -v -alias $ALIAS -keystore /src/$KEYSTORE -storepass $STOREPASS > /out/keystore-info.txt
    " >/dev/null 2>&1

    ( cd "$OUT" && shasum -a 256 "$KEYSTORE" > "$KEYSTORE.sha256" )

    step "核对指纹"
    GOT="$(grep -i 'SHA256:' "$OUT/keystore-info.txt" | grep -oE '([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}|[0-9a-fA-F]{64}' | fingerprint || true)"
    WANT="$(expected)"
    [ -n "$WANT" ] || die "读不到 $FINGERPRINT_FILE 里的记录指纹"
    [ "$GOT" = "$WANT" ] || die "导出的密钥指纹与记录不符！\n  记录：$WANT\n  实际：$GOT"
    ok "指纹与记录一致：$GOT"

    step "结果"
    ls -1 "$OUT"
    printf '\n备份目录：%s\n\n' "$OUT"
    printf '\033[1;33m请立刻把这个目录复制到别的地方（NAS 上放一份最稳妥）：\033[0m\n'
    printf '    %s/%s\n    %s/link-vault-debug.crt\n\n' "$OUT" "$KEYSTORE" "$OUT"
    printf '只需密钥文件本身就能重建整套构建环境；.crt 是公开证书，随便放。\n'
    printf '注意：调试密钥库的口令是固定的 android，所以这个文件等同于签名权，\n'
    printf '别放在公开的共享目录里。\n'
    ;;

restore)
    SRC="${2:-$DEFAULT_DIR}"
    step "从 $SRC 恢复签名密钥到 Docker 卷 $VOLUME"
    [ -f "$SRC/$KEYSTORE" ] || die "找不到 $SRC/$KEYSTORE"

    if docker volume inspect "$VOLUME" >/dev/null 2>&1; then
        printf '\033[1;33m卷 %s 已存在。确认要用 %s 覆盖它吗？[y/N] \033[0m' "$VOLUME" "$SRC/$KEYSTORE"
        read -r reply
        [ "$reply" = "y" ] || [ "$reply" = "Y" ] || die "已取消"
    else
        docker volume create "$VOLUME" >/dev/null
    fi

    docker run --rm --platform "$PLATFORM" -v "$VOLUME:/dst" -v "$SRC:/src:ro" "$JDK_IMAGE" bash -c "
        set -e
        cp /src/$KEYSTORE /dst/$KEYSTORE
        chmod 600 /dst/$KEYSTORE
    " >/dev/null 2>&1
    ok "已写入卷 $VOLUME"

    step "核对"
    GOT="$(docker run --rm --platform "$PLATFORM" -v "$VOLUME:/root/.android" "$JDK_IMAGE" \
            keytool -list -v -alias "$ALIAS" -keystore "/root/.android/$KEYSTORE" -storepass "$STOREPASS" 2>/dev/null \
            | grep -i 'SHA256:' | grep -oE '([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}|[0-9a-fA-F]{64}' | fingerprint || true)"
    WANT="$(expected)"
    [ "$GOT" = "$WANT" ] || die "恢复后的指纹不符！\n  记录：$WANT\n  实际：$GOT"
    ok "指纹与记录一致：$GOT"
    ;;

verify)
    step "当前卷 $VOLUME 内的证书指纹"
    docker volume inspect "$VOLUME" >/dev/null 2>&1 || die "找不到卷 $VOLUME"
    GOT="$(docker run --rm --platform "$PLATFORM" -v "$VOLUME:/root/.android" "$JDK_IMAGE" \
            keytool -list -v -alias "$ALIAS" -keystore "/root/.android/$KEYSTORE" -storepass "$STOREPASS" 2>/dev/null \
            | grep -i 'SHA256:' | grep -oE '([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}|[0-9a-fA-F]{64}' | fingerprint || true)"
    WANT="$(expected)"
    echo "  记录：$WANT"
    echo "  实际：$GOT"
    [ "$GOT" = "$WANT" ] && ok "一致，可以正常覆盖升级" || die "不一致！现在构建出的 APK 将无法覆盖升级已装版本"
    ;;

*)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
