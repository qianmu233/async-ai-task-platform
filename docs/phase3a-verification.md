# Phase 3A 验证报告：真实 RabbitMQ / MySQL 核心验收通过

日期：2026-10-13，Asia/Shanghai；基线 `a2dff33`（既有 Phase 2A/2B 提交）。本轮没有 commit/push、SQL DDL/清理，也没有修改其他项目或 P2 资源。

**当前结论：2026-10-13 13:15:06 +08:00 完整 verify 成功，113 项离线和 1 项真实 HTTP/MySQL/RabbitMQ 端到端测试全部通过；13:17:36 的既有 22 项真实 MySQL 定向回归也通过。达到 Phase 3A 核心验收条件，停止等待审核，不自动开展下一阶段。** 本次续验只扩展现有真实测试的双实例场景并更新文档，没有重新开发生产功能或增加重复测试类。

## 完成的实现

- BOM 管理 Spring AMQP 3.2.12 / Rabbit client 5.25.0，Boot 3.5.16 和 Java 17 目标不变。
- TaskService → 可切换 TaskDispatcher；LocalTaskDispatcher 保留原线程池和测试构造方法；共享 TaskWorker 保留原状态/模型/存储边界。JdbcTaskRepository/唯一键/SQL 未修改。
- Rabbit 模式：持久化任务 → canonical taskId 消息 → durable/persistent 发布 + confirm/mandatory 检查 → 手动 ACK 消费 → 数据库条件认领 → Mock → 终态持久化。
- 模型业务失败落库再 ACK；终态重复 ACK；认领存储失败有限退避；未解决 RUNNING、完成结果未知、坏消息/未知 ID 等确认转入隔离后 ACK。
- 隔离发布或 ACK 失败时暂停消费者，不立即无限 requeue。无自动消费者恢复、无模型 exactly-once 宣称。
- MQ 派发失败返回安全 503 / DISPATCH_UNAVAILABLE / taskId，PENDING 留存；Rabbit 模式同键同输入且 PENDING 时可重派，终态不重派。不是 outbox/XA，不保证无客户端重试时的自动派发。
- 一键真实入口 verify-rabbit.ps1，独立 P1 Docker Compose，环境密码，无数据/队列/卷清理；缺失配置明确失败。

## 本轮文件清单

| 文件 | 用途 |
| --- | --- |
| pom.xml | AMQP starter 和默认关闭的 rabbit-it Failsafe profile |
| AiTaskPlatformApplication.java | 排除默认 Rabbit 自动连接，仅显式 rabbit 条件配置启用 |
| service/TaskService.java | 注册与可切换派发，保留原构造契约和本地拒绝逻辑 |
| service/TaskWorker.java | 共享认领、Mock、终态、存储阶段分类 |
| service/TaskDispatcher.java、LocalTaskDispatcher.java、TaskDispatchException.java | 传输边界、本地执行及安全发布错误 |
| config/MessagingConfiguration.java | 存储/消息模式校验、专用 Rabbit 组件、手动 ACK/prefetch、暂停 |
| messaging/TaskQueueNames.java、TaskMessages.java | durable 拓扑、v1 canonical UUID 消息 |
| messaging/ConfirmedRabbitPublisher.java、RabbitTaskConsumer.java、ConsumerPause.java | confirm/return、消费/隔离/暂停 |
| api/ApiExceptionHandler.java | 派发 503 响应，既有响应保留 |
| src/main/resources/application.yml、config/application-rabbit.yml.example | 外部环境配置，默认 local 不连 MQ |
| src/test/java/com/example/aitask/messaging/RabbitMessagingTest.java | 11 项离线关键发布/ACK/重复/故障用例 |
| src/test/java/com/example/aitask/config/MessagingConfigurationTest.java | 3 项模式门禁及无连接装配用例 |
| src/test/java/com/example/aitask/repository/TaskPipelineRabbitIT.java | 1 项 opt-in 真实 DB/MQ/HTTP 链路，扩展交叉派发与同任务双实例竞争，实际通过 |
| scripts/verify-rabbit.ps1、infra/rabbitmq-compose.yml | 一键验收和独立 P1 容器方案 |
| README.md、docs/project-review.md、docs/rabbitmq-design.md、本报告 | 运行、选型/原理/面试、证据与边界 |

