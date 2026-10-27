# Phase 4C：OpenAI-compatible HTTP 模型适配验收

日期：2026-10-14，Asia/Shanghai。基线 ff3eb42。默认 Mock 保留；**146 项本地测试＋1 项真实基础设施/本地模拟模型 HTTP 综合测试通过**。未调用任何第三方模型/收费 API，不将 HTTP 模拟服务作为真实 LLM 验收。无 DDL/新表、旧数据修改、资源清理或新 Redis/XXL-JOB 功能。

## 实现与文件

| 文件 | 变化 |
| --- | --- |
| model/OpenAiCompatibleModelClient.java | Java 17 HttpClient 非流式 Chat Completions，有界 body/总请求期限，严格响应校验、禁止重定向和应用重试 |
| model/OpenAiModelSettings.java、model/ModelCallException.java | 显式配置、安全 URL/凭据/范围检查、脱敏对象与分类错误码 |
| config/ModelConfiguration.java、model/MockModelClient.java | Mock/HTTP 互斥装配，默认 Mock，HTTP 需 Worker 期限保护及 15s 写回预算 |
| service/TaskWorker.java、application.yml | 持久化分类模型错误，数据库 StorageFailure 原契约保留；MODEL_* 环境变量 |
| model/LocalModelHttpServer.java、model/OpenAiCompatibleModelClientTest.java、config/ModelConfigurationTest.java | 测试专用 loopback HTTP 服务及 8 项关键测试 |
| repository/TaskPipelineModelHttpIT.java、pom.xml | model-http-it 显式 profile，真实 MySQL/Rabbit/HTTP 链路，不访问第三方端点 |
| scripts/verify-model-http.ps1、scripts/verify-outbox.ps1 | 复用安全交互凭据/专用 Broker 检查/独立报告；原 Outbox 默认入口兼容 |
| repository/TaskPipelineRabbitIT.java | 旧计数 Mock 配置加专用 profile，保留旧测试并阻止污染新模型装配 |
| README.md、docs/project-review.md、本报告 | 配置、证据、源码/面试问题和能力边界 |

生产前缀 src/main/java/com/example/aitask；测试前缀 src/test/java/com/example/aitask。没有新依赖/表/API。Mock 和未知旧客户端异常仍 MODEL_CALL_FAILED；HTTP 使用 MODEL_TIMEOUT/RATE_LIMITED/AUTH_FAILED/UPSTREAM_UNAVAILABLE/REQUEST_REJECTED/HTTP_ERROR/RESPONSE_INVALID/RESPONSE_TOO_LARGE/TRANSPORT_FAILED/INTERRUPTED。失败结果沿用 HTTP 409 的原 DTO。

## 自动化结果

2026-10-14 **15:53:38 +08:00** 完整默认 Maven verify 成功：146 tests、0 failures/errors/skipped。原 138 全保留，新增 6 个 HTTP/Worker 测试＋2 个配置测试；循环只用于同类边界，不为数量扩展框架。证据 `target/phase4c-offline/cf16fe7d4bd34dd088688eca896f02da/verify.log`、build/surefire-reports。

新增覆盖：实际 HTTP 成功/Authorization/非流式协议；HTTP 429/503/401/302 分类与无重试；错误 JSON/空内容/结构/截断/超限；慢响应头及慢响应体超时；拒绝连接；晚到真实 HTTP 结果不覆盖 TASK_TIMEOUT；默认 Mock、显式 HTTP 装配；缺凭据、错误 URL/模式/超时范围/Worker 预算拒绝。无真实第三方 API Key，服务仅监听 127.0.0.1 随机端口。

初次编译发现两种 enum 的 RATE_LIMITED 静态导入冲突，已修复；配置测试缺线程池参数已补；完整回归发现旧 Rabbit 计数 Mock 被扫描进入其他测试上下文，已加专用 profile 隔离，并为新集成测试的定时隔离配置也加 profile。失败日志保留 target/phase4c-focused/f2cc756d715847aa9501ea3939f6eec9、7525df1d366843308ef496941b733b0e 和 target/phase4c-offline/e534acb66f184ca590fbf9dddba748db。未删除/跳过旧测试、未将失败报告为通过。

## 完整链路实际证据

本机 Docker 29.8.1，P1 Rabbit/Redis 容器健康；只读检查所有容器/网络/卷，P2 的 mysql-erp/rag-demo/pgvector 与卷保留。运行脚本前安全核对 P1 Broker owner=p1、Compose project=p1-ai-task、127.0.0.1:5673。MySQL 密码经本地交互窗口进入子进程环境；Broker 凭据仅在本机进程内从已验证 P1 容器读取，不输出。人工迁移批准沿用已确认的 002/003，在测试进程设置对应审批变量，未再执行 DDL。

