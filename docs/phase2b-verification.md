# Phase 2B：真实 MySQL 核心并发验收报告

日期：**2026-10-13，Asia/Shanghai**。基线提交 `a99313a`；开始时已有 Phase 2A 的未提交改动，本报告只列 Phase 2B 新增/修改文件。没有 commit/push，没有执行 DDL 或数据库清理。真实验收只访问既有业务库，新增并修改本轮唯一键任务。

**结论：2026-10-13 11:18:21 +08:00 自动完成真实 MySQL 核心验收；完整 verify 退出码 0，99 项离线与 22 项真实测试全部通过。两真实连接的认领/完成竞争、幂等插入竞争及终态保护已验证，满足 Phase 2B 核心验收和进入 Phase 3 的条件。更广故障注入、恢复和生产能力仍未验证/实现。**

## 本轮文件清单

| 文件 | 类型 | 用途 |
| --- | --- | --- |
| `pom.xml` | 修改 | 显式 mysql-it profile，Failsafe integration-test/verify，专用日志配置；可覆盖构建目录以避免 Windows 运行中 JAR 锁定 |
| `README.md` | 修改 | 当前进度、默认离线/显式真实命令及证据边界 |
| `docs/mysql-integration-plan.md` | 修改 | 历史验收与本轮区别、安全凭据输入、人工执行和完整通过标准 |
| `docs/phase2b-verification.md` | 新增 | 本报告 |
| `src/test/java/com/example/aitask/repository/JdbcTaskRepositoryMySqlIT.java` | 新增 | 两个真实 MySQL 连接/生产 Repository 的竞争及终态保护，22 项显式测试 |
| `src/test/java/com/example/aitask/repository/MySqlAcceptanceSupport.java` | 新增 | 外部环境门禁、固定业务库/账号和脱敏错误分类 |
| `src/test/java/com/example/aitask/repository/MySqlAcceptanceSupportTest.java` | 新增 | 12 项离线安全门禁/诊断测试 |
| `src/test/resources/mysql-it-logback.xml` | 新增 | IT 关闭框架/驱动日志，仅显式输出脱敏证据 |
| `scripts/verify-mysql.ps1` | 新增 | 一键安全输入/环境凭据、完整 verify、独立目录与实际 XML/证据汇总 |
| `AGENTS.md` | 新增 | 固化自主开发、最小关键测试、阶段推进、数据/凭据边界 |
| `docs/project-review.md` | 新增 | 架构、技术选型、核心原理、源码导航、面试问题和可验证简历亮点 |

本轮没有改生产 Repository、HTTP、模型或数据库初始化脚本。没有 Mock/H2/OfflineJdbcFixture 用于新增真实 IT；新增离线测试只验证配置/故障分类，不冒充数据库。

## 审查及实现

现有 JdbcTaskRepository 使用 REQUIRES_NEW/READ_COMMITTED 短事务。认领/完成 UPDATE 带状态条件；0 行抛 TaskStateConflictException，1 行才成功。状态和结果/错误/时间一次写入，version 每次 +1。DuplicateKeyException 才进入新事务按键回查，不同输入明确 IDEMPOTENCY_CONFLICT。没有发现本轮需要修改的生产竞争逻辑，真实 InnoDB 效果由待执行 IT 验证。

IT 不启动 Spring 或 HTTP；两个 DriverManager 连接持续打开，每个使用自己的 SingleConnectionDataSource（抑制事务内 close）、JdbcTemplate、事务管理器和生产 Repository。预检 server_uuid 相同、CONNECTION_ID 不同、数据库/账号/MySQL 8.0/InnoDB/strict mode/READ-COMMITTED。两 Worker 到达屏障后释放；Future 有限时等待，线程退出后才复用连接。所有测试顺序执行；默认 Surefire 命名规则不包含 `*MySqlIT`。

每次运行 run UUID，每个键另有独立 UUID，所有写入目标都是本轮 `created=true` 的新任务；不扫描/修改已有任务。没有自动建表或删除操作，完整运行保留 22 行。终态保护比较完整 TextTask 快照和直接 SELECT 的 version，两连接回读一致，不推断 version。

失败不重试/不降级：1213 DEADLOCK、1205 LOCK_WAIT_TIMEOUT、SQLState 08 CONNECTION_FAILURE、其他 SQL_FAILURE，线程等待 WORKER_TIMEOUT。原始 SQL message/cause 不进入报告。错误分类的离线证据不证明真实死锁、锁等待或断连已复现。