Java 生产相对路径均以 src/main/java/com/example/aitask 为前缀。原有全部测试文件保留，没有增加 MQ 测试框架或 Mockito/H2。

## 初版代码阶段的历史离线与 MySQL 验证

完整阶段质量门槛通过复用 scripts/verify-mysql.ps1 -Offline 执行一次 `mvn -o -B -ntp -Pmysql-it -Dplatform.build.directory=<独立目录> verify`：

```text
2026-10-13 11:52:47 +08:00
Surefire: Tests run: 113, Failures: 0, Errors: 0, Skipped: 0
Failsafe MySQL: Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Maven/script exit: 0
```

113=既有 99+新增 14。首轮新增配置测试缺少线程池参数导致一项失败，已补测试上下文配置，再运行通过；没有将首轮失败报为成功。后续完整 verify 同样全部通过。默认构建只含离线测试，不依赖 DB/MQ。

真实 MySQL 回归：8.0.45，CONNECTION_ID **27/28**；认领成功/状态冲突 **5/5**，终态完成成功/冲突 **5/5**，同键不同输入插入/冲突 **5/5**，等价输入创建/复用 **5/5**，终态保护及最终 version 断言通过。本轮新增 22 行回归数据留存；没有操作原业务任务。这是 **真实 MySQL 验证，不是 RabbitMQ 验证**。

回归脚本 run=`c818599a52e049e48e95f5dc57fd10e8`，IT run=`44f4e388-4d0d-4521-a71f-641826c0c81f`；证据在 target/phase2b-runs/c818599a52e049e48e95f5dc57fd10e8/verify.log、build/surefire-reports、build/failsafe-reports、result.json。独立完整打包 JAR 同目录 build 中，包含本轮实现。

其他实际检查：PowerShell 脚本 AST 解析零错误；无凭据非交互脚本返回 **2**；Docker 不可用时 -StartBroker 门禁返回 **2**。直接 rabbit-it 的 Failsafe 负向门禁返回 **1**（缺 MYSQL_IT_HOST，1 error、0 skipped），未连 DB/MQ；日志 target/phase3a-optin-refusal.log。这些是拒绝执行证据，不是 MQ 链路成功。

Docker Compose `config --quiet` 使用合成占位凭据作纯语法检查，退出码 0，未创建容器。git diff --check 无空白错误；JdbcTaskRepository、TaskRepository、database/001_init.sql 与基线无差异。本地源码/配置密码常量规则检查 31 个文件，最终疑似数 0；初次未锚定 YAML 行首的规则误命中 Java @Value 环境占位符，已确认并修正规则。父进程未保留 MYSQL_IT/AI_TASK_DB/RABBITMQ 密码；该检查不是按真实密码进行精确匹配。

最终为尚未运行的真实 MQ IT 增加启动命令参数，提前强制关闭 Spring debug/框架日志，避免初始化阶段受到终端 DEBUG 影响。随后 test-compile 于 12:11:18 +08:00 退出 0，Java release 17；此轮仅编译，没有再次执行测试，不能写成新的真实 MQ 成绩。生产源码在完整 verify 后无变更。

曾计划向外部 Node 工具传入真实密码作精确泄漏扫描，自动审批因敏感凭据外传风险拒绝，该调用未执行；没有重试/绕过，已清除工具临时上下文并仅做不带真实秘密的本地规则检查。不能将被拒绝的扫描描述为通过。

## 本次恢复后的实际 Docker 与真实 MQ 验收

用户恢复 Docker Desktop 后，确认 client/server 均 **29.8.1**。只读核对容器、网络、卷和镜像，确认上次中断未创建 P1 资源，再使用现有 infra/rabbitmq-compose.yml 启动；没有重装 WSL、修改系统配置、prune、down -v 或删除任何资源。

| 资源 | 实际值 |
| --- | --- |
| 容器 | ai-task-platform-rabbitmq，healthy，保留运行 |
| 镜像 / 实际服务器 | rabbitmq:4-management / RabbitMQ **4.3.6**（真实 AMQP preflight） |
| Compose / 网络 | p1-ai-task / p1-ai-task_default |
| vhost / 数据卷 | /ai-task-platform / ai-task-platform-rabbitmq-data |
| 回环端口 | 127.0.0.1:5673 → 5672；127.0.0.1:15673 → 15672 |
| 实际镜像摘要 | rabbitmq@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd |

