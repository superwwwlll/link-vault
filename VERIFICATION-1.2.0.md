# 链藏 1.2.0 实际验证结果

## 已交付

- 桌面 APK：`/Users/mima0000/Desktop/lian-cang-1.2.0-debug.apk`
- 工程 APK：`deliverables/lian-cang-1.2.0-debug.apk`
- 版本化源码：`deliverables/link-vault-1.2.0-source.zip`
- 中文用法：`README.md`；界面参考说明：`DESIGN-REFERENCE.md`
- 实际界面图：`deliverables/screenshots/`，共 7 张（由 VisualTest 本次运行重新生成）
- 校验记录按版本分开保存：`deliverables/apk-verification-1.2.0.txt`、`deliverables/SHA256SUMS-1.2.0.txt`
  （同时补回了 1.0.0 与 1.1.0 的校验记录，`apk-verification.txt` / `SHA256SUMS.txt` 仍指向最新版）
- 历史产物 `lian-cang-1.0.0-debug.apk`、`lian-cang-1.1.0-debug.apk` 及其源码包均保留，未覆盖、未删除。

APK 字节数 **9,341,391**；SHA-256：`77ce609aee5add309ceb19d9e4d21a921da2197ba0fdf3a9b2fcf51a5d2c5f9f`。桌面副本哈希相同。

## 构建与自动测试

最终冻结源码后执行 `testDebugUnitTest lintDebug assembleDebug`：

- **BUILD SUCCESSFUL in 1m 33s**，脚本退出码 0。
- **83 项测试通过，0 失败、0 错误、0 跳过。**（1.1.0 为 31 项）
- lint：**0 errors、0 warnings**（1.1.0 为 0 errors / 9 warnings）；仍为 `abortOnError = true`，未屏蔽测试、未新增 lint baseline。
- APK：构建成功，`apksigner verify` v2 签名通过。

| 测试类 | 数量 | 验证内容 |
|---|---:|---|
| LinksTest | 28 | 原有 X/Twitter 归一、s/t 去重、标点与多链接、安全校验、标签搜索；新增跟踪参数（utm_*/fbclid/spm 等）跨站去重、同一文章多来源折叠成同一键、路径推导可读标题、随机 ID 不当作标题、站点名映射与子域上溯、系统分享标题清洗、剪贴板 HTML 取标题、标签大小写归一 |
| HtmlTest | 9 | HTML 实体（含数字实体/未知实体）解码、三种引号写法的属性解析、meta 按键优先级取值、title 标签、抓取结果纯解析、非 https 在任何网络操作前即拒绝 |
| StampTest | 2 | 今天/昨天/同年省略年份/跨年补年份、备份时效文案与过期判定 |
| CaptureTest | 8 | ACTION_SEND 的 SUBJECT/TITLE/HTML_TEXT 取值、SEND_MULTIPLE 合并、PROCESS_TEXT 划词、非文本分享忽略、剪贴板同时取出纯文本与 HTML、空剪贴板不崩 |
| LibraryTest | 8 | 置顶/已读/归档不改动 updatedAt、范围筛选、归档时关闭详情、标签跨条目重命名与合并去重、非法标签名拒绝、批量收藏去重、抓取开关与明文 http 拦截、草稿只在真实进程重启后恢复 |
| PaletteTest | 5 | 同域名同色、常见来源两两不同色、40 域名样本分散度、深浅色背景与前景可分辨、取色对同一键稳定 |
| BackupTest | 13 | 原有往返/限额/非法输入/事务回滚；新增 v1 备份缺少 v2 字段时按默认值导入、新字段精确往返、新字段类型与长度非法时拒绝整份文件 |
| CompatibilityTest | 2 | 按第一版 schema fixture 建真实旧库：新版 Room 打开后旧记录字段与时间正确；去重键重算时**碰撞不合并、不删除、不改写原链接** |
| BackupStateTest | 4 | 预览缓存恢复、缓存丢失失败关闭、ContentResolver 导出/导入往返、模拟磁盘写失败且草稿保留 |
| DatabaseTest | 1 | Room 磁盘保存、关闭重开、更新、查询、唯一索引、删除 |
| ActivityTest | 1 | 冷启动/热分享、Activity 重建、编辑草稿保护 |
| StateTest | 1 | SavedStateHandle 草稿重建、多链接提示、非法保存不丢草稿 |
| VisualTest | 1 | API35 实际 Compose 导航/详情/编辑、返回确认、底栏点击、主题切换、导入确认去重与原生绘制截图 |

原始 JUnit XML 与 lint 报告在 `deliverables/reports-1.2.0/`；构建日志 `deliverables/build-success-1.2.0.log`。

本次未新增任何测试绕过、未降低断言强度。三处既有断言按**有意的行为变更**同步更新：导出成功与导入完成的提示从错误通道改到轻提示通道（`error` → `message`）各一处，VisualTest 一处；迁移测试由「v1 打开 v1」升级为真正的「v1 迁移到 v2」。

## 覆盖升级与安全检查

- 包名保持 **cn.linkvault**；versionCode **2 → 3**，versionName **1.2.0**。
- 最低 API26 / Android8；target/compile API35。
- 对 1.0.0、1.1.0、1.2.0 三个实际 APK 分别运行签名验证，证书 SHA-256 完全相同：
  `5105679d9dab4656582dd7e6c3b85c2e40a2a1bd09146ac3474e052677ad3e7d`
  → 可以覆盖安装升级，不会因签名不一致被系统拒绝。
