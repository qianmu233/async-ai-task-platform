# Phase 2A 验收报告：代码与离线验证

日期：**2026-10-12，Asia/Shanghai**。基线提交：`a99313a`，开始时工作区干净。本轮仅修改 `E:\codeplace\llm-agent\ai-task-platform`；不修改参考 XXL-JOB、P2 RAG-Agent 或原搜广推工程，不 Git commit/push。

**结论：MySQL 持久化、数据库唯一键幂等、状态条件更新的代码已实现；原有 44 项测试保留并通过，新增 43 项离线测试，总计 87 项通过。没有连接真实 MySQL，也没有执行 DDL/迁移。**

## 1. 修改与新增文件

修改 14 个文件：

| 文件 | 修改目的 |
| --- | --- |
| `.gitignore` | 忽略本地 mysql/local YAML、config 属性文件、环境秘密与私钥 |
| `README.md` | 更新 Phase 2A 进度、规范化、存储切换、错误与恢复边界 |
| `pom.xml` | 增加 Spring JDBC starter 和 MySQL runtime 驱动，不改变既有框架/JUnit/Java 目标 |
| `config/application-mysql.yml.example` | 提供可启用的 MySQL 模式与外部凭据模板 |
| `database/001_init.sql` | 在原脚本上增加 normalization_version 与 CHECK，注明不自动迁移 |
| `docs/persistence-design.md` | 记录实际实现、事务、CAS、幂等与故障语义 |
| `src/main/resources/application.yml` | 默认 memory 模式及 MySQL 环境变量映射 |
| `src/main/java/com/example/aitask/AiTaskPlatformApplication.java` | 排除 DataSource 自动配置与 SQL 自动初始化 |
| `src/main/java/com/example/aitask/api/ApiExceptionHandler.java` | 存储/事务错误 503 与状态竞争 409 的安全响应 |
| `src/main/java/com/example/aitask/domain/TextTask.java` | 统一结果容量/Unicode、错误码校验 |
| `src/main/java/com/example/aitask/repository/InMemoryTaskRepository.java` | 保留内存实现，共享规范化与显式预期状态更新 |
| `src/main/java/com/example/aitask/repository/TaskRepository.java` | 增加预期状态失败写入，保留原 fail(id, code) 调用方式 |
| `src/main/java/com/example/aitask/service/TaskInputRules.java` | NFC/LF 规范化与有效 Unicode 验证 |
| `src/main/java/com/example/aitask/service/TaskService.java` | 提交规范化、认领失败跳过模型、分离模型失败与存储失败 |

新增 10 个文件：

| 文件 | 用途 |
| --- | --- |
| `src/main/java/com/example/aitask/config/StorageConfiguration.java` | 显式存储模式、固定业务库 URL、延迟连接池、JdbcTemplate 与事务管理器 |
| `src/main/java/com/example/aitask/repository/JdbcTaskRepository.java` | MySQL INSERT/查询/条件更新实现 |
| `src/main/java/com/example/aitask/repository/TaskStateConflictException.java` | 明确状态竞争结果 |
| `src/test/java/com/example/aitask/repository/OfflineJdbcFixture.java` | 离线 SQL/事务替身，不是真实数据库 |
| `src/test/java/com/example/aitask/repository/JdbcTaskRepositoryOfflineTest.java` | 幂等、竞争、映射、事务流程及错误翻译测试 |
| `src/test/java/com/example/aitask/config/StorageConfigurationOfflineTest.java` | 两种模式、错误配置和禁止自动建表的离线测试 |
| `src/test/java/com/example/aitask/service/TaskPersistenceSemanticsTest.java` | 规范化、重复 Runnable、存储/事务失败及提交不确定性测试 |
| `src/test/java/com/example/aitask/api/StorageErrorApiTest.java` | 存储/事务错误 503、SQL 内容不泄漏与禁止错误调度 |
| `docs/mysql-integration-plan.md` | 后续人工初始化与真实单实例/多实例验证方案 |
| `docs/phase2a-verification.md` | 本报告 |

