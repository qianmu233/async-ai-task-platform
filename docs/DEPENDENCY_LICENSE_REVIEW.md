# Maven 依赖与许可证审查

日期：2026-10-26。POM 依赖版本不变：JDK 17、Spring Boot 3.5.16、JUnit Jupiter 5.13.4。使用临时、无认证内容的 Maven settings，保持 HTTPS/TLS 校验，不读取或修改全局 settings。

历史阶段记录（已被本次最终授权取代）：2026-10-27 续审：用户明确保留未指定项目许可证；已重新通过离线完整构建及依赖树解析，不改 POM、项目许可证声明或任何依赖版本。

## 完整解析范围

固定 `maven-dependency-plugin:3.8.1:tree`，不设置 scope 过滤，导出实际解析树；按 groupId/artifactId/version/classifier 去重，**87 项**，包含 6 个直接依赖及其传递依赖。64 项属于 compile/runtime，23 项仅 test。未把重复树节点或不同日期的测试数量相加。

- [完整依赖树](third-party/resolved-dependency-tree.json)
- [逐制品 JSON](third-party/dependency-inventory.json)：版本、scope、PURL、许可声明、继承 POM 链、POM/JAR 摘要、内嵌法律文件身份和验证状态。
- [CSV 清单](third-party/dependency-inventory.csv)：便于逐项复核。
- [实际打包组件清单](third-party/packaged-runtime-inventory.json)：2026-10-27 新构建应用内的 57 个嵌套 JAR，逐项摘要与身份/法律文件证据。

87 项均从实际本地解析的 POM（必要时沿 parent 继承）找到许可证声明，并检查 JAR 中可识别的 LICENSE/NOTICE/COPYING 文件及其摘要。POM 多个许可条目原样记录，**不自动把它们解释为全部必须履行或任意可选**。

## 外部验证限制

本次额外向 HTTPS Maven Central 读取官方 POM 和制品校验文件：仅 **9/87** 完成官方 POM 与本地文件一致、官方制品校验一致的完整核对。其余 78 项遇到官方端点 404 或校验文件不可取得；对两个官方 Central 主机的只读诊断及受控网络权限复核仍无法完成。没有关闭 TLS 校验、改用 HTTP、换版本或把缓存声明冒称在线已验证。

因此：**依赖解析/本地许可声明覆盖为 87/87；独立官方制品验证覆盖为 9/87**。CSV/JSON 对后者标记 `UNVERIFIED_HTTP_ACCESS_FAILURE`。本地 checksum sidecar 比较是完整性线索，不是独立来源认证。失败元数据留在本机忽略目录；不能把这些未完成项记作通过。

2026-10-27 对两个官方 Central 主机、普通/Maven User-Agent、POM 与 JAR 做了一次有限只读复核，仍为异常 404，没有关闭证书校验或转用未知镜像。独立验证覆盖仍为 9/87，不重复无界请求。

## 打包范围对账

新构建应用的 `BOOT-INF/lib/` 有 57 个 JAR：56 个与既有项目依赖清单的完整 SHA-256 一致，另 1 个为打包注入的 `spring-boot-jarmode-tools-3.5.16.jar`。64 个 compile/runtime 坐标中有 8 个 starter 未打入应用，不能把声明数量直接当成实际二进制组件数。

注入组件没有嵌入 Maven POM/coordinates，因此只记录其文件名与 Manifest 的模块 `spring.boot.jarmode.tools`、Implementation-Version 3.5.16，以及内嵌 LICENSE/NOTICE 摘要；来源标记 `LOCAL_MANIFEST_AND_FILENAME_ONLY`，外部状态 `NOT_INDEPENDENTLY_VERIFIED`。这不是官方制品身份认证，也未将它计入原 87 项的外部核验成功数。

该清单补充了实际打包边界，但仍不是完整 SPDX/CycloneDX SBOM，不覆盖全部 shaded/native 子组件或构建插件依赖图。打包应用依旧只在忽略的 target 中，不作为待发布源码文件。

## 需特别注意的许可

| 依赖 | 本次识别的许可与依据 | 发布注意事项 |
|---|---|---|
| Spring/多数基础依赖 | 各 POM 的 Apache-2.0 声明 | 保留适用许可/NOTICE；不能覆盖其他依赖 |
| `com.mysql:mysql-connector-j:9.7.0` | GPLv2 + Universal FOSS Exception 1.0；POM、JAR LICENSE、[官方固定版本 LICENSE](https://github.com/mysql/mysql-connector-j/blob/9.7.0/LICENSE) | 不可标为 Apache/MIT。FOSS 例外有条件，不能据此宣称任意闭源或未授权组合均可分发；分发含该库的 JAR/镜像须另核完整条款 |
| `ch.qos.logback:logback-classic/core:1.5.34` | EPL-2.0 / LGPL-2.1-only，官方明确双许可 | 按选择及分发形式履行相应义务，不改依赖代码，也不将其重新许可为项目许可 |
| JUnit 5.13.4 / Platform 1.13.4 | EPL-2.0；仅测试 | 测试依赖与生产制品范围区分 |
| Jakarta Annotation | EPL 2.0 / GPL2 with Classpath Exception 声明 | 保留具体选项与例外，不笼统说所有 Jakarta 都同一许可 |
| Jakarta Activation / JAXB | EDL 1.0 声明 | 按各自 POM/JAR 法律文件核查 |
| 其余 MIT、MIT-0、BSD、JSR cache 多许可项 | 逐项保留在清单中 | 不通过根许可证覆盖这些条款；MIT 与 MIT-0 分开 |

官方说明：[MySQL Universal FOSS Exception](https://oss.oracle.com/licenses/universal-foss-exception/)、[Logback 双许可证](https://logback.qos.ch/license.html)。从本机实际 MySQL JAR 提取的原法律全文见 [第三方法律文件](third-party/licenses/)，保留其版权、例外及附带第三方条款，不编辑或删节。

## 项目许可证最终决定

用户已正式确认 P1 主要由其独立开发，没有未经授权复制的第三方源码，也没有学校、公司或合作者的权利限制，并授权对其有权授权的自有源码采用 Apache-2.0。根 LICENSE、README 和 POM 已按该决定统一；此确认来自权利人声明，不是从 Git 作者信息推导的法律保证。

此前“未指定许可证、不添加 LICENSE”的决定作为历史保留，已被本次明确授权替代。Apache-2.0 不替代任何第三方组件的许可。当前仅公开本项目源码及必要法律文本，不交付第三方实现、JAR、依赖缓存或容器镜像；未发现阻碍该源码公开范围的具体第三方许可冲突。9/87 独立核验、78 项未验证仍按原清单保留；HTTP 404 不是恶意或违法证据，也不是验证通过。未来二进制再分发须另审。

独立源码与依赖声明不等于重新分发所有依赖二进制的许可结论。当前候选不包含依赖 JAR、Maven 缓存、构建应用或容器；本清单也不声称完成 build-plugin 依赖图、JAR 内嵌/合并组件的逐组件 SBOM、漏洞数据库或未来二进制分发的全部法律审查。

最终状态与阻塞见 [发布审查](PUBLICATION_REVIEW.md)。
