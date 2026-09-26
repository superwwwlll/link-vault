# 链藏 1.2.2 实际验证结果

本版新增**应用内一键自更新**。数据库结构、备份格式与 1.2.1 完全相同，1.2.0/1.2.1 的验证结论继续适用。

## 本版改动

**应用内自更新**：打开应用时静默检查一次，发现新版本就在收藏页顶部出现提示条，
点「更新」即下载并自动拉起系统安装器。不再需要 Obtainium 或任何第三方应用。

新增/改动的文件：

| 文件 | 作用 |
|---|---|
| `Updater.kt`（新增） | 版本清单解析、下载与 SHA-256 校验、安装意图 |
| `UpdaterTest.kt`（新增） | 用本地 `ServerSocket` 桩服务把下载与校验路径完整测穿 |
| `VaultViewModel.kt` | 安装版本读取、更新状态机、检查/下载/安装动作 |
| `Settings.kt` | 「应用更新」区块：手动检查、自动检查开关、下载进度、授权引导 |
| `VaultScreen.kt` | 启动时静默检查；收藏页顶部的一键更新提示条 |
| `AndroidManifest.xml` | 新增 `REQUEST_INSTALL_PACKAGES`；新增 FileProvider（只暴露 cache/updates/） |
| `res/xml/file_paths.xml`（新增） | FileProvider 路径白名单 |
| `release.sh` | 发布时一并上传 `latest.json`（应用内更新读的就是它） |

## 已交付

- 桌面 APK：`/Users/mima0000/Desktop/lian-cang-1.2.2-debug.apk`
- 工程 APK：`deliverables/lian-cang-1.2.2-debug.apk`；发布目录 `publish/`
- GitHub Release：<https://github.com/superwwwlll/link-vault/releases/tag/v1.2.2>
- 版本化源码：`deliverables/link-vault-1.2.2-source.zip`
- 校验记录：`deliverables/apk-verification-1.2.2.txt`、`deliverables/SHA256SUMS-1.2.2.txt`

APK 字节数 **9,120,405**；SHA-256：`59a1531a1c8ba4d5e02b850d69897972368f0ce390984d381a6c89fcd2b4bdaa`。桌面副本哈希相同。

## 构建与自动测试

`./release.sh` 完整跑通（构建 → 签名核对 → 发布 → 回拉校验）：

- **BUILD SUCCESSFUL**，退出码 0，回拉校验 SHA-256 一致。
- **94 项测试通过，0 失败、0 错误、0 跳过**（1.2.1 为 83 项，本版新增 11 项）。
- lint：**0 errors、9 warnings**。9 条全部是「依赖有新版本可用」的常规提醒，
  与本次改动无关，没有一条指向新权限或 FileProvider。
- 签名证书 SHA-256 与 1.0.0 / 1.1.0 / 1.2.0 / 1.2.1 **完全一致**，可覆盖升级。

新增测试覆盖：清单字段解析与校验（原生包名、versionCode 缺失/为 0/为负、
versionName 为空、sha256 长度与字符集非法、体积离谱、JSON 畸形）、
版本高低比较、**下载内容与校验和一致才落盘**、**校验和不符必须删文件**、
**声明的体积超限必须在读取前拒绝**、**HTTP 错误不得写出垃圾文件**。

## 应用内更新链路的实际验证

对线上地址实测：

```
GET releases/latest/download/latest.json           → 200，369 字节
  内容：package=cn.linkvault  versionName=1.2.2  versionCode=5
        size=9120405  sha256=59a1531a…  releasedAt=2026-09-26T10:37:25Z
  与本地 publish/latest.json 逐字节一致
GET releases/latest/download/lian-cang-debug.apk   → 200，Content-Type 正确
```

下载后的 SHA-256 与清单、与本地构建产物三者一致。

## 权限变化（累计）

| 权限 | 引入版本 | 用途 |
|---|---|---|
| `INTERNET` | 1.2.0 | 可选的逐条页面信息抓取 |
| `REQUEST_INSTALL_PACKAGES` | 1.2.2 | 应用内自更新安装自己的新版 |

两处都会联网/安装，因此设置页的隐私说明已改为如实表述：
「只有两件事会联网：① 打开应用时检查一次更新；② 你自己开启抓取开关后逐条点击。」
「启动时自动检查」可以关掉，关掉后只有手动点「检查更新」才会联网。

`file_paths.xml` 只白名单了 `cache/updates/` 一个子目录，没有暴露外部存储或整个私有目录。
自更新下载的 APK 放在该目录，安装时经 FileProvider 单次授权给系统安装器。

## 仍需真机验证

- **完整的自更新流程**：下载 → 系统安装器弹出 → 覆盖安装 → 数据保留。
  这一条无法在 Robolectric 里验证，必须在真机上走一遍。
- 首次更新时「安装未知应用」授权的实际跳转与回跳。
- 系统分享实际带哪些标题字段、浏览器复制链接是否带 HTML、SAF 文件夹授权、
  真实网络下的抓取、覆盖升级安装流程 —— 同 1.2.0 的未尽事项。

## 本版修掉的一个真实缺陷

把自更新对象命名为 `Update` 时，它与 Room 的 `@Update` 注解**同名**，
而**同包声明优先于导入**，导致 `Database.kt` 里的 `@Update` 被解析成我的对象，
KSP 直接报「DAO 方法缺少注解」。已改名为 `Updater`。

这类冲突不会有编译警告，只会在构建期以完全不相干的报错形式出现，值得记一笔。

另外：测试最初用 `com.sun.net.httpserver` 写桩服务，编译失败 —— 单元测试虽然跑在 JVM 上，
但**编译期只有 android.jar**，不包含 JDK 的 `com.sun.*`。已改用 `ServerSocket` 手写。
