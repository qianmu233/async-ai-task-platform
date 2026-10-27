# 002 Outbox 迁移：人工审核与管理员执行

状态：**2026-10-14 用户已人工执行并核对新增表；Codex 只读确认 MySQL 8.0.45、InnoDB、四个有效 CHECK、FK/唯一键及最小业务权限。11:41:41 真实联合验收通过。** 本机无需再次执行 SQL。本文保留新环境的审核/初始化说明，Codex 未执行 DDL 或清理。

## 变更边界

只在已有 `ai_task_platform` 增加 `ai_task_outbox` InnoDB 表、索引、外键和 CHECK。没有 CREATE DATABASE、ALTER 已有表、数据回填、DROP/TRUNCATE/DELETE 或 xxl_job 对象。`USE ai_task_platform` 固定目标；不允许修改脚本去复用其他项目库。

SQL 未使用 IF NOT EXISTS：重复执行应明确失败，不能掩盖既有同名不兼容表。若已存在同名表，先停止并对比 SHOW CREATE TABLE，再决定后续人工方案；不删除或覆盖。

| 字段/约束 | 审核目的 |
| --- | --- |
| event_id / task_id / event_type | UUID 事件标识、任务引用、当前 TASK_SUBMITTED 类型，消息只载 taskId |
| UNIQUE(task_id,event_type) / FK(task_id) | 每任务每类型一条意图，不生成孤儿事件；依赖现有 ascii_bin CHAR(36) task PK |
| status / attempts / next_attempt_at | PENDING→DISPATCHING→SENT 或 RETRY/ISOLATED，有限尝试、退避 |
| version / claim_token / locked_until | 版本 CAS 认领、所有权与到期重派；只用于 Outbox，不是 RUNNING Worker 租约 |
| sent_at / isolated_at / last_error_code | 发布确认后的状态和隔离证据，不保存原异常文本或密码 |
| created_at / updated_at / DATETIME(6) | UTC 数据库时钟；生命周期 CHECK 限制字段一致性 |
| 两个状态/到期索引 | 支持待派发与过期认领扫描；尚无性能指标或 EXPLAIN 验收 |

## 新环境首次人工初始化（本机已完成）

**仅新环境首次部署：审核并用本机数据库管理员执行 database/002_outbox.sql。** 不要用 ai_task_app 执行 DDL，不要把管理员密码粘贴到聊天或命令参数。可使用已登录的 MySQL Workbench/客户端运行文件；Codex 不代为执行 CREATE/ALTER。已存在兼容表时不要重复执行或清理它。

执行前由管理员确认 `SELECT DATABASE()`/目标连接为该 P1 业务库、既有 ai_text_task 为 InnoDB/MySQL 8.0.16+，并检查有没有同名 Outbox 表。脚本中的 USE 会显式切换业务库，不创建库。

执行后管理员对比 `SHOW CREATE TABLE ai_task_platform.ai_task_outbox`；检查 ai_task_app@127.0.0.1 原权限。已有库级 SELECT/INSERT/UPDATE 授权自动覆盖新表；若原来仅表级授权，管理员需审核后补新表相同三项权限，不授予业务用户 DDL/DELETE。迁移脚本不执行 GRANT。无需重新初始化旧任务表，不清理任何数据。

## 审批后的自动验收入口

迁移完成并明确授权后，再在测试进程设置 `OUTBOX_IT_SCHEMA_APPROVED=true`。这只是审批门禁，不创建表；真实 preflight 仍读表结构、MySQL 产品/版本、业务账号及独立连接，不存在任何内存降级。默认构建不使用该开关。

```powershell
# 仅在人工迁移与审批完成后执行；没有任何密码参数。
$env:OUTBOX_IT_SCHEMA_APPROVED='true'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-outbox.ps1 -UseP1Broker
```

脚本只在本地检查 P1 Rabbit 归属后读取运行时 MQ 凭据；MySQL 密码若未在 MYSQL_IT_PASSWORD 环境中配置，提示一次安全终端输入。也可用秘密管理方式给进程提供 MYSQL_IT_HOST/PORT/USERNAME/PASSWORD 和 RABBITMQ_*，使用 -NonInteractive。不要写 .env、配置文件、命令参数或 Git。脚本恢复原环境，日志不记录密码。

脚本执行完整 `mvn -B -ntp -Poutbox-it verify`（可选 -Offline 仅关闭依赖下载），使用独立 target 输出目录。只有 130 项离线与 1 项真实 Outbox 综合测试全部通过、退出码 0 且存在 OUTBOX_IT RESULT 才报告通过。没有批准时退出 2；直接 Maven 门禁也失败而不是 skipped。

真实测试已覆盖：同事务写入与真实 FK 错误回滚、两独立 Dispatcher JDBC 会话对同候选版本的 CAS 竞争、实际 Rabbit confirm/mandatory、受控 confirm 后状态更新故障、自然等待 Outbox 认领到期并重复投递、旧令牌拒绝、生产 Consumer 手动 ACK 与任务不重复成功执行、发布失败有限重试和隔离。Dispatcher 按本轮随机 taskId 收窄查询，不扫描/修改其他人的事件；仅新增并保留本轮 3 个任务/3 个事件，失败回滚任务不保留。随机 P1 队列保留，不 purge/drop。

本次三条真实 MySQL 会话为 11/12/13（两 Dispatcher、一 Worker），实际 MQ listener 共收到 3 个投递，仅 2 次 Mock 调用；不称为跨 JVM 崩溃验收。confirm 后故障为受控 pre-update 注入，不冒充真实断网；到期等待真实数据库时钟，约 30 秒。**130 离线＋1 真实联合测试通过**；证据在 docs/phase4a-verification.md。

## 启用与回退限制

实际应用仅在迁移后显式设置 TASK_STORAGE_MODE=mysql、TASK_MESSAGING_MODE=rabbit、TASK_OUTBOX_ENABLED=true，再使用外部 DB/MQ 凭据启动。启动先 SELECT 必要列校验存储/结构；不可用明确拒绝启动。HTTP 202 表示任务和事件已持久化，不意味着消息已送达或模型已成功。

旧 PENDING 任务未自动回填 Outbox；本阶段保证显式启用后新创建任务的派发意图。需要旧任务修复时先设计并单独批准方案，不能偷偷批量写入。达到最大尝试的事件 ISOLATED，任务仍可 PENDING，需后续人工/补偿重放；不把隔离当成功。

若暂停 Outbox 组件，已有意图保留；不得通过删除表/事件“回退”。关闭开关会回到明确的 Phase 3A 直接发布模式，该模式有双写窗口，不能称为可靠回退；恢复待派发事件需再次启用 Outbox。多实例必须保持相同 Rabbit 拓扑、最大尝试与退避配置。
