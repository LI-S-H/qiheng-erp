# ERP State 管理的最小实现

日期：2026-10-14。本文结合 DataAgent、DeepResearch、AssistantAgent 内部实现，以及 ERP 当前固定的 SAA 1.1.2.0 说明如何管理 State。配套 [可运行示例](./examples/ai-state-demo/README.md) 已完成编译和运行；它是隔离的学习工程，不接入 ERP 生产流程。

## 1. 先收敛实现规模

不需要先开发独立状态管理框架。使用已有的 **OverAllState + 字段更新策略 + Node 返回更新 + 官方 Saver**，应用负责定义业务字段、允许的状态变化和任务归属。

之前的 TaskState 是完整业务设计名称，不要求另写一个能替代 OverAllState 的容器或给每个节点配置一个 StateManager。首版直接注册少量顶层 key，复杂字段使用可序列化 Map/List 或明确 DTO，统一读取入口即可。

## 2. 三个项目内部怎样管理

| 项目 | 实际代码方式 | 可以借鉴什么 |
| --- | --- | --- |
| DataAgent | DataAgentConfiguration 的 KeyStrategyFactory 集中注册输入、规划、当前步骤、SQL/Python 输出、重试、Human 等字段，主要使用 REPLACE；Node 从 OverAllState 读值并返回 Map 更新；Dispatcher 读路由字段 | 集中字段定义，按步骤更新，不把整份上下文返回给模型 |
| DeepResearch | DeepResearchConfiguration 注册 query、current_plan、observations、各执行器结果和 next_node 等字段的 ReplaceStrategy；节点读 state，返回更新；条件边读取 next_node | 计划、结果和路由分别保存，Node 与条件边分工 |
| AssistantAgent | CodeactAgent 基于 ReactAgent；Evaluation 图显式定义状态字段和策略；代码会话等模块也有应用侧状态更新 | Agent messages、评估状态、运行时上下文分开，不能假定每个模块完全使用同一更新方式 |

DataAgent 当前配置默认官方 MysqlSaver，也可选 MemorySaver，编译配置在 Human 节点前设置中断；会话 ChatMemory 是另外的组件。ERP 当前已有官方 RedisSaver，学习和实施阶段可以继续沿用接口，不需要为了模仿上游立即改数据库。

