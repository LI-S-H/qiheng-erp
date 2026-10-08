# AI 模块设计：主智能体与查询、分析子智能体

本文与 [项目功能说明书 AI 章节](./feature-spec.md) 保持一致，作为当前 AI 架构说明。

## 架构与边界

| 角色 | 职责 | 可调用能力 |
|---|---|---|
| OrchestratorAgent | 接收用户问题、任务规划、调度、上下文注入、反思、结果汇总 | 任务管理工具和两个子智能体 |
| DataQuerySubAgent | 在当前用户权限内读取 ERP 数据，返回结构化结果 | 18 个受控只读 Tool，调用已有业务 Service |
| DataAnalysisSubAgent | 分析主智能体注入的数据，返回可校验结论 | 纯计算 Tool；不查库、不写表、不调用外部 HTTP |

主智能体不直接持有业务 Tool。查询结果通过 resultRef 关联，由主智能体解析并注入分析输入；数据不足时回到查询步骤，按明确上限重试或追问用户。任何正式业务写入都由用户在 ERP 页面完成。

## 本阶段：基础对话骨架

本阶段只接入一个可配置 ChatModel 和一个 OrchestratorAgent，完成真实文本对话、SSE、会话历史和追问。查询子智能体、分析子智能体、任务管理、反思、RAG 和业务草稿属于后续扩展，本阶段不注册或模拟这些能力。

包职责保持清晰：chatmodel 创建模型，agent 定义 Agent，service 承担会话校验、上下文和消息保存，controller 提供 HTTP 接口；实体与 Mapper 保留既有结构。

POST /ai/assistant/messages 返回 JSON，POST /ai/assistant/messages/stream 返回 SSE；两者共用 Service 的会话、上下文和保存流程。具体字段、事件和错误行为以 [OpenAPI](./api/erp-openapi.yaml) 为准。

## 交付与后续扩展

基础阶段交付方案见 [AI 基础骨架实施方案](./ai-module-p0-plan.md)。图表组件保留供后续使用，但当前不生成图表、采购建议、调拨建议或虚构协同轨迹。

基础链路通过后，再实施 DataQuerySubAgent 的权限与审计、DataAnalysisSubAgent 的计算能力，最后接入主智能体任务清单与反思。定时经营任务在真实后端和执行状态查询完成前不开放入口。