原有 TaskController、MockModelClient、TaskStatus 和五个原测试文件没有修改。REST 路径和成功/失败响应结构保留；新增存储错误响应，不将存储失败错误解释成 404 或模型失败。

## 2. 持久化方案与约束

目标 MySQL 8.0.16+，独立库 `ai_task_platform`，InnoDB 单表 `ai_text_task`。UUID 主键、大小写敏感 ASCII 幂等键唯一索引、`(status, created_at)` 索引，以及状态/规范化版本/字段生命周期/非负更新计数 CHECK。

输入规范化版本 NFC_LF_V1：Unicode NFC、CRLF/CR -> LF，保留空格与大小写。存储规范化文本，Java 精确比较，不使用数据库输入列的忽略大小写/重音排序规则比较。时间为 UTC DATETIME(6)，结果/失败原因/完成时间与终态一并写入。

运行账号/密码只由环境变量或被忽略的本地文件提供，没有真实凭据写入源码。URL 由主机/端口与固定库名生成，不能传入 `xxl_job` URL；没有缺失配置或故障时的内存降级。具体权限仍需用户在真实服务器上人工配置和验收。

## 3. 幂等与任务认领原理

- 先 INSERT，由唯一索引仲裁；不采用“先查再插”。失败插入事务结束后，仅对 DuplicateKeyException 开启新只读事务按键回查，比较规范化输入；相同返回原任务，不同 409，找不到同键任务则保留原 SQL 错误。
- 所有 Repository 事务为 REQUIRES_NEW / READ_COMMITTED；认领提交后才调用模型，结果另开短事务，模型不占用数据库事务。
- 认领条件 `WHERE task_id=? AND status='PENDING'`；受影响行数必须为 1。认领失败的 Worker 不调用模型。
- SUCCESS 仅从 RUNNING 写入；FAILED 显式指定 PENDING 或 RUNNING 为预期状态，终态不能被覆盖。
- version 每次状态变更 +1，未作为 WHERE 中的乐观版本或 fencing token，不具备重试后的 Worker 所有权保障。
- 数据库/事务错误对 API 返回 503；模型成功后落库失败不会再改写 MODEL_CALL_FAILED。真正的提交结果可能不确定，需后续查询，不自动重跑。

这是已实现的数据库并发控制代码，真实 InnoDB/跨实例效果仍未验收。[实现与事务依据](persistence-design.md)。

## 4. 实际测试命令与结果

实际使用 JDK **25.0.4.1**、Maven **3.9.1**，Java 编译目标 **17**。最终运行命令为：

```powershell
mvn -B -ntp verify
```

最终一次执行将输出保存在 `target/phase2a-build.log`，等价实际命令：

```powershell
mvn -B -ntp verify *> target/phase2a-build.log
```

于 **2026-10-12 17:18:40 +08:00** 完成，进程退出码 **0**：

