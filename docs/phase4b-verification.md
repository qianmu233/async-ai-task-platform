# Phase 4B：最小 RUNNING 所有权与超时处理

日期：2026-10-14，Asia/Shanghai。开发基线 43c9b49。用户人工完成 003 迁移后，**15:33:09 完整 verify：138 项离线＋1 项真实 MySQL 综合测试通过，Phase 4B 最小验收条件满足，可以安排 Phase 4C。** 按本轮明确授权建立本地提交，不 push；完成后停止，不开发下一阶段。

## 完成功能

显式开启后，claimExecution 在 PENDING→RUNNING 时生成 UUID 执行令牌和固定截止时间；TaskWorker 携带内部 Execution 句柄完成任务。MySQL 使用同一短事务的行锁和数据库 UTC 时间，终态 UPDATE 要求 taskId/RUNNING/token/未过期均匹配；旧 Worker、错误 token、已终态及过期完成均拒绝更新。成功、模型失败或超时清空所有权字段，version 正常终态=2。

TaskTimeoutChecker 复用既有 Spring @Scheduled 机制固定延迟检查，单次最多 20 行条件 UPDATE：带 token 的超时 RUNNING→FAILED/TASK_TIMEOUT，output=NULL、completed_at=DB UTC、version+1。竞争处理者必须再次匹配条件，不能重复成功；不重新调用模型、注册任务或派发事件。

MySQL 认领/完成先 SELECT FOR UPDATE 获得本地行锁，再启动新的 UTC_TIMESTAMP 语句，避免锁等待期间时钟过期仍使用旧语句时间。模型调用在数据库事务外。Memory 使用相同语义和进程内 Clock/同步，仅作开发模式，不宣称分布式。

默认开关关闭，以兼容尚未迁移数据库，原 memory/local 与所有旧测试保留；启用缺字段/无效配置时明确失败，不退回无保护执行。关闭时不宣称有本阶段的有效期保护。TaskService/REST DTO、Rabbit Consumer/Publisher、Outbox、Redis 未改动；TaskWorker 通过新增 Repository 句柄方法工作，原构造方法/旧调用仍用于明确的旧模式。

## 文件与最小迁移

| 文件 | 用途 |
| --- | --- |
| database/003_worker_lease.sql | 用户人工增加两个可空字段、索引和执行所有权 CHECK；Codex 只读核对 |
| repository/TaskRepository.java | 内部 Execution 句柄、受保护完成/过期接口，旧模式显式兼容 |
| repository/JdbcTaskRepository.java、InMemoryTaskRepository.java | 所有权/期限验证、短事务行锁、条件超时失败、旧记录忽略 |
| service/TaskWorker.java | 认领后携带句柄完成；晚到结果/失败不覆盖超时，无模型重试 |
| service/TaskTimeoutChecker.java、config/TaskTimeoutConfiguration.java | 复用定期扫描、有限批次、故障分类、显式配置/结构门禁 |
| config/StorageConfiguration.java、application.yml | 默认关闭及配置值，MySQL 模式显式装配 |
| service/TaskExecutionTimeoutTest.java、config/TaskTimeoutConfigurationTest.java | 8 项关键离线测试 |
| repository/TaskExecutionLeaseIT.java、pom.xml、scripts/verify-worker-lease.ps1 | 已通过的 1 项真实 MySQL 综合验收，独立 opt-in profile；写入前只读结构/权限门禁 |
| README、docs/project-review.md、docs/worker-lease-migration-review.md、本报告 | 配置、审核、证据与源码/面试说明 |

Java 生产前缀 src/main/java/com/example/aitask；测试前缀 src/test/java/com/example/aitask；配置 src/main/resources/application.yml。

原任务生命周期 CHECK 不改动；新增约束允许旧 NULL/NULL 数据，仅新受保护 RUNNING 要求 token/deadline 成对且截止时间晚于 started_at。旧 RUNNING 不回填、不自动处理；部署前停止/排空旧 Worker、所有实例一致开启。详情 docs/worker-lease-migration-review.md。

