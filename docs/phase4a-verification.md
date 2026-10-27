# Phase 4A 验收：真实 MySQL/RabbitMQ Outbox 核心验收通过

日期：2026-10-14，Asia/Shanghai。**11:41:41 完整 verify：130 项离线＋1 项真实 MySQL/RabbitMQ Outbox 综合测试通过，0 failures/errors/skipped，退出 0。达到 Phase 4A 核心验收条件，按授权安全检查后建立本地检查点，不 push，不进入 Phase 4B。** 用户已人工执行并核对 002_outbox.sql；本轮 Codex 只读核对结构/权限，没有执行额外 DDL，也未修改生产代码或扩展测试数量。

## Phase 3A/3B Git 检查点

已读取 AGENTS/README/项目复盘/两阶段报告，核对当前历史及所有待提交文件。此前 HEAD a2dff33 包含 Phase 2A/2B，Phase 3A/3B 未提交。按本轮授权采用合并检查点，避免拆分重写共享文件：

```text
f4ba045 feat: add verified RabbitMQ dispatch and Redis submission limits
37 files changed, 2446 insertions(+), 76 deletions(-)
```

提交前 77 个 tracked/untracked 候选文件：产物/.env/日志/密钥路径 0，凭据特征 0；本地读取已校验 owner=p1 的 MQ/Redis 运行时密码及 XML/URL 编码形式精确匹配 0。没有外部工具接收凭据。精确扫描仅涵盖已知 P1 MQ/Redis 秘密，不声称证明任意秘密不存在。暂存仅 37 个已审源码/配置模板/文档/脚本，diff --cached --check 通过，提交后工作区干净。没有 reset/clean/push。

Phase 3A 历史真实 MQ/MySQL、Phase 3B 真实 Redis 证据留在该提交及 target；本轮未重跑它们。Phase 4A 改动发生在该检查点后，本次按用户授权建立独立本地检查点，提交主题为 `feat: add verified transactional outbox dispatch`，实际 ID 以 Git 历史/最终报告为准，不 push。

## 实现闭环

显式 mysql/rabbit/outbox → HTTP 注册 → **同一 JDBC 本地事务任务+事件 INSERT** → 202 → OutboxPoller 固定延迟扫描 → version/DB 时间 CAS 认领 → 事务外 Rabbit durable/persistent、confirm+mandatory → 有效 token/未到期条件标记 SENT。失败/未知 RETRY，有界指数退避；到期重派，预算耗尽 ISOLATED。Consumer 保留生产手动 ACK、MySQL 原子任务认领、业务失败和终态保护。

SENT 是 Broker 确认且有路由，不是消费/模型成功。confirm 丢失或 confirm 后标记失败可重复发布；消费者通过 taskId/任务状态仲裁，不能宣称端到端 exactly-once。Outbox 的 claim_token/locked_until 只保护事件派发，尚无 Worker RUNNING 租约/fencing。

| 文件 | 核心用途 |
| --- | --- |
| database/002_outbox.sql | 用户已人工执行并核对；本轮只读验证，未代为执行 DDL |
| repository/JdbcTaskRepository.java、config/StorageConfiguration.java | 显式配置下 task/event 同事务，事件错误不伪装请求幂等 |
| outbox/OutboxRepository.java、JdbcOutboxRepository.java | 事件认领、版本 CAS、token 条件更新、只读结构门禁、可选任务范围 |
| outbox/OutboxDispatcher.java、OutboxSettings.java、OutboxPoller.java | 确认/标记分离、有界批次/预算/退避/扫描、存储与意外失败计数 |
| outbox/OutboxSubmissionDispatcher.java、config/OutboxConfiguration.java | 取消 HTTP MQ 双写、mysql/rabbit/schema 门禁、默认关闭 |
| application.yml、pom.xml | 开关和预算环境变量，显式 outbox-it profile |
| repository/OfflineJdbcFixture.java、TaskOutboxTransactionTest.java | 最小扩展原 fixture，验证两写事务契约及回滚 |
| outbox/OutboxDispatcherTest.java、config/OutboxConfigurationTest.java | 调度成功/失败/重复/竞争、有效令牌、默认及启用配置 |
| repository/TaskPipelineOutboxIT.java、scripts/verify-outbox.ps1 | 显式迁移审批后的真实联合验收，本次实际通过 |
| README、docs/project-review.md、docs/outbox-migration-review.md、本报告 | 配置、原理/面试、审核步骤与证据边界 |

