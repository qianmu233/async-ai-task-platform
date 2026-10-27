# Phase 1 实际验证记录

验证日期：2026-10-12，Asia/Shanghai。工作目录：`E:\codeplace\llm-agent\ai-task-platform`。最终依赖组合为 Spring Boot **3.5.16**、Spring Framework **6.2.19**、JUnit Jupiter **5.13.4** / JUnit Platform **1.13.4**。

## 构建与测试

实际执行：

```powershell
mvn -B -ntp clean verify
```

使用 Oracle JDK **25.0.4.1**、Maven **3.9.1**。最终构建于 **15:51:56 +08:00** 完成，退出码为 **0**：

```text
Compiling 13 source files with javac [debug parameters release 17]
Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 数量 | 主要验证 |
| --- | ---: | --- |
| TaskStatusTest | 17 | 四种状态的全部 16 对转换；终态字段和不可变快照 |
| TaskServiceTest | 19 | 参数边界；同键复用和冲突；32 个并发重复请求只调度一次；Mock/无效输出失败；执行拒绝；缺失任务 |
| TaskControllerTest | 5 | 提交与复用响应；状态/结果查询；业务错误码；400/404/409/503 |
| ExecutionConfigurationTest | 2 | 有界队列拒绝；错误配置拒绝 |
| ApplicationTest | 1 | 无数据库情况下真实 Spring 组件装配 |

报告保存在 `target/surefire-reports/`（构建产物，不纳入 Git）。读取最终报告 XML 的 `java.class.path`，确认实际执行使用的是 `junit-jupiter-api-5.13.4.jar`、`junit-jupiter-engine-5.13.4.jar` 和 `junit-platform-engine-1.13.4.jar`。

另实际执行：

```powershell
javap -verbose target/classes/com/example/aitask/AiTaskPlatformApplication.class
```

输出 `major version: 61`，对应 Java 17 字节码。已验证 JDK 25 编译与运行；未在独立 JDK 17 安装上启动，不声称已覆盖所有 JDK 17 运行时行为。

本机 Maven 3.9.1 在 JDK 25 下有 JANSI native access 和旧 Guava Unsafe 警告，未导致最终构建失败。下载缺失依赖需要写入本机已有 Maven 缓存，已通过工具的自动权限审核完成；没有更改用户 Maven 配置或 JDK 安装。

## 打包应用与真实 HTTP

实际启动的是最终可执行 JAR：

```powershell
java -jar target/ai-task-platform-0.1.0-SNAPSHOT.jar --debug=false --server.port=18081
```

启动日志确认 Spring Boot **3.5.16**、Java **25.0.4.1**、Tomcat **10.1.55**，在 `127.0.0.1:18081` 正常监听。默认端口仍为 8081，验证时仅用命令行覆盖。

另一个终端实际执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-test.ps1 -BaseUrl http://127.0.0.1:18081
```

退出码 **0**，输出 `SMOKE PASS`。实际响应：

| 场景 | HTTP / 检查 |
| --- | --- |
| 新任务提交 | 202 |
| 同键同输入再次提交 | 200；任务 ID 相同，created=false |
| 成功任务状态 | 200 / SUCCESS |
| 成功结果 | 200 / `[MOCK] hello platform` |
| 同键不同输入 | 409 |
| 空白输入 / 未知 JSON 字段 | 400 / 400 |
| 不存在的 UUID / 不合法 UUID | 404 / 400 |
| `[MOCK_FAIL]` 提交 | 202，随后状态 FAILED |
| 失败结果查询 | 409 / MODEL_CALL_FAILED |

此轮成功任务 ID：`f7a1c31f-3282-4999-9c2b-5a93aaa9785b`；失败任务 ID：`bbf8d6b9-37c0-4107-a9b0-2fc227a00c5f`。它们是验证进程的临时内存数据，停止进程后不再可查询。验证进程已停止。

HTTP 验证不是压力测试；并发重复提交测试仅验证业务原子性，不代表吞吐量或生产负载能力。

## 未验证或未实现

未连接、创建、删除任何 MySQL 数据库，未执行 SQL 初始化脚本；SQL 仍需要后续真实 MySQL 集成验证。没有数据库 Repository、持久化任务队列、跨实例幂等、自动重试、超时、租约、崩溃恢复、过期清理、认证或生产监控。没有接入 XXL-JOB、消息中间件、Redis、Sentinel 或真实模型。

本轮没有修改参考项目或其他业务工程，也没有 Git commit/push。当前仓库原先只有 `.git` 且无提交，Phase 1 文件处于待审核状态。
