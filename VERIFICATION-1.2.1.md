# 链藏 1.2.1 实际验证结果

本版是 1.2.0 的小改 + 发布链路搭建。功能、权限、数据库结构、备份格式**与 1.2.0 完全相同**，
1.2.0 的验证结论继续适用（见 `VERIFICATION-1.2.0.md`）。这里只记录差异。

## 相对 1.2.0 的改动

1. **设置页的版本号改为读真实安装版本**（`链藏 <versionName>（build <versionCode>）`）。
   原来写死成 `链藏 1.2.0`，升级之后界面上仍显示旧版本号 ——
   而这正是判断「更新到底装上没有」的唯一依据。这类硬编码会在每个版本留下一个假信息，必须去掉。
2. 版本提升到 `1.2.1` / `versionCode 4`。
   **同一个版本号绝不发布两次**：内容变了却不升版本号，会让"现在装的是哪一版"无法回答，
   也会干扰 Obtainium 与 Android 的新旧判断。
3. 构建镜像不再内置 Android SDK（原因见下），SDK 改由运行时挂载 `link-vault-sdk` 卷提供；
   `build-in-container.sh` 与 `release.sh` 的版本号统一从 `app/build.gradle.kts` 读取，不再多处硬编码。

## 已交付

- 桌面 APK：`/Users/mima0000/Desktop/lian-cang-1.2.1-debug.apk`
- 工程 APK：`deliverables/lian-cang-1.2.1-debug.apk`
- 发布目录：`publish/`（固定名 APK + 版本化 APK + `index.html` + `latest.json` + 公开证书摘要）
- 版本化源码：`deliverables/link-vault-1.2.1-source.zip`
- 校验记录：`deliverables/apk-verification-1.2.1.txt`、`deliverables/SHA256SUMS-1.2.1.txt`
- 自动更新搭建说明：`AUTO-UPDATE.md`

APK 字节数 **9,103,513**；SHA-256：`cf6730d43123b9772acea9fc518d2b009fe725969e4f6daaeaf640e176337992`。桌面副本哈希相同。

## 构建与自动测试

`./release.sh` 完整跑通（构建 → 签名核对 → 生成发布目录 → 可选推送）：

- **BUILD SUCCESSFUL**，退出码 0。
- **83 项测试通过，0 失败、0 错误、0 跳过**（与 1.2.0 相同，本版未改功能逻辑）。
- lint：**0 errors、0 warnings**。
- 签名证书 SHA-256：`5105679d9dab4656582dd7e6c3b85c2e40a2a1bd09146ac3474e052677ad3e7d`，
  与 1.0.0 / 1.1.0 / 1.2.0 **完全一致**，可覆盖升级。
- 发布目录中的固定名 APK 与版本化 APK 经 `shasum` 确认**逐字节相同**。

## 发布链路的实际验证

用本地静态服务模拟了 NAS，实测手机端会走的那条路径：

```
GET /latest.json                → 200，versionName 1.2.1 / versionCode 4
HEAD /lian-cang-debug.apk       → 200，Content-Type: application/vnd.android.package-archive
GET  /lian-cang-debug.apk       → 下载后 sha256 与发布清单一致
```

## 签名密钥的备份（本版新增的安全措施）

签名密钥此前**只有一个副本**，就在 Docker 卷 `link-vault-debug-signing` 里，卷外没有任何备份。
这意味着一次 `docker volume prune`、一次 Docker Desktop 重置或换机器，
就会永久失去覆盖升级的能力 —— 只能卸载重装，也就是丢掉全部收藏。

新增 `signing-key.sh`（`backup` / `restore` / `verify`），已导出并核对：

```
~/Documents/Android/link-vault-signing-backup/debug.keystore     权限 600，完整性校验通过
~/Documents/Android/link-vault-signing-backup/link-vault-debug.crt
```

导出的密钥经 `keytool` 复核，指纹与记录一致。`release.sh` 在每次发布前核对证书指纹，
不一致即中止发布 —— 防止某天密钥被重新生成后，悄悄发出一个装不上已有版本的包。

## 仍需你确认 / 真机验证

- NAS 的具体型号与地址未知，指南中给出了群晖 / QNAP / 通用三种做法，需要按实际情况选一种落地。
- 「一键发布到 NAS」这条路径本身没有实测（没有可用的 NAS 地址），只验证到了本地发布目录与 HTTP 拉取。
- Obtainium 的实际配置与后台更新行为需要在手机上完成一次真机验证。
- 1.2.0 中列出的其余真机项（系统分享实际带哪些标题字段、剪贴板 HTML、SAF 文件夹授权、
  真实网络下的抓取、覆盖升级安装流程）仍未验证，详见 `VERIFICATION-1.2.0.md`。
