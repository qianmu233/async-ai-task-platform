# Phase 5 最终工程验收

> 2026-10-26 发布快照脱敏说明：Windows 用户目录以 `%USERPROFILE%` 展示，不是当时原始命令的字面路径；版本、运行 ID、测试结果和失败记录保留。

日期：2026-10-14，Asia/Shanghai；开发基线 adfc5da。**Java17、真实独立服务/生产定时派发、小规模延迟、必要回归和启动拒绝验收均已通过，达到简历级 MVP 收尾条件。** 按授权本地提交，不 push；停止开发。第三方模型调用 0 次，不声称生产性能/HA。

## 功能范围与环境

保留 REST/状态机、MySQL幂等/事务、Rabbit确认/手动ACK/隔离、Redis Lua限流、Outbox、Worker所有权/固定期限、兼容HTTP模型适配；未扩展框架、表、模型路由或调度平台。本阶段新增一键启动/停止和真实独立服务验收脚本、只读预检、已授权的最小验收前缀过滤，并修复真实启动暴露的超时扫描锁顺序死锁。

| 环境 | 实际值 |
| --- | --- |
| OS / CPU / 内存 | Windows11 家庭版，AMD Ryzen9 8945HX，32逻辑CPU，系统识别物理内存31.3GB（不是当前空闲内存） |
| JVM / Maven | Microsoft OpenJDK17.0.16+8-LTS / Maven3.9.1；真正以该JAVA_HOME构建/运行 |
| Java17路径 | %USERPROFILE%/.jdks/ms-17.0.16，无安装/系统设置变更 |
| MySQL | 8.0.45，ai_task_platform，业务账号仅SELECT/INSERT/UPDATE |
| Docker / Rabbit / Redis | Docker29.8.1，P1 Rabbit4.3.6、Redis容器健康；Redis本轮关闭，复用历史跨JVM证据 |
| 应用限制 | -Xms128m/-Xmx256m；Outbox轮询1000ms、batch20；Worker固定期限60000ms；Mock模型 |

P2 mysql-erp/rag-demo/pgvector 与现有卷保留，没有容器/队列/数据清理。001/002/003由用户此前人工执行，本轮没有DDL/新表或旧数据更新。

## 只读预检与隔离

16:15:30 初次只读预检：旧PENDING=31，旧NULL/NULL RUNNING=16，带所有权RUNNING=0，未派发Outbox=2，其中关联已终态任务=2；globalScanSafe=false。即使消费者会跳过终态，扫描仍会修改旧事件，不能贸然启动全库派发。预检读取元数据/计数、字段及CHECK、最小权限，没有读取输入正文。

按用户随后明确授权，新增 TASK_ACCEPTANCE_KEY_PREFIX：默认空（正常行为不变），显式验收时只允许ASCII字面前缀、冒号结尾、禁止LIKE通配符，要求mysql。过滤既有Outbox候选和超时扫描；使用随机键前缀及独立队列。API并不限制前缀，所以不要把此配置用于普通业务或当作租户权限；前缀外提交会滞留。共享旧测试库演示必须限定范围，全新独立库审核为空后才可全库扫描。

真实驱动在应用启动前/完成后对前缀外任务与事件的ID、status、version、时间/attempts等元数据进行SHA-256比较，结果一致；不是声称核验每个业务字段。没有复用测试专用Bean、移除Scheduled或手工调用Dispatcher。

## 自动测试与失败定位

- 16:15:24，修改前在真实JDK17完整verify：146项通过；证据 target/phase5-jdk17/9a44740864a84502a62bb80cd5e6c39e/。
- 最终代码在JDK17完整verify：**147 tests，0 failures/errors/skipped**。保留原146，新增仅1项验收前缀拒绝通配符/不能静默作用于memory的配置测试。证据 target/phase5-runs/638f25cf34344b2ea853b760b8178785/verify.log、build/surefire-reports/JAR。
- 独立memory/Mock JDK17 JAR进程（PID53836、3612及最终脚本校验54576）通过smoke：成功、失败、幂等复用、输入冲突、校验和查询；已停止。新的start/stop脚本检查端口/就绪及PID/开始时间/实际JAR命令，不停止其他项目。最终stop的只读CIM查询在默认沙箱被拒绝，随后按授权使用本机执行权限完成身份校验和停止；没有绕过身份检查或修改系统设置。
- 唯一新增生产启动拒绝路径：model.mode=invalid，独立JDK17退出1，理由明确为模式非法，无fallback。证据成功目录 startup-failure.log、startup-failure-result.json。

初次Windows安全输入续跑被java -version的stderr/PowerShell ErrorAction机制阻断，未启动持久化业务；已修复。随后两次真实调度尝试发现COMPLETION_UNKNOWN，第二次脱敏诊断明确 **MySQL deadlock SQLSTATE40001/vendor1213**。日志/失败保留于 target/phase5-runs/6d275595fd324fca9480c29af6c4d04d 和 d93652fe722942ee8d3da115765500f5，没有伪装通过或清理。

原因：扫描UPDATE可能先锁二级索引后等主键，Worker先持有主键再更新索引。修复 JdbcTaskRepository.expireRunning：无锁读取有限过期候选，再按主键逐条条件UPDATE；重新校验RUNNING/token/deadline/前缀，batch仍受限；没有模型重试、续期、SKIP LOCKED框架或分布式锁。TaskWorker仅补sqlState/vendorCode/type脱敏诊断，不保存原异常/SQL/输入。最终生产批次通过；这不证明所有数据库死锁永不发生，真实存储故障仍失败/隔离。

