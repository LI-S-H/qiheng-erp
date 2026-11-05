# AI 图产物发布拍改造方案（落库与推送前移）

> 历史施工与验证记录（2026-10-27 标记）：本文保留旧图的发布与落库改造记录。现行整体设计见 [ERP Agent 架构设计](ai-agent-architecture.md)；旧节点、状态和恢复规则不再约束新开发。发布与落库原则可供参考，具体时点、事件和接口需在新模块实施时重新核对。


> 类型：设计与施工方案；本方案经对话评审收敛，作为实施依据。
> 日期：2026-10-17
> 范围：第 1 期立即实施（当前 MVP 图）；第 2 期挂起（未来规划图，Phase 3 立项时开工）。
> 关联：`docs/ai-graph-mvp.md`（现状 MVP）、`E:\Projects\deepsearch\docs\learning\最终规划表.md`（未来图设计，§3.3/§4/§14/§18/§19）。
> 注：文中行号以写文档时的代码为准，实施前必须重读核对（近期持续有手动编辑）。
> 状态（2026-10-19）：第 1 期已完成；随后一轮「终态处理改造」把 §2.4 第 3 条对澄清的过渡安排一次做完——澄清消息已随计划校验拍（准备节点）落库、恢复锚点从框架 `checkpointId` 平移为准备拍自生成的 `pauseId`；收尾收敛为「读结局 → 结清用户行 → 发终态事件」的零补写三步，`RunFinalizer`、`RunEnding`、`GraphResponse` 已删除，失败路径保留 `RunFailureHandler` 的 ERROR 兜底。§1/§3 的行号与类名是写作时基线，保留供追溯；§4 的 prepare 系列施工仍待第 2 期。

---

## 1. 背景与问题

### 1.1 现状链路

```text
RunCoordinator.execute()（worker 线程）
   ├─ 节点执行：各节点返回 Map → 快照流经 doOnNext(tracker::onSnapshot)
   │     └─ 快照层当前只发 node_start / node_end 进度事件，不携带任何业务内容
   ├─ blockLast() 返回（图全部跑完）
   ├─ RunFinalizer.finish（RunFinalizer.java:47-68）
   │     ├─ 读终态快照 → RunOutcome 组装 GraphResponse（:49-51）
   │     ├─ 落库助手终态消息 messageStore.complete（:56-57）   ← 计划消息在这里才落库
   │     └─ 记录被采纳计划 nodeMemoryRecorder.acceptedPlan（:62-66）
   └─ tracker.terminal(finalizer.finish(run))（RunCoordinator.java:115）  ← 计划内容在这里才推给前端
```

### 1.2 两个问题

| 问题 | 描述 | 现在为何"看着没事" |
|---|---|---|
| 落库晚 | 计划消息在收尾才写库（RunFinalizer.java:56） | READY 下一拍就是 END（GraphConfiguration.java:103），落库时刻≈计划定稿时刻 |
| 推送晚 | 计划内容只在 terminal 的 result 事件里给前端（RunCoordinator.java:115） | 同上，只差一拍 |

**MVP 图上是巧合；未来图（check 后接 plan_executor → SQL/Python → reporter → …）就是错误**：计划在 check 那拍已定稿，却要等全部执行节点跑完才落库、才推前端。

### 1.3 内容与状态的分离结论

- **内容（用户可见产物：计划/报告/草稿/回执/澄清）**：没有任何一份需要等图结束，全部在"产物定稿的那一拍"落库+推送（规划表 §18 :665-667 同构）。
- **状态（run_finished/run_stopped/错误兜底/结清用户行）**：必须有且只能在图结束——前端靠它知道"本轮结束、锁释放、可发新消息"。

---

## 2. 设计原则

### 2.1 三拍模型

