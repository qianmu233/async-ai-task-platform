# README 正式版修订依据

> 2026-10-26 发布快照脱敏说明：Windows 用户目录以 `%USERPROFILE%` 展示，不是当时原始命令的字面路径；版本、运行 ID、测试结果和失败记录保留。

审查日期：2026-10-21。修改前基线：`636b706`。本清单在 README 修改前建立；历史验收报告保留原样。

| 原句或小节 | 问题类别 | 可核查依据 | 修改方案 |
|---|---|---|---|
| “P1”“Phase 1–5 核心 MVP 工程验收通过”作为标题和开头 | 开发阶段用语 | `TaskController`、`TaskService`、`docs/phase5-verification.md` | 用异步任务平台定位开篇，历史阶段仅作为证据链接 |
| `%USERPROFILE%\...`、`Set-Location E:\codeplace\...` | 本机路径、不可移植命令 | `pom.xml` 的 Java 17；启动脚本的 JavaHome 参数 | 改为仓库根目录、已配置的 JDK 17 与 JAVA_HOME |
| “默认…138 项离线测试”与开头“147 项”并存 | 版本范围混用 | 最终独立运行目录的 20 份 Surefire XML 合计 147；根 target 仍有旧 99 项 | README 只列最终历史 147 项，注明日期、范围及本轮未重跑 |
| “MySQL 与后续范围”“Phase 4A 历史记录” | 开发日记压过项目介绍 | `JdbcTaskRepository`、`OutboxDispatcher`、`TaskWorker` | 归纳事务、消息、所有权的调用链，保留各份诊断链接 |
| “新增 TASK_ACCEPTANCE_KEY_PREFIX…” | 临时实现口吻 | `StorageConfiguration`、`JdbcOutboxRepository`、`TaskTimeoutChecker` | 说明这是验收隔离配置及其不能作为权限边界的限制 |
| README 没有许可证导航；仓库不存在 LICENSE/NOTICE | 公开复用权利不明确 | Git 跟踪文件、`pom.xml`，未发现整库复制的上游实现 | 明确未指定项目许可证；不代替权利人选择许可证 |

实现依据入口：`src/main/java/com/example/aitask/` 下的 `api`、`repository`、`outbox`、`messaging`、`ratelimit`、`model`、`service`；测试范围以 [最终验收](phase5-verification.md) 及其原始 XML 为准。个人贡献应根据提交和实际工作说明，不能把 AI 辅助生成的代码全部归为手写原创。
