# Phase 2A 持久化、幂等与恢复语义

> 本文保留 Phase 2A 的基线设计。Phase 2B 已真实验证 MySQL 并发，Phase 3A 未改变下文 JDBC SQL/事务语义，新增可切换 TaskDispatcher 与共享 TaskWorker。Rabbit 模式同键 PENDING 可重新发布消息以修复派发失败；local 模式仍不重派。MQ 确认/隔离和双写窗口见 [RabbitMQ 设计](rabbitmq-design.md)，当前验证边界见 [Phase 3A 报告](phase3a-verification.md)。

实现：`JdbcTaskRepository` + Spring JDBC + `DataSourceTransactionManager` + `TransactionTemplate`。原 InMemoryTaskRepository 保留，默认内存模式。版本继续使用 Spring Boot 3.5.16、Java 17 编译目标和 JUnit 5.13.4；数据库驱动及 JDBC 依赖由原 Boot BOM 管理，没有引入 ORM、迁移框架或新的服务。

**本轮仅完成代码和离线验证，没有连接真实 MySQL，没有初始化或迁移数据库。** 真实数据库行为按 [集成计划](mysql-integration-plan.md) 在审核后验证。

## 独立库与配置

目标为 MySQL 8.0.16+，独立库 `ai_task_platform`，InnoDB。配置只允许主机、端口和外部凭据，库名由代码固定，无法把任意 JDBC URL 指向 `xxl_job`。连接池不会在构造时启动；第一次 Repository 访问才连接。应用账号需人工设置，只授予 `ai_task_platform.*` 的 SELECT/INSERT/UPDATE，不持有 `xxl_job.*` 权限。

默认 `TASK_STORAGE_MODE=memory`，无需数据库配置且不创建 DataSource。设置 `TASK_STORAGE_MODE=mysql` 或使用本地 mysql profile 可切换。密码无默认值，必须外部提供；本地配置已在 `.gitignore` 忽略。未知模式或缺失凭据明确失败，不静默回退。

应用全局排除 DataSource 自动配置和 SQL 自动初始化。只有显式 mysql 模式创建自定义 DataSource，无论 `spring.sql.init.mode` 如何设置，都不执行 SQL 初始化。没有扫描初始化脚本、Flyway/Liquibase 或 ORM 自动建表行为。

## 当前表与约束

在原 `database/001_init.sql` 上扩展，没有新增第二份初始化 SQL：

| 字段/约束 | 用途 |
| --- | --- |
| task_id CHAR(36) 主键 | 服务端 UUID，保持 REST ID 不变 |
| idempotency_key VARCHAR(128) / UNIQUE | ASCII、ascii_bin，大小写敏感；全局键空间，尚未按租户隔离 |
| input_text VARCHAR(5000) | 规范化文本，不是原始文本副本 |
| normalization_version | 固定 `NFC_LF_V1`，读取未知版本时拒绝复用 |
| status / CHECK | 仅 PENDING、RUNNING、SUCCESS、FAILED |
| output_text TEXT / error_code VARCHAR(64) | 成功输出或稳定错误码，不存原始异常消息 |
| DATETIME(6) 时间 | UTC 创建/开始/完成时间，数据库 `UTC_TIMESTAMP(6)` 写入 |
| version BIGINT / CHECK >= 0 | 每次状态更新 +1，目前是更新计数，不是租约或 fencing token |
| UNIQUE uk_task_idempotency_key | 数据库仲裁并发提交 |
| INDEX idx_task_status_created | 为后续扫描做准备，本轮没有恢复扫描器 |
| ck_task_lifecycle | 约束状态与输出、错误码、时间字段的一致性 |

应用限制规范化输入不超过 5000 个 UTF-16 单位，拒绝孤立代理字符；输出限制为有效非空白 Unicode、UTF-8 不超过 65535 字节，避免超过 TEXT 容量；错误码限制为 1–64 个大写 ASCII 字母、数字、下划线。建表约束不能替代上一状态校验、规范化和应用参数验证。

DATETIME 按 UTC 约定存储，读取为 LocalDateTime 再显式转 UTC Instant，不依赖 JVM 默认时区。连接配置也要求 UTC 会话。时间与驱动配置仍需真实环境验收：[Connector/J 时间配置](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)。

## 数据库幂等与事务边界

规范化规则版本为 NFC_LF_V1：Unicode NFC；CRLF/CR 统一为 LF；保留大小写、所有空格和其他文本内容。内存与 JDBC Repository 使用同一规则。幂等键不规范化，区分大小写。比较在 Java 使用 `String.equals`，不使用输入列的默认排序规则比较。

`createOrGet` 流程：

