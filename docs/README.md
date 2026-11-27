# 叠石 · 项目文档

日常开发从仓库根目录启动 Expo。需要改业务逻辑或定位设备问题时，可以按下表找到对应实现和测试。

| 要做的事 | 入口 |
| --- | --- |
| 配置模拟器、启动开发包、查看热刷新 | [Android 开发指南](development.md) |
| 找到页面、组件、Kotlin 业务逻辑和 Android 适配 | [客户端工程说明](android-client.md) |
| 运行单元测试、设备测试或回放固定样例 | [测试说明](../test/README.md) · [晚餐样例](../test/fixtures/issue10/README.md) |
| 构建调试包、签名候选包和发布稳定版 | [Android 构建与发布](android-build-release.md) · [0.1.0 功能与体验步骤](releases/0.1.0.md) |
| 参与开发、维护资产和提交改动 | [参与说明](../CONTRIBUTING.md) · [AI 协作约定](../AGENTS.md) |

## 产品与设计

| 文档 | 回答的问题 |
| --- | --- |
| [产品需求](prd.md) · [产品与技术调研](research.md) · [用户研究与同题对照](user-research.md) | 为谁保留哪些材料，如何用晚餐场景验证建议、依据和纠正？ |
| [信息架构](information-architecture.md) · [Paper 信息架构](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-4-0) | 对话与记录、记录库和设置分别承载什么？ |
| [系统设计](system-design.md) · [数据库设计](database-design.md) · [图形源文件与 SVG](diagrams/) | 数据怎样在模块间流动，材料、证据、偏好和任务如何关联？ |
| [第二次团队作业要求](https://edu.cnblogs.com/campus/fzu/202601SofwareEngineering/homework/16773) · [早期菜谱方向](recipe/) | 课程要求和产品演变的原始材料在哪里？ |

系统与数据库设计说明书参照 GB/T 8567—2006 编排。维护图形时可使用 [UML 渲染脚本](../scripts/render-diagrams.py)和[信息架构渲染脚本](../scripts/render-information-architecture.py)。

## 视觉与原型

| 文档或资产 | 内容 |
| --- | --- |
| [移动端原型说明](prototype-design.md) · [Paper 原型](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-5-0) · [本地点击导览](../assets/prototype/index.html) | 画板、状态、交互流程与需求对应 |
| [VI 与设计系统](vi-design.md) · [Paper VI 基线](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-1-0) | 颜色、字体、浅色新拟物控件与验证记录 |
| [视觉选择记录](design-decisions.md) · [Paper 探索页](https://app.paper.design/file/01M41JA45T3KFWNYX3GFW8SB5Q/p-3-0) | 霞鹜文楷、Leftist Mono Serif、Iconoir Regular 与控件风格的选择依据 |
| [字体](../assets/fonts/README.md) · [品牌图形与项目资产](../assets/README.md) · [Iconoir 图标](../assets/icons/SOURCES.md) | 字体角色、品牌源图、图标语义与许可 |
| [设计 token](../assets/design-tokens.json) · [CSS 主题](../assets/theme.css) · [组件配方](../assets/components.css) · [VI 快照](../assets/vi/) | Android 与原型共用的视觉依据 |

## 客户端实现

| 文档 | 内容 |
| --- | --- |
| [Android 客户端](android-client.md) · [记录与详情](android-record-flow.md) · [Compose 共用组件](android-components-compose.md) | 应用装配、记录保存与回查、页面控件和小屏验证 |
| [业务接口](core-api-contract.md) · [回执样例](core-api-receipt-examples.json) · [本地存储](android-storage.md) | 操作重放、修订冲突、Room 事务与数据迁移 |
| [调度状态机](scheduler-core.md) · [WorkManager 接入](scheduler-android.md) | 任务领取、阶段检查点、暂停和进程恢复 |
| [饮食算法](algorithm/dinner-ranking.md) | 经历提取、偏好条件、菜单排序与回放基线 |

## 设计资产维护

以下命令从仓库根目录运行。

| 命令 | 产物或检查 |
| --- | --- |
| `python scripts/build-prototype-preview.py` | 从 Paper 导出源和动作清单生成本地点击导览 |
| `python scripts/build-prototype-preview.py --check` | 检查画板与动作映射 |
| `python scripts/render-information-architecture.py` | 从 Mermaid 思维导图和 VI token 生成信息架构 SVG |
| `python scripts/render-diagrams.py --plantuml-jar "JAR 路径"` | 使用 Java 与官方 PlantUML JAR 渲染系统图和数据库图 |