```text
拍1 节点拍（NodeAction.apply 内）
   └─ 产物 owner 节点做两件事：
      a) 落库：调 ConversationMessageStore（唯一读写入口，自带事务/seq）
      b) 投影：把产物放入 state 专用 key「PUBLISHED_ARTIFACT」（随消息协议同源构造）

拍2 快照拍（doOnNext(tracker::onSnapshot)）
   └─ tracker 发现 PUBLISHED_ARTIFACT 出现新指纹 → 发 artifact 内容事件
      · checkpointId 在此可得（StateSnapshot.config().checkPointId()，同 RunOutcome.java:82）
      · 每 run 实例持有指纹簿记，重放不重发
      · 节点不发事件：节点拿不到 SSE 通道，规划表 §19.8 亦约束节点不碰运行时通道

拍3 终拍（blockLast 之后，RunCoordinator.finishRun / RunFailureHandler）
   └─ 结局判定（RunOutcome 状态机统一入口）＋结清用户行＋发布终态事件；
      正常轮不补写内容（澄清已由拍 1 的暂停节点落库，失败兜底仍补 ERROR 卡）
```

**次序保证**：节点拍先于快照拍 ⇒ 天然"先落库、后让用户看到"，沿用现有收尾的"先写库后发事件"次序。

### 2.2 三同源

产物内容构造收敛到单一工厂：**落库消息 content、artifact 事件载荷、前端渲染协议**三者共用同一份产物形状，杜绝双份转换漂移（entryType 类 bug 的预防手段）。

### 2.3 故障域与补偿边界

```text
现状：落库集中收尾 → 收尾前进程崩 = 整轮历史全丢（故障域=全轮）
改后：每条产物自己落自己 → 单条失败最多丢该条历史（故障域=单产物）
```

- 实时可见性 > 历史完整性：事件来自 state 不经过库，落库失败用户当场照看，只损失"刷新后的一条历史"；沿用现有告警哲学（RunFinalizer.java:35 HISTORY_SAVE_FAILED_WARNING 文案）。
- 第 1 期补偿全覆盖（MVP 每轮恰好一条产物，收尾 findFinal 兜底=安全网）。
- 第 2 期起中间产物无收尾补偿（收尾只知最后一条，中间产物无从补起）——止损线为"单产物自保＋告警"，属有意取舍（低频×小损失，不值得建全量补偿）。
- 可选增强（第 2 期再定）：产物落库失败把告警写进 state，收尾汇总进 terminal，前端可提示"第 N 阶段历史未保存"。

### 2.4 明确不改的边界

1. 节点内不落"事件发射"职责；checker 不承担用户消息落库原则之外的判结；收尾代码整体保留（结清/兜底/澄清/错误/history 回填各有不可挪理由）。
2. `finishRequest` SQL 只救 RUNNING 行（AiMessageMapper.java:130）⇒ **节点拍只 append 助手消息行，绝不调 complete()**，结清永远留给收尾。
3. 澄清消息与 ERROR 兜底在第 1 期不迁：当时澄清 content 的 checkpointId 是恢复插槽与 selectClarification 锚点的功能必需，而 checkpointId 由框架写检查点时生成，节点拍拿不到；该过渡已在 2026-10-19 终态处理改造收口——计划校验节点自生成 pauseId，澄清消息与恢复锚点随暂停拍落库，ERROR 兜底仍由失败收尾补写。

---

## 3. 第 1 期施工单（当前 MVP 图）

### 3.1 改动清单

| # | 文件 | 动作 | 预检 |
|---|---|---|---|
| 1 | 新建 `erp-ai/.../service/graph/VisibleResultFactory.java` | 从 RunFinalizer.java:73-114 原样抽出三入口+describe | 否 |
| 2 | `RunFinalizer.java` | visibleResult 改为分派 factory（逻辑等价；complete/记忆写入不动） | 否 |
| 3 | `GraphStateKeys.java` / `GraphConfiguration.java` | 新增 PUBLISHED_ARTIFACT + ReplaceStrategy（:85-88 处）；:76 构造传参（+messageStore、+threads） | 否 |
| 4 | `PlanCheckNode.java` | READY 分支：persistQuietly 落库 + PUBLISHED_ARTIFACT 投影 | 否 |
| 5 | `RunProgressTracker.java` | 新增制品产物事件发射；顺带清理 :45/:86 channel null 死分支（channel 现恒非 null，RunCoordinator.java:58 必建） | 否 |
| 6 | `RunCoordinator.java` | 零逻辑改动；顺带修 :80 过时 javadoc（"同步请求本身也是执行线程"→独立 worker） | 否 |
| 7 | `erp-openapi.yaml` | SSE 事件 schema 新增 artifact | **是** |
| 8 | `erp-web`（graph-stream.ts / 视图 / 契约测试 / openapi 检查脚本） | 解析并消费 artifact(type=PLAN) | **是** |
| 9 | `NodeMemoryRecorder.java`（entryType bug） | record() 增加 entryType 参数：acceptedPlan→OUTPUT、userInput 保持 INPUT；NodeMemoryRecorderTest.java:39 断言改 OUTPUT；补"写入→PlannerMemoryContext.isAcceptedPlan 可识别"契约测试（读取方契约 PlannerMemoryContext.java:160-168） | 否 |