- Room schema **1 → 2**，`app/schemas/.../1.json` 与 1.1.0 版本**逐字节一致**（未被改写），新增 `2.json`。
  迁移 `Migration1To2` 只在旧库上追加列并回填 `createdAt`，不改写任何原链接。
- **权限变化（重要）**：新增 `android.permission.INTERNET`，这是本版本唯一新增权限，用于可选的页面信息抓取。
  1.1.0 的「零权限」属性不再成立，设置页文案已同步改为准确表述。抓取默认关闭，只有用户显式打开开关后，
  在详情页逐条点击才会发起请求；只请求 https、只读页面开头约 96 KB、不发送 Cookie、不执行脚本、
  不跟随跨站重定向（最多 3 跳且必须仍是 https）、明文 http 一律拒绝。
- 未新增外部存储、相机、位置、通讯录等权限；未新增服务、账号或后台任务。
- `allowBackup=false`、`fullBackupContent=false`、cloud/device-transfer 排除规则保持。
- 备份只经系统文档选择器授予的 content URI 读写；「备份文件夹」使用 `ACTION_OPEN_DOCUMENT_TREE`
  并申请持久化权限，用户可随时在设置里取消。JSON 仍有 10MB/10000 条上限、32 层嵌套上限、
  严格字段类型与 URL 校验；异常详情最多显示 600 字符；导入只新增、不覆盖、不删除，异常整体回滚。
- 调试私钥仍只在专用 `link-vault-debug-signing` Docker 卷（权限600），未读取、未移动、未打包。

## 本次发现并修复的一个真实缺陷

实现「来源域名配色」时，测试立刻暴露出：直接对 `String.hashCode()` 取模会让常见域名频繁撞色
（12 个色相下，`x.com`/`bilibili.com`/`zhihu.com` 三者同色）。改用 Murmur3 风格位混合后，
40 个常见域名可落到的不同配色从 20 种提升到 24 种，常用 8 个域名两两不同。

调色板有限、域名无限，撞色无法彻底消除；`PaletteTest` 因此对「常见来源」要求两两不同，
对「大样本」只要求达到可接受的分散度，并在代码注释里写明了这一取舍。

## UI 截图与验证的真实范围

七张截图由 **Robolectric API35 + NATIVE graphics + 原生 Skia Canvas 对实际 Android/Compose View 树绘制**：

1. `01-collection-light.png` — 收藏首页浅色（含范围筛选、彩色来源标识、标题/描述/备注/标签层级）
2. `02-detail-light.png` — 独立详情（含状态切换、页面描述、抓取入口）
3. `03-editor-light.png` — 独立编辑（含粘贴提取与批量收藏）
4. `04-tags-light.png` — 标签网格（含重命名/删除菜单入口）
5. `05-settings-light.png` — 设置（含联网抓取开关与备份时效）
6. `06-collection-dark.png` — 收藏首页深色
7. `07-import-preview-dark.png` — 导入确认预览

这些是实际运行的 Compose/Android 视图，不是 HTML 效果图；样例收藏只在测试沙箱里，不会预置到用户数据库。
截图文件大小在 110–290 KB 之间，说明是真实渲染内容而非空白画布；`VisualTest` 内部的
`check(root.width > 0 && root.height > 0)` 也会在布局塌陷时直接失败。

**本次没有目视查看截图。** 当前 Backend 的图片通道返回 image omitted，模型无法读取图像，
因此对「新版卡片视觉是否好看」没有做主观确认，只做了上述可程序化验证的部分
（布局非空、点击路径可达、配色分散度由 `PaletteTest` 覆盖）。

**没有完整 Android 模拟器或真机截图。** 当前 Mac 未装原生 Android SDK/emulator，Docker 没有 /dev/kvm。

## 尚未验证，需手机测试

- 真实安装与从 1.1.0 覆盖升级的系统安装流程（只验证了包名、签名、版本与数据库迁移）。
- **系统分享是否真的带有页面标题。** `EXTRA_SUBJECT` / `EXTRA_TITLE` / `EXTRA_HTML_TEXT` 的读取路径
  已按 Android API 实现并在 Robolectric 下测过，但 Chrome、X、微信等具体应用实际会带上哪些字段，
  必须在真机上确认。若某个应用不带标题，回退路径（路径推导 → 站点名）仍然可用，不会显示裸链接。
- **剪贴板 HTML 富文本是否可取到标题。** `ClipData.Item.getHtmlText()` 的调用路径已实现并测试，
  但真实浏览器复制链接时是否附带 HTML 表示，需真机验证。
- 系统 SAF 文件选择器、`ACTION_OPEN_DOCUMENT_TREE` 的文件夹授权与「立即备份到文件夹」实际写入，
  以及不同文档提供器/云盘的表现。
- 联网抓取在真实网络下的表现：超时、GBK 编码站点、需要登录的站点、被墙站点。
- 厂商分享菜单、真实 X/浏览器外部跳转、系统实际进程回收、草稿在强制停止后的恢复。
- 各屏幕尺寸、大字体、系统软键盘和手势栏的设备表现。
- 「标签重命名/删除」「置顶/归档」等新交互的实际手感。

建议：**直接覆盖安装，不要卸载。** 升级完成后先去设置做一次导出备份（设置页会显示上次备份时间，
超过 30 天会以警示色提醒）。无论签名兼容测试还是 Robolectric 测试，都不等同于真机验收。
