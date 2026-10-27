# 003：执行所有权/超时字段人工迁移

2026-10-14：**用户已确认人工执行 003，并授权真实 MySQL 验收；不要重复执行迁移。** Codex 不执行 DDL 或数据回填；验收先只读核对字段类型、索引、执行所有权/原生命周期 CHECK 和业务账号最小权限，再创建本轮随机任务。脚本 database/003_worker_lease.sql 只修改 P1 的 ai_task_platform.ai_text_task。

## 最小增量

- execution_token：可空 ASCII UUID，用于识别本次有效 Worker。
- execution_deadline：可空 UTC DATETIME(6)，由数据库认领语句计算固定截止时间。
- idx_task_execution_deadline：状态/截止时间/任务 ID 索引，支持有界过期扫描。
- ck_task_execution_ownership：两字段同时 NULL，或仅 RUNNING 时同时非 NULL 且 deadline > started_at。

原 ck_task_lifecycle、任务状态及 REST 响应均不修改。成功/失败/超时终态写入时清空两字段。003 没有 UPDATE/回填/清理，没有修改 Outbox 或 xxl_job 表。业务账号不获得 DDL/DELETE 权限，原 SELECT/INSERT/UPDATE 足够运行新代码。

## 旧 RUNNING 兼容策略

所有既有记录的新字段为 NULL/NULL，约束允许，原字段和值不变。没有可靠执行者和原截止时间时不凭空创建令牌/期限。后台只处理带令牌的 RUNNING：**旧 NULL/NULL RUNNING 不自动超时、不自动重跑或修改**，以后需要处理时单独审核。

上线前停止/排空旧 Worker，再统一启用新版本和 TASK_EXECUTION_TIMEOUT_ENABLED=true。不能混用有/无超时模式：旧版本无令牌终态 SQL 不清空新字段，会被新增 CHECK 拒绝；不同模式同时认领新任务也会产生无期限记录。关闭开关只能停用本阶段能力，不能称仍具备超时/执行者保护。

## 用户最少操作

**本机人工迁移已完成，无需再次执行。** 新环境仍须使用本机数据库管理员审核执行 database/003_worker_lease.sql；不用业务账号执行 DDL，不把管理员密码粘贴聊天或命令参数。不重新执行 001/002，不重建表或清理数据；同名字段/约束已存在时先停止、对比结构，不能覆盖。

管理员执行后只读核对 SHOW CREATE TABLE：确认两个字段、索引和 CHECK 已创建且生效，原生命周期 CHECK 保留。Codex 本轮不会代为执行 ALTER。

## 迁移后的自动验收

本机已有 MySQL/Outbox，不新增基础设施。批准后在测试进程显式设置 WORKER_LEASE_IT_SCHEMA_APPROVED=true，一键执行：

```powershell
# 只有人工迁移和授权完成后才执行，无密码参数。
$env:WORKER_LEASE_IT_SCHEMA_APPROVED='true'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-worker-lease.ps1
```

脚本对缺失 MySQL 密码提示一次安全终端输入，或使用进程 MYSQL_IT_HOST/PORT/USERNAME/PASSWORD；不保存凭据。-NonInteractive 缺密码拒绝；-Offline 只控制 Maven 依赖解析。默认构建不运行真实数据库，直接入口 `mvn -B -ntp -Pworker-lease-it verify` 也要求迁移审批。

仅 1 项 MySQL 综合测试，**2026-10-14 15:33:09 +08:00 已实际通过**：两个独立 JDBC 会话 25/26，正常受保护认领/完成、错误 token 拒绝、自然过期后完成拒绝、两处理者并发超时单胜/版本=2、旧 Worker 不能覆盖 TASK_TIMEOUT，以及测试新建 NULL/NULL 记录被忽略。按随机 taskId 限定扫描，不处理原业务数据，不调用或重试模型、不连接 MQ/Redis。保留 3 个本轮任务/2 个 Outbox 事件（事件不由该测试派发），没有 DELETE/DDL/清理。完整结果及证据路径见 docs/phase4b-verification.md。

## 能力边界

这是固定执行期限和数据库所有权校验，没有 heartbeat/续期/任务重跑、单调 fencing 序列或外部服务隔离。超时标 FAILED 不强行取消已启动的模型调用；晚到结果被丢弃，不代表外部调用被退款/撤销。定期检查复用 Spring scheduler，可能因停机、DB 故障或 Outbox 慢任务延迟；终态写入的 token/期限检查独立生效。

在认领/完成短事务内先获取行锁，再执行新的数据库时间语句，避免把锁等待前的语句时间误用为有效期；模型调用不在事务内。这是 InnoDB 本地行锁，不新增 Redis 分布式锁或调度系统。迁移后的真实 SQL/数据库时间/两个会话条件更新已通过；没有单独验证长锁等待或跨 JVM 崩溃。
