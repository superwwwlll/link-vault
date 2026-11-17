# 三套设计方案

这些图是当时的设计效果图，不是真机截图。川山选定 B 之后，应用界面按这一套落地；示例收藏没有写入业务数据。

同一组示例收藏：少数派《把读过的长文留在自己的书架上》，备注「先看结构，再决定要不要翻译。」另有知乎、36氪、GitHub、维基百科四条。

- A 工具：白底、无衬线、五条细线列表，黑色下划线页签。
- B 书刊：暖纸、衬线标题、朱红下划线，首屏三篇，备注当作摘句。
- C 收藏库：冷灰、编号目录、青绿下划线，第一条标题更大。

推荐先按 B 发展。现在的薄列表已经接近 A，看过之后觉得不好看。链藏是用来留长文和一句自己的话的，B 改的是留白、字号和字体，四档页签、底部导航、新增、查找、阅读、笔记都还在。想要现代目录就选 C，想要最少装饰就选 A。

页签参考 Material 3 Tabs（https://m3.material.io/components/tabs ，规格 https://m3.material.io/components/tabs/specs ）。工程里已有的 Compose Material 3 就能做文字页签和下划线，这次没有加依赖，也没有升级版本。分段按钮不是这套页签。

底栏和正文字号参考 Apple Human Interface Guidelines 的 Tab bars（https://developer.apple.com/design/human-interface-guidelines/tab-bars ）和 Typography（https://developer.apple.com/design/human-interface-guidelines/typography ）。只借 17pt 正文和「底栏用来切换分区」。这仍是 Android 应用，没有改成 iOS 工程，没有用 SF Symbols，也没有做 Liquid Glass。

compose-cupertino（https://github.com/alexzhirkevich/compose-cupertino ，Apache-2.0）目前是 alpha，扩展图标包含受版权保护的 SF Symbols，不建议加进工程。

shadcn/ui（https://ui.shadcn.com ，源码许可 MIT，https://github.com/shadcn-ui/ui/blob/main/LICENSE.md ）是网页 React 组件，不能直接用于 Android。这三套没有按它来画。

渲染用的是 OFL 的 Noto Sans SC 和 Noto Serif SC，字体文件没有放进仓库。图标是自绘描边。生成脚本是 `build_mockups.py` 和 `compose_sheets.py`。
