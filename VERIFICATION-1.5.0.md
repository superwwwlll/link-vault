# 链藏 1.5.0 实际验证结果

本版新增**笔记区**：保存剪贴板文字、数据、密码等，可搜索。数据库 schema 升到 v5，JSON 备份格式升到 v2。

## 本次交付内容

- 底部新增「笔记」页签：手动新建/粘贴，列表搜索，16000 字上限，硬删除 + 二次确认。
- 「私密」开关：正文以主密码派生密钥按行 AES/GCM 加密（PBKDF2，12 万次迭代，零新依赖），
  列表显示掩码；解锁前/后窗口 FLAG_SECURE 禁截屏。
- 主密码统一：设密、解锁、改密（全量重加密、失败整体回滚）；笔记主密码与 `.lvault`
  加密备份口令是同一个。
- 首页剪贴板提示条：剪贴板有纯文本时提示存为笔记（不自动监听，不留残留处理）。
- JSON 备份升 v2：非私密笔记进明文 JSON；私密笔记只进 `.lvault` 加密通道；
  声称带 notes/vault 的 v1 文件一律拒绝。
- UI 修复：收藏页页签徽标（「未读 10」竖叠）与「最近收藏 · N 条」折行改硬性单行，
  窄屏/大字不再换行。

## 已交付

- 工程 APK：`deliverables/lian-cang-1.5.0.apk`；发布目录 `publish/`
- GitHub Release：<https://github.com/superwwwlll/link-vault/releases/tag/v1.5.0>
- 校验记录：`deliverables/apk-verification-1.5.0.txt`、`deliverables/SHA256SUMS-1.5.0.txt`

APK 字节数 **1,415,946**（1.4.0 为 1,383,182，+32,764）；
SHA-256：`cc439977fc299a792b08a210a0c18b115ca90155211fe7b9aea40b482e30a2fd`。
回拉校验：从 `releases/latest/download/lian-cang-debug.apk` 下载回的包与本地 SHA-256 一致。

## 构建与自动测试

`./release.sh` 完整跑通：

- **BUILD SUCCESSFUL**，退出码 0。
- **192 项测试通过，0 失败、0 错误**（含新增 NoteCryptoTest / NotesStateTest）。
- lint：**0 errors、10 warnings**（全部是「依赖有新版本」的常规提醒，与本次改动无关）。
- 签名证书 SHA-256 与 1.0.0 起的各版本**完全一致**（`5105679d…3e7d`），可覆盖升级；
  v2 签名方案通过，`android:debuggable` 出现 0 次。

应用内更新清单实测：`releases/latest/download/latest.json` →
`versionName=1.5.0 versionCode=21`，即手机上运行 1.4.0 时会自动提示更新。

## 截图验证

`deliverables/screenshots/` 十七张截图（Robolectric API35 + 原生 Skia 绘制实际 View 树），
新增笔记编辑、锁定、解锁、剪贴板提示条四张，均已逐张目视核对。

已知缺口：**设主密码 / 修改主密码两个弹窗没有截图**。含 `OutlinedTextField` 的 Dialog
在 Robolectric 原生图形模式下会让弹窗停在 0×0 并挂起空闲检测，整条通道只有这两个弹窗
拍不了；流程改由直接调用 ViewModel 方法覆盖（断言弹窗入口按钮存在），缺口在此说明而不伪造。

## 仍需真机验证

- 主密码全流程在真机上的表现：PBKDF2 12 万次派生的解锁耗时（低端机可能 1–2 秒）、
  FLAG_SECURE 禁截屏的实际效果。
- Android 13+ 剪贴板预览与提示条的实际体验。
- 窄屏（约 360dp）下两处单行修复后的真机观感。
- 覆盖升级安装流程：v4→v5 迁移、笔记与收藏数据完整保留。