## 前轮离线基线结果

实际完整命令（`-o` 为 Maven 离线依赖解析，未开启 mysql-it）：

```powershell
mvn -B -ntp -o "-Dplatform.build.directory=target/phase2b-build" verify *> target/phase2b-offline-build.log
```

最终于 **2026-10-13 11:10:24 +08:00** 完成，退出码 **0**：

```text
Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

既有 87 项全部保留通过，新增 MySqlAcceptanceSupportTest 12 项通过；Surefire XML 汇总同样为 99/0/0。报告：`target/phase2b-build/surefire-reports/`。编译目标仍 Java 17，使用既有 JDK 25/Maven 3.9.1，Failsafe 3.5.6 由现有 Boot parent 管理。git diff --check 无空白错误，日志及测试报告被 target 的 Git 忽略规则覆盖。

第一次默认输出目录的 verify 已通过 99 项测试，但 repackage 无法将默认 JAR 重命名为 `.original`，打包失败；可能存在运行中 JAR 占用。没有终止现有实例，改用独立构建目录后完整 verify 成功。未将第一次打包失败描述成完整构建通过。

## 历史缺失环境拒绝结果

确认 `MYSQL_IT_HOST/PORT/USERNAME/PASSWORD` 均不存在后执行的负向验证（不是数据库测试成功记录）：

```powershell
mvn -B -ntp -o -Pmysql-it "-Dplatform.build.directory=target/phase2b-build" verify *> target/phase2b-optin-refusal.log
```

于 **2026-10-13 11:05:55 +08:00** 完成，退出码 **1**，99 项离线仍通过，Failsafe 初始化 **1 error / 0 skipped**：

```text
Real MySQL test refused: missing environment variable MYSQL_IT_HOST
MYSQL_IT SUMMARY ... realConnections=0 claimSuccesses=0 claimConflicts=0 completionSuccesses=0 completionConflicts=0 ...
BUILD FAILURE
```

没有尝试连接，无回退/静默跳过。四个必填变量各自缺失的拒绝、非法端口、主机注入、管理员账号、错误诊断脱敏由新增离线测试覆盖。

## 本轮实际真实结果与一键命令

用户提供凭据后，通过安全终端输入送入脚本进程环境，未写入源码/日志/报告/Git；直接自动执行以下命令，未要求用户逐条操作或启动实例：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-mysql.ps1 -Offline
```

脚本运行 ID：`f16e5a069dd549a8b633b3189e8bab14`；IT run UUID：`949c3670-c0c8-4fcd-a2a3-c26723d06f35`。脚本在独立 `target/phase2b-runs/<runId>/build` 上执行一次完整 `mvn -o -B -ntp -Pmysql-it -Dplatform.build.directory=<独立目录> verify`，原始日志结束于 **11:18:21 +08:00**。脚本和 Maven 均退出 **0**。

| 检查项 | 实际结果 |
| --- | --- |
| Surefire 离线 | 99 项通过，0 failures/errors/skipped，原有 87 项保留 |
| Failsafe 真实 | 22 项通过，0 failures/errors/skipped |
| MySQL | 8.0.45，业务库/account/InnoDB/strict mode/READ-COMMITTED 预检通过 |
| 真实连接 | 2 个：CONNECTION_ID=25、26，同一 server_uuid |
| 认领竞争 5 轮 | 成功 5，明确状态冲突 5，最终各 RUNNING/version=1 |
| 终态竞争 5 轮 | 成功 5，明确状态冲突 5；3 SUCCESS、2 FAILED，version=2 |
| 同键不同输入 5 轮 | 新增成功 5，IDEMPOTENCY_CONFLICT 5，每键一行 PENDING/version=0 |
| 同键等价输入 5 轮 | 新增 5，复用 5，UUID 相同，每键一行 PENDING/version=0 |
| 终态保护 | 两连接尝试再次认领/成功/失败/非法终态迁移，原完整快照及 version 不变 |

最终保留本轮 **22 行**：10 PENDING/version=0，5 RUNNING/version=1，4 SUCCESS/version=2，3 FAILED/version=2（终态竞争加确定终态保护）。测试通过两真实连接回读完整状态、输入/输出、错误码、创建/开始/完成时间，以及直接 SELECT version，一致性断言全部通过。未清理测试数据，未触碰已有任务。

实际关键日志节选：

