export type AiTone = 'neutral' | 'good' | 'watch' | 'risk';

export type AiMessageRole = 'assistant' | 'user';

export type AiPromptFieldType = 'PRODUCT_MULTI' | 'WAREHOUSE_MULTI' | 'DAYS' | 'TEXT';

export interface AiPromptField {
  fieldKey: string;
  label: string;
  fieldType: AiPromptFieldType;
  required: boolean;
  placeholder: string;
  defaultValue: string | string[];
}

export interface AiQuickPrompt {
  promptId: string;
  title: string;
  description: string;
  intentCode: string;
  promptTemplate: string;
  fields: AiPromptField[];
}

export interface AiContextSource {
  sourceId: string;
  sourceType: 'TOOL' | 'WORKFLOW' | 'KNOWLEDGE' | 'TASK';
  title: string;
  description: string;
  freshness: string;
}

export type AiChartType = 'line' | 'bar' | 'pie';

export type AiChartRow = Record<string, string | number | null>;

export interface AiChartSpec {
  chartId: string;
  type: AiChartType;
  title: string;
  description: string;
  xField: string | null;
  yFields: string[];
  fieldLabels?: Record<string, string>;
  yUnit?: string | null;
  nameField: string | null;
  valueField: string | null;
  data: AiChartRow[];
}

export interface AiAssistantOverview {
  refreshedAt: string;
  quickPrompts: AiQuickPrompt[];
  conversations: AiConversationSummary[];
}

export interface AiConversationSummary {
  conversationId: string;
  title: string;
  description: string;
  updatedAt: string;
}

export interface AiTaskCard {
  title: string;
  description: string;
  parameters: Array<{
    label: string;
    value: string;
  }>;
}

export interface AiChatMessage {
  messageId: string;
  role: AiMessageRole;
  content: string;
  createdAt: string;
  charts: AiChartSpec[];
  actionCards: AiActionCard[];
  sources: AiContextSource[];
  agentTraces: AiAgentTrace[];
  taskCard: AiTaskCard | null;
}

export interface AiActionCard {
  actionId: string;
  title: string;
  description: string;
  actionType: 'NAVIGATE' | 'WORKFLOW' | 'PREVIEW';
  route: string | null;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface AiAgentTrace {
  traceId: string;
  agentCode: string;
  agentName: string;
  summary: string;
  status: 'PLANNED' | 'RUNNING' | 'DONE' | 'FAILED';
}

export interface AiChatRequest {
  conversationId: string | null;
  message: string;
}

export interface AiChatResponse {
  conversationId: string;
  message: AiChatMessage;
}

export interface AiConversationUpdateRequest {
  title: string;
}

export type AiScheduledTaskStatus = 'ENABLED' | 'DISABLED' | 'RUNNING' | 'FAILED';
export type AiScheduledTaskFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type AiScheduledTaskCategory = 'REPORT' | 'INVENTORY' | 'PURCHASE' | 'SALES' | 'SUPPLIER';
export type AiScheduledTaskOutputFormat = 'CHAT_CARD' | 'REPORT';

export interface AiScheduledTaskSummary {
  totalCount: number;
  enabledCount: number;
  failedCount: number;
  nextRunCount: number;
}

export interface AiScheduledTask {
  taskId: string;
  taskName: string;
  category: AiScheduledTaskCategory;
  frequency: AiScheduledTaskFrequency;
  cronExpression: string;
  productIds: string[];
  warehouseIds: string[];
  recipientRoleIds: string[];
  productScope: string[];
  warehouseScope: string[];
  analysisGoal: string;
  nextRunAt: string;
  lastRunAt: string | null;
  lastResultSummary: string;
  recipients: string[];
  status: AiScheduledTaskStatus;
  agentCodes: string[];
  painPoint: string;
  outputFormat: AiScheduledTaskOutputFormat;
  editable: boolean;
}

export interface AiScheduledTaskTemplate {
  templateId: string;
  taskName: string;
  category: AiScheduledTaskCategory;
  frequency: AiScheduledTaskFrequency;
  description: string;
  defaultCronExpression: string;
  agentCodes: string[];
  defaultProductScope: string[];
  defaultWarehouseScope: string[];
  defaultAnalysisGoal: string;
  outputFormat: AiScheduledTaskOutputFormat;
}

export interface AiTaskExecutionMetric {
  label: string;
  value: string;
  tone: AiTone;
}

export interface AiTaskExecutionAction {
  actionId: string;
  title: string;
  description: string;
  route: string | null;
}

export interface AiTaskExecution {
  executionId: string;
  taskId: string;
  taskName: string;
  startedAt: string;
  finishedAt: string | null;
  status: 'SUCCESS' | 'FAILED' | 'RUNNING';
  resultSummary: string;
  agents: string[];
  findings: string[];
  suggestions: string[];
  metrics: AiTaskExecutionMetric[];
  charts: AiChartSpec[];
  nextActions: AiTaskExecutionAction[];
}

export interface AiScheduledTaskPage {
  refreshedAt: string;
  summary: AiScheduledTaskSummary;
  templates: AiScheduledTaskTemplate[];
  tasks: AiScheduledTask[];
  recentExecutions: AiTaskExecution[];
}

export interface AiScheduledTaskUpdateRequest {
  taskName: string;
  category: AiScheduledTaskCategory;
  frequency: AiScheduledTaskFrequency;
  cronExpression: string;
  productIds: string[];
  warehouseIds: string[];
  recipientRoleIds: string[];
  analysisGoal: string;
  outputFormat: AiScheduledTaskOutputFormat;
}

export interface AiScheduledTaskRunAccepted {
  executionId: string;
  taskId: string;
  status: 'RUNNING';
  acceptedAt: string;
}