```text
Tests run: 87, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 数量 | 结果 |
| --- | ---: | --- |
| 原 TaskStatusTest | 17 | 全通过 |
| 原 TaskServiceTest | 19 | 全通过 |
| 原 TaskControllerTest | 5 | 全通过 |
| 原 ExecutionConfigurationTest | 2 | 全通过 |
| 原 ApplicationTest | 1 | 全通过 |
| 新 JdbcTaskRepositoryOfflineTest | 18 | 全通过 |
| 新 StorageConfigurationOfflineTest | 6 | 全通过 |
| 新 TaskPersistenceSemanticsTest | 13 | 全通过 |
| 新 StorageErrorApiTest | 6 | 全通过 |
| 总计 | 87 | 0 失败 / 0 错误 / 0 跳过 |

首轮增加 JDBC 后也单独跑过原 44 项，全部通过；最终原 44 项仍在同一次 87 项运行中通过，没有删除/改写旧断言。日志中故意模拟故障的 ERROR/WARN，以及预期配置拒绝的 Spring WARN，不是测试失败。

报告位于 `target/surefire-reports/`。实际测试 classpath 核对为 JUnit Jupiter **5.13.4**、Spring JDBC **6.2.19**、HikariCP **6.3.3**、MySQL Connector/J **9.7.0**；后三项由既有 Boot 3.5.16 BOM 管理。[Connector/J 9.7.0 官方发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html)明确支持 MySQL Server 8.0 及以上，但本轮没有验证实际连接。

另执行 `javap -verbose target/classes/com/example/aitask/AiTaskPlatformApplication.class`，输出 `major version: 61`，对应 Java 17 字节码。仍未在真实 JDK 17 安装上运行，不扩展兼容性结论。

执行 `git diff --check` 无空白错误；`git check-ignore` 确认 mysql/local 本地配置、额外 secrets YAML、`.env` 被忽略，提交用 `.yml.example` 模板未被忽略。没有本地真实配置或凭据新文件。

## 5. 离线测试的证据边界

OfflineJdbcFixture 使用限定 SQL 替身和 Java 锁模拟唯一性/状态条件更新/事务回滚，多个 JdbcTaskRepository 对象共享此替身，针对并发分支、终态保护和错误处理验证契约。**它不是真实 MySQL，不证明 SQL 语法、行锁、隔离、驱动编码、死锁或跨进程执行效果；也没有使用 H2 代替 MySQL。**

配置测试只构造延迟 HikariDataSource，并确认 `isRunning=false`，从不调用 getConnection；默认内存模式不创建 DataSource。mysql 模式下即便配置 `spring.sql.init.mode=always`，也没有 SQL 初始化 Bean。完整应用装配测试仍为 memory 模式，没有真实数据库依赖。

## 6. 人工数据库初始化命令（本轮未执行）

用户审核后，用管理员账号人工进入 MySQL 客户端，密码交互输入：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=root --password
```

客户端执行：

```sql
SOURCE E:/codeplace/llm-agent/ai-task-platform/database/001_init.sql;
SHOW CREATE TABLE ai_task_platform.ai_text_task;
SHOW INDEX FROM ai_task_platform.ai_text_task;
```

IF NOT EXISTS 不能升级已有旧表；存在旧表时先人工比对结构，另行审核迁移。本轮未运行 ALTER/DROP/CREATE，也未自动建库。专用账号创建和业务库最小授权步骤见 [真实 MySQL 集成计划](mysql-integration-plan.md)。

## 7. 真实 MySQL 验证与待验证风险

审核后再按 [集成计划](mysql-integration-plan.md) 进行：人工初始化、最小权限校验、mysql 模式基础 HTTP、停机重启后复用、两个实例共享同库的提交竞争、真实连接上的认领/完成竞争、事务/死锁/断连故障、Unicode 和 UTC 驱动映射。

没有现成自动连库的 Maven profile，也没有真实集成测试成功记录。CHECK 生效、SQL 兼容性、Connector/J 与本地 MySQL 的认证/TLS/时区、死锁/锁等待、提交不确定性和服务器耐久性设置都仍需真实验证。

## 8. 尚未实现及停止位置

- 持久化调度队列/outbox 和启动恢复扫描；滞留 PENDING 不会自动执行。
- RUNNING 租约、心跳、执行超时、attempt 与 fencing；认领后崩溃或落库失败可能悬挂。
- 自动重试、退避、数据库事务重试、任务清理、认证、租户隔离和生产监控。
- 模型调用 exactly-once；新幂等键可创建另一任务，人工重置 RUNNING 可能造成重复执行。
- 真实 MySQL 集成测试及跨实例验收；本轮不把离线替身视为这些能力已经通过。

不接入 RabbitMQ、Redis、XXL-JOB 或真实收费模型。本轮代码及离线测试已完成，停止并等待用户审核，再开展真实数据库初始化与集成测试。
