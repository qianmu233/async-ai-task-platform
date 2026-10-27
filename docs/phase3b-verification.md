# Phase 3B：Redis 分布式提交限流验收

日期：2026-10-14，Asia/Shanghai。结论：最小核心功能与真实跨 JVM 验收通过，等待审核后进入 Phase 4。

## 恢复点与 Git 状态

恢复前读取 AGENTS.md、README、Phase 3A 验收与项目复盘；检查历史、工作区和待提交文件。上次暂停在 Redis 实施之前，仓库中无 Redis 源码/脚本或单独暂停记录；已完成 RabbitMQ 改动和真实证据完整保留，没有重新开发 Phase 3A。

当前 HEAD=`a2dff33 feat: complete MySQL persistence and concurrency verification`，父提交=`a99313a`。a2dff33 合并包含 Phase 2A/2B，独立于 Phase 1，但不是仅 Phase 2B 的单独提交。Phase 3A 仍未提交；按最新“本轮不自动提交或推送”要求没有创建新 Git 检查点，也没有暂存文件。

既有真实 MySQL/MQ 验收不重复运行：本轮未修改 Repository、SQL、TaskService、Worker 或 MQ 生产代码的既有行为；119 项离线包含全部原有测试。22 项真实 MySQL 和 1 项真实 MQ 的通过结果属于 2026-10-13 历史证据，见 phase3a-verification.md。

## 实现与文件

POST `/api/tasks` → 可选拦截器 → Redis Lua 原子受理计数/TTL → 原 TaskService 注册/派发。GET 不限流。固定窗口从首个受理请求开始，不使用 JVM 时钟或本地计数；拒绝不自增、不延期。限流启用时，重复幂等提交和无效请求也占用额度。

- 默认关闭，显式排除 Redis 默认自动配置，默认 memory/mysql/rabbit 均不需要 Redis 服务。
- 仅使用 TCP peer IP，SHA-256 后写入 P1 namespace；不信任 Forwarded/X-Forwarded-For/X-Client-Id。自动 forwarded-header 处理必须为 NONE，否则启用时拒绝启动。
- 超限 HTTP 429 / RATE_LIMIT_EXCEEDED + Retry-After；Redis 连接/脚本/响应异常明确 fail-closed 为 503 / RATE_LIMIT_UNAVAILABLE，任务不注册，无静默降级。
- 连接/命令超时默认 1000ms。启用必须提供外部密码；没有默认密码或明文密码文件。多实例需要一致的 namespace、额度和窗口。
- Boot BOM 管理 Spring Data Redis 3.5.13 / Lettuce 6.6.0.RELEASE；Java 17 编译目标与 Boot 3.5.16 不变。

| 新增/修改文件 | 用途 |
| --- | --- |
| pom.xml、AiTaskPlatformApplication.java、application.yml | Redis starter、默认关闭配置、显式 redis-it profile |
| config/SubmissionRateLimitConfiguration.java | 连接参数/来源策略校验、条件装配、超时、MVC 拦截器 |
| ratelimit/SubmissionRateLimiter.java、RedisSubmissionRateLimiter.java | 限流边界、Lua 执行、来源摘要键、fail-closed |
| ratelimit/SubmissionRateLimitInterceptor.java、RateLimitException.java、api/ApiExceptionHandler.java | 注册前限流、429/503 安全响应 |
| src/main/resources/redis/submission-window.lua | 原子计数、TTL、拒绝不延期、不增长 |
| src/test/java/com/example/aitask/ratelimit/SubmissionRateLimitTest.java | 6 项关键离线用例 |
| src/test/java/com/example/aitask/ratelimit/SubmissionRateLimitRedisIT.java | 1 项真实 Redis / HTTP / 独立 JVM 综合验收 |
| infra/redis-compose.yml、scripts/verify-redis.ps1 | 专用 Docker 资源、凭据环境、完整 verify 与真实验收汇总 |
| README.md、docs/project-review.md、本报告 | 配置、证据、原理与面试问题 |

Java 生产相对路径前缀 src/main/java/com/example/aitask；YAML 为 src/main/resources/application.yml。原有 Phase 3A 未提交文件另见该阶段报告。

## 实际测试结果

2026-10-14 **10:32:15 +08:00** 完整执行 `mvn -B -ntp -Predis-it -Dplatform.build.directory=<独立本轮目录> verify`：

```text
Surefire: 119 tests, 0 failures, 0 errors, 0 skipped
Failsafe: 1 real Redis test, 0 failures, 0 errors, 0 skipped
BUILD SUCCESS; Maven/script exit=0
```

119=原 113+新增 6，未减少原测试。新增用例仅覆盖受理/来源、429/读请求、存储异常 503/不注册、不启用无连接、凭据/代理门禁以及关键配置/摘要。离线 template 替身只验证错误契约，真实测试使用实际 Redis/Lettuce/HTTP，不使用 H2/Redis mock。

| 真实项目 | 证据 |
| --- | --- |
| 实际 Redis | 7.4.11，真实 PING 与 INFO server |
| 两独立 JVM | PID A=48080 / B=48368；HTTP=62428 / 62429，与 Failsafe JVM 独立 |
| 共享受理计数 | A/B 各先成功提交一次，再并发 10 次；共 4 个 202 / 8 个 429，Redis GET counter=4 |
| TTL | 并发完成时 59654ms；拒绝后 TTL 不增加 |
| 来源伪造 | 每请求不同 X-Forwarded-For/Forwarded/X-Client-Id，仍共享直连 127.0.0.1 额度 |
| 非提交接口 | 额度耗尽后 GET 已受理任务仍 200 |
| TTL 复位 | 同生产 Lua、独立随机 expiry 键自然过期，重新受理；无 DEL/时钟替身 |
| 真实连接失败 | 第三个临时 JVM PID=48904 连接未监听端口，POST 返回 503 / RATE_LIMIT_UNAVAILABLE / 无 taskId；真实 Redis 未停止 |
| 生命周期 | 仅本次 3 个子 JVM 退出；Redis 容器/卷保留 |

