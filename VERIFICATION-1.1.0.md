# 链藏 1.1.0 实际验证结果

## 已交付

- 桌面 APK：`/Users/mima0000/Desktop/lian-cang-1.1.0-debug.apk`
- 工程 APK：`deliverables/lian-cang-1.1.0-debug.apk`
- 版本化源码：`deliverables/link-vault-1.1.0-source.zip`
- 中文用法：`README.md`；安装提示：`deliverables/安装说明-1.1.0.md`
- 实际界面图：`deliverables/screenshots/`，共 7 张。
- 原 APK `lian-cang-1.0.0-debug.apk` 与原源码 `link-vault-source.zip` 均保留，未覆盖。

APK 字节数 **9,146,307**；SHA-256：`2735934c06003d1710f0dc49eef2dd30c916399378f600581525b481b20aaa56`。桌面副本哈希相同。

## 构建与自动测试

最终冻结源码后执行 `testDebugUnitTest lintDebug assembleDebug`：

- **BUILD SUCCESSFUL in 2m 3s**，脚本退出码 0。
- **31 项测试通过，0 失败、0 错误、0 跳过。**
- lint：**0 errors、9 warnings**，全部是固定依赖已有新版提醒；未屏蔽测试、未新增 lint baseline。
- APK：构建成功，`apksigner verify` v2 签名通过。

| 测试类 | 数量 | 验证内容 |
|---|---:|---|
| LinksTest | 12 | 原 X 示例、Twitter 归一、s/t 去重、其他 query/fragment、标点与多链接、安全校验、标签搜索 |
| ActivityTest | 1 | 冷启动/热分享、Activity 重建、编辑草稿保护 |
| StateTest | 1 | SavedStateHandle 草稿重建、多链接提示、非法保存不丢草稿 |
| DatabaseTest | 1 | Room 磁盘保存、关闭重开、更新、查询、唯一索引、删除 |
| BackupTest | 10 | JSON/Unicode 往返、空备份、版本/格式、无效 UTF-8、非法 URL/字段、10MB/条数/嵌套限制、重复导入、事务回滚 |
| BackupStateTest | 4 | 预览缓存恢复、缓存丢失失败关闭、ContentResolver 导出/导入往返、模拟磁盘写失败且草稿保留 |
| CompatibilityTest | 1 | 按第一版 schema fixture 建真实旧数据库并写旧记录，新版 Room 打开、去重导入不覆盖旧数据 |
| VisualTest | 1 | API35 实际 Compose 导航/详情/编辑、返回确认、底栏点击、主题切换、导入确认去重与原生绘制截图 |

原始 JUnit XML、lint HTML/TXT 在 `deliverables/reports-1.1.0/`；最终成功日志 `deliverables/build-success-1.1.0.log`。

## 覆盖升级与安全检查

- 包名保持 **cn.linkvault**；versionCode **1 → 2**，versionName **1.1.0**。
- 最低 API26 / Android8；target/compile API35。
- 对新旧实际 APK 分别运行签名验证，证书 SHA-256 完全相同：
  `5105679d9dab4656582dd7e6c3b85c2e40a2a1bd09146ac3474e052677ad3e7d`
- Room 仍为 schema version1；生成的 `1.json` 与第一版固定 fixture **逐字节一致**；旧 schema 打开测试通过。
- 最终 APK 清单无 INTERNET 或外部存储/相机/位置权限；保留 AndroidX 本包的 signature 级接收器保护权限。
- `allowBackup=false`、`fullBackupContent=false`、cloud/device-transfer 排除规则保持，不新增服务或账号。
- 备份只经系统文档选择器授予的 content URI 读写；未主动访问网络。用户可在系统文件提供器中选择云盘，界面明确说明。
- JSON 有10MB/10000条上限、最大32层嵌套、严格字段类型和URL校验；异常详情最多显示600字符；不信任文件ID/去重键，不替换原数据，异常事务回滚。
- 调试私钥仍只在专用 `link-vault-debug-signing` Docker 卷（权限600），不包含在源码或APK交付文档中。文档只记录公开证书摘要。

## UI 截图与验证的真实范围

七张截图由 **Robolectric API35 + NATIVE graphics + 原生 Skia Canvas 对实际 Android/Compose View 树绘制**：

1. `01-collection-light.png` — 收藏首页浅色
2. `02-detail-light.png` — 独立详情
3. `03-editor-light.png` — 独立编辑
4. `04-tags-light.png` — 标签网格
5. `05-settings-light.png` — 设置与备份入口
6. `06-collection-dark.png` — 收藏首页深色
7. `07-import-preview-dark.png` — 导入确认预览

主界面截图画布为1233×2673（411×891dp，xxhdpi）。这些是实际运行的 Compose/Android 视图，不是 HTML 效果图；样例收藏只在测试沙箱里，不会预置到用户数据库。使用中文 macOS Vision OCR 核验首页标题、摘要、域名、标签和底栏均真实可读。

**没有完整 Android 模拟器或真机截图。** 当前 Mac 未装原生 Android SDK/emulator，Docker 没有 /dev/kvm；没有为模拟器改变宿主机或业务容器。起初 Compose PixelCopy 在 Robolectric 缺少真实 Window 合成器时超时，已改用原生 Canvas 绘制真实视图，保留原生测试和界面点击断言，没有将截图伪装成模拟器截图。

官方 Cubox 图像已下载并浏览器打开，但当前Backend图片附件通道不能传输图像；设计研究使用官方文档与实际截图OCR位置/内容，并明确未目视。参考过程见 DESIGN-REFERENCE.md。

## 尚未验证，需手机测试

- 真实安装与从1.0.0覆盖升级的系统安装流程（只验证了包名、签名、版本与数据库兼容）。
- 系统 SAF 文件选择器、不同文档提供器/云盘实际导出导入，以及文件访问权限变化。
- 厂商分享菜单、真实X/浏览器外部跳转、系统实际进程回收、备份和设备迁移策略。
- 各屏幕尺寸、大字体、系统软键盘和手势栏的设备表现。

建议：**直接覆盖安装，不要卸载。第一版没有导出功能，首次升级完成后先去设置导出备份；此后升级前先备份。** 无论签名兼容测试还是Robolectric测试，都不等同于真机验收。