P2 的 mysql-erp、rag-demo、pgvector 均保持原停止状态；spring-ai-rag-demo_default 网络和 spring-ai-rag-demo_mysqldata / spring-ai-rag-demo_pgdata 两个数据卷保留。没有执行它们的 start/stop/exec 或修改配置。

Rabbit 凭据在本地环境随机生成；随后通过本地归属校验（owner=p1、project=p1-ai-task）读取本容器运行时凭据到测试进程环境。MySQL 使用安全终端输入。没有打印实际密码、写入项目文件/Git，或传递给外部连接器。

实际运行 `scripts/verify-rabbit.ps1 -Offline`，脚本内部完整命令为 `mvn -o -B -ntp -Prabbit-it -Dplatform.build.directory=<本次独立 build> verify`。`-Offline` 仅控制依赖解析，测试实际连接 MySQL 8.0.45、RabbitMQ 4.3.6 和真实 HTTP。

```text
2026-10-13 13:15:06 +08:00
Surefire: 113 tests, 0 failures/errors/skipped
Failsafe: 1 real pipeline test, 0 failures/errors/skipped
BUILD SUCCESS
Maven/script exit: 0
```

| 实际场景 | 证据 |
| --- | --- |
| HTTP 提交/派发、Worker 写回 MySQL | 成功任务 SUCCESS，Mock 业务失败任务 FAILED/MODEL_CALL_FAILED；同键 HTTP 200 复用原 UUID |
| 终态重复消息 | 3 次实际投递，不重复调用模型，字段和 version 保持 |
| 无法处理的消息 | 坏 schema / 未知 ID 实际确认写入 parking，随后 ACK 原投递 |
| publisher confirm + mandatory | 正常发布获得确认；真实 no-such-route 发布被拒绝，不能当作成功 |
| 交叉派发 | 停 A 消费，让 A 的 HTTP 由 B 消费；反向停 B 消费，让 B 的 HTTP 由 A 消费；生产 listener/worker 和真实连接均复用 |
| 双实例同任务竞争 | 两实例同时活跃，4 个真实消费者；8 条同 UUID 消息 A/B 各收到 4 条；模型运行时固定在 RUNNING，7 次重复投递产生状态冲突并隔离，仅一次执行 |
| 最终状态与版本 | 5 个新任务：4 SUCCESS、1 FAILED，全部 version=2，两独立 Repository 回读完整快照一致；认领期间检查 version=1 |

**双实例范围为同一 JVM 内两个独立 Spring Boot 应用上下文**，有不同 HTTP server、DataSource/连接池、Repository、Rabbit connection factory 与 listener registry，并非两个独立 JVM。HTTP A/B=**60909/56926**，MySQL session=**32/34**；A/B 完成 delivery=**12/5**，模型调用=**4/1**。这是最小消费实例验证，跨进程部署/重启故障仍未验证。

关键日志：

```text
RABBIT_IT PREFLIGHT run=2d8acabf-0bd4-4376-a04c-96d90b82213d mysql=8.0.45 rabbit=4.3.6
RABBIT_IT INSTANCES ... sameJvm=true httpA=60909 httpB=56926 mysqlConnectionA=32 mysqlConnectionB=34 deliveredA=12 deliveredB=5 modelCallsA=4 modelCallsB=1 raceDeliveriesA=4 raceDeliveriesB=4
RABBIT_IT RESULT ... realDelivered=17 modelCalls=5 terminalDuplicates=3 runningRaceConflicts=7 parked=9 mandatoryReturnRejected=true retainedRows=5 allVersions=2
```

真实消息总计 **17**，模型调用 **5**；9 条隔离记录由 2 条坏消息/未知任务及 7 条 RUNNING 竞争副本组成。竞争任务 UUID 为 `2df3bddc-6db6-4f7d-b026-1363338328d0`；初始成功/失败任务为 `8b9f1ceb-971a-42f5-b3d5-d76cf28c42f8` / `099a23e9-3ee1-472b-89b8-eaa1c21673b1`。所有键/拓扑为本次随机 run，未改已有任务。

两个测试应用结束后，真实 broker 的 list_queues 显示：