第 7、8 项实施前必须先跑：`cd erp-web && npm run preflight:frontend -- ai`，并按输出核对 openapi 与数据库契约；改动后跑契约检查、构建与 SSE 测试。

### 3.2 关键伪代码

```java
// VisibleResultFactory（新类，三处共用）
public VisibleMessageContent acceptedPlan(Plan plan, String threadId)
// → {"type":"PLAN","data":{"text":描述,"plan":计划,"threadId":...}}   不含 checkpointId（前端可选字段已核实：message-contract.ts:47-50，恢复插槽只认 CLARIFICATION：AiAssistantView.vue:92-99）
public VisibleMessageContent clarification(Plan plan, String threadId, String checkpointId)
// → 同上 + questions + checkpointId（必需，不迁）
public VisibleMessageContent failed(String threadId, String checkpointId)

// PlanCheckNode（READY 分支）
} else {
    update.put(PLAN_CHECK_RESULT, PlanCheckResult.READY.name());
    update.put(PUBLISHED_ARTIFACT, artifactOf(plan, threads.of(stateUserId, stateConversationId)));
    persistAcceptedPlanQuietly(state, plan);
}

private void persistAcceptedPlanQuietly(OverAllState state, Plan plan) {
    try {
        // conversationId/userId/requestId 从 state 解析（同 recordFeedback 的样式）
        String threadId = threads.of(userId, conversationId);
        if (messageStore.findFinal(conversationId, userId, requestId) != null) return; // 幂等
        messageStore.append(conversationId, userId, requestId, "assistant",
                AiMessageKind.ASSISTANT_FINAL, null, null,
                visibleResults.acceptedPlan(plan, threadId));   // 只 append，不结清
    } catch (Exception exception) {
        log.warn("计划消息发布拍保存失败，等待收尾兜底", exception);   // 不阻塞图
    }
}

// RunProgressTracker（新增）
private final Set<String> publishedFingerprints = new HashSet<>();

private void publishArtifactIfAny(NodeOutput output) {
    if (!(output instanceof StateSnapshot snapshot)) return;
    Map<String, Object> artifact = snapshot.state().value(PUBLISHED_ARTIFACT, Map.of());
    if (artifact.isEmpty()) return;
    if (!publishedFingerprints.add(artifact.get("type") + ":" + artifact.get("version"))) return;
    String checkpointId = snapshot.config().checkPointId().orElse(null);
    publisher.publish(conversationId, channel, RunProgressPublisher.EVENT_ARTIFACT,
            new RunArtifactEventVO(artifact.get("type"), checkpointId, artifact.get("content")));
}

// RunFinalizer（visibleResult 分派，前三件职责不动）
private VisibleMessageContent visibleResult(GraphResponse response) {
    if (RunStatuses.WAITING_HUMAN.equals(response.status()))
        return factory.clarification(response.plan(), response.threadId(), response.checkpointId());
    if (response.plan() == null)
        return factory.failed(response.threadId(), response.checkpointId());
    return factory.acceptedPlan(response.plan(), response.threadId());
}
```

事件命名取固定事件名 `artifact`＋载荷内 type 区分，前端解析分支一次写好，未来新增产物类型零事件协议改动。