## 独立 JVM 真实定时链路

成功驱动：scripts/verify-phase5.ps1 → Java17 source acceptance driver → **独立可执行生产JAR**。生产环境MySQL/rabbit/outbox/执行期限均开启，Model=Mock，真实ScheduledAnnotationBeanPostProcessor保持开启。日志为 scheduling-1 自动派发，消息实际通过P1 Broker进入原Consumer/Worker。

| 检查 | 实际结果 |
| --- | --- |
| 业务run / JVM / HTTP | 94d8afaa-61e3-4aa1-9516-92c75fb229d4 / PID46232 / 60736 |
| 前缀 | phase5:94d8afaa-61e3-4aa1-9516-92c75fb229d4: |
| HTTP新受理 | 25次202：24正常、1 [MOCK_FAIL] |
| 生产定时派发 | 25个SENT；无需驱动调用Dispatcher |
| 任务终态 | 24 SUCCESS/v2、1 FAILED/MODEL_CALL_FAILED/v2，完成时间非NULL，所有权字段清空 |
| 幂等/查询 | 同键同输入200且相同UUID，不同输入409；成功结果200、失败结果409 |
| 超时数量 | 工作负载0；没有人为将小规模Mock请求称作超时压力测试 |
| 历史元数据 | 比较一致，旧任务/事件未处理；成功和失败批次均只使用各自随机记录 |
| 模型证据 | Mock业务闭环；本阶段没有本地HTTP模型链路重跑或第三方模型调用 |

示例 firstTask=0b86e820-b0d3-4381-b786-5a5133047dc5；failureTask=3a89e64a-384e-4c8e-a1c2-d4d44e87aea9。日志/原始CSV/result.json/summary.json在 `target/phase5-runs/638f25cf34344b2ea853b760b8178785/`，最新 `target/phase5-latest.json` passed=true。所有产物Git忽略。

两次失败批次共新建4任务/4事件并保留；成功批次25/25。失败的COMPLETION_UNKNOWN消息及未完成行保留人工审核，不重跑它们的模型；新的验收前缀也不回扫旧失败批次。JVM均已停止，队列/事件/数据不删除。

## 超时 SQL 的必要真实回归

因修改expiry SQL，复用已有 TaskExecutionLeaseIT（只运行既有1项，未扩测试）。**16:43:03 JDK17/真实MySQL通过，0失败/错误/跳过**，新业务run=a2149d8b-f841-40ca-9b80-a38e0d34ef68，独立连接50/51。正常成功1，timeoutChanged=1、另一个处理者=0，终态version2；错误token、自然过期后的完成、旧Worker覆盖均拒绝。测试新建NULL/NULL RUNNING被忽略，不修改真正遗留记录。

保留3新任务/2事件；原始证据 target/phase5-lease-regression/runner.log 和成功build/failsafe-reports。没有调用模型或清理数据。与成功批次相加，本阶段所有真实测试留存32任务/31事件（含失败批次），其中并非全部成功；不能隐去失败记录或宣称全库任务都已恢复。

## 有限性能口径

无独立预热，先8个顺序请求，再16个请求/4个客户端线程；每个客户端提交后轮询到终态和结果，不是纯入队吞吐压测。147测试中模拟错误的日志不计为工作负载业务失败。

| 分组 | n / 并发 | 受理p50/p95(ms) | 观察到完成p50/p95(ms) |
| --- | --- | --- | --- |
| 顺序 | 8 / 1 | 5.814 / 64.777 | 1013.688 / 1029.149 |
| 并发 | 16 / 4 | 4.799 / 10.447 | 1042.901 / 1082.065 |

采样用System.nanoTime，HTTP受理=POST开始到202响应，观察完成=POST开始到轮询终态并取结果；轮询25ms，包含GET/客户端等待成本。百分位为nearest-rank；顺序p95为8个样本最大值，含冷启动后首次受理，样本不足以推导服务器容量。主要完成延迟体现默认1s Outbox轮询；不编造QPS/生产吞吐、线上规模或收益。原始samples.csv可复核。

## 部署、复盘与能力边界

README包含真实架构Mermaid、API、模式/环境、安全启动、001/002/003顺序和限制。demo-guide含一键演示/独立部署/5分钟讲解；resume-highlights区分实现、真实基础设施、本地模拟HTTP和未调用第三方模型；project-review索引源码/原理/面试问题。

既有NULL/NULL RUNNING不自动恢复，旧PENDING不补Outbox，隔离/parking不自动重放；没有模型重试/心跳/续期/外部fencing/XXL-JOB、真实模型质量费用、Broker HA/崩溃耐久性、Kubernetes/生产监控。系统故障可能延迟超时扫描，存储完成未知可遗留RUNNING；不承诺所有错误自动恢复、外部调用取消或exactly-once。

全部凭据经本机安全输入/进程环境传入，没有DDL或数据清理。提交前119个候选文件、成功批次日志/XML/CSV/JSON的凭据特征扫描命中0，禁止产物/密钥路径0；该启发式检查结合差异审核，不声称能识别任意秘密。父进程未留下MYSQL/DB/MQ/MODEL密码或审批标志，target/.env/日志保持忽略；git diff --check及4个PowerShell入口AST通过。仅暂存本阶段19个源码/测试/脚本/文档，按授权本地提交，不push；**项目核心MVP收尾完成，停止开发，等待简历审核和集中学习**。