1. 规范化、校验；生成 UUID。
2. 在独立写事务里直接 INSERT，不预查幂等键；读取该 UUID 的完整行；成功提交后返回 created=true，业务层才排队。
3. 只有 DuplicateKeyException 才进入幂等回查。TransactionTemplate 已结束并回滚失败的写事务，然后在一个新的只读事务里按唯一幂等键查询。
4. 规范化输入相同返回原 UUID 和实际状态、created=false；不同返回 409 / IDEMPOTENCY_CONFLICT。找不到同键行时重新抛出唯一键异常，避免把无关主键冲突误判为幂等。

使用显式 MySQL 错误码翻译，1062 归为 DuplicateKeyException。锁等待/死锁、编码、连接错误不会进入“同键重复”分支。数据库错误对 HTTP 返回 503 / STORAGE_UNAVAILABLE；不自动重试事务，客户端可用同键重试。并发插入会受到唯一索引与 InnoDB 锁协调影响；死锁是待验收的情况，不能保证每个首次并发请求都直接得到 200/202。[MySQL 8.0 错误处理](https://dev.mysql.com/doc/refman/8.0/en/innodb-error-handling.html)。

所有 Repository 事务使用 **REQUIRES_NEW + READ_COMMITTED**，超时 10 秒，只读查询单独标记 readOnly。新事务避免调用方已有事务快照导致回查不到胜出行，保证插入提交后才排队。事务在 Repository 方法返回前结束，TaskService 没有包围模型调用的事务。[Spring 独立事务语义](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html)。若未来外层也持有事务，REQUIRES_NEW 可能额外占用连接，需重新审视连接池大小和锁顺序；当前没有这种外层事务。

这实现了数据库级幂等的代码路径，不表示真实跨实例效果已经验收，更不保证模型调用 exactly-once。真实唯一索引、锁争用、事务提交和驱动行为本轮没有被测试替身证明。

## 状态认领与完成

认领不先查询状态，直接执行：

```sql
UPDATE ai_text_task
SET status='RUNNING', started_at=UTC_TIMESTAMP(6), version=version+1
WHERE task_id=? AND status='PENDING';
```

受影响行数必须为 1。两个 Worker 竞争时应只有一个成功，其余返回 TaskStateConflictException，业务层跳过模型调用。返回 0 时查询区分“不存在”和“状态已经变化”。成功与失败结果连同 completed_at 在一个条件 UPDATE 中写入，仅 RUNNING 可以完成；执行拒绝仅能把 PENDING 变成 FAILED。

失败写入显式接收 expectedStatus，防止队列拒绝后误把其他 Worker 已认领的 RUNNING 改为 FAILED。SUCCESS/FAILED 不能再次认领，也不能被终态更新覆盖。保留旧 `fail(id, code)` 的兼容方法，但它最终仍使用观测状态的条件更新，不能无条件覆盖。

目前状态不会重置，因此 `WHERE status=...` 足以实现单次认领与终态保护；`version` 并未加入 WHERE，不宣称已具备乐观版本重试或执行 fencing。如果未来支持 RUNNING 重置或重试，必须增加 attempt/租约/所有权，不可直接复用当前完成接口。

模型调用在认领提交后执行，再用另一短事务写结果。TaskService 分离模型异常和存储异常：模型返回无效结果或抛出异常才尝试写 MODEL_CALL_FAILED；成功结果写库失败仅记录存储故障，不再尝试写模型失败，避免提交状态不确定时误覆盖 SUCCESS。

## 持久化与恢复边界

| 故障窗口 | 当前可能状态 | 当前行为与风险 |
| --- | --- | --- |
| INSERT 提交后、线程池排队前崩溃 | PENDING | 行按设计保留，但无人执行；重复提交只返回原任务，不重新调度 |
| 已排队、认领前进程退出/DB 认领失败 | PENDING | 进程内队列丢失或已消费；没有自动扫描恢复 |
| RUNNING 提交后进程崩溃 | RUNNING | 悬挂；没有租约、心跳或超时 |
| 模型已完成、写 SUCCESS 失败 | RUNNING 或 SUCCESS | 提交可能失败，也可能已成功但响应丢失；先查询，不自动重跑模型 |
| 模型异常、写 FAILED 失败 | RUNNING 或 FAILED | 日志知道模型异常，数据库最终状态可能不确定 |
| 多实例用内存模式 | 各自独立 | 不提供跨实例幂等；可能重复执行 |
| 新键重复提交同一业务输入 | 两个任务 | 允许；幂等以键为范围，不是按内容去重 |

MySQL 模式设计为重启后保留已提交行和幂等键，可继续查询和复用任务；不能恢复执行队列或保证待处理任务最终完成。没有实现恢复扫描、outbox、租约、自动重试、退避、执行尝试记录、fencing token、任务超时或清理。真实重启持久化行为仍需后续验证。

未来应先做事务内待执行记录/扫描认领，再加入租约与重试记录，明确至少一次执行和下游幂等。不能把唯一键去重或状态 CAS 当作 exactly-once；外部模型可能已执行但结果尚未落库。XXL-JOB 未来可触发周期扫描，本轮未接入，且不作为高吞吐消息队列。
