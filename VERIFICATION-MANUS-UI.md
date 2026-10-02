# Manus 风格 UI 与本地环境验证

## 结果

- Apple Silicon 原生 JDK 17.0.19、Gradle 8.9、Android 35 SDK、build-tools 34.0.0 已安装并验证可用。
- `bash preview-ui-macos.sh`：`testDebugUnitTest lintDebug assembleDebug` 成功。
- 21 个测试套件、194 项测试，0 失败、0 错误、0 跳过。
- lint：0 errors、10 warnings，另有 8 条 information。
- 真实 Android Compose/View 树通过 Robolectric API35 原生 Skia 渲染，生成 17 张截图。
- 新版截图：`deliverables/screenshots-manus/`，旧版截图未覆盖。
- debug APK：`app/build/outputs/apk/debug/app-debug.apk`，9,504,502 字节；未发布。

## 复现

```bash
bash setup-macos.sh
bash preview-ui-macos.sh
```

工具、缓存和用户目录隔离在 `~/.local/share/link-vault-android/`。
JDK、Gradle、SDK 命令行工具下载均核对官方校验值，系统 Java 配置不变。

首次运行发现既有迁移测试缺失 `room-v4-schema.json`，改为直接读取版本化的 v4 schema，
并仅将 `app/schemas/` 加入测试资源。数据库 schema 与迁移代码未改动。

## 目视核对

已检查收藏浅色、收藏深色、笔记展开/掩码、标签网格、设置和收藏详情截图：
新中性色、胶囊筛选、轻描边卡片、圆形新增按钮和底部导航均实际渲染。
收藏浅色截图的搜索框处于测试点击后的焦点态，因此显示深色焦点描边。

## 边界

这些是 Android UI 的真实渲染截图，不是完整模拟器或真机截图。
主密码输入弹窗、厂商分享菜单、禁截屏效果、真实网络、覆盖升级仍需真机验收。
截图内笔记和口令均为测试样本。
预览 APK 使用隔离环境的 debug 签名，**不能覆盖已有正式版**；未使用或替换正式签名私钥。