Java 生产前缀 src/main/java/com/example/aitask；测试前缀 src/test/java/com/example/aitask；YAML 为 src/main/resources/application.yml。原 TaskService、Publisher、Consumer、Worker 和 Redis 代码未修改；任务注册内部扩展，原认领/终态 SQL 保留。

## 自动执行的测试与结果

历史离线开发门槛 **2026-10-14 11:15:32 +08:00**：

```text
mvn -o -B -ntp -Dplatform.build.directory=<独立本轮目录> verify
Tests run: 130, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS; Maven exit=0
```

130=原 119＋新增 11：同事务/幂等及回滚 2、Dispatcher 成功/发布失败/确认后存储失败/并发/过期令牌/异常隔离 6、默认门禁/预算/启用与缺表配置 3。没有 Mockito/H2、新测试框架或重复增加旧 MQ 场景。默认不依赖真实服务；-o 只用于 Maven 依赖解析，与“离线测试”概念分开。

原始证据：target/phase4a-offline/c227185c24ba4e12803100d4a89f511e/verify.log、build/surefire-reports、build/ai-task-platform-0.1.0-SNAPSHOT.jar、result.json；最新 target/phase4a-offline-latest.json 标记 scope=offline-only、realOutboxExecuted=false、schemaApproved=false。

历史过程保留：先完整 129 项通过；增加配置装配测试后出现 7 个失败（辅助 @Configuration 被其他测试扫描），定位后移除组件扫描标记、改仅显式注册，最终 130 全通过。编译期间命名冲突已修复，初始 compile.log 后来被成功编译覆盖；不能称仍有原始编译失败文件。测试失败证据保留在 target/phase4a-offline/40a4602c3e884edda58573d940682dc9/；不把旧失败或早期少一项的运行算最终结果。

PowerShell AST 0 错误；verify-outbox.ps1 -UseP1Broker -NonInteractive 在没有迁移批准时退出 **2**，没有 Docker inspect、凭据提示、数据库连接或写入。直接 -Poutbox-it failsafe 门禁退出 **1**（1 预期失败、0 errors/skipped，approval=null），在 JDBC/AMQP 连接前拒绝。独立证据 target/phase4a-refusal/84d0405e0caf49f8b4baa1cc125de209/gate.log，不覆盖通过报告，也不算真实 Outbox 通过。

离线 fixture 是 Java 锁/事务快照替身，只验证调用边界、回滚控制流、CAS 调度契约；不证明真实 MySQL 锁、SQL 语法、提交耐久性或 MQ 行为。

## 本次只读前置核对与真实集成

用户确认已由管理员人工执行并核对新增表。先用业务账号建立单独只读 JDBC preflight（会话 10）：MySQL **8.0.45** / ai_task_platform / ai_task_app@127.0.0.1；SHOW CREATE TABLE 确认 InnoDB、事件唯一键、两个扫描索引、任务 FK 和四个 CHECK，information_schema 确认四个 CHECK 均 ENFORCED=YES，FK 指向本业务库 ai_text_task.task_id。

SHOW GRANTS 只有 USAGE ON *.* 和 **SELECT, INSERT, UPDATE ON ai_task_platform.***，没有 DDL/DELETE/GRANT OPTION 或其他业务库权限。核对证据 target/phase4a-preflight/preflight.log；该日志中的 CREATE TABLE 是 SHOW CREATE 的只读输出，未执行创建语句。

