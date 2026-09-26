#!/usr/bin/env bash
#
# 一条命令完成：构建 → 校验签名 → 生成更新清单 → 发布到 GitHub Release。
#
#   ./release.sh
#   PUBLISH_GITHUB=owner/repo ./release.sh        # 指定发布仓库
#   PUBLISH_GITHUB= PUBLISH_DIR=~/Sites/lv ./release.sh   # 只生成本地目录，不发 GitHub
#   PUBLISH_REMOTE=user@nas:/path ./release.sh     # 额外另存一份到 NAS
#
# 发布目录里固定提供：
#   lian-cang-debug.apk   固定文件名，永远是最新版
#   index.html            给人看的下载页
#   latest.json           机器可读的版本清单
# GitHub 上每个版本只上传这一个同名 APK，于是
#   https://github.com/<owner>/<repo>/releases/latest/download/lian-cang-debug.apk
# 就是一个永远指向最新版的固定地址。
#
set -euo pipefail
cd "$(dirname "$0")"

die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok()  { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
step(){ printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
fingerprint() { tr 'A-Z' 'a-z' | tr -d ':' | tr -d ' '; }

CONTAINER="${CONTAINER:-link-vault-dev}"
# 版本号只有一个来源：app/build.gradle.kts。不要在多处硬编码，否则升级时会不同步。
VERSION="${VERSION:-$(grep -oE 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | cut -d'"' -f2)}"
[ -n "$VERSION" ] || die "无法从 app/build.gradle.kts 读出 versionName"
IMAGE="${IMAGE:-link-vault-android-builder:local}"
PLATFORM="${PLATFORM:-linux/amd64}"
PUBLISH_DIR="${PUBLISH_DIR:-$PWD/publish}"
# 默认发布到 GitHub Release；设为空字符串则跳过 GitHub 发布
PUBLISH_GITHUB="${PUBLISH_GITHUB-superwwwlll/link-vault}"
PUBLISH_REMOTE="${PUBLISH_REMOTE:-}"
BUILD_TOOLS="/opt/android-sdk/build-tools/34.0.0"

WANT="$(grep -oE '[0-9a-fA-F]{64}' SIGNING-CERT.txt | head -1 | tr 'A-Z' 'a-z')"
[ -n "$WANT" ] || die "读不到 SIGNING-CERT.txt 里的记录指纹"

# ---------------------------------------------------------------- 构建容器

docker image inspect "$IMAGE" >/dev/null 2>&1 || die "找不到镜像 $IMAGE
先执行一次：  bash docker-build.sh"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
        step "启动已有容器 $CONTAINER"
        docker start "$CONTAINER" >/dev/null
    else
        step "创建构建容器 $CONTAINER"
        docker run -d --platform "$PLATFORM" --name "$CONTAINER" --cpus 4 --memory 6g \
            -v "$PWD:/project" -v link-vault-gradle:/root/.gradle \
            -v link-vault-sdk:/opt/android-sdk -v link-vault-debug-signing:/root/.android \
            "$IMAGE" >/dev/null
    fi
fi
docker volume inspect link-vault-sdk >/dev/null 2>&1 || die "缺少 SDK 卷 link-vault-sdk（见 README 的构建说明）"
docker volume inspect link-vault-debug-signing >/dev/null 2>&1 || die "缺少签名卷 link-vault-debug-signing
用 ./signing-key.sh restore 从备份恢复，否则构建出的 APK 装不上已有版本"

# ---------------------------------------------------------------- 构建

step "构建（测试 + lint + 打包）"
docker exec "$CONTAINER" bash -lc "cd /project && VERSION=$VERSION bash build-in-container.sh" \
    | tee /tmp/link-vault-release-build.log | tail -5

APK_SRC=""
for f in deliverables/lian-cang-*-debug.apk; do
    [ -e "$f" ] || continue
    if [ -z "$APK_SRC" ] || [ "$f" -nt "$APK_SRC" ]; then APK_SRC="$f"; fi
done
[ -n "$APK_SRC" ] || die "构建结束但没找到 APK"

# ---------------------------------------------------------------- 签名校验（发布前的闸门）

step "核对签名证书"
# apksigner 打印的指纹不带冒号，keytool 打印的带冒号，两种都要能取。
# 末尾的 `|| true` 是必需的：set -e + pipefail 下，grep 没命中会让整个脚本静默退出。
CERT_DUMP="$(docker exec "$CONTAINER" bash -lc "cd /project && $BUILD_TOOLS/apksigner verify --print-certs '$APK_SRC'" 2>&1 || true)"
GOT="$(printf '%s\n' "$CERT_DUMP" | grep -i 'certificate SHA-256 digest' \
        | grep -oE '([0-9a-fA-F]{2}:){31}[0-9a-fA-F]{2}|[0-9a-fA-F]{64}' | fingerprint || true)"
if [ -z "$GOT" ]; then
    printf '%s\n' "$CERT_DUMP"
    die "读不出 APK 的证书指纹，无法确认可覆盖升级，已中止发布"
fi
if [ "$GOT" != "$WANT" ]; then
    cat >&2 <<EOF

记录了：$WANT
实际是：$GOT

两者不一致，**已中止发布**。
这样的 APK 装不到已经装在手机上的版本上，用户只能卸载重装并丢掉全部收藏。
先执行 ./signing-key.sh verify 查清签名卷里到底是哪份密钥，确认无误再改 SIGNING-CERT.txt。
EOF
    exit 1
fi
ok "指纹一致：$GOT"

# ---------------------------------------------------------------- 元数据

BADGING="$(docker exec "$CONTAINER" bash -lc "cd /project && $BUILD_TOOLS/aapt2 dump badging '$APK_SRC'" 2>/dev/null | head -1 || true)"
VERSION_NAME="$(printf '%s' "$BADGING" | grep -oE "versionName='[^']*'" | cut -d"'" -f2 || true)"
VERSION_CODE="$(printf '%s' "$BADGING" | grep -oE "versionCode='[^']*'" | cut -d"'" -f2 || true)"
MIN_SDK="$(docker exec "$CONTAINER" bash -lc "cd /project && $BUILD_TOOLS/aapt2 dump badging '$APK_SRC'" 2>/dev/null | grep -oE "^sdkVersion:'[^']*'" | cut -d"'" -f2 || true)"
SIZE="$(stat -f%z "$APK_SRC" 2>/dev/null || stat -c%s "$APK_SRC")"
SHA="$(shasum -a 256 "$APK_SRC" | awk '{print $1}')"
STAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
[ -n "$VERSION_NAME" ] || die "无法从 APK 读出版本号（aapt2 输出：${BADGING}）"
ok "链藏 $VERSION_NAME (versionCode $VERSION_CODE) · $SIZE 字节"
ok "sha256 $SHA"