## 自动测试结果

迁移前 **2026-10-14 12:17:16 +08:00** 完整 Maven verify（历史离线证据）：

```text
mvn -o -B -ntp -Dplatform.build.directory=<独立本轮目录> verify
Tests run: 138, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS; exit=0
```

138=原 130＋新增 8。5 项执行测试：合法/错误所有者、到期前后与终态保护、晚到模型成功/失败不覆盖超时且不重试、两个处理者单次超时成功、有限批次/范围；3 项配置测试：默认关闭/内存可开启、参数门禁、缺 MySQL 字段拒绝。没有新的测试框架、H2 或 Mock 数据库冒充真实。

证据 target/phase4b-offline/20cca7b420fb4b1c97df5d05ef3f0ab7/verify.log、build/surefire-reports、build/ai-task-platform-0.1.0-SNAPSHOT.jar、result.json；最新 target/phase4b-offline-latest.json 的 scope=offline-only、realWorkerLeaseExecuted=false、schemaApproved=false。

初次 test-compile 有方法声明拼写错误，已修复，日志留 target/phase4b-initial-compile-failure.log；随后 12:13:39 的完整 138 已通过。审查发现数据库语句时间/锁等待边界后补短事务行锁，再执行最终 12:17:16 全套通过，没有删除旧测试或把失败写成通过。

PowerShell AST 0 错误；没有审批时 verify-worker-lease.ps1 -NonInteractive 退出 2，未连接数据库/提示密码。直接 worker-lease-it failsafe 门禁退出 1（1 个预期 AssertionFailedError，0 errors/skipped），在连接/写入前拒绝，证据 target/phase4b-refusal/881f154441744c5d82702afdf8a3c95c/gate.log。该负向拒绝不算真实数据库通过。

## 真实验证结果

用户已确认人工执行 003。Codex 使用本地交互密码窗口，仅向本机测试子进程环境传入凭据和 WORKER_LEASE_IT_SCHEMA_APPROVED=true；窗口/测试进程已退出，密码未进入命令参数、源码、日志、报告或 Git。没有执行任何 DDL。

实际入口 `scripts/verify-worker-lease.ps1 -NonInteractive -Offline`；Offline 仅禁止 Maven 下载依赖，真实数据库连接仍实际执行。完整 Maven verify 于 **2026-10-14 15:33:09 +08:00** 成功，exit=0：138 项离线、1 项真实 MySQL，分别 0 failures/errors/skipped。没有增加测试数量，也没有重跑此前 22 项 MySQL 或 MQ/Redis 综合验收。

写入前，两条独立 JDBC 连接只读核对 MySQL 8.0.45、业务库和 ai_task_app 身份、InnoDB、两个字段的类型/长度/精度/可空性、索引列顺序、所有权 CHECK 和原 lifecycle CHECK 的 ENFORCED 状态，以及仅 SELECT/INSERT/UPDATE 的业务库权限。此后只创建、更新本轮随机任务。

| 实际验证项 | 结果 |
| --- | --- |
| 同一真实 MySQL 的独立 JDBC 会话 | connectionA=25、connectionB=26，物理连接数 2；不是两个 JVM |
| 正常受保护认领/完成 | UUID token/deadline 非空，有效所有者成功 1 次，最终 SUCCESS/version=2 |
| 错误执行者 | 错误 token 的成功、失败写回均状态冲突 |
| 自然超时，扫描前写回 | DB 截止时间自然到期；旧 token 成功/失败写回均拒绝，任务仍 RUNNING 等待检查 |
| 两处理者同时处理同一过期任务 | 成功更新总数 1，另一个影响行数 0；后续重复检查为 0 |
| 超时终态一致性 | 两连接均读取 FAILED/TASK_TIMEOUT/version=2，output=NULL、completed_at 非 NULL、token/deadline=NULL |
| 旧 Worker 终态写回 | SUCCESS/FAILED 两条路径均拒绝，TASK_TIMEOUT 不被覆盖 |
| 旧 NULL/NULL RUNNING 兼容 | 本轮新建的模拟旧记录被扫描忽略；没有读取/处理真实旧任务 |
| 保留数据 | 3 个随机测试任务、2 个对应 Outbox 事件；没有删除/清理，没有 MQ 派发或模型调用 |

