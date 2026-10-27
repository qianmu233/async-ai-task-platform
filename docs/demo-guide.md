# P1 演示、部署与集中复盘

> 2026-10-26 发布快照脱敏说明：Windows 用户目录以 `%USERPROFILE%` 展示，不是当时原始命令的字面路径；版本、运行 ID、测试结果和失败记录保留。

## 最简安全启动（无基础设施、无费用）

在项目根目录使用现有 JDK 17，构建一次：

先把当前终端的 `JAVA_HOME` 配置为自己的 JDK 17 安装目录。以下使用环境变量，不需要复制历史用户目录：

```powershell
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
mvn -B -ntp verify
powershell -NoProfile -File scripts/start-platform.ps1 -JavaHome $env:JAVA_HOME
powershell -NoProfile -File scripts/smoke-test.ps1
powershell -NoProfile -File scripts/stop-platform.ps1
```

默认 memory/local/Mock。start 仅启动 P1 JAR，检查端口/存活/HTTP 就绪；stop 校验 PID、进程开始时间和 P1 路径，仅停止自己记录的 JVM。日志/PID 记录在 target；不停止 Docker、不清理数据。使用不同端口时 start 加 -Port、smoke 加 -BaseUrl。后台 stop 是进程停止，不承诺优雅排空；需要优雅关闭时前台 `java -jar ... --debug=false` 后 Ctrl+C。

## 真实 MySQL / Rabbit /生产定时派发的一键验收

本机 MySQL 已人工迁移 001→002→003，P1 Rabbit/Redis Docker 环境已建立。不重复执行迁移，不创建/清理基础设施。运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-phase5.ps1 -JavaHome $env:JAVA_HOME
```

脚本安全提示 MySQL 业务密码，Broker 凭据只从归属已验证的 P1 容器读入进程；不输出/保存。先只读数据库预检，再用随机验收前缀和随机队列启动独立生产 JAR，真实 Spring 定时器自行派发，自动提交/查询并记录小规模延迟和结果。-Offline 只控制 Maven 依赖解析；-NonInteractive 需外部 MYSQL_IT_PASSWORD。默认 Maven verify 不连接数据库/MQ/第三方模型。

最新已通过：JDK17 独立 PID=46232，HTTP=60736，MySQL8.0.45、Rabbit4.3.6；25 个任务：24 SUCCESS、1 MODEL_CALL_FAILED、0 TASK_TIMEOUT，25 个 SENT；历史元数据未变。HTTP 幂等复用 200，同键不同输入 409；结果成功 200、失败 409。这里使用 Mock，Phase 4C 的真实 HTTP 模型协议另外使用本地模拟服务；第三方调用一直为 0。

## 手动演示持久化模式

在共享旧测试库中始终用验收前缀。外部进程环境先提供 AI_TASK_DB_USERNAME/PASSWORD、RABBITMQ_USERNAME/PASSWORD；密码用本地 Read-Host -AsSecureString 转换到进程环境，不写命令参数、.env 或源码。前缀使用随机 UUID，所有演示 POST 都必须以该前缀开头：

```powershell
$demoPrefix='demo:'+([Guid]::NewGuid().ToString('N'))+':'
powershell -NoProfile -File scripts/start-platform.ps1 -JavaHome $env:JAVA_HOME -Mode mysql-outbox -AcceptancePrefix $demoPrefix
powershell -NoProfile -File scripts/smoke-test.ps1 -KeyPrefix $demoPrefix
powershell -NoProfile -File scripts/stop-platform.ps1
```

该启动器刻意要求显式前缀，避免全库扫描历史事件；前缀外提交会滞留 PENDING，不应将此配置用于一般业务部署。优先直接使用上一节的一键真实验收，无需逐条配置 Broker 或 HTTP。

API 示例（正常模式/前缀演示模式请替换幂等键）：

```powershell
$body=@{idempotencyKey=$demoPrefix+'one';input='hello platform'}|ConvertTo-Json
$task=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8081/api/tasks -ContentType application/json -Body $body
Invoke-RestMethod -Uri ('http://127.0.0.1:8081/api/tasks/'+$task.taskId)
Invoke-RestMethod -Uri ('http://127.0.0.1:8081/api/tasks/'+$task.taskId+'/result')
```

结果未完成返回 202，失败返回 409（PowerShell Invoke-RestMethod 会抛非 2xx 异常；smoke 已处理）。HTTP 202 代表受理，不代表模型成功；Outbox SENT 代表派发确认，也不代表模型成功。

## 独立部署最小步骤

1. 准备 Java17+、MySQL8.0.16+、独立 P1 Rabbit；Redis 可选。现有 Compose 是独立 P1 的端口/vhost/卷，不改 P2。
2. 新环境由管理员审核顺序执行 database/001_init.sql、002_outbox.sql、003_worker_lease.sql，业务账号仅 SELECT/INSERT/UPDATE。本机三份均已执行，禁止再次盲跑；001 的 IF NOT EXISTS 不会升级旧表。
3. 构建 Maven JAR；通过进程环境配置 mysql/rabbit/outbox/Worker 期限和凭据，默认 Mock。所有环境变量完整表见 README；不用 Spring 自动 DDL。远端服务需自行配置网络隔离/认证/TLS，不将该未认证演示服务暴露公网。
4. 上线前只读审核历史 PENDING/RUNNING/未派发事件与旧 Worker。本机已保留历史数据，不能直接开启无范围扫描。新独立数据库若初始为空且审核通过，可不设置 TASK_ACCEPTANCE_KEY_PREFIX，运行正常全库扫描。没有自动清理/回填/恢复旧记录。
5. HTTP 模式只在用户授权后配置 MODEL_MODE/BASE_URL/API_KEY/NAME；请求期限+15000ms<=Worker期限。未授权不使用第三方模型。Redis/Outbox/超时保护配置失败明确拒绝，不回退。

## 5 分钟讲解顺序

先演示提交与状态/结果查询；解释 PENDING→RUNNING→SUCCESS/FAILED。接着展示 task/event 同事务和 scheduling-1 的 confirm 日志；再用同键重试说明唯一键幂等。最后讲 token/deadline 保护、模型错误分类、Redis 只负责限流，以及真实测试和模拟模型的边界。

复盘按 project-review.md 的源码表学习：TaskService → JdbcTaskRepository → JdbcOutboxRepository/OutboxDispatcher → RabbitTaskConsumer → TaskWorker → ModelClient。重点理解锁顺序、事务结束后调用模型、失败结果与派发状态区分。性能仅为本机小样本，不展示“生产 QPS”；不宣称 XXL-JOB、exactly-once、真实模型质量或线上收益。