```text
MYSQL_IT PREFLIGHT ... worker=0 connectionId=25 mysql=8.0.45 isolation=2
MYSQL_IT PREFLIGHT ... worker=1 connectionId=26 mysql=8.0.45 isolation=2
MYSQL_IT STATE_CONFLICT ... connectionId=26 detail=Task 0c3eca1c-95c6-40ae-94aa-465f83547c00 is RUNNING; expected PENDING
MYSQL_IT SUMMARY ... realConnections=2 claimSuccesses=5 claimConflicts=5 completionSuccesses=5 completionConflicts=5 inputSuccesses=5 inputConflicts=5 sameInputCreated=5 sameInputReused=5
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

终态关键字段的实际例子（UTC）：

| task UUID | status / version | output / error | completed_at |
| --- | --- | --- | --- |
| `e4a8f9d4-e536-4f57-b75f-dca82f16ae6e` | SUCCESS / 2 | `phase2b-result-e4a8f9d4-e536-4f57-b75f-dca82f16ae6e` / null | `2026-10-13T03:18:21.177501Z` |
| `91f08088-1999-4608-a233-c0250e5fe5d3` | FAILED / 2 | null / PHASE2B_FAILURE | `2026-10-13T03:18:21.216921Z` |

原始证据：`target/phase2b-runs/f16e5a069dd549a8b633b3189e8bab14/verify.log`、`build/surefire-reports/`、`build/failsafe-reports/`、`result.json`；最新摘要 `target/phase2b-latest.json`。报告归档过程中发现 Get-Content 附带的 PowerShell 路径元数据使 JSON 过大，已改为纯 .NET 字符串序列化，并从同次原始日志重建 JSON；45 条证据均为字符串、文件 13427 字节，不重复执行已通过的数据库测试。

脚本 AST 解析零错误；缺凭据的 `-NonInteractive -Offline` 自动验证返回 2、明确未连库、不覆盖已有成功结果。它是脚本门禁检查，不能当作真实数据库成绩。

最终检查：git diff --check 无空白错误；在内存中扫描 130 个源码/配置/文档/日志/JSON/XML 文本文件，凭据原文及 XML/URL 编码形式匹配数为 0，仅输出扫描数量；本项目父进程环境不留数据库密码。没有把凭据写入命令行参数或报告。扫描结束清除临时工具上下文。

用户提供的历史结果：MySQL 8.0.45，独立 `ai_task_platform` 与 SELECT/INSERT/UPDATE 账号 `ai_task_app`；真实 HTTP 与重启 SUCCESS/FAILED 持久化、双实例 18081/18082 共享数据库、4 请求同键同输入（1×202/3×200/同 UUID/仅一行）、不同输入 HTTP 409 已通过。本轮不重新宣称这些是自己执行的测试，也不据此推断 Worker 竞争通过。

以后重复验收只需一个入口（有环境凭据直接运行，无凭据安全提示一次）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-mysql.ps1
```

不要设置真实密码为 Maven 系统属性，不要开启 debug，不要将密码传给聊天。脚本自动保存退出码、`MYSQL_IT PREFLIGHT/STATE_CONFLICT/ROW/SUMMARY`、Failsafe XML/text；失败不会被当作通过。阶段最小测试原则已记录在 AGENTS.md，本轮未增加 Java 测试数量。

## 待验证、未实现与提交条件

- 待验证：真实死锁/锁等待/断连及提交不确定性的主动故障注入。本轮竞争路径真实通过；分类器仅有离线故障覆盖，不能声称这些真实故障已复现或恢复已验证。
- 更广验收未做：CHECK 生效的负向 SQL、TLS/认证策略、不同 JVM 时区、服务器崩溃耐久性及进程故障窗口。复用既有的 HTTP/重启证据不覆盖这些项。
- 未实现：持久化队列/outbox、启动扫描、租约/心跳/attempt/fencing、自动重试/退避、运行超时和悬挂恢复、exactly-once、认证/租户/生产监控。无 RabbitMQ、Redis、收费模型或认领 HTTP 接口。
- **满足 Phase 2B 核心验收提交条件，可以进入 Phase 3。** 真实 opt-in 退出码 0、22 项真实通过、两个不同服务端连接 ID、单胜出/单冲突及最终字段/version 证据均已取得；这不表示生产级恢复/故障验收完成。本轮不自动 commit/push。

下一阶段：单独实施 RabbitMQ 异步派发、幂等消费与失败分类，先复用已有条件认领；关注 MySQL 与 MQ 双写窗口、发布确认及 ACK 时机，不同时引入 Redis/复杂恢复。集中学习资料持续维护于 docs/project-review.md。
