# 真实 MySQL 初始化与 Phase 2B 并发验收计划

**2026-10-13：Phase 2B 真实并发核心验收已通过。** 自动完成 99 项离线及 22 项真实 MySQL 8.0.45 测试；两真实连接、认领/终态/幂等竞争及终态保护通过。用户此前提供的 HTTP、重启、双进程共享库结果，本轮没有重复执行。

本轮一键脚本完成完整 verify 并归档 XML/日志/result.json，不需要人工启动多实例或逐条 HTTP 检查。既有 SQL 替身不证明 MySQL 行锁/隔离效果，真实 `JdbcTaskRepositoryMySqlIT` 不使用它。**现有业务库已经初始化，运行本轮测试无需执行下方历史初始化 SQL。** 本轮报告见 [phase2b-verification.md](phase2b-verification.md)。

## 1. 人工初始化与权限

使用 MySQL **8.0.16+**，独立数据库 `ai_task_platform`，检查版本、严格 SQL 模式和本地服务端口。不要使用 `xxl_job` 数据库或它的业务账号。管理员手动进入客户端（密码由终端提示输入）：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=root --password
```

客户端内由用户人工执行：

```sql
SELECT VERSION(), @@sql_mode;
SOURCE E:/codeplace/llm-agent/ai-task-platform/database/001_init.sql;
SELECT DATABASE();
SHOW CREATE TABLE ai_task_platform.ai_text_task;
SHOW INDEX FROM ai_task_platform.ai_text_task;
```

脚本在原 001_init.sql 上增加 normalization_version 与 CHECK，没有自动迁移。**若独立库中已存在 Phase 1 的表，IF NOT EXISTS 不会补列或升级约束**：先检查 SHOW CREATE TABLE，由用户另行审核差异和备份/迁移方案；不要直接重复执行脚本就宣称升级完成。本轮没有生成或执行 ALTER/DROP。

另由管理员通过本地安全方式创建专用业务账号（自行设置密码，不把真实秘密写入仓库）。它只需 SELECT、INSERT、UPDATE；按实际连接来源替换账号 host。授予的权限范围应为：

```sql
GRANT SELECT, INSERT, UPDATE ON ai_task_platform.* TO 'ai_task_app'@'localhost';
SHOW GRANTS FOR 'ai_task_app'@'localhost';
```

以上 GRANT 以账号已人工创建为前提，不含固定密码。不要授予 `xxl_job.*`、CREATE、ALTER、DROP 权限，应用不使用管理员账号。

## 2. 配置与基础验收

用 README 的外部环境变量方式设置主机/端口、账号、密码和 `TASK_STORAGE_MODE=mysql`，或使用被忽略的 mysql 本地 profile。启动：

```powershell
java -jar target/ai-task-platform-0.1.0-SNAPSHOT.jar --debug=false --platform.storage.mode=mysql --server.port=18081
```

本轮未启动此命令。启动成功不代表已连库，第一次数据访问才初始化连接池。人工确认缺失凭据会启动失败，错误账号/数据库不可用会在请求时返回 503，且不会降级内存。

审核后执行已存在的 HTTP smoke 脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-test.ps1 -BaseUrl http://127.0.0.1:18081
```

该脚本会提交少量持久化 Mock 任务，不运行 DDL。再从只访问业务库的连接查看任务行，确认 normalization_version、status、error_code、UTF-8 中文/emoji、微秒时间与 version。使用不同 JVM 默认时区的实例核对 UTC 时间映射。

停止并重启应用，使用停止前记录的 UUID 查询 SUCCESS/FAILED 结果；同键同规范化输入再次提交必须复用原 UUID，不调用模型、不新建行。此步骤才是实际持久化验收，本轮未做。

## 3. 两个实例共享同一业务库

审核后分别在两个终端使用相同 MySQL 配置启动端口 18081 和 18082，确认都为 mysql 模式、连接相同服务器的同一业务库。使用新的唯一测试幂等键，保存响应、SQL 查询结果和测试日志，不触碰其他工程数据。