在测试进程中按本次授权设置 OUTBOX_IT_SCHEMA_APPROVED=true，MySQL 密码通过本地 Read-Host -AsSecureString 输入后仅置进程环境；MQ 凭据由脚本校验 P1 broker 归属后在本地读取。实际运行：

```powershell
$env:OUTBOX_IT_SCHEMA_APPROVED='true'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-outbox.ps1 -UseP1Broker -NonInteractive -Offline
```

-Offline 只关闭 Maven 依赖下载，SQL/AMQP 全部真实连接。脚本自动执行完整 `mvn -o -B -ntp -Poutbox-it -Dplatform.build.directory=<本轮目录> verify`：

```text
2026-10-14 11:41:41 +08:00
Surefire: 130 tests, 0 failures/errors/skipped
Failsafe: 1 real Outbox test, 0 failures/errors/skipped
BUILD SUCCESS; Maven/script exit=0
```

| 实际场景 | 结果/证据 |
| --- | --- |
| 真实会话 | Dispatcher A/B=11/12，Worker=13，均为独立物理 MySQL 连接；preflight/快照连接另计 |
| 同事务和幂等 | 新任务与事件一起提交；同键重试同 UUID，事件只有一条 |
| Dispatcher CAS 竞争 | 两个真实 SELECT 通过门栅先读同一候选版本；只有 1 次成功认领/标记 SENT，另一方 CAS 未胜出 |
| confirm/mandatory | 3 次正常发布实际确认并消费；2 次真实无路由 return 拒绝，不标记 SENT |
| confirm 后更新故障 | 受控 pre-update 异常注入，真实事件保持 DISPATCHING；不是伪称真实断网或 commit 丢包 |
| 到期重派与旧令牌 | 自然等待数据库时间约 30 秒，重认领/发布；旧 token 无法标记新认领，最终 SENT |
| 重复消费保护 | 3 条实际投递、仅 2 次 Mock 调用；重派任务保持 SUCCESS/version=2 |
| 退避/隔离 | 无路由事件 RETRY 后第二次失败 ISOLATED，attempts=2；对应任务保留 PENDING |
| 真实事务回滚 | 构造本轮事件的真实 FK 违反，MySQL 拒绝并回滚任务 INSERT；回查 rollback key 0 行 |
| ACK 与停机 | 原生产 Consumer/Worker 执行，pause=0；停测试 listener 后真实队列 ready/unacked/consumers 全 0 |

这是同一测试 JVM 内两个 Dispatcher 和一个 Worker，使用实际 SQL/真实 MQ；未启动 HTTP 服务、未覆盖跨 JVM 崩溃。复用原生产类，不用 OfflineJdbcFixture/Mock broker 代替真实基础设施。门栅只同步实际 SELECT，故障注入只发生在确认后的状态更新调用前；实际持久化、到期时钟、重复投递和 FK 错误均真实。

```text
OUTBOX_IT RESULT run=b57e7514-e910-4759-9fcd-41765b6144d5 dispatchConnectionA=11 dispatchConnectionB=12 workerConnection=13 claimedRace=1 markedSentRace=1 delivered=3 modelCalls=2 replay=true staleOwnerRejected=true mandatoryFailures=2 isolated=1 rollback=true retainedTasks=3 retainedEvents=3
```

原始证据：target/phase4a-runs/**b7dc00b31e634f11a1b848e6e1c98048**/verify.log、build/surefire-reports、build/failsafe-reports、result.json；最新 target/phase4a-latest.json。真实 IT 用时 32.03s；不将其写成吞吐或性能基准。首次真实执行通过，无需修改生产/测试代码或重复旧 MQ/MySQL/Redis 验收。

## 数据库最终快照与 Broker 状态

