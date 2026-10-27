# P1 源码公开最终审查

**状态：READY_FOR_SOURCE_PUBLICATION。分发范围：SOURCE_ONLY。**

项目：异步 AI 任务调度与执行平台 / Asynchronous AI Task Orchestration Platform。
源码基线：`master` / `8f5ddf39c9a192037ec7150915543ef41fbe8ba5`。本目录是独立 Git 根，不继承旧历史；目标新仓库为 `qianmu233/async-ai-task-platform`，本轮不创建远端、不推送、不修改原 Private 仓库可见性。

## 权利人授权与项目许可证

用户已明确确认：“P1 代码主要由我独立开发，没有未经授权复制的第三方源码，也没有学校、公司或合作者的权利限制。”用户授权其有权许可的 P1 自有源码采用 Apache License 2.0（SPDX：Apache-2.0），取代此前暂不确认许可证的决定。此结论基于权利人声明和已审源码范围，不将 Git 作者身份当成完整法律证明。

根 LICENSE 从 [Apache 官方网站](https://www.apache.org/licenses/LICENSE-2.0.txt) 经 TLS 校验取得，11358 字节，SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`；完整保留第 1–9 节与 Appendix。README、POM 和 THIRD_PARTY_NOTICES 已统一项目许可及第三方边界，未批量添加版权头，未编造版权主体。

未发现需要继承的根 NOTICE 或随本源码分发的第三方实现；不新建没有事实依据的根 NOTICE。第三方法律原文和 THIRD_PARTY_NOTICES 保留；未来若分发依赖二进制、修改组件或引入第三方源码，应另行核查并保留适用 NOTICE。

## 本轮实际验证

- Microsoft JDK 17.0.16 / Maven 3.9.1；`mvn -o -B -ntp verify`：BUILD SUCCESS，147 tests，0 failures/errors/skipped，20 份 Surefire XML；只使用既有缓存和无认证临时 settings，不改依赖版本。
- 44 份 Java 生产文件、29 份 Java 测试/支持文件、Lua 及 3 份 SQL 不改；POM 仅新增项目许可证元数据。
- 新打包结果仍为 57 个嵌套 JAR，逐项 SHA-256 与此前打包清单一致；仅用于本地核对，不进入发布清单。
- 最终白名单 130 项，比此前 129 项仅新增 LICENSE；7 项既有文件更新。清单及 SHA-256 见 [PUBLICATION_MANIFEST.json](PUBLICATION_MANIFEST.json)，清单自身不递归哈希。
- 最终 Gitleaks、路径/隐私、暂存内容一致性及 git diff --cached --check 必须通过后才执行已授权的独立根提交；脱敏结果保留于本地忽略目录 .local/publication-audit。运行产物、真实环境、缓存和私钥不暂存。

本轮未运行真实 MySQL、RabbitMQ、Redis、基础设施 IT、DDL 或付费模型，模型外部请求为 0。原有历史成功/失败记录不因本次构建重复计数或改写。

## 第三方许可与供应链验证边界

87/87 项 Maven 本地许可声明盘点保留，包含直接与传递依赖；独立官方核验仍为 **9/87**，其余 **78 项 UNVERIFIED_HTTP_ACCESS_FAILURE** 原样保留。未追加大规模网络查询，未把 404 解释成恶意、非法或已验证通过。构建插件依赖图、全部 shaded/native 子组件和漏洞数据库审查未完成。

MySQL Connector/J 9.7.0 仍适用 GPLv2 + Universal FOSS Exception；Logback 1.5.34 仍为 EPL-2.0/LGPL-2.1-only 双许可；JUnit Jupiter 5.13.4 仍为 EPL-2.0；其余依赖按逐项清单适用原许可。项目 Apache-2.0 不覆盖第三方授权。见 [依赖审查](DEPENDENCY_LICENSE_REVIEW.md) 和 [第三方说明](../THIRD_PARTY_NOTICES.md)。

本次只公开自有源码、测试、配置示例、人工 DDL、文档及必要法律原文，不分发 JAR、依赖缓存、容器镜像或第三方实现。基于该分发边界、用户权利声明和已有证据，未发现影响当前源码公开的实质性权属、许可或泄密阻塞。该就绪结论不等于全部供应链来源核验通过、二进制再分发获批、生产可靠性或绝对法律保证。

## 历史审查（授权更新前，保留失败和未验证记录）

以下“BLOCKED”“未指定许可证”“不创建提交”等文字均是此前阶段的原始结论，已由本页最终授权与 SOURCE_ONLY 决定更新；其中依赖未验证和真实基础设施未执行的事实仍然有效。

### P1 独立发布候选审查

日期：2026-10-26。**状态：BLOCKED_PUBLICATION。** 工程与安全检查可以继续，项目权利归属/许可证尚待用户确认，且完整独立供应链来源验证未完成；本轮不添加项目 LICENSE/POM 许可，不创建初始提交，不配置远端。

2026-10-27 续审依据：用户已明确“暂不确认，保留未指定许可证”。继续完成可安全执行的准备，保留原许可证声明；项目权利与许可属于明确待确认事项，不再反复请求选择。

#### 来源与白名单

源仓库 `master` / `8f5ddf39c9a192037ec7150915543ef41fbe8ba5`，开始时工作区 clean。以该提交文件为基础，在独立目录初始化新 Git；不复制旧 `.git`、旧引用、日志、数据库、缓存、本地配置或运行产物。

源跟踪文件共 120 个。排除原 `AGENTS.md` 开发指令，重建忽略和换行规则，保留全部 Java 源码、测试/显式 IT 入口、SQL、配置示例、启动/验收脚本与历史文档。仅在候选中修正文档环境路径、补充运行配置与发布/许可证据。44 份 Java 生产源码、29 份 Java 测试/支持文件及三份 SQL 与源提交逐字一致，未改依赖版本或测试断言。

完整清单、大小、SHA-256、来源摘要与差异分类见 [PUBLICATION_MANIFEST.json](PUBLICATION_MANIFEST.json)，来源边界见 [SOURCE_PROVENANCE](SOURCE_PROVENANCE.md)。没有模型权重、第三方前端制品或源代码子模块。

#### 安全与隐私

本次单独取得官方 Gitleaks v8.30.1 Windows x64，没有读取其他项目的工具或文件。ZIP 与 checksum 文件的 GitHub Release API digest 一致，ZIP 同时符合官方校验清单；工具只保留在忽略的 `.local/tools/`。未做独立签名验证，不能把摘要校验写成签名认证。

- 原始可达历史 11 个提交：0 条发现。
- 当前源仓库 120 个跟踪文件：逐文件 stdin 扫描，0 条发现；没有遍历真实 `.env`、未跟踪运行目录或其他项目。
- 2026-10-26 候选 128 个白名单文件：0 条发现；对应独立审查 index 与候选逐字一致，差异检查通过。2026-10-27 补充 1 份打包组件清单，最终 129 个白名单文件重新扫描为 0 条发现；临时审查 index 的 129 个文件与候选逐字一致，差异检查通过。实际 main 的 index 保持为空，未提交。
- Scanner 使用内置规则、`--redact=100`、忽略内联豁免，不添加 baseline/关闭规则。原始 stdout 仅在内存解析，不打印或保存 Secret/Match。

三份文档中的 4 处用户目录匹配在候选中脱敏，当前操作示例改用 `JAVA_HOME`；历史版本、运行 ID、失败与测试结果保留。手机号模式没有发现业务联系人值。非 loopback IPv4 候选分别是历史 JDK 四段版本号，以及 MockMvc 限流测试使用的 RFC 文档地址；邮箱候选来自 `example.invalid` 的故意无效 URL 负向测试，不是真实账号或凭据，保留测试断言。

宽松手机号模式在文件清单的两个 SHA-256 字段内匹配到数字子串，已按完整摘要字段确认不是号码；没有更改资源摘要或扫描规则来去除它们。当前发布文件的硬编码 Windows 用户主目录匹配为 0。最终检查还比对了源仓库全部 120 个跟踪文件原始摘要、所有 Git 引用及 remote，均与开始时一致，源仓库仍 clean/master/原 HEAD。

SQL 只有人工 DDL/迁移，没有业务 seed、联系人或聊天记录。本轮不运行 SQL、不启动基础设施、不对真实应用提交任务、不读取个人或其他项目凭据。

`.gitignore` 覆盖 target、`.local`、本地 Maven 缓存、真实环境与本地 YAML、日志、数据库文件、私钥容器和编辑器目录；必要 `.yml.example` 不受忽略。构建 JAR 与依赖缓存不纳入候选清单。

#### 构建与测试

使用实际 Microsoft JDK 17.0.16、Maven 3.9.1。子进程只保留必要系统环境，显式默认 mock/memory/local，未继承模型、数据库、Broker 或 Redis 真实凭据。临时 settings 无 server 凭据，只使用默认 HTTPS Central，不改全局配置。

| 检查 | 实际结果 |
|---|---|
| `mvn -version` | Maven 3.9.1、Java 17.0.16 |
| 初次 `mvn -o -B -ntp verify` | 缓存缺 `junit-platform-launcher:1.13.4`，Surefire 启动失败，不能算测试通过 |
| 安全补齐依赖后的 `mvn -B -ntp verify` | BUILD SUCCESS，147 tests，0 failures/errors/skipped |
| 随后的完整 `mvn -o -B -ntp verify` | BUILD SUCCESS，147 tests，0 failures/errors/skipped；20 份 Surefire XML |
| 2026-10-27 完整离线 `mvn -o -B -ntp verify` | BUILD SUCCESS，147 tests，0 failures/errors/skipped；重新打包，未重复累计测试数 |
| 完整 `maven-dependency-plugin:3.8.1:tree` JSON | BUILD SUCCESS，87 项去重后的依赖，包括 6 个直接依赖、全部解析到的传递及 test 范围 |
| 2026-10-27 固定插件离线完整依赖树 | BUILD SUCCESS；依赖与原清单逐项对账，未修改依赖版本 |
| 6 个显式基础设施 IT 类 | 未执行；不是跳过的默认单元测试，也不伪称本次真实基础设施通过 |
| 真实 MySQL/RabbitMQ/Redis、DDL、业务验收与付费模型 | 未执行；第三方模型请求 0 |

完整依赖树命令：`mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree -DoutputType=json -DoutputFile=target/publication/dependency-tree.json`。Maven settings 通过当前进程参数指定，模型和基础设施 opt-in Profile 均未启用。两个 147 项运行不是 294 个不同测试。

首次失败与后续成功日志留在本目录的 `.local/publication-audit/`，测试 XML 在 target；没有复制旧日志。当前 POM 不变，无跳过测试、修改断言或依赖降级。

#### 许可与供应链结论

87/87 项已识别本地 POM/继承链中的许可声明；其中 64 项 compile/runtime、23 项仅 test。核查相应本地制品摘要、可用 checksum sidecar 和内嵌法律文件，保存逐项证据；不是只审直接依赖。

独立官方 POM 与制品校验仅完成 9/87。剩余访问受异常 404/校验不可得阻塞，2026-10-27 的有限复核仍未解决。不能据此认定制品不存在或缓存恶意，更不能记作全部外部校验通过。本轮补充 57 个实际打包 JAR 对账：56 个匹配项目依赖摘要，1 个注入工具只有本地 Manifest/法律文件证据、未独立认证。构建插件依赖图、全部 shaded/native 子组件与漏洞数据库审查仍未完成；本候选不分发二进制。

MySQL Connector/J 为 GPLv2＋Universal FOSS Exception，不是 Apache/MIT；Logback 双许可、Jakarta 与 JUnit 的适用条款各自保留。逐项证据与边界见 [DEPENDENCY_LICENSE_REVIEW](DEPENDENCY_LICENSE_REVIEW.md) 和 [第三方说明](../THIRD_PARTY_NOTICES.md)。

项目源码权利仍需确认学校/雇主/共同作者/未声明复制代码等限制，单一 Git 作者身份不替代授权。依据用户 2026-10-27 的明确选择，保留未指定许可，不添加 LICENSE 或变更项目许可声明，不虚构版权所有者，**不建立本地初始提交，也不声称具备公开条件**。

#### 后续门槛

1. 用户确认源码许可权利并选择项目许可证；再核对最终 LICENSE/POM 元数据及必要声明。
2. 补齐或明确处置 78 项外部来源校验缺口；需要二进制分发时另审合并组件与相应义务。
3. 对最终许可后的候选再次执行扫描、清单与差异检查，无关键阻塞后才创建新根提交；上传和 GitHub 操作仍须单独授权。

原仓库的源码、README、分支、提交与远端配置不作为本次修改对象。原始内容、引用及 remote 的最终比对结果保存在本地审查元数据中；P2/P3/P4 未访问。