### 3.3 测试清单

| 用例 | 断言 |
|---|---|
| PlanCheckNodeTest：READY | append 调用一次，content.type=PLAN、含 threadId、无 checkpointId；PUBLISHED_ARTIFACT 已写入返回 Map |
| PlanCheckNodeTest：final 已存在 | 不重复 append |
| PlanCheckNodeTest：store 抛异常 | update 正常返回，READY 路由不受影响 |
| PlanCheckNodeTest：WAITING_HUMAN/RETRY/EXHAUSTED | 不落库、不投影 |
| RunProgressTrackerTest | 首拍发一次；重放快照不重复发；快照无 checkpoint 时载荷可空 |
| RunFinalizerTest | findFinal 已存在→跳过补写、仍结清、withHistory 用已有消息 |
| NodeMemoryRecorderTest | entryType=OUTPUT；PlannerMemoryContext 契约测试通过 |
| 前端 | graph-stream 解析 artifact、视图提前渲染计划且与 result 幂等；契约测试更新事件序列 |
| 回归 | `mvn -pl erp-ai -am test` 全绿；GraphRedisIntegrationTest 5/5（16380/db15）；前端 build + check-openapi + SSE 测试 |

### 3.4 风险登记

1. SSE 事件序列在 node_end(check) 之后、result 之前新增 artifact 事件——有意演进，前端测试断言同步更新；
2. MVP 图 artifact 与 result 双渲染同计划——幂等，无视觉差异；
3. PUBLISHED_ARTIFACT 随检查点写 Redis，多存一份计划副本——计划体积小可接受；第 2 期规定大产物只放引用（规划表 :665"报告内容或引用"）；
4. 节点拍落库后、收尾前进程死了：消息已在、用户行 RUNNING——现状窗口更大（消息也没有），净效果不劣化；RUNNING 行的轮次判定逻辑现状即如此处理。

---

## 4. 第 2 期施工单（未来规划图，Phase 3 立项时开工）

1. kind 重划：新增 `ASSISTANT_ARTIFACT`（阶段产物），`ASSISTANT_FINAL` 收窄为收尾兜底/薄终态专用（AiMessageKind.java）；
2. mapper 三处可见分页/删除 SQL 的 `kind IN` 扩展（AiMessageMapper.java:39、:58、:86）；
3. store 新增 `saveArtifact`（requestId+kind+content.type 幂等，事务内查重；JSON_EXTRACT 仿 selectClarification 样式）；
4. prepare 系列节点（规划表 §4 :194/:205/:207/:210）按第 1 期模板接入：pauseId 由准备节点自生成（:416），澄清锚点从框架 checkpointId 平移到 pauseId，澄清消息迁至准备拍（当前澄清节点已按同一机制完成改造，其余 prepare 节点待第 2 期）；
5. 事件：`step_progress`（PlanExecutor 顺序进度）、`clarification_required` 从 result 拆出、`report_ready`/`draft_ready`/`business_result` 载荷（:665-667）；
6. terminal 瘦身：`complete()` 拆为 `settleRequest()`（结清+history 回填）与补写兜底（仅 ERROR 用）；result 演化为 `run_finished` 纯状态，plan/questions 从场次载荷退役；
7. RunFailureHandler 错误兜底永久保留；
8. 前端 content.type 渲染分支扩展 + openapi + 预检（scope：ai，含 shared 视影响面）。

## 5. 与规划表对照

| 规划表条目 | 本方案落点 |
|---|---|
| §4 :183 "Reporter 完成后再记录真实报告" | 第 2 期 prepare_report_review 拍落库+推送 |
| §18 :665-667 产物事件带 id/version | 第 2 期 artifact type 扩展与 reportId/draftId 引用 |
| §18 :670 "经明确投影后才公开" | PUBLISHED_ARTIFACT 即投影 key，事件/库/前端三同源 |
| §19.8 "单例 Node 不保存 Run" | 注入无状态 store/threads 属动作非状态，不违反；如需可回写规划表澄清 |
| §19.18 :697 "前端只接收公开事件投影" | artifact 事件载荷即投影产物 |