测试后另用只读连接，限定本次 `outbox-it:b57e7514-e910-4759-9fcd-41765b6144d5:%` 回查；证据 target/phase4a-preflight/snapshot.log。表中时间为数据库 UTC DATETIME(6)，无输出正文或敏感凭据。

| 场景 / taskId | 任务状态/version | Outbox 状态/attempts/version | 关键字段 |
| --- | --- | --- | --- |
| race / 8b85887b-971f-4b52-82bc-68afd458a0c1 | SUCCESS / 2 | SENT / 1 / 2 | 输出存在、任务错误 NULL；sent_at=03:41:09.784790 |
| replay / ebe6de4f-901a-4ef8-ab99-54aae83e3d52 | SUCCESS / 2 | SENT / 2 / 3 | 输出存在、任务错误 NULL；sent_at=03:41:39.957640 |
| return / 06424a03-6b73-4863-bb6e-b67bcb1e9407 | PENDING / 0 | ISOLATED / 2 / 4 | 无输出/完成时间，eventError=PUBLISH_UNCONFIRMED；isolated_at=03:41:40.202597 |

三个事件 claim_token/locked_until 均 NULL，rollback key 0 行；保留 3 个任务/3 个事件，无 DELETE 或旧数据更新。两个成功任务 completed_at 分别 03:41:09.789698 / 03:41:09.909841，后者早于最终 SENT，清楚证明模型成功与 Outbox 标记成功是不同语义。

RabbitMQ **4.3.6** / ai-task-platform-rabbitmq healthy。只读 list_queues 检查本次 p1.it.outbox.b57e7514-e910-4759-9fcd-41765b6144d5 的 tasks/parking：durable=true、ready=0、unacked=0、consumers=0。测试 listener/连接已关闭，队列保留，没有 purge/drop 或容器/数据卷清理；P2 和 Redis 未修改。

## 已处理的窗口与保留边界

当前真实验证：任务与事件同事务写入/失败回滚、发布前的已持久化意图可由独立 Dispatcher 派发、双连接 CAS 单胜、正常 confirm 与无路由 return、确认后未标记的到期重派、旧令牌拒绝、重复投递不重复成功任务认领，以及有限失败隔离。最终尝试崩溃到期隔离/一般存储异常等仍有离线契约证据，不能说全部故障都已真实注入。

未实现：旧 PENDING 自动回填、ISOLATED/parking 重放、已暂停 Consumer 自动恢复、RUNNING Worker 所有权/租约/fencing、完成不确定性对账、XXL-JOB 补偿。派发失败耗尽可能留下 PENDING+ISOLATED，不保证自动最终成功。默认关闭 Outbox 或手动停扫描时不能宣称可靠派发仍生效。

未验证：真实进程崩溃、DB/MQ 断网或提交结果丢包、Broker 重启/磁盘耐久性、跨 JVM Outbox 派发与吞吐/性能、JDK 17 运行。当前 JDK 25、Java 17 release。只等待 confirm 的时限不代表已验证所有连接/通道阻塞的总耗时上界；Broker 故障实验留待后续。

用户人工完成 DDL；本轮 Codex 没有 DDL、旧数据操作、数据/卷清理、P2/XXL-JOB 修改或真实收费模型。真实测试只创建/修改本轮任务与事件。按用户授权建立 Phase 4A 本地提交，不 push。**达到 Phase 4A 核心验收条件，本轮停止等待审核，不立即开发 Phase 4B。**

初版安全检查（提交前）：git diff --check 通过，产物/.env/日志/密钥路径及凭据特征 0；P1 MQ/Redis 密码精确扫描 468 个文本命中 0。本次真实验收后，在本地对 **515** 个文本/日志/XML 精确扫描 **实际 MySQL/RabbitMQ/Redis** 密码及 XML/URL 编码形式，命中 0；秘密未放入源码/文件/命令参数或外部连接器。父环境密码及 schema approval 标志未留存。只提交审查过的源码/SQL/脚本/文档，target/.env/本地配置继续忽略。
