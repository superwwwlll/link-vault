# 链藏 1.3.4 · 本地链接收藏夹

Kotlin · Jetpack Compose · Room ｜ Android 8.0（API 26）及以上

为链接留个位置，为灵感保留线索。无需账号，无服务器，无云同步。

## 安装 / 从旧版升级

新版：`deliverables/lian-cang-1.3.4-debug.apk`，另复制到电脑桌面同名文件。

**更新只需在应用内点一下**：打开链藏时若发现新版本，收藏页顶部会出现提示条，点「更新」即下载并安装，
收藏一条不丢。详见 [AUTO-UPDATE.md](AUTO-UPDATE.md)。

发布侧只需一条 `./release.sh`：构建 → 核对签名 → 发布到 GitHub Release → 回拉校验。
APK 发布在 <https://github.com/superwwwlll/link-vault/releases>。

固定下载地址（永远指向最新版）：
`https://github.com/superwwwlll/link-vault/releases/latest/download/lian-cang-debug.apk`

1. 将 APK 传到手机，点击安装；按系统提示给文件管理器临时允许“安装未知应用”。
2. **已有旧版本时直接覆盖安装，不要卸载，不要清除数据。** 全版本包名、签名和数据库迁移链均兼容，
   versionCode 依次为 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 → 14。
3. 装好后到「设置 → 导出收藏」立即做一份 JSON 备份。设置页会显示上次备份时间，超过 30 天会以警示色提醒。
4. 本包是个人自用 debug 版，不是应用商店正式发布版；请只安装可信来源的 APK。

原包 `lian-cang-1.0.0-debug.apk`、`lian-cang-1.1.0-debug.apk` 与原源码压缩包均保留。各版本的最终验证报告
（`VERIFICATION-1.2.0.md` / `VERIFICATION-1.1.0.md` / `VERIFICATION.md`）与按版本的校验记录
（`apk-verification-<版本>.txt`、`SHA256SUMS-<版本>.txt`）分别保留。

## 1.2.0 主要变化

（1.2.1 只改了一处：设置页的版本号改为读真实安装版本，不再写死；功能与 1.2.0 相同。）

一句话：**让列表不再只有域名和日期，并且把"收藏夹迟早会变成坟场"这件事当成功能来处理。**

### 信息密度

- **标题不再依赖手输**。分享链接时系统常常已经把页面标题一起递过来了（`EXTRA_SUBJECT` / `EXTRA_TITLE` /
  `EXTRA_HTML_TEXT`），复制超链接时剪贴板里也常常带一份 HTML。这两条路径**完全离线**，现在都会被用上。
- 标题为空时按链接路径推导（`/docs/room-migration` → “Room Migration”），再退回站点名，
  **不再把整条 URL 当作标题显示**。
- 来源域名显示品牌名（GitHub / 哔哩哔哩 / 微信公众号…），子域自动上溯到已知主域。
- **来源标识按域名取色**，同源同色、异源尽量异色；已读的收藏转为灰色调，于是彩色本身就代表「还没处理」。
  以前所有来源共用一个颜色，字母标识等于只是把首字母重复了一遍。
- 可选：在设置里打开「页面信息抓取」后，可在收藏详情逐条手动抓取页面标题与描述。

### 组织与状态

- **未读 / 已读 / 归档**三种范围筛选，配合低干扰的视觉弱化，用来把已经处理完的收藏从视野里挪走。
- **置顶**，常用链接不必每次搜索。
- **标签可重命名、可合并、可删除**；标签按大小写无关归一键聚合，"Design" 和 "design" 是同一个标签。
- **卡片长按**即可置顶 / 标记已读 / 归档 / 复制 / 分享 / 删除，不必先进详情页。
- 一次粘贴或分享正文里包含多条链接时，可以**一键全部收藏**（默认仍然只取第一条）。
- 收藏时间与内容更新时间分开记录。标记已读、置顶、归档都不会改动更新时间，列表不会因此跳位。

### 可靠性与数据安全

- **草稿落盘**：应用被强制停止或任务被划掉后，未保存的草稿仍会恢复（只在真正重启进程后恢复，
  同一进程内不会串味）。
- **备份文件夹**：选一个文件夹后可一键备份，不必每次重走文件选择器；设置页显示备份时效。
- 去重键扩展了通用跟踪参数（`utm_*`、`fbclid`、`gclid`、`spm`、`share_*` 等），
  同一篇文章从微信和 X 分别分享进来不会再存成两条。X/Twitter 的 `s`/`t` 仍是特例，
  普通站点的 `s`/`t` 有真实含义，不会被删。
