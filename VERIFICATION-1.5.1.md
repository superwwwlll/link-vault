# 链藏 1.5.1 验证记录

## 整合与升级

- 在迁移包的 `android-link-vault/` 核心源码上合并 GitHub `main` 的 Manus 风格 UI，保留原有未提交截图和迁移测试资源。
- 版本为 1.5.1 / versionCode 22；包名仍为 `cn.linkvault`，数据库仍为 v5。业务、加密和迁移代码未修改。
- 使用迁移包原签名私钥构建。私钥不纳入 Git，也不上传 Release。
- 从 GitHub 回拉 1.5.0 APK，SHA-256 与迁移包旧 APK 一致。新旧 APK 证书 SHA-256 都为 `5105679d9dab4656582dd7e6c3b85c2e40a2a1bd09146ac3474e052677ad3e7d`。

## 本次实际构建

- Apple Silicon 本地工具链：JDK 17 / Gradle 8.9 / Android 35 / build-tools 34.0.0。
- 执行 `testDebugUnitTest lintDebug assembleRelease`，BUILD SUCCESSFUL。
- 21 个测试套件、194 项测试，0 失败、0 错误、0 跳过。包括历史数据库迁移、笔记加密与 UI 点击测试。
- lint：0 errors、10 warnings、8 information；release 的 lintVital 检查通过。
- release 关闭调试、启用 R8 和资源收缩；实际 APK manifest 不含 `android:debuggable`。
- apksigner 验证通过，使用 v2 签名。
- 本次 17 张真实 Compose/View 树渲染截图存于 `deliverables/screenshots-1.5.1/`，未覆盖已有截图。

## 交付文件

- APK：`deliverables/lian-cang-1.5.1.apk`，1,415,950 字节。
- SHA-256：`f0a54e6b527ffb57f64a04f635fc19ceb62fdcf048aa129185b3ba2fdfdf9384`。
- 签名校验：`deliverables/apk-verification-1.5.1.txt`。
- 更新清单：`publish/latest.json`，versionCode 22。
- GitHub Release 保留历史资产名 `lian-cang-debug.apk`，实际为正式签名 release 包。

## 真机边界

尚未在用户手机上实际安装。包名、签名、版本递增均满足覆盖升级条件，但不声称已完成真机验收。
安装前导出收藏 JSON；有私密笔记时另导出 `.lvault` 加密备份。直接覆盖安装，不要卸载或清除数据。
主密码弹窗、系统分享、禁截屏、真实网络及真机上的数据保留仍需验收。