| queue（前缀 p1.it.2d8acabf-0bd4-4376-a04c-96d90b82213d） | durable | ready | unacked | consumers |
| --- | --- | ---: | ---: | ---: |
| .tasks | true | 0 | 0 | 0 |
| .parking | true | 9 | 0 | 0 |

原投递已 ACK，工作队列无遗留；隔离记录保留，没有 purge/delete。容器保持 healthy，测试应用已关闭，不占用这两个 HTTP 端口。

原始证据：`target/phase3a-runs/9434b259be074ee78b2c4f76b708fa4c/verify.log`、`build/surefire-reports/`、`build/failsafe-reports/`、`result.json`；最新 `target/phase3a-latest.json`，broker 启动日志 `target/phase3a-broker-start.log`。首次真实执行即通过，无需修改生产代码。

最终安全/范围检查：仅在本地读取已归属校验的 P1 broker 运行时密码，对本项目 **259 个文本文件**（源码/配置/报告/XML/日志）的原文及 XML/URL 编码形式匹配，命中文件 **0**；工具参数不含该秘密，没有使用外部连接器。父执行环境 MYSQL_IT_PASSWORD / AI_TASK_DB_PASSWORD / RABBITMQ_PASSWORD 均未留存。git diff --check 通过；JdbcTaskRepository、TaskRepository 和初始化 SQL 与基线仍无差异。此本地 broker 扫描不同于上文历史被拒绝的外部扫描，也不把它说成真实 MySQL 密码的精确扫描。

## 本次真实 MySQL 定向回归

复用已经完整编译的 classes/test-classes，在独立目录执行 `mvn -o -B -ntp -Pmysql-it -Dplatform.build.directory=<独立目录> failsafe:integration-test failsafe:verify`，不重复运行 113 项离线用例。

**2026-10-13 13:17:36 +08:00，22 项通过，0 failures/errors/skipped，退出码 0。** MySQL 8.0.45，真实连接 **36/37**，认领成功/冲突 **5/5**、终态完成成功/冲突 **5/5**、不同输入注册/冲突 **5/5**、等价输入创建/复用 **5/5**，原终态保护与 version 断言通过。

证据：`target/phase3a-mysql-regression/20c0041878d6475cb807b850e96e643e/verify.log` 和 `failsafe-reports/`；IT run=`d231a7e0-fc01-4425-9edd-d0bc5b04a996`。本次新增并保留 22 行回归数据，加 MQ 链路 5 行，共 **27 行本次任务**；不清理、不更新原有数据。

后续重复验收：已有 P1 broker 时安全配置环境后运行 verify-rabbit.ps1，不加 -StartBroker；初次独立启动才使用 -StartBroker（同名容器/卷存在会拒绝重建）。日志/报告都在 Git 忽略的 target 下，密码仍不进入命令行参数。

## 可靠性、风险与阶段条件

已真实验证：HTTP/DB/MQ/Worker 完整链路、正常 confirm 与无路由 return 拒绝、手动 ACK 后无 ready/unacked 工作消息、模型业务失败落库、坏消息/未知 ID 隔离、终态重复与双实例 RUNNING 竞争不再调用模型。队列 durable 和隔离消息 persistent 在真实 broker 上检查。有限存储退避、隔离/ACK 故障暂停、HTTP 发布失败 PENDING 重派仍主要由离线契约测试覆盖。

未验证：MQ 重启/断连/主动 nack/ACK 通道失效/崩溃/磁盘故障与吞吐；真实 DB 暂时故障退避及完成提交不确定性；两个独立 JVM 的消费/重启部署。durable/persistent 配置与正常链路通过不能等同于这些故障耐久性或生产 HA 已验收。

留给 Phase 4：MySQL 提交后发布前崩溃、无人重试的 PENDING 自动恢复、派发 outbox/标识与补偿、RUNNING 所有权/租约/attempt/fencing、完成不确定性对账、隔离消息的幂等重放及消费者暂停后的自动恢复。更广故障/性能实验放到 Phase 5。不能宣称数据库/MQ 双写事务或 exactly-once 模型执行。

**达到 Phase 3A 核心验收条件，满足审核后进入 Phase 3B 的基础条件。** 保留上述可靠性边界；本轮停止等待审核，不开发 Redis，不自动 commit/push。