依据：[DataAgentConfiguration](https://github.com/spring-ai-alibaba/DataAgent/blob/main/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)、[DeepResearchConfiguration](https://github.com/spring-ai-alibaba/deepresearch/blob/main/src/main/java/com/alibaba/cloud/ai/example/deepresearch/config/DeepResearchConfiguration.java)。

AssistantAgent 的具体区别也值得保留：`CodeactAgent extends ReactAgent`，沿用父框架的模型/工具循环；`EvaluationSuiteBuilder` 为每个评估项注册 `_result/_status/_completed/_value/_error` 的 ReplaceStrategy，节点返回更新；评估 Hook 将父 State 转成评估输入，再把有限结果返回父状态。其 `SessionCodeManager` 则直接调用 state.updateState 保存可序列化代码 Map，因此不能概括为“项目中所有地方都只返回 patch”。ERP 的普通节点仍建议统一返回 patch，特别是未来并发执行器。

源码：[CodeactAgent](https://github.com/spring-ai-alibaba/AssistantAgent/blob/main/assistant-agent-autoconfigure/src/main/java/com/alibaba/assistant/agent/autoconfigure/CodeactAgent.java)、[EvaluationSuiteBuilder](https://github.com/spring-ai-alibaba/AssistantAgent/blob/main/assistant-agent-evaluation/src/main/java/com/alibaba/assistant/agent/evaluation/builder/EvaluationSuiteBuilder.java)、[BeforeAgentEvaluationHook](https://github.com/spring-ai-alibaba/AssistantAgent/blob/main/assistant-agent-extensions/src/main/java/com/alibaba/assistant/agent/extension/evaluation/hook/BeforeAgentEvaluationHook.java)、[SessionCodeManager](https://github.com/spring-ai-alibaba/AssistantAgent/blob/main/assistant-agent-core/src/main/java/com/alibaba/assistant/agent/core/context/SessionCodeManager.java)。

上游 main 的配置不等于 ERP 依赖版本的行为。下面的示例专门固定并验证 SAA 1.1.2.0。

## 3. 你负责什么，框架负责什么

| 你定义的内容 | 框架承担的内容 |
| --- | --- |
| 有哪些业务字段、数据类型和默认值 | OverAllState 承载运行数据 |
| 哪个节点读取/更新哪些字段 | 合并 Node 返回的更新 |
| 每个字段替换还是追加 | 根据 KeyStrategy 执行合并 |
| 什么时候追问、执行、完成或失败 | 执行节点及条件边 |
| 当前任务身份、权限、预算和工具幂等 | 使用配置的 Saver 保存/读取检查点 |
| 任务与 thread/checkpoint 的关联、保留期限 | 保存图状态及下一执行位置 |

Saver 不替你定义业务合法性，也不提供数据库写操作的 exactly-once 保证。框架保存的是稳定节点边界的进度，节点内部发生业务提交而检查点未保存时，需要真实工具幂等与回执查证。

## 4. 首版先用八个字段

| 字段 | 类型 | 更新策略 | 谁负责更新 |
| --- | --- | --- | --- |
| taskId | String | Replace | 应用入口初始化；节点不改 |
| request | String | Replace | 应用入口保存原需求 |
| parameters | Map | Replace | 参数提取/人工补充入口 |
| plan | Map 或 Plan DTO | Replace | Planner |
| result | Map 或 TaskResult DTO | Replace | 执行节点 |
| status | String/枚举 | Replace | 检查、执行、输出及错误处理阶段 |
| question | String | Replace | 信息检查；补齐后清空 |
| trace | List<String> | Append | 仅用于示例观察节点执行顺序 |

trace 不是生产审计日志设计；真正的长轨迹独立归档，不能无限增长。第一阶段单任务、串行执行；后续多任务再加入 planVersion、tasks/results、预算和产物引用。

字段常量集中管理；节点读取通过统一访问方法，避免 String key 散落和随意类型转换。第一版不需要另起自定义事件总线、全量状态同步或多个复杂领域层。

## 5. Node 返回的是修改补丁

假设当前状态中已经有 request、parameters、status。Planner 只返回：

```java
return Map.of(
        "plan", generatedPlan,
        "status", "PLANNED"
);
```

框架将这两个字段合并到已有 State，request 和 parameters 仍然保留。执行节点返回 result，报告节点照样可以读取 request、parameters、plan 和 result。

节点默认做三件事：**读取需要的字段 → 完成本节点工作 → 返回变化字段**。不要直接修改 state.data() 内嵌的可变 Map/List；复制修改后返回新值，避免绕开合并、检查点与并发边界。

应用收到人工补充时，用编译图的 `updateState(config, patch)` 更新检查点，而不是直接手写 Redis JSON。应用写入与图执行同样需任务级租约/版本检查；Demo 没有生产租约。

## 6. Replace 和 Append 最容易混淆

```text
Replace:
  旧 parameters = {metric: sales, warehouseId: old}
  新 parameters = {warehouseId: east}
  合并后        = {warehouseId: east}
  metric 不会自动保留。

正确更新:
  读取旧 parameters → 复制 → 改 warehouseId → 返回完整的新 parameters

Append:
  旧 trace = [planner]
  新 trace = [check]
  合并后   = [planner, check]
```

计划、查询结果、状态等使用 Replace；消息序列才按框架要求使用 Append。不能对 plan 使用追加；也不能把同一完整历史列表反复当作新增消息追加。

首版串行 result 可以整体 Replace。将来并行任务 T1/T2 都返回 `results` 时，Replace 会整字段替换，不能当深合并使用；要由 ResultCollectNode 统一合并，或引入明确按 taskId 合并且经过验证的 reducer。

## 7. 保存和恢复到底怎样工作

一条任务有稳定框架 threadId，例如 `erp:task:42`。每次 HTTP 请求另有 requestId；同一任务暂停后的下一次请求继续使用原任务 thread/checkpoint，新建运行时身份、租约和取消作用域。

```text
开始：输入 request + parameters
  ↓
Planner：返回 plan
  ↓
Check：缺仓库 → 写 WAITING_HUMAN + question
  ↓
框架 interruptBefore("human")：保存检查点，nextNode=human
  ↓
本次请求结束，用户补充仓库
  ↓
应用合并 parameters，调用 graph.updateState
  ↓
使用返回的 config / checkpoint 恢复原图
  ↓
Human → Planner → Check → Execute → Reporter → END
```

SAA 1.1.2.0 的配套示例使用 `CompileConfig` 注册官方 Saver、`interruptBefore("human")`、`releaseThread(false)`。首次流执行结束后检查 checkpoint.nextNodeId 确实为 human；补充使用两参数 updateState，恢复后先执行 Human 再回 Planner，不需要手工指定下一节点。

```java
RunnableConfig resume = graph.updateState(
        originalConfig.withCheckPointId(pausedCheckpoint.getId()),
        Map.of("parameters", mergedParameters)
);
graph.stream(Map.of(), resume).collectList().block();
```

这是与固定版本配套示例一致的核心调用，完整工程另有限时等待和验证。关键不是空 Map 本身，而是指定原 thread/checkpoint：同一 thread 的普通新输入和明确恢复不能混为同一入口。不要从 START 重新提交完整需求冒充恢复。

示例还在 Execute 后主动中断，验证恢复从 Reporter 开始，Execute 计数保持一次。它只证明已保存执行后检查点的正常继续，不覆盖“业务已经提交但执行后检查点尚未保存”的故障窗口。

## 8. checkpoint 与业务数据库不要存两份可独立修改的完整 State

推荐第一版：

- 完整图执行状态与下一节点由官方 Saver 管理。
- 任务业务索引保存 taskId、归属、threadId、checkpoint 关联、状态及更新时间等少量元信息；需要时再设计，不在本轮加表。
- 真实订单、幂等操作回执和审计由业务持久化负责，数据集/图表独立保存，State 引用它们。
- task 索引状态可由最新检查点派生并对账，不能假定 Redis 与 MySQL 双写是一个事务。

沿用当前 RedisSaver 时，要明确 Redis 持久化、检查点保留期和 WAITING_HUMAN 有效期。当前 TTL 配置不能自动成为可靠长期任务记录；检查点过期时不可凭聊天摘要恢复精确执行位置。恢复入口应报告状态不可用并按业务回执查证，必要时重新规划读取任务。

后续如果希望长期任务的 checkpoint 与业务索引更容易管理，可以评估官方 MysqlSaver；这属于部署和持久化选择，不改变 Node 返回 patch 的基本编程方式。

## 9. 当前 ERP 的最小改动顺序

1. **隔离跑通串行图**：少量字段、假 Planner/执行器、MemorySaver，理解每个节点更新；配套示例已经完成这一步。
2. **新增任务图组装**：复用当前 SAA 与序列化基础，注册这些字段；现有主 ReactAgent 的聊天路径保留。
3. **把假 Planner 换成模型**：模型只输出 plan，ContextFactory 从 State 拼输入，不把内部输出保存为用户最终回复。
4. **接入已有 RedisSaver**：采用独立任务 thread 命名与恢复入口，验证序列化、Redis真实恢复、权限及租约；不经过普通聊天 ensureCheckpoint 重建路径。
5. **接一个真实只读业务场景**：执行写 result，报告读 result，失败也有明确状态。
6. **再加入子 Agent、并行、Python与写工具**：每加入一项扩展其必要状态与测试，不一次搬进所有上游字段。

当前已有 `AiMemoryConfig`、`AiContextRebuilder` 和检查点测试可以作基础。不要原样挂载 `AiMessageRecordingHook` 到 Planner/Check/Reflection；当前 Hook 会把没有工具调用的输出当最终候选。节点产物独立记录，Response阶段才提交最终消息。

已有 `AiRunRegistry.Run` 是本请求运行控制，不是持久 TaskState；身份、取消、连接、调度器不保存到状态中，恢复时重新建立并复核权限。

## 10. 已验证的范围

配套工程固定 Java 21 / SAA 1.1.2.0，没有调用模型、ERP数据库或真实工具，使用官方 MemorySaver。

- Replace 对 Map/List 的整字段替换、Append 对轨迹追加。
- 缺参数暂停：status=WAITING_HUMAN、nextNode=human、执行次数=0。
- 合并人工补充后恢复：旧参数保留、轨迹连贯、执行次数=1。
- Execute 后的检查点恢复只继续 Reporter。
- 同一个图配置下，不同 threadId 保留不同状态。

MemorySaver 不跨进程持久化；示例重建的是图实例，不能称作进程重启恢复。生产 Redis、任务归属、取消、过期、并发租约和订单幂等，需要实施时另外验证。