- 深色模式下冷启动不再白闪（新增 `values-night` 主题）。
- 日期补上年份，去年的收藏不再显示成「9月26日」。

## ⚠️ 权限变化（请读这一段）

**1.2.0 新增了一个权限：`android.permission.INTERNET`。** 这是本版本唯一新增的权限。

1.0.0 / 1.1.0 的「零权限」属性在 1.2.0 **不再成立**。如果你更看重这一点，可以不升级，
或者把 `AndroidManifest.xml` 里的这一行删掉自行重新构建——删掉之后除「页面信息抓取」外的所有功能
仍然完全可用（标题提取的两条离线路径不受影响）。

新增权限只服务于一个默认关闭的可选功能。抓取的边界是有意收窄的：

- **默认关闭**，需要在「设置 → 联网抓取」显式打开。
- 只在用户在收藏详情**逐条点击**「抓取页面信息」时才发起请求，绝不在后台自动联网。
- 只请求 `https`，明文 `http` 一律拒绝（因而也不涉及放开明文流量）。
- 只读页面开头约 96 KB，只保存标题与描述，不下载正文、图片或整页。
- 不发送 Cookie、不带凭据、不执行脚本、不跟随跨站重定向（最多 3 跳，且必须仍是 https）。
- 抓取结果只存在本机数据库里，不经过任何第三方服务。

## 界面

- **收藏**：卡片含来源标识、站点名、标题、页面描述、备注与标签；顶部可切换全部 / 未读 / 已读 / 归档，
  下方是标签筛选；搜索覆盖标题、链接、备注、标签、页面描述与站点名。
- **标签**：卡片网格聚合各标签及收藏数量，点击直接筛选；卡片右上角菜单可重命名或删除标签。
- **设置**：外观、联网抓取开关、本地备份（导出 / 导入 / 备份文件夹）、隐私与版本信息。
- **详情**：状态切换、完整标题、页面描述、原始链接、我的备注、标签、分享与删除。
- **编辑**：独立页面，标题/备注/多标签；「粘贴并提取」会同时利用剪贴板的纯文本与 HTML，
  从链接里直接取出标题；可点选已有标签，退出前确认放弃，保存按钮固定在底部。
- 从 X、浏览器等的分享菜单选择「链藏」；也支持一次分享多条链接，以及选中文字后从系统菜单划词收藏。

视觉沿用 1.1.0 的内容优先层级与自有配色、线性图标，来源标识改为按域名取色的字母方块；
没有使用 Cubox 商标或专有素材，没有伪造 favicon 或网页封面。

## JSON 导出 / 导入

### 导出
设置 → 导出收藏 → 在安卓系统文件选择器中选择文件名与位置；或先设置「备份文件夹」后一键备份。
导出全部收藏的原始链接、标题、备注、标签、收藏与更新时间、已读/置顶/归档状态和已抓取的页面描述，
不导出数据库内部 ID、签名密钥或主题设置。

**备份是未加密 JSON。** 系统文件选择器可能提供云盘，是否使用由你选择。不完整/失败导出会明确提示。

### 导入
设置 → 导入备份 → 选择 JSON → 检查预览数量与示例 → 确认合并。

- 格式标识 `cn.linkvault.backup`，格式版本仍为 **1**：1.0.0 / 1.1.0 导出的备份**可以直接导入**，
  新增字段在缺失时按默认值处理（`createdAt` 退回 `updatedAt`），不会因为升级而失效。
- 单文件最多 **10 MB / 10000 条**；非法 UTF-8、未知版本、字段错误、非法链接、超过32层嵌套或超限均拒绝整份文件。
- 原始链接重新校验并计算去重键，不信任文件中的 ID 或规范化键。
- **仅新增，重复跳过，不覆盖、不删除已有收藏。** 写入异常整体回滚。
- 只有点击确认才写数据库；预览缓存在应用私有 cache，失效则要求重新选择。

建议定期导出到你能保管的位置，或设置备份文件夹后定期一键备份。
**卸载或清除应用数据会永久删除本地收藏，系统云备份和设备迁移仍被关闭。**

## 链接与状态边界

- 仅带有效主机的 HTTP/HTTPS，拒绝凭据、控制字符、无效端口及其他协议。
- X/Twitter（含 www/mobile）统一到 x.com，忽略 s/t 参数与 fragment；普通网页保留其他查询及 fragment。
- 去重键会丢弃通用跟踪参数，但**原始链接永远按用户输入原样保存**，打开原链接时只规范化协议大小写。
- 不解析短网址、不跟踪重定向来还原真实地址，不检查网页/推文是否存在，不抓取截图、正文或封面；
  唯一的联网行为是上文所述、默认关闭、逐条手动的「页面信息抓取」。