| 场景 | 验收证据 |
| --- | --- |
| 同键同规范化输入的并发 POST | 两个实例返回同一 UUID，业务表只有一行；只有插入成功者 created=true。并发死锁/锁等待可能返回 503，记录后用同键重试 |
| 同键不同输入并发 POST | 仅一个输入落库，其余为明确 409；若瞬时 503，则同键重试后应成为同输入复用或不同输入冲突 |
| NFC 组合字符、CRLF/LF | 同键规范化等价输入复用；尾部空格/大小写变化仍冲突；不同大小写的幂等键是两个键 |
| 两 Worker 同时认领同一 PENDING | 两个独立连接/进程调用真实 JdbcTaskRepository.start，用屏障同步；更新行数只有一次为 1，另一方状态冲突；模型计数不应重复 |
| RUNNING 完成竞争 | 两个独立连接同步执行 succeed 与 fail(RUNNING)，仅一方成功；终态输出、错误码、时间一致，version 为 2 |
| SUCCESS/FAILED 保护 | 再认领、再写成功或失败都不能覆盖原终态；PENDING 拒绝不能覆盖 RUNNING |
| 非幂等 SQL 故障 | 1062 正确复用/冲突；1213 死锁、1205 锁等待、连接错误不能误报幂等成功 |

**当前没有对外暴露认领/完成 API，本轮也没有新增。** HTTP 并发提交只能测试提交路径。已新增 `mysql-it` Maven profile 和 `JdbcTaskRepositoryMySqlIT`，用两个持续打开的真实 JDBC 连接、两个 SingleConnectionDataSource/事务管理器/生产 Repository，通过双 Worker 就绪屏障同步操作。服务端 CONNECTION_ID 必须不同且 server_uuid 相同。默认 verify 不运行这个 IT，环境不满足则失败，无跳过或内存回退。

## 4. 隔离级别、锁与驱动行为

在真实连接上验证 READ_COMMITTED、新事务回查、插入失败后的回滚和查询；运行多次并发测试，记录死锁/锁等待，不伪造性能数字。必要时在测试专用业务库中用两会话复现：一方暂时不提交，另一方同键 INSERT 等待；第一方 commit/rollback 后确认最终响应符合唯一索引语义。

人工检查所有 CHECK 在当前 MySQL 版本确实被执行、strict SQL 模式下不会静默截断文本、ASCII 键不会按忽略大小写去重；Connector/J LocalDateTime 映射、UTC 会话和 TLS/认证均需真实验证。不同应用实例必须使用同一规范化规则和同一业务库，不允许读写分离后从滞后副本回查胜出行。

## 5. 故障与恢复验收（预期仍有缺口）

- 提交落库后、排队前终止进程：允许观察到滞留 PENDING；重复提交只复用，不自动恢复。
- 认领后终止进程：允许观察到 RUNNING 悬挂；不能声称已有超时恢复。
- 模型执行后阻断结果落库：数据库可能保留 RUNNING，或已提交 SUCCESS 但客户端未知；先查询，不盲目重跑。
- 模型失败后阻断 FAILED 写入：可能仍 RUNNING，错误日志不等于数据库 FAILED。
- 当前没有租约/fencing，禁止用人工 RUNNING -> PENDING 重置来宣称安全重试；恢复需要独立方案和审核。

记录每种窗口的状态、时间和副作用风险。MySQL 进程自身崩溃后的提交耐久性还取决于实际存储、事务日志和服务器配置，本轮没有评估。数据库幂等不是 exactly-once 模型执行保障。

验收产物应包括 MySQL 版本、已有 SHOW CREATE TABLE/INDEX、应用依赖版本、测试命令、响应与 UUID、实际行数、并发/故障结果及未通过项。到达 Worker 竞争的真实证据前，保持“Phase 2B 真实并发尚未验收”的结论。

## 6. Phase 2B 人工 opt-in 运行步骤

本步骤只访问已经存在的 `ai_task_platform.ai_text_task`，不初始化表，不迁移，不清理。测试需要 MySQL **8.0.16+ 的 8.0 系列**、InnoDB、strict SQL mode，固定业务用户 `ai_task_app`（SELECT/INSERT/UPDATE）。凭据仅由当前进程环境传入，不从 application-local/mysql 文件读取，不能通过 `-D...password`、URL、Maven settings 或命令行明文传入。

