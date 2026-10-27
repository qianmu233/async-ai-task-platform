# Phase 3A RabbitMQ 消息驱动设计

## 范围与依赖

使用现有 Spring Boot 3.5.16 BOM 管理 `spring-boot-starter-amqp`，实际解析为 Spring AMQP **3.2.12**、RabbitMQ Java client **5.25.0**。Java 17 编译目标不变。默认 `TASK_MESSAGING_MODE=local` 保持内存/线程池开发链路；`rabbit` 只允许 `TASK_STORAGE_MODE=mysql`，缺失配置或连接故障不降级。生产应用显式排除 RabbitAutoConfiguration，只在 rabbit 条件配置里创建 Rabbit 组件。

本阶段不改变 JdbcTaskRepository 的唯一键/事务/条件更新 SQL，不增加认领 HTTP 接口，不引入 Redis、XXL-JOB、真实 LLM 或 SQL DDL。

## 请求与执行链路

1. HTTP → TaskService.submit：共享原有规范化、校验和数据库 createOrGet。
2. PENDING 已提交后，TaskDispatcher.dispatch 发送任务 ID。默认 LocalTaskDispatcher 使用原有有界线程池；rabbit 模式使用 ConfirmedRabbitPublisher。
3. 发布到 durable direct exchange，消息 persistent + mandatory，并等待对应 CorrelationData 的 confirm；ACK 且没有 return 才成功。失败/超时返回 503 DISPATCH_UNAVAILABLE，携带 taskId，不把 PENDING 改成模型失败。
4. RabbitTaskConsumer.receive 手动处理 deliveryTag；TaskWorker.execute 调用生产 Repository.start，从数据库原子 PENDING → RUNNING，并读取输入，随后执行 MockModelClient。
5. 终态/结果写库成功后 basicAck(tag, false)。模型业务失败写 MODEL_CALL_FAILED，完成失败状态提交后 ACK。消费者不信任消息里的文本/状态，也不把模型调用放入数据库事务。

正常消息仅有 **36 字节规范 UUID**，contentType=application/vnd.ai-task-id.v1，x-task-schema=1。它是明确的 wire schema，不是 Java 对象序列化，任务输入只由 MySQL 查询获得。格式不合法、未知 taskId 等进入隔离队列，日志不输出原始消息体。

## 最小拓扑与确认

专用 vhost 固定 `/ai-task-platform`，资源前缀要求 `p1.*`。默认拓扑为 `p1.ai-task.exchange`、`p1.ai-task.tasks` 和 `p1.ai-task.parking`；两个队列 durable、非 exclusive、非 auto-delete。工作消息 route=task，隔离消息 route=park。没有消费 parking 的后台任务，也没有 TTL/DLX 重试环。