这些 HTTP 应用使用 memory/local，目的是证明跨进程共享限流，不将其描述为跨 JVM MySQL/MQ 联合验证。业务幂等/Worker 保持原 MySQL 实现，先前真实证据保留。

关键日志：

```text
REDIS_IT RESULT run=77c6dcce-1c06-485e-829e-f074369546e3 redis=7.4.11 pidA=48080 pidB=48368 httpA=62428 httpB=62429 crossJvm=true admitted=4 rejected=8 counter=4 ttlMs=59654 spoofedHeadersIgnored=true readsAllowed=true expiryReset=true unavailableHttp=503 faultPid=48904
```

脚本 run=`8ab307abaed24d699edd41fb4827fece`；原始 verify.log、build/surefire-reports、build/failsafe-reports、build/redis-processes/{A,B,fault}.log、result.json 位于 target/phase3b-runs/8ab307abaed24d699edd41fb4827fece/；最新汇总 target/phase3b-latest.json。target 被 Git 忽略，子进程日志关闭框架日志，仅有 JDK 25/Netty 兼容警告；核心验收首次运行通过，无需修复生产代码。

门禁：PowerShell AST 解析错误 0；无环境的 `verify-redis.ps1 -NonInteractive` 退出 2，拒绝执行，不启动连接或进程。另在独立目录直接运行 redis-it 的 failsafe:integration-test/failsafe:verify，缺 REDIS_HOST 明确 Maven 退出 1（1 个预期 AssertionError / failure，0 errors/skipped），没有连接数据库或启动 JVM；证据在 target/phase3b-refusal/ba565c57902d4bbe8bcc8642cb8c50f7/gate.log。该负向门禁是预期拒绝，不能算作真实服务验收通过，原通过报告未被覆盖。

## 专用基础设施与安全边界

Docker 29.8.1。仅新增 ai-task-platform-redis（healthy），redis:7.4-alpine，实际 7.4.11；镜像摘要 `redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499`。Compose/network=`p1-ai-task-redis` / `p1-ai-task-redis_default`；卷 ai-task-platform-redis-data；仅绑定 127.0.0.1:6380→6379。AOF 开启、64MB/noeviction，容量不足明确故障，避免静默驱逐正在使用的限流计数。

新容器凭据随机生成，仅经本地进程/容器环境传入；配置通过 stdin 进入 Redis，无密码命令参数或密码配置文件。复用入口校验 owner/project/运行状态/专用卷/本地端口后读取本项目容器运行时环境。Docker 管理权限仍可读取环境，不能视为生产秘密管理。

P1 RabbitMQ ai-task-platform-rabbitmq 保持 healthy；P2 mysql-erp/rag-demo/pgvector 保持原停止状态，原 network 与 mysqldata/pgdata 卷保留。没有执行它们的 start/stop/exec，不读取其凭据，不修改配置。

本地精确扫描已校验归属的 P1 Redis/Rabbit 运行时密码及 XML/URL 编码形式：313 个项目文本/日志/XML，命中 0。没有调用外部连接器；该扫描不声称精确验证未知 MySQL 密码。Git 已跟踪 target/.env/密钥/日志路径为 0；暂存为 0；ignore 门禁覆盖 target、.env 和本地配置。git diff --check 通过。

后续对 77 个待审工作区候选文本文件和全部 2 个 Git 历史提交进行私钥、令牌及 JDBC 明文密码特征扫描，命中 0；这类特征扫描不等于证明不存在任意格式的秘密。测试子 JVM 存活数为 0，父执行环境未保留 REDIS/RABBITMQ/MYSQL_IT/AI_TASK_DB 密码。

不访问数据库、无 DDL/DROP/TRUNCATE/DELETE，不清理已有 Redis 数据、容器或数据卷；自然 TTL 到期是限流机制本身。没有 commit/push 或收费模型调用。

## 可复现命令与限制

已运行专用 Redis 复验：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-redis.ps1 -UseP1Container
```

仅首次经授权启动使用 `-StartRedis`，已有任何同名 P1 容器/卷/网络会拒绝重建。外部凭据模式先在本地安全配置 REDIS_HOST=127.0.0.1、REDIS_PORT=6380、REDIS_PASSWORD，再运行脚本 -NonInteractive 或 `mvn -B -ntp -Predis-it verify`；不要将密码放进命令参数、.env、Git 或聊天。

已解决：跨实例共享额度、计数与 TTL 原子性、客户端自报身份绕过、明确 429/503、安全默认关闭、一键真实验收和子进程回收。

边界：无认证，NAT/代理共用额度，未实现可信代理用户身份；固定窗口边界突发；各实例必须保持一致配置；单节点 Redis 的故障切换/重启/持久化丢失、TLS/细粒度 ACL、限流性能未验收。计数丢失可能重置额度，不宣称生产 HA。JDK 17 运行未验证（编译目标 17，本机运行 25）。

Phase 4 仍需最小可靠派发与任务恢复方案：MySQL 提交后 MQ 发布前崩溃、无人重试 PENDING、RUNNING 所有权/租约/fencing、隔离重放及消费者恢复。此轮不开发 Outbox/恢复/XXL-JOB，也不宣称模型 exactly-once。

**满足 Phase 3B 核心验收条件，可审核后进入 Phase 4；当前停止，不自动推进或提交。**