一键脚本会采用现有 MYSQL_IT 环境，缺失时使用本机 127.0.0.1:3306 / ai_task_app，并仅提示一次安全密码输入。输入后在脚本进程环境中调用 Maven，结束时恢复环境；不用粘贴密码到聊天、命令参数或文件：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-mysql.ps1
```

没有正在运行的实例占用默认 JAR 时也可用 `mvn -B -ntp -Pmysql-it verify`。独立 build 目录参数只改变产物位置，不启用数据库；默认离线验证可使用 `mvn -B -ntp "-Dplatform.build.directory=target/phase2b-build" verify`。

自动化调用使用 `-NonInteractive`（必需 MYSQL_IT_PASSWORD，缺失返回 2，未连库）；可选 `-Offline` 只控制 Maven 依赖解析，真实测试仍连接真实 MySQL，不降级。已有环境优先于 `-MySqlHost/-MySqlPort` 默认值。脚本自动汇总实际 XML，只有 Maven 退出码 0、离线完整通过、22 项真实测试通过且两连接预检才返回成功；不启动/停止现有实例，不安装软件。每次用新的 run 目录，避免旧成功报告掩盖本次失败。

不要在运行时开启 Maven `-X`、JDBC/pool/driver debug 或输出环境变量。IT 专用 Logback 配置关闭框架日志，只输出显式脱敏验收证据；错误只报告类别/数值 vendor code，不附原始异常 message/cause。密码不会转成系统属性或测试参数。日志和 XML 在被 Git 忽略的 target 目录，仍不要将凭据粘贴到聊天或提交 Git。

### 完整通过的判定（以下是期望，不是实际结果）

- Surefire：99 项离线测试通过；Failsafe：22 项真实测试通过，0 failures/errors/skipped；Maven 退出码 0。
- `MYSQL_IT PREFLIGHT`：两个不同 connectionId，MySQL 8.0，业务库/account/engine/strict mode/READ-COMMITTED 预检通过。
- 认领竞争 5 轮：claimSuccesses=5、claimConflicts=5，每行 RUNNING/version=1，started 非空，output/error/completed 为空；旧 PENDING 拒绝不覆盖 RUNNING。
- 完成竞争 5 轮：completionSuccesses=5、completionConflicts=5，每行 SUCCESS 或 FAILED/version=2。SUCCESS 的 output 为胜出写入且 error 为空；FAILED 的 output 为空且 error=PHASE2B_FAILURE；完成时间与胜出 Repository 返回值完全一致，两连接读到相同行快照。
- 确定创建 SUCCESS/FAILED 各一行，覆盖竞争中的两种终态保护。两连接再次 start、succeed、fail(PENDING/RUNNING/终态) 均被拒绝；整个快照及 version 保持不变。
- 同键不同输入 5 轮：inputSuccesses=5、inputConflicts=5，每键仅一行 PENDING/version=0；胜出输入精确匹配，其后同输入复用、不同输入冲突。
- 同键规范化等价输入 5 轮：sameInputCreated=5、sameInputReused=5，每键同一 UUID、仅一行 PENDING/version=0。

完整运行新增并保留 **22 行**：5 行认领后 RUNNING，5 行完成竞争终态，2 行确定终态，10 行插入竞争 PENDING。它们不加入应用队列，不调用模型；测试失败时可能只留下部分测试行，不清理、不重试。同一运行的 key 包含随机 run UUID 和独立 UUID，所有状态写入只使用本次 `created=true` 的任务 ID。

一键脚本的证据在 `target/phase2b-runs/<runId>/verify.log`、`build/failsafe-reports/`、`result.json`；最新汇总在 `target/phase2b-latest.json`。直接 Maven 的证据仍在所选 build 目录。每行 `MYSQL_IT ROW` 包含 run、task UUID、key、status、version、output、error 和 UTC 时间。`MYSQL_IT STATE_CONFLICT` 显示冲突的真实 connectionId、当前/预期状态。`MYSQL_IT SUMMARY` 是观察计数，是否通过须结合退出码和 Failsafe 报告，不能仅看 SUMMARY。

1213 分类为 DEADLOCK，1205 为 LOCK_WAIT_TIMEOUT，SQLState 08 为 CONNECTION_FAILURE，其他 SQL 为 SQL_FAILURE（vendorCode）；Worker 超时为 WORKER_TIMEOUT。任何这些错误都令测试失败，不计作合法状态/幂等冲突，不自动重试。本轮没有主动注入真实死锁/断连；离线只验证分类器，不能据此声称真实故障恢复已经验收。