# ---------------------------------------------------------------- 发布内容

step "生成发布目录 $PUBLISH_DIR"
mkdir -p "$PUBLISH_DIR"
cp "$APK_SRC" "$PUBLISH_DIR/lian-cang-debug.apk"
cp "$APK_SRC" "$PUBLISH_DIR/lian-cang-${VERSION_NAME}-debug.apk"
cp SIGNING-CERT.txt "$PUBLISH_DIR/SIGNING-CERT.txt"

cat > "$PUBLISH_DIR/latest.json" <<EOF
{
  "app": "链藏",
  "package": "cn.linkvault",
  "versionName": "$VERSION_NAME",
  "versionCode": $VERSION_CODE,
  "minSdk": ${MIN_SDK:-26},
  "file": "lian-cang-debug.apk",
  "size": $SIZE,
  "sha256": "$SHA",
  "signedWith": "$WANT",
  "releasedAt": "$STAMP"
}
EOF

SIZE_MB="$(awk -v s="$SIZE" 'BEGIN{printf "%.1f", s/1048576}')"
cat > "$PUBLISH_DIR/index.html" <<EOF
<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>链藏 · 下载</title>
<style>
 body{margin:0;padding:32px 20px;background:#F5F6F9;color:#252934;
      font:15px/1.7 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
 .card{max-width:520px;margin:0 auto;background:#fff;border:1px solid #E7EAF1;
       border-radius:20px;padding:26px}
 h1{font-size:26px;margin:0 0 4px}
 .kicker{font-size:11px;letter-spacing:2.5px;color:#5265CC;font-weight:600;margin-bottom:14px}
 .ver{font-size:20px;font-weight:600;margin:14px 0 2px}
 .meta{font-size:12px;color:#747C8E}
 a.btn{display:block;margin:22px 0 14px;padding:15px;background:#5265CC;color:#fff;
       text-align:center;border-radius:15px;text-decoration:none;font-weight:600}
 .note{font-size:12px;color:#747C8E;line-height:1.8}
 .warn{background:#EBEEFF;color:#3D4EA6;border-radius:13px;padding:13px;font-size:12px;margin-top:18px}
 code{font-size:11px;word-break:break-all;color:#747C8E}
</style></head><body>
<div class="card">
  <div class="kicker">LINK VAULT</div>
  <h1>链藏</h1>
  <div class="meta">本地链接收藏夹 · Android 8.0+</div>
  <div class="ver">$VERSION_NAME</div>
  <div class="meta">versionCode $VERSION_CODE · $SIZE_MB MB · 构建于 $STAMP</div>
  <a class="btn" href="lian-cang-debug.apk">下载 APK</a>
  <div class="warn"><b>直接覆盖安装，不要卸载。</b>卸载或清除数据会永久丢失全部收藏。
    升级前建议先在应用内「设置 → 导出收藏」备份一次。</div>
  <div class="note" style="margin-top:16px">
    签名证书指纹<br><code>$WANT</code><br><br>
    SHA-256<br><code>$SHA</code>
  </div>
</div>
</body></html>
EOF

ls -1 "$PUBLISH_DIR"
ok "已生成"

# ---------------------------------------------------------------- 发布到 GitHub

DOWNLOAD_URL=""
if [ -n "$PUBLISH_GITHUB" ]; then
    step "发布到 GitHub Release：$PUBLISH_GITHUB"
    command -v gh >/dev/null || die "本机没有 gh CLI（brew install gh）"
    gh auth status >/dev/null 2>&1 || die "gh 未登录。先执行：gh auth login"

    TAG="v${VERSION_NAME}"
    if gh release view "$TAG" --repo "$PUBLISH_GITHUB" >/dev/null 2>&1; then
        die "GitHub 上已存在 ${TAG}，拒绝重复发布。

同一个版本号绝不发两次：Android 靠 versionCode 判断能否覆盖，
Obtainium 靠版本号判断新旧；重发同号会让「现在装的是哪一版」无法回答。

请先改 app/build.gradle.kts 里的 versionCode（递增）与 versionName。"
    fi

    NOTES="$(mktemp)"
    cat > "$NOTES" <<EOF
本地链接收藏夹。**直接覆盖安装，不要卸载** —— 卸载或清除数据会永久丢失全部收藏。

- versionCode $VERSION_CODE · $SIZE 字节
- SHA-256 \`$SHA\`
- 签名证书 SHA-256 \`$WANT\`

手机端用 [Obtainium](https://github.com/ImranR98/Obtainium) 订阅本仓库即可收到更新通知；
也可以直接下载下面的 APK 覆盖安装。
EOF
    # latest.json 必须一起传：应用内更新读的就是
    # releases/latest/download/latest.json 这个固定地址。
    # 每个 release 只放一个 APK，所以 releases/latest/download/lian-cang-debug.apk
    # 也永远指向最新版。
    gh release create "$TAG" --repo "$PUBLISH_GITHUB" \
        --title "链藏 $VERSION_NAME" --notes-file "$NOTES" \
        "$PUBLISH_DIR/lian-cang-debug.apk" "$PUBLISH_DIR/latest.json" >/dev/null
    rm -f "$NOTES"
    ok "已发布 $TAG"

    step "回拉校验（确认手机下到的就是刚构建的这份）"
    DOWNLOAD_URL="https://github.com/$PUBLISH_GITHUB/releases/latest/download/lian-cang-debug.apk"
    GOT_SHA="$(curl -sL "$DOWNLOAD_URL" | shasum -a 256 | awk '{print $1}' || true)"
    [ "$GOT_SHA" = "$SHA" ] || die "从 GitHub 下载回来的 APK 与本地不一致！
  本地：$SHA
  远端：$GOT_SHA"
    ok "sha256 一致"
fi

# ---------------------------------------------------------------- 可选：另存一份到 NAS

if [ -n "$PUBLISH_REMOTE" ]; then
    step "同步到 $PUBLISH_REMOTE"
    command -v rsync >/dev/null || die "本机没有 rsync"
    rsync -av --delete --exclude 'lian-cang-*-debug.apk' "$PUBLISH_DIR/" "$PUBLISH_REMOTE/"
    ok "已同步"
fi

printf '\n\033[1;32m发布完成\033[0m  链藏 %s (versionCode %s)\n' "$VERSION_NAME" "$VERSION_CODE"
printf '本地目录  %s\n' "$PUBLISH_DIR"
[ -n "$DOWNLOAD_URL" ] && printf '固定地址  %s\n' "$DOWNLOAD_URL"
[ -n "$PUBLISH_REMOTE" ] && printf 'NAS 副本  %s\n' "$PUBLISH_REMOTE"
printf '\n手机端在 Obtainium 里添加仓库地址即可自动跟踪更新：\n'
[ -n "$PUBLISH_GITHUB" ] && printf '  https://github.com/%s\n' "$PUBLISH_GITHUB"
