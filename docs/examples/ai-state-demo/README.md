# State 管理可运行示例

这不是 ERP 业务改造。示例使用项目相同的 Spring AI Alibaba 1.1.2.0，演示八个状态字段、Node 返回 Map 更新、Replace/Append、官方 MemorySaver、Human 暂停与恢复。

无需模型 API Key、Redis 或数据库。需要 Java 21、Maven，以及可用的 Maven 依赖缓存或依赖下载网络。

## 运行

在仓库根目录执行：

```powershell
.\docs\examples\ai-state-demo\run.ps1
```

本机已使用下面的命令离线编译并运行通过：

```powershell
.\docs\examples\ai-state-demo\run.ps1 `
  -MavenCommand 'E:\Develop\apache-maven-3.9.4\bin\mvn.cmd' `
  -Offline
```

## 从哪里看起

1. `strategies()`：字段及其更新规则。
2. `graph()`：节点只返回需要修改的字段。
3. `verifyHumanPauseAndResume()`：补充字段并恢复真实中断检查点。
4. `verifyResumeAfterExecutedNode()`：执行已保存后，不重跑该节点。
5. `verifyThreadIsolation()`：同图配置、不同任务状态。

预期主要输出：

```text
PAUSED: status=WAITING_HUMAN, next=human, executions=0
RESUMED: status=COMPLETED, executions=1, trace=[planner, check, human, planner, check, execute, reporter]
PASS: resumed at reporter without executing the completed node again.
PASS: different threadIds retain different state while sharing graph configuration.
ALL CHECKS PASSED: update strategies, Human resume, checkpoint continuation, thread isolation.
```

## 边界

MemorySaver 仅在当前 Java 进程保留数据。示例不证明真实订单 exactly-once、业务提交与 checkpoint 原子性、跨进程恢复或生产鉴权。实际项目采用 RedisSaver 后，仍需对应集成测试。

详细设计见 [最小 State 管理指南](../../ai-state-management-guide.md)。编译产物放在 target，不加入版本控制。
