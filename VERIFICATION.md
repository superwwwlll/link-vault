# 实际验证结果 — 链藏 1.0.0

## 结论

已在独立 Docker linux/amd64 容器成功构建 debug APK。**未连接安卓真机，未启动完整 Android 模拟器；不宣称真机验收通过。**

- `testDebugUnitTest`：15 项通过，0 失败、0 错误、0 跳过。
- `lintDebug`：通过，0 errors、9 warnings，全部为 GradleDependency 新版依赖提醒；没有屏蔽错误或新增 lint baseline。
- `assembleDebug`：成功。
- 全流程第二轮 `BUILD SUCCESSFUL in 2m 31s`，脚本退出码 0。
- `apksigner verify --verbose --print-certs`：通过，APK v2 签名，RSA 2048 位。
- 标准 `gradle wrapper` 生成成功，并固定 Gradle 分发 SHA-256。

## APK

- 文件：`deliverables/lian-cang-1.0.0-debug.apk`
- 字节数：8,922,689
- SHA-256：`982b4714da663978c069b3dfdf14a22ed02f7b8fc8809d5e45835c8c3451847c`
- 包名：`cn.linkvault`；版本：1.0.0 / versionCode 1。
- 最低 Android 8.0 / API 26；target / compile API 35。
- 包含 arm64-v8a、armeabi-v7a、x86、x86_64 原生依赖，非仅限构建机 amd64。
- 从最终 APK 解出的清单确认：`ACTION_SEND text/plain`、singleTop、`allowBackup=false`、`fullBackupContent=false`、dataExtractionRules。
- 无 INTERNET、外部存储、相机或位置权限。AndroidX 添加一个本应用的 signature 级动态接收器保护权限，不是运行时敏感授权。
- 调试签名私钥仅位于专用 Docker volume `link-vault-debug-signing`，权限已收紧为 600；没有复制进工程或源码压缩包。

## 自动测试覆盖

1. **LinksTest — 12 项**：用户提供 X 示例、Twitter/mobile 域名归一、忽略 s/t、保留其他参数、普通网页查询/fragment、多链接/标点提取、平衡括号、非法协议/凭据/控制字符/端口拒绝、HTTP、大小写 scheme、假冒 X 子域、标签去重及搜索/精确筛选。
2. **DatabaseTest — 1 项组合测试（Robolectric API28）**：写入磁盘 Room、关闭并重开数据库、原始 URL/主键保留、更新备注、搜索+标签筛选、唯一索引拒绝重复、删除。
3. **StateTest — 1 项组合测试（Robolectric API28）**：SavedStateHandle 重建草稿、编辑中新分享不覆盖、待收分享处理、多链接提示、非法保存保留草稿并反馈错误。
4. **ActivityTest — 1 项组合测试（Robolectric API28）**：真实 Activity 冷启动分享、Activity recreate 保留编辑、热启动新分享保护草稿、空闲时热分享导入。

原始 JUnit XML、lint HTML/TXT 在 `deliverables/reports/`。成功构建日志在 `deliverables/build-success.log`。证书与打包信息见 `apk-verification.txt`、`apk-badging.txt`、`apk-manifest.txt`。

## 修复与已知提示

- 第一轮曾因数据库测试方法返回 Boolean 而不符合 JUnit void 要求失败；已修正为 Unit，第二轮全部测试通过。不是绕过测试。
- 审查后修正：普通网页 fragment 不被误去重、大小写 scheme 规范化跳转、读取重试取消旧订阅、Kotlin 文本换行。
- 初始 Docker Hub 认证服务不可用，换用微软官方固定 JDK 镜像成功构建；未修改/停止业务容器。
- 初始跨架构文件监听警告已通过关闭 Gradle VFS watch 处理；Compose 自带 libandroidx.graphics.path.so 无法剥离调试符号的提示不影响打包和签名。
- 固定使用已验证兼容的依赖版本，而非追逐全部最新版本。9 个 lint 新版本提醒完整保留。

## 尚需手机上验证

- 安装与首次启动、系统深浅色、软键盘/大字体/窄屏布局。
- 各品牌手机分享菜单，X 实际分享，真正进程回收恢复与系统备份/迁移行为。
- 外部浏览器/X 跳转，未安装可处理应用的提示。
- 触屏操作保存/搜索/多标签，删除弹窗取消与确认，覆盖升级保持数据库。
- 没有测试设备端强制停止/清除任务等系统场景；未保存草稿不保证在这些操作后保留。

建议首次安装后，先用少量测试链接完成以上检查，再保存重要收藏。卸载和清除数据会永久丢失收藏。
