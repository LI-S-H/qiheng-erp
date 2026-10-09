# AI 基础骨架实施方案

需求来源：用户确认先跑通基础交互，保留有效会话和界面。完整架构以 [feature-spec AI 章节](./feature-spec.md) 为准。

## 本阶段范围

- 两个独立的 DeepSeek ChatModel（deepseek-v4-pro、deepseek-flash），一个 OrchestratorAgent 显式注入 Pro；未来其他智能体可以同时注入 Flash。
- 会话创建、改名、删除、历史分页和用户隔离。
- JSON 与 SSE 入口共用消息业务流程，前端实时显示增量文字。
- 数据库历史回灌，刷新后消息可回读，同会话能够追问。
- 配置缺失、模型失败、超时、取消和异常断流有明确反馈。

暂不实施业务 Tool、SubAgent、RAG、任务规划、业务草稿和定时经营任务。图表组件保留，当前图表及业务结构化数组为空，不用演示数据表示业务分析已经完成。

## 后端分层

```text
com.qiheng.erp.ai
├── chatmodel/       模型创建与配置
├── agent/           Agent 创建与提示词
├── controller/      参数校验及 HTTP 协议
├── service/impl/    用户隔离、上下文、发送和持久化
├── domain/          DTO、VO、Entity
└── mapper/          会话与消息数据访问
```

模型未启用或没有有效配置时，ERP 应能正常启动，发送返回 503。当前使用 Spring AI Alibaba 1.1.2.0、Spring AI 1.1.2；DeepSeek 的 OpenAI 兼容接入不要求加入 DashScope starter。

本机直接编辑 `erp-server/.env.ai.yaml`，在 `erp.ai.api-key` 填入真实 Key；文件已被 Git 忽略，不提交到仓库。两个模型可共用一个 Key。以 `erp-server` 为工作目录启动后端，`application-ai.yml` 自动导入该文件，填写后重启后端即可。

```yaml
erp:
  ai:
    enabled: true
    api-key: "你的真实密钥"
```

`chatmodel/DeepSeekProChatModelConfig.java` 注册 `deepseekProChatModel`，`chatmodel/DeepSeekFlashChatModelConfig.java` 注册 `deepseekFlashChatModel`。它们是两个不同实例，可同时提供给不同智能体。当前 OrchestratorAgent 使用 `@Qualifier("deepseekProChatModel")` 注入 Pro；以后子智能体可用 `@Qualifier("deepseekFlashChatModel")` 注入 Flash。不设置全局模型选择器，也不要求在前端二选一。

默认服务地址为 https://api.deepseek.com。环境变量 `ERP_AI_ENABLED`、`DEEPSEEK_API_KEY`（兼容 `OPENAI_COMPAT_API_KEY`）仍支持，`OPENAI_COMPAT_BASE_URL` 可覆盖地址。本地 Key 文件不能提交，Key 不放前端或聊天。未填写 Key 时仍可查看会话界面，发送返回 503。模型 ID 与服务地址依据 [DeepSeek 官方接入文档](https://api-docs.deepseek.com/en/)。

Service 在请求线程捕获可信 userId，校验会话归属，读取有序历史并保存用户消息。模型调用不包在长事务内；异步完成后的持久化显式使用捕获的身份并重新验证归属，不能依赖已经结束的 HTTP 请求上下文。

数据库历史是对话上下文来源，每次读取最近 100 条并按时间正序回灌，不同时叠加另一份 Saver 历史，以免消息重复。当前不承诺无限长度的会话记忆。取消或失败保留已保存用户消息，不保存半截助手回答；完成时先保存助手，再发送最终事件。

## HTTP 与事件协议

POST /ai/assistant/messages 返回 JSON，POST /ai/assistant/messages/stream 返回 SSE。两者 conversationId 必填，必须先创建或选择当前用户会话。

| Accept / 事件 | 载荷及行为 |
|---|---|
| application/json，/messages | Result<AiChatResponse>，返回最终助手消息 |
| text/event-stream，/messages/stream | SSE；每个 data 均是 JSON |
| MESSAGE_DELTA | eventType、requestId、conversationId、contentDelta |
| MESSAGE_FINAL | eventType、requestId、conversationId、已保存的完整 message |
| ERROR | eventType、requestId、conversationId、安全的 errorMessage；随后结束 |

SSE event 名称与 data.eventType 一致，ID 按字符串传输。校验和未启用错误发生在建立流之前，使用正常 HTTP 错误；生成中的失败使用 ERROR。没有收到 MESSAGE_FINAL 的断流不视为成功。

前端使用 fetch POST + ReadableStream，认证请求头名称来自登录结果；解析器处理 UTF-8 跨分块、CRLF、多行 data、事件跨分块和心跳。页面只追加到当前生成中的助手消息，最终事件替换为有真实 messageId 的结果。

## 前端与 Mock 精简

保留现有聊天布局、输入区、会话管理和图表组件；移除大段业务假数据及其旧专家名称。暂未实现的任务入口收起，避免请求不存在的后端接口。

发送期间禁止重复提交，允许停止生成；切换会话或离开页面取消旧请求，旧回调不得覆盖新会话。失败或取消后重新读取历史，保留用户输入便于重试，不伪造生成完成。

图表由未来业务 Tool/Service 返回可信数据后接入，当前不从模型自由文本推断业务工作框或创建单据。

## 验收与验证

1. AI 模块编译、Controller 和 Service 测试通过；没有 Key 时应用上下文可建立、发送返回 503。
2. 查询历史、保存消息都验证当前用户归属；异步保存不依赖 ThreadLocal。
3. 浏览器发送后至少观察两次增量，MESSAGE_FINAL 替换临时助手消息，刷新可以回读。
4. 覆盖 ERROR、缺少最终事件的断流、主动取消、切换会话和认证头。
5. SSE 解析测试覆盖中文 UTF-8 跨分块、CRLF、多行 data 和取消。
6. OpenAPI 契约检查、前端构建和对应浏览器 smoke 通过。

自动化使用可控模型/HTTP 流式测试服务验证链路，不冒充真实云模型测试。真实厂商连通性需要有效密钥与模型 ID，未执行时应如实报告。