执行 `scripts/verify-model-http.ps1 -UseP1Broker -NonInteractive -Offline`；Offline 仅使用 Maven 本地依赖，真实服务连接确实执行。于 **2026-10-14 15:54:29 +08:00** BUILD SUCCESS、exit=0：146 项本地＋1 项 model-http-it，分别 0 failures/errors/skipped。

| 验证 | 实际结果 |
| --- | --- |
| HTTP 提交、持久化派发意图 | 2 次新请求；成功任务派发前读取 PENDING/v0 及对应 PENDING Outbox |
| Scoped Outbox → Publisher Confirm → Rabbit | 2 个事件均 SENT，使用现有生产 Dispatcher/Publisher，限定本轮 taskId |
| Worker → 本地模型 HTTP → MySQL | 正常任务 SUCCESS/v2，HTTP 结果查询 200，内容 `[LOCAL HTTP] hello HTTP model 中文` |
| 事务外模型执行 | 模型请求处理中另一真实 MySQL 连接取得同一 RUNNING 行的 FOR UPDATE 锁并提交 |
| 重复消息/幂等 HTTP 提交 | 3 次 MQ 投递、2 次模型 HTTP 调用；正常任务重复消息无新增模型请求，同键 HTTP 返回 200 |
| 模型 HTTP 429 | FAILED/MODEL_RATE_LIMITED/v2；事件 SENT，代表派发成功、模型执行失败，未立即重试 |
| ACK/队列 | 本轮 work/parking ready=0，重复消息不隔离；消费沿用原手动 ACK |
| 数据隔离 | 2 个随机任务、2 个 Outbox 事件保留，未修改旧任务/事件、未清理队列/数据卷 |
| 外部模型 | thirdPartyCalls=0；模型地址/令牌由测试自建并优先覆盖外部 MODEL_* |

为避免全库扫描旧数据，联合测试专用配置移除 ScheduledAnnotationBeanPostProcessor，生产 Bean 和调度代码不变；通过 HTTP 后手动调用真实限定 taskId 的 Outbox Dispatcher。此次验证不是定时扫描/跨 JVM/崩溃恢复测试。Redis 本轮关闭。

```text
MODEL_HTTP_IT RESULT run=2dbe66d5-5303-45a4-950e-d0619484f211 mysql=8.0.45 localModelOnly=true thirdPartyCalls=0 httpAccepted=2 eventsSent=2 deliveries=3 modelHttpCalls=2 duplicateSuppressed=true rateLimitFailed=true modelLockFree=true taskVersions=2 retainedTasks=2 retainedEvents=2
```

本轮输出目录 `target/phase4c-runs/837dba8e18d1475889ca08c2089af1ae/`：verify.log、result.json、build/surefire-reports、build/failsafe-reports；最新 `target/phase4c-latest.json` passed=true。原始日志、模拟令牌、构建文件和本地交互辅助脚本不进入 Git；模拟服务/应用/本地交互进程均结束。核心复盘见 docs/project-review.md 第 13 节。

## 重跑和边界

默认 `mvn verify` 不连接 MySQL/MQ/外部模型，只执行本地测试（含 loopback HTTP）。真实基础设施链路需显式 opt-in：确认已人工迁移 002/003，设置 OUTBOX_IT_SCHEMA_APPROVED=true、WORKER_LEASE_IT_SCHEMA_APPROVED=true 后执行 `scripts/verify-model-http.ps1 -UseP1Broker`；缺审批/密码明确拒绝，不降级。脚本复用现有安全终端密码输入，-NonInteractive 需外部 MYSQL_IT_PASSWORD。不要将密码/API Key 粘贴聊天、命令参数或配置文件。

真实第三方适配仍需用户明确授权和安全 API Key 才可执行，本阶段不阻塞于此。尚未验证供应商特定协议/TLS/质量/费用、远端网络故障、真实取消、JDK 17 运行（本机 JDK 25、release 17）、跨 JVM 崩溃或性能指标。适配只接受纯文本完整 stop 结果，无流式/tool_calls/重试；客户端取消不能保证上游未执行/未收费。既有 NULL/NULL RUNNING 不自动恢复，无 heartbeat/续期/外部 fencing/XXL-JOB。

PowerShell 两个入口 AST 0 错误；父进程没有审批标记时，新入口非交互执行退出 2，在凭据读取/连接前拒绝。提交前核对 111 个 tracked/untracked 候选文件：禁止产物路径 0、凭据特征命中文件 0；真实验收日志/XML/JSON 同类特征命中 0。该启发式扫描不能证明任意秘密不存在；结合环境传入方式和人工差异审查，暂存仅本阶段 18 个文件。父进程未遗留 MYSQL/DB/MQ/MODEL 密码或 002/003 审批变量，git diff --check 通过；target/.env/日志/本地密码配置保持忽略。

**满足 Phase 4C 最小可用适配与工程链路门槛，可以进入 Phase 5 收尾；尚未达到真实第三方模型验收。** 本轮按明确授权进行敏感文件检查后本地提交，不 push；完成后停止。
