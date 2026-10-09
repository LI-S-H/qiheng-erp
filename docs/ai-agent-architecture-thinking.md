# ERP AI Agent 架构边界

架构以 [项目功能说明书 AI 章节](./feature-spec.md) 为准，本文件解释三个智能体的职责与协作边界。

## 为什么区分查询与分析

ERP 数据查询需要当前用户身份、业务权限、参数校验和稳定的数据口径；分析计算需要完整、明确的数据输入。两者分离后，查询子智能体负责取得可信数据，分析子智能体只处理注入的数据，避免分析过程中自行查询或修改业务数据。

## 三个智能体的协作

OrchestratorAgent 维护任务清单，调用 DataQuerySubAgent 获得结构化查询结果，再将结果注入 DataAnalysisSubAgent。主智能体检查输出结构、数据引用和任务完成情况；缺数据时补充查询，缺用户参数时追问，错误和重试均有上限。

- DataQuerySubAgent 的 18 个 Tool 通过现有 ERP Service 查询，不直接执行模型生成的 SQL。
- DataAnalysisSubAgent 只持有计算工具，不持有 Mapper 或业务查询工具。
- 业务状态机、权限与一致性由业务代码强制，不依赖提示词承诺。
- 供应商评分沿用采购模块真实口径；AI 只解释或引用结果，不维护另一套评分公式。

## 当前实现与演进

当前阶段注册 deepseek-v4-pro 和 deepseek-flash 两种模型，当前主智能体显式注入 Pro，未来其他智能体可同时注入 Flash，验证文本对话闭环。数据库消息历史作为上下文来源，流式与非流式发送共用 Service；HTTP SSE 由应用层转换框架输出，不把内部 NodeOutput 直接暴露给前端。

未来引入子智能体时，继续遵循上述边界，在已验证的对话链路上追加受控能力。模型配置与 Agent 配置分别放入 chatmodel/ 和 agent/，业务流程保持在 service/，HTTP 协议保持在 controller/。

实施范围与验收见 [AI 基础骨架实施方案](./ai-module-p0-plan.md)。
