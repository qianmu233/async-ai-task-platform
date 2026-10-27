# P1 简历亮点与证据边界

面向 Java 后端 / AI 平台研发实习。项目为本机可运行的 AI 文本任务后端 MVP，没有线上用户、商业收益或真实第三方模型调用数据。

## 推荐项目描述

**AI 异步任务处理与模型服务平台｜Java 17、Spring Boot、MySQL、RabbitMQ、Redis**

- 实现任务提交、状态/结果查询与异步状态机，基于 MySQL 唯一幂等键和条件更新处理跨实例重复请求及任务竞争。
- 基于 Transactional Outbox 将任务与派发意图同事务落库，结合 CAS、Publisher Confirm、有限退避和手动 ACK 实现至少一次派发及重复消费保护。
- 使用 Redis Lua 原子计数与 TTL 实现分布式提交限流，通过两个独立 Spring Boot JVM 验证共享额度和 Redis 故障拒绝策略。
- 实现执行者 UUID、固定执行期限和保守超时失败，拒绝过期/旧 Worker 终态写回；集成可配置 OpenAI-compatible HTTP 适配器，限制响应大小和调用期限，分类保存上游错误。

根据岗位选择其中 3–4 条。AI 平台岗位可强调模型接口和异步编排；Java 后端岗位优先解释事务、幂等、并发控制及消息确认。不要用组件数量替代对源码的理解。

## 能说到什么程度

| 能力 | 实际证据 | 表述边界 |
| --- | --- | --- |
| MySQL 持久化/幂等/竞争 | Phase 2B 真实 MySQL 独立连接、双实例 HTTP/重启验证 | 不称全系统 exactly-once |
| Rabbit 发布/确认/消费隔离 | Phase 3A 真实 MySQL/Rabbit/HTTP，17 条消息综合验收 | 未验证 Broker 崩溃或生产 HA |
| Redis 分布式限流 | Phase 3B 两个独立 JVM，12 请求中 4 放行/8 限流 | IP 来源限流，不是认证用户配额；不称生产吞吐 |
| Outbox 事务与重派 | Phase 4A 真实 MySQL/Rabbit 并发、回滚、确认后重派 | 有限至少一次派发；隔离后不自动重放 |
| Worker 所有权与超时 | Phase 4B 两个真实 JDBC 会话，超时更新总数 1、终态 version=2 | 固定期限，没有心跳/续期/自动重跑或外部 fencing |
| 模型 HTTP 适配 | Phase 4C 本地真实 HTTP、MySQL/Rabbit 联合链路 | 模型服务是协议模拟；第三方 LLM 调用 0 次 |
| Java 17 运行与生产定时派发 | Phase 5 JDK 17.0.16、147 项本地测试；独立 JAR/MySQL/Rabbit/真实 @Scheduled 25 个任务通过 | 采用已授权随机键前缀隔离历史数据；不称 HA/崩溃耐久性 |

性能补充可选：本机 16 样本、客户端并发 4，HTTP 受理 p95=10.447ms，观察到的任务完成 p95=1082.065ms；默认 1s Outbox 轮询、Mock 模型。这是小规模工程验证，推荐写进演示材料，简历不必堆数字；不能推出生产 QPS、可用性或容量。完整环境/统计口径见 phase5-verification.md。尚未实现 XXL-JOB、RAG、Agent、流式模型、多模型路由、Kubernetes、生产监控/HA，不要写进已实现技术亮点。

## 面试时按一条链路讲解

从 POST 任务进入 TaskService 开始，解释唯一幂等键仲裁和同事务 Outbox，再讲 Dispatcher 的短事务 CAS、Rabbit confirm/ACK，最后讲 Worker 所有权认领、事务外模型调用和受保护终态写入。用“事件 SENT 但任务 FAILED”说明派发成功和业务/模型成功的区别。

优先准备：唯一键冲突后的事务恢复、MySQL/MQ 双写窗口、confirm 与 ACK、重复消息是否再次调用模型、超时后晚到结果如何拒绝、Redis 不可用时如何处理、为什么模拟 HTTP 不能证明真实模型质量。核心方法和更多问题见 project-review.md。