- 草稿、搜索、筛选、范围、详情/导航由 ViewModel + SavedStateHandle 恢复，并额外落盘一份，
  强制停止或划掉任务后仍可恢复未保存的草稿。
- 输入上限：标题 200、备注 8000、标签文本 1000、页面描述 8000、分享/链接 16000 个 UTF-16 单元。
- 数据库 schema 已升到 v2，升级安装时由 `Migration1To2` 自动迁移；该迁移**不删除、不合并任何一条收藏**，
  即使去重键重算后与别的记录冲突，也会保留原来的键。

## 构建

```bash
bash docker-build.sh
```

固定兼容工具链：AGP 8.7.3 / Gradle 8.9 / JDK17 / Kotlin2.0.21 / KSP2.0.21-1.0.28 / Compose BOM2024.12.01 / Room2.6.1 / compile & target35。

在 linux/amd64 专用 Docker 环境执行 `testDebugUnitTest lintDebug assembleDebug`；支持标准 `gradlew`，Gradle 分发已固定 SHA-256。单次构建 30 分钟超时。

仅挂载本项目和专用缓存：`link-vault-gradle`、`link-vault-robolectric`、`link-vault-debug-signing`。

**不要删除或公开 `link-vault-debug-signing` 卷。** 私钥仅在该卷内（权限600），未打包进源码。若丢失原签名，新构建无法覆盖升级原安装；卸载重装会丢数据。

### 关于 dl.google.com 不可达

原 `Dockerfile` 在构建镜像时会从 `dl.google.com` 下载 Android 命令行工具。在网络受限的环境里
该下载会以 `curl: (35) SSL ... unexpected eof` 失败，导致镜像根本构建不出来。
本次构建改为**不在镜像里下载 SDK，而是在运行时挂载已有的 `link-vault-sdk` 卷**（内含
`platforms;android-35`、`build-tools;34.0.0` 与已接受的许可证），只从可达的 `downloads.gradle.org`
获取 Gradle 本体。`docker-build.sh` / `Dockerfile` 未改动；如需复现本次构建方式，见本文档末尾说明。

## 验证和截图

以各版本 `VERIFICATION-*.md` 与构建产物校验记录为准。
当前版本 1.3.4 摘要：**106 项测试通过、0 失败、0 错误**；lint **0 errors**；
APK 9,307,898 字节；签名证书 SHA-256 与 1.0.0 起各版本完全一致。
1.2.3 把收藏页顶部从 392dp 压到 282dp，一屏可见卡片从 2.3 张增加到 3 张完整（量测方式见该验证文档）。

`deliverables/screenshots/` 是在 Robolectric API35 原生图形模式下，对实际 Compose/Android View 树
使用原生 Skia Canvas 绘制的七张截图，不是网页样稿；包含首页浅/深色、详情、编辑、标签、设置和导入确认。
截图里的示例收藏仅存在于测试沙箱，不会预置到用户安装包。

**这些不是完整 Android 模拟器或手机截图。** 当前 Mac 无原生 Android emulator/SDK，Docker 没有 KVM。
以下仍需真机验证：系统分享实际带哪些标题字段、浏览器复制链接时剪贴板是否带 HTML、
SAF 文件夹授权与一键备份、真实网络下的抓取表现、覆盖升级安装流程、厂商分享菜单与外部跳转。
详见 `VERIFICATION-1.2.0.md`。

## 复现本次构建环境（dl.google.com 不可达时）

```bash
cat > /tmp/Dockerfile.linkvault-local <<'EOF'
FROM --platform=linux/amd64 mcr.microsoft.com/openjdk/jdk:17-ubuntu
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/*
RUN curl -fL --retry 3 --connect-timeout 20 --max-time 900 https://downloads.gradle.org/distributions/gradle-8.9-bin.zip -o /tmp/gradle.zip \
 && echo "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab  /tmp/gradle.zip" | sha256sum -c - \
 && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=/opt/gradle-8.9/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/build-tools/34.0.0:$PATH
WORKDIR /project
CMD ["sleep", "infinity"]
EOF

docker build --platform linux/amd64 -f /tmp/Dockerfile.linkvault-local -t link-vault-android-builder:local .
docker run -d --platform linux/amd64 --name link-vault-dev --cpus 4 --memory 6g \
  -v "$PWD:/project" -v link-vault-gradle:/root/.gradle \
  -v link-vault-sdk:/opt/android-sdk -v link-vault-debug-signing:/root/.android \
  link-vault-android-builder:local
docker exec link-vault-dev bash -lc 'cd /project && VERSION=1.2.0 bash build-in-container.sh'
```