confirm 只确认发布到 broker，消费者 ACK 是另一层确认；它们不能互相替代，也不能构成数据库和 MQ 的事务。[RabbitMQ 确认语义](https://www.rabbitmq.com/docs/confirms)；mandatory 无路由时返回消息，不能把 broker ACK 单独当作送达目标队列。[RabbitMQ 可靠性指南](https://www.rabbitmq.com/docs/reliability)

实现通过 CorrelationData future 等待，检查 isAck 和 getReturned；框架保证 return 信息在 confirm future 完成前可见。[Spring AMQP 3.2 发布确认与返回](https://docs.spring.io/spring-amqp/reference/3.2/amqp/template.html#template-confirms)

durable/persistent 是持久化基础，真实 RabbitMQ 4.3.6 链路、durable 队列和 persistent 隔离消息已检查；不能据此宣称 broker 重启/崩溃/磁盘故障/集群高可用已经验证。

## 消费结果与失败分类

| 场景 | 行为 | 模型是否再执行 |
| --- | --- | --- |
| 初次认领与终态提交成功 | ACK | 一次 |
| SUCCESS/FAILED 重复投递 | 读到终态后 ACK | 否 |
| 已是 RUNNING | 确认写入 parking，理由 RUNNING_UNRESOLVED，然后 ACK | 否；没有租约不能自行重置 |
| 模型异常/无效输出 | 持久化 FAILED / MODEL_CALL_FAILED，再 ACK | 否 |
| 认领阶段存储异常 | 当前未确认 delivery 内最多 3 次尝试，两次 1000ms 退避；耗尽后 parking / STORAGE_RETRY_EXHAUSTED，再 ACK | 仅成功认领后执行 |
| 模型执行后的存储异常 | parking / COMPLETION_UNKNOWN，再 ACK；不再尝试执行模型 | 否 |
| 无效 schema/未知 taskId/其他未处理异常 | 有界消息体与可信理由头写入 parking，确认后 ACK | 否 |
| parking 发布失败/未路由/超时 | 不 ACK，不立即 requeue，异步停止整个 P1 listener container | 否；连接关闭后 broker 重投，需应用重启恢复消费 |
| ACK 连接异常 | 停止消费者，不覆盖已经写好的数据库终态 | 重投终态可安全 ACK |

prefetch=1，固定 2 个消费者。有限退避只重试认领，不自动重跑外部模型。当前所有认领阶段 DataAccessException/TransactionException 均有限重试（包含可能永久的存储错误），最多 3 次后隔离；没有按每个 vendor code 制定重试策略。后续可细分，但不会无限立即重试。

parking 采用“确认副本 → ACK 原投递”，不依赖 classic queue 的无确认 DLX 转移。[RabbitMQ DLX 安全性说明](https://www.rabbitmq.com/docs/dlx#safety) 此操作仍非原子：确认副本后 ACK 丢失可能产生多个隔离副本；保留任务 ID，人工/未来恢复逻辑应幂等处理。隔离消息体最多保留 4096 字节，附原长度，避免透传任意 header 图或异常栈。

## MySQL 与发布之间的窗口

**这里没有事务 outbox、XA 或可靠双写事务。** 最小派发协议是“已持久化任务 + 有路由的确认发布 + 同键 PENDING 重派”。它能验证并修复有客户端同键重试的发布失败，不能保证无人重试时自动完成派发。

| 故障窗口 | 结果与当前处理 |
| --- | --- |
| MySQL 提交后、发布前崩溃 | PENDING 留存，无消息；后续同键同输入请求可重新派发，自动扫描留给 Phase 4 |
| 发布失败/未路由 | 返回 503，数据库仍 PENDING；按同键重试，不创建新行 |
| 发布超时但 broker 实际接收 | 重试可能重复发；数据库条件认领防重复执行 |
| confirm 成功后、HTTP 响应丢失 | 同键请求返回原 UUID；若仍 PENDING 可再发，否则只返回当前状态 |
| RUNNING 后进程崩溃 | 原消息重投也不能重新认领，进入隔离；没有 attempt/租约/fencing，不重置任务 |
| 模型完成后终态提交结果未知 | 可能 RUNNING 或终态；保留隔离记录，不自动重跑模型 |

这个选择复用既有表，不扫描/修改已有 PENDING 数据，也不把 Phase 2B 留存的测试任务自动投入新队列。Phase 4 需要独立设计恢复扫描/持久化派发标识或 outbox、租约及执行所有权，涉及新 schema 时另行人工审核迁移。

## 运行与验证入口

- 默认 `mvn verify` 不连数据库/MQ；原有 99 项保留，新增 14 项离线关键契约测试。
- `rabbit-it` profile 只运行 TaskPipelineRabbitIT：真实 HTTP→真实 MySQL→真实 RabbitMQ→生产 consumer/worker→终态；缺少环境直接失败，不使用 H2、OfflineJdbcFixture 或假 broker。
- 一键 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-rabbit.ps1`：读取进程环境，缺密码安全提示，完整 verify 和 XML/日志/result.json 汇总。用 `-NonInteractive` 禁止提示，`-Offline` 仅离线解析 Maven 依赖，MQ/DB 仍必须真实存在。
- 已授权且 Docker engine 可用时，首次可加 `-StartBroker` 使用 infra/rabbitmq-compose.yml。仅新建专用 P1 容器/数据卷、绑定 localhost:5673/15673；已有同名容器/卷则拒绝，禁止重建/清理。默认不安装、启动或停止其他服务。

Compose 采用官方安装指南列出的 rabbitmq:4-management 标签，本次实际拉取/启动为 **RabbitMQ 4.3.6**，镜像摘要记录于验收报告；标签今后可能变化，复跑仍按真实 IT preflight 记录版本。[RabbitMQ Docker 安装入口](https://www.rabbitmq.com/docs/download)

应用配置用外部环境：TASK_STORAGE_MODE=mysql、TASK_MESSAGING_MODE=rabbit、AI_TASK_DB_HOST/PORT/USERNAME/PASSWORD、RABBITMQ_HOST/PORT/USERNAME/PASSWORD。Rabbit vhost 固定专用值。集成入口使用单独 MYSQL_IT_HOST/PORT/USERNAME/PASSWORD，Rabbit 参数共享 RABBITMQ_*。

现有综合真实测试仅 1 项，扩展同 JVM 两个独立 Spring Boot 上下文，HTTP/连接池/Rabbit factory 分离。它创建随机 p1.it.* 拓扑和 **5 行任务**，实际 **17 次 delivery、5 次 Mock 调用**；3 次终态重复、两应用交叉派发、8 消息同任务竞争（1 次执行/7 次 RUNNING 冲突）、9 条隔离记录、mandatory 无路由拒绝均通过。竞争期间 version=1，最终全部 version=2，两 Repository 完整快照一致。parking 检查后本轮消息放回，工作队列 ready/unacked=0，隔离 ready=9/unacked=0；不删除表/行/队列。

本测试证明真实独立消费实例的核心行为，不证明两个 JVM/不同主机部署、进程崩溃或 broker 故障恢复。真实 DB 暂时故障退避、完成不确定性、隔离发布/ACK 故障暂停仍主要由离线测试覆盖，没有假装做过真实故障注入。

每次输出 `target/phase3a-runs/<runId>/verify.log`、build 下 XML、result.json 和 target/phase3a-latest.json；没有原始凭据、异常 message/cause。当前自动验证及环境阻挡记录见 phase3a-verification.md。