证据目录 `target/phase4b-runs/3d6f363266a74a1bbee26f7b8a5cc204/`：verify.log、result.json、build/surefire-reports、build/failsafe-reports；最新汇总 `target/phase4b-latest.json` passed=true。真实业务 run UUID=935d26b4-66ce-469b-9eeb-48e68b97fe15。所有原始产物在 Git 忽略的 target 中。

```text
WORKER_LEASE_IT PREFLIGHT run=935d26b4-66ce-469b-9eeb-48e68b97fe15 connection=25 mysql=8.0.45 schema003=true engine=InnoDB permissions=SELECT,INSERT,UPDATE
WORKER_LEASE_IT PREFLIGHT run=935d26b4-66ce-469b-9eeb-48e68b97fe15 connection=26 mysql=8.0.45 schema003=true engine=InnoDB permissions=SELECT,INSERT,UPDATE
WORKER_LEASE_IT RESULT run=935d26b4-66ce-469b-9eeb-48e68b97fe15 connectionA=25 connectionB=26 normalSuccess=1 timeoutChanged=1 timeoutLosers=1 oldOwnerRejected=true wrongOwnerRejected=true legacyIgnored=true terminalVersions=2 retainedTasks=3 retainedOutbox=2 modelRetries=0
```

modelRetries=0 表示该真实 Repository/Checker 测试没有调用模型；TaskWorker 晚到 Mock 结果/失败和不重跑语义由离线测试覆盖，不声称真实 LLM 超时或费用验证。重跑需显式批准环境变量、外部 MYSQL_IT_* 或脚本安全输入；不用再次执行 003，不需逐条 SQL/HTTP。

## 未解决与阶段门槛

- 已有 NULL/NULL RUNNING 留给单独审核，不擅自处理；混用旧 Worker/开关不一致不在受保护范围内。
- 超时只保守 FAILED，不取消已开始的模型调用，不续期/重跑，不提供外部服务单调 fencing 或 exactly-once。
- 数据库故障/停机/共享 scheduler 的 Outbox 阻塞可延迟扫描；有效期守卫独立拒绝晚到写入，但 FAILED 状态可能延后可见。
- 过期完成在扫描前遇到 RUNNING 时，Worker 返回 IN_PROGRESS，沿用 Rabbit 的 RUNNING_UNRESOLVED 隔离；后续超时变 FAILED，不自动重放 parking。
- 真正模型超时、取消、故障耐久性/跨 JVM、性能与 JDK 17 运行尚未验证（本机 JDK 25，release 17）。

默认 memory/local 及既有单元契约通过，**人工迁移和最小真实 MySQL 验收已完成，满足本阶段 MVP 门槛，可以进入 Phase 4C**。只读预检和本轮新任务验证未涉及 DDL/旧任务更新、资源清理、其他项目修改、XXL-JOB/Redis 新功能或收费模型调用。完成后停止，等待用户安排真实 LLM 适配与收尾。

提交前检查：候选文件未包含 target、.env、本地密码配置、测试日志或密钥；凭据特征扫描未命中（静态启发式检查，不声称能精确证明任意秘密不存在）。git diff --check 通过；暂存范围限定 Phase 4B 的 18 个源码/脚本/迁移/文档文件，依据本轮明确授权建立本地提交，禁止 push。安全输入辅助脚本及测试报告均在忽略的 target 下。
