import { AUTH_TOKEN_NAME_STORAGE_KEY, AUTH_TOKEN_STORAGE_KEY } from '@/shared/constants/storage';
import { readSseStream } from './sse';
import type { AiChatStreamEvent } from './types';
import { getResult, http } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { normalizeMoneyNumber } from '@/shared/utils/money';
import type { Result } from '@/shared/types/api';
import type {
  AiActionCard,
  AiActionPreview,
  AiAgentTrace,
  AiAssistantOverview,
  AiAssistantWorkbench,
  AiChartSpec,
  AiChatMessage,
  AiChatRequest,
  AiChatResponse,
  AiContextSource,
  AiConversationSummary,
  AiConversationMessagePage,
  AiConversationUpdateRequest,
  AiPromptField,
  AiQuickPrompt,
  AiScheduledTask,
  AiScheduledTaskPage,
  AiScheduledTaskStatus,
  AiScheduledTaskRunAccepted,
  AiScheduledTaskUpdateRequest,
  AiTaskCard,
  AiWorkbenchLine,
  AiWorkbenchSection,
} from './types';

function normalizeStringArray(value: unknown) {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : [];
}

const allowedBusinessRoutes = new Set([
  '/ai/tasks',
  '/purchase/orders',
  '/purchase/suppliers',
  '/sales/orders',
  '/warehouse/inbound-bills',
  '/warehouse/outbound-bills',
  '/warehouse/stocks',
]);

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function requiredText(value: unknown, fieldName: string) {
  const normalized = typeof value === 'string' ? value.trim() : '';
  if (!normalized) throw new Error(`接口字段 ${fieldName} 必须为非空字符串`);
  return normalized;
}

function normalizeBusinessRoute(value: unknown, fieldName: string): string | null {
  if (value === null || value === undefined || value === '') return null;
  const route = requiredText(value, fieldName);
  if (!allowedBusinessRoutes.has(route)) throw new Error(`接口字段 ${fieldName} 不是允许的业务路由`);
  return route;
}

function normalizeActionPreview(value: unknown): AiActionPreview | null {
  try {
    const item = asRecord(value);
    if (!item || !Array.isArray(item.steps) || item.steps.length === 0) return null;
    const steps = item.steps.map((step, index) => requiredText(step, `preview.steps[${index}]`));
    return {
      title: requiredText(item.title, 'preview.title'),
      description: requiredText(item.description, 'preview.description'),
      steps,
      route: normalizeBusinessRoute(item.route, 'preview.route'),
      confirmLabel: requiredText(item.confirmLabel, 'preview.confirmLabel'),
    };
  } catch {
    return null;
  }
}

function normalizeWorkbenchSection(value: unknown, index: number): AiWorkbenchSection {
  const item = asRecord(value);
  if (!item || !Array.isArray(item.items) || item.items.length === 0) throw new Error('工作框说明区结构无效');
  return {
    title: requiredText(item.title, `workbench.sections[${index}].title`),
    items: item.items.map((text, itemIndex) => requiredText(text, `workbench.sections[${index}].items[${itemIndex}]`)),
  };
}

function normalizeWorkbenchLine(value: unknown, workbenchType: AiAssistantWorkbench['workbenchType'], index: number): AiWorkbenchLine {
  const item = asRecord(value);
  if (!item) throw new Error('工作框明细结构无效');
  const suggestedQty = normalizeFiniteNumber(item.suggestedQty, `workbench.lines[${index}].suggestedQty`);
  if (suggestedQty <= 0) throw new Error('工作框建议数量必须大于零');
  const line: AiWorkbenchLine = {
    lineId: normalizeStringId(item.lineId, `workbench.lines[${index}].lineId`),
    productId: normalizeStringId(item.productId, `workbench.lines[${index}].productId`),
    productName: requiredText(item.productName, `workbench.lines[${index}].productName`),
    warehouseId: normalizeStringId(item.warehouseId, `workbench.lines[${index}].warehouseId`),
    warehouseName: requiredText(item.warehouseName, `workbench.lines[${index}].warehouseName`),
    suggestedQty,
    reason: requiredText(item.reason, `workbench.lines[${index}].reason`),
    supplierProductId: normalizeNullableStringId(item.supplierProductId, `workbench.lines[${index}].supplierProductId`),
    supplierId: normalizeNullableStringId(item.supplierId, `workbench.lines[${index}].supplierId`),
    supplierName: item.supplierName == null || item.supplierName === '' ? null : requiredText(item.supplierName, `workbench.lines[${index}].supplierName`),
    unitPrice: item.unitPrice == null ? null : normalizeMoneyNumber(item.unitPrice, `workbench.lines[${index}].unitPrice`, false),
    selectedSupplierScore: item.selectedSupplierScore == null ? null : normalizeFiniteNumber(item.selectedSupplierScore, `workbench.lines[${index}].selectedSupplierScore`),
    targetWarehouseId: normalizeNullableStringId(item.targetWarehouseId, `workbench.lines[${index}].targetWarehouseId`),
    targetWarehouseName: item.targetWarehouseName == null || item.targetWarehouseName === '' ? null : requiredText(item.targetWarehouseName, `workbench.lines[${index}].targetWarehouseName`),
    sourceNo: item.sourceNo == null || item.sourceNo === '' ? null : requiredText(item.sourceNo, `workbench.lines[${index}].sourceNo`),
  };
  if (workbenchType === 'PURCHASE_DRAFT' && (!line.supplierId || !line.supplierName || line.unitPrice === null || line.unitPrice < 0)) {
    throw new Error('采购建议缺少受控供应商或价格');
  }
  if (workbenchType === 'TRANSFER_DRAFT' && (!line.targetWarehouseId || !line.targetWarehouseName || line.targetWarehouseId === line.warehouseId)) {
    throw new Error('调拨建议缺少合法调入仓');
  }
  return line;
}

function normalizeWorkbench(value: unknown): AiAssistantWorkbench | null {
  try {
    const item = asRecord(value);
    if (!item) return null;
    const workbenchType = item.workbenchType;
    if (workbenchType !== 'PURCHASE_DRAFT' && workbenchType !== 'TRANSFER_DRAFT' && workbenchType !== 'LOCK_RELEASE' && workbenchType !== 'STOCK_FILTER') return null;
    if (!Array.isArray(item.lines) || !Array.isArray(item.sections)) return null;
    if (workbenchType !== 'STOCK_FILTER' && item.lines.length === 0) return null;
    return {
      workbenchId: normalizeStringId(item.workbenchId, 'workbench.workbenchId'),
      title: requiredText(item.title, 'workbench.title'),
      description: requiredText(item.description, 'workbench.description'),
      workbenchType,
      route: normalizeBusinessRoute(item.route, 'workbench.route'),
      lines: item.lines.map((line, index) => normalizeWorkbenchLine(line, workbenchType, index)),
      sections: item.sections.map(normalizeWorkbenchSection),
    };
  } catch {
    return null;
  }
}

function normalizeSource(item: AiContextSource): AiContextSource {
  const sourceType = ['TOOL', 'WORKFLOW', 'KNOWLEDGE', 'TASK'].includes(item.sourceType) ? item.sourceType : 'TOOL';
  return {
    sourceId: normalizeStringId(item.sourceId, 'sourceId'),
    sourceType,
    title: String(item.title || ''),
    description: String(item.description || ''),
    freshness: String(item.freshness || ''),
  };
}

function normalizeTrace(item: AiAgentTrace): AiAgentTrace {
  return {
    traceId: normalizeStringId(item.traceId, 'traceId'),
    agentCode: normalizeStringId(item.agentCode, 'agentCode'),
    agentName: String(item.agentName || ''),
    summary: String(item.summary || ''),
    status: item.status === 'PLANNED' || item.status === 'RUNNING' || item.status === 'FAILED' ? item.status : 'DONE',
  };
}

function normalizeActionCard(value: unknown): AiActionCard | null {
  try {
    const item = asRecord(value);
    if (!item || (item.actionType !== 'NAVIGATE' && item.actionType !== 'PREVIEW' && item.actionType !== 'WORKFLOW')) return null;
    if (item.riskLevel !== 'LOW' && item.riskLevel !== 'MEDIUM' && item.riskLevel !== 'HIGH') return null;
    const preview = normalizeActionPreview(item.preview);
    const route = normalizeBusinessRoute(item.route, 'action.route');
    if (item.actionType === 'NAVIGATE' ? !route : !preview) return null;
    return {
      actionId: normalizeStringId(item.actionId, 'actionId'),
      title: requiredText(item.title, 'action.title'),
      description: requiredText(item.description, 'action.description'),
      actionType: item.actionType,
      route,
      riskLevel: item.riskLevel,
      preview,
    };
  } catch {
    return null;
  }
}

function normalizeTaskCard(card: AiTaskCard | null | undefined): AiTaskCard | null {
  if (!card) return null;
  return {
    title: String(card.title || ''),
    description: String(card.description || ''),
    parameters: Array.isArray(card.parameters)
      ? card.parameters.map(item => ({ label: String(item.label || ''), value: String(item.value || '') }))
      : [],
  };
}

function normalizeChartRow(row: unknown) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) return {};
  return Object.entries(row).reduce<Record<string, string | number | null>>((result, [key, value]) => {
    if (typeof value === 'number' && Number.isFinite(value)) {
      result[key] = value;
      return result;
    }
    if (typeof value === 'string') {
      result[key] = value;
      return result;
    }
    result[key] = value == null ? null : String(value);
    return result;
  }, {});
}

function normalizeChart(chart: AiChartSpec): AiChartSpec {
  const type = chart.type === 'bar' || chart.type === 'pie' ? chart.type : 'line';
  return {
    chartId: normalizeStringId(chart.chartId, 'chartId'),
    type,
    title: String(chart.title || ''),
    description: String(chart.description || ''),
    xField: chart.xField ? String(chart.xField) : null,
    yFields: Array.isArray(chart.yFields) ? chart.yFields.map(String).filter(Boolean) : [],
    fieldLabels: chart.fieldLabels && typeof chart.fieldLabels === 'object' ? Object.fromEntries(Object.entries(chart.fieldLabels).map(([key, value]) => [String(key), String(value)])) : undefined,
    yUnit: chart.yUnit ? String(chart.yUnit) : null,
    nameField: chart.nameField ? String(chart.nameField) : null,
    valueField: chart.valueField ? String(chart.valueField) : null,
    data: Array.isArray(chart.data) ? chart.data.map(normalizeChartRow) : [],
  };
}

function normalizeMessage(item: AiChatMessage): AiChatMessage {
  if (!item || !['user', 'assistant'].includes(item.role) || typeof item.content !== 'string') throw new Error('消息字段不符合接口契约');
  return {
    messageId: normalizeStringId(item.messageId, 'messageId'),
    role: item.role === 'user' ? 'user' : 'assistant',
    content: String(item.content || ''),
    createdAt: String(item.createdAt || ''),
    charts: Array.isArray(item.charts) ? item.charts.map(normalizeChart) : [],
    actionCards: Array.isArray(item.actionCards) ? item.actionCards.map(normalizeActionCard).filter((card): card is AiActionCard => Boolean(card)) : [],
    sources: Array.isArray(item.sources) ? item.sources.map(normalizeSource) : [],
    agentTraces: Array.isArray(item.agentTraces) ? item.agentTraces.map(normalizeTrace) : [],
    taskCard: normalizeTaskCard(item.taskCard),
    workbench: normalizeWorkbench(item.workbench),
  };
}

function normalizePromptField(item: AiPromptField): AiPromptField {
  const fieldType = ['PRODUCT_MULTI', 'WAREHOUSE_MULTI', 'DAYS', 'TEXT'].includes(item.fieldType) ? item.fieldType : 'TEXT';
  return {
    fieldKey: normalizeStringId(item.fieldKey, 'fieldKey'),
    label: String(item.label || ''),
    fieldType,
    required: Boolean(item.required),
    placeholder: String(item.placeholder || ''),
    defaultValue: Array.isArray(item.defaultValue) ? item.defaultValue.map(String) : String(item.defaultValue || ''),
  };
}

function normalizeQuickPrompt(prompt: AiQuickPrompt): AiQuickPrompt {
  return {
    promptId: normalizeStringId(prompt.promptId, 'promptId'),
    title: String(prompt.title || ''),
    description: String(prompt.description || ''),
    intentCode: String(prompt.intentCode || ''),
    promptTemplate: String(prompt.promptTemplate || ''),
    fields: Array.isArray(prompt.fields) ? prompt.fields.map(normalizePromptField) : [],
    actionPreview: normalizeActionPreview(prompt.actionPreview),
  };
}

function normalizeConversation(conversation: AiConversationSummary): AiConversationSummary {
  return {
    conversationId: normalizeStringId(conversation.conversationId, 'conversationId'),
    title: String(conversation.title || ''),
    description: String(conversation.description || ''),
    updatedAt: String(conversation.updatedAt || ''),
  };
}

function normalizeOverview(data: AiAssistantOverview): AiAssistantOverview {
  return {
    refreshedAt: String(data.refreshedAt || ''),
    conversations: data.conversations.map(normalizeConversation),
    quickPrompts: data.quickPrompts.map(normalizeQuickPrompt),
  };
}

function normalizeConversationMessagePage(data: AiConversationMessagePage): AiConversationMessagePage {
  const pageNum = normalizeFiniteNumber(data.pageNum, 'pageNum');
  const pageSize = normalizeFiniteNumber(data.pageSize, 'pageSize');
  const total = normalizeFiniteNumber(data.total, 'total');
  if (pageNum < 1 || pageSize < 1 || total < 0) throw new Error('会话历史分页字段不符合约束');
  return {
    records: Array.isArray(data.records) ? data.records.map(normalizeMessage) : [],
    pageNum,
    pageSize,
    total,
    ...(typeof data.hasNext === 'boolean' ? { hasNext: data.hasNext } : {}),
  };
}


export async function getAiAssistantOverview() {
  return getResult<AiAssistantOverview>('/ai/assistant/overview').then(normalizeOverview);
}

async function getAiConversationMessagePage(conversationId: string, pageNum: number, pageSize: number, signal?: AbortSignal) {
  const response = await http.get<Result<AiConversationMessagePage>>(`/ai/assistant/conversations/${conversationId}/messages`, {
    params: { pageNum, pageSize }, signal, skipPageLoading: true, suppressErrorToast: true,
  });
  return normalizeConversationMessagePage(response.data.data);
}

export async function getAiConversationHistory(conversationId: string, signal?: AbortSignal) {
  const pageSize = 50;
  const messageById = new Map<string, AiChatMessage>();
  let pageNum = 1;
  for (;;) {
    const page = await getAiConversationMessagePage(conversationId, pageNum, pageSize, signal);
    const sizeBefore = messageById.size;
    page.records.forEach(message => messageById.set(message.messageId, message));
    const hasNext = typeof page.hasNext === 'boolean'
      ? page.hasNext
      : page.records.length >= page.pageSize && page.pageNum * page.pageSize < page.total;
    if (!hasNext) break;
    if (messageById.size === sizeBefore) throw new Error('会话历史分页重复，已停止继续加载');
    pageNum += 1;
  }
  return Array.from(messageById.values()).sort((left, right) => left.createdAt.localeCompare(right.createdAt) || left.messageId.localeCompare(right.messageId));
}

function normalizeStreamEvent(value: unknown, conversationId: string): AiChatStreamEvent {
  const item = asRecord(value);
  if (!item) throw new Error('AI 流式事件格式无效');
  const requestId = requiredText(item.requestId, 'requestId');
  const id = normalizeStringId(item.conversationId, 'conversationId');
  if (id !== conversationId) throw new Error('AI 流式事件返回了其他会话');
  if (item.eventType === 'MESSAGE_DELTA') {
    if (typeof item.contentDelta !== 'string') throw new Error('AI 文本增量格式无效');
    return { eventType: item.eventType, requestId, conversationId: id, contentDelta: item.contentDelta };
  }
  if (item.eventType === 'MESSAGE_FINAL') {
    const message = normalizeMessage(item.message as AiChatMessage);
    if (message.role !== 'assistant') throw new Error('AI 最终消息角色无效');
    return { eventType: item.eventType, requestId, conversationId: id, message };
  }
  if (item.eventType === 'ERROR') {
    return { eventType: item.eventType, requestId, conversationId: id, errorMessage: requiredText(item.errorMessage, 'errorMessage') };
  }
  throw new Error('AI 流式事件类型无效');
}

/** 与普通请求共用地址及动态认证头；长连接不使用普通 JSON 请求的 15 秒超时。 */
export async function sendAiAssistantMessage(request: AiChatRequest, onDelta: (text: string) => void, signal?: AbortSignal): Promise<AiChatResponse> {
  if (!/^[1-9]\d*$/.test(request.conversationId)) throw new Error('请先创建有效会话');
  const message = request.message.trim();
  if (!message || message.length > 2000) throw new Error('消息长度必须在 1 到 2000 字之间');
  const token = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
  const tokenName = localStorage.getItem(AUTH_TOKEN_NAME_STORAGE_KEY) || 'satoken';
  const headers = new Headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' });
  if (token) headers.set(tokenName, token);
  const baseURL = String(http.defaults.baseURL || '/api').replace(/\/$/, '');
  const response = await fetch(`${baseURL}/ai/assistant/messages/stream`, {
    method: 'POST', headers, body: JSON.stringify({ ...request, message }), signal,
  });
  if (!response.ok) {
    let errorMessage = `AI 请求失败（${response.status}）`;
    const error = await response.json().catch(() => null);
    if (error && typeof error.message === 'string') errorMessage = error.message;
    if (response.status === 401 && token === localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)) {
      localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
      localStorage.removeItem(AUTH_TOKEN_NAME_STORAGE_KEY);
      window.location.href = '/login';
    }
    throw new Error(errorMessage);
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
    await response.body?.cancel();
    throw new Error('AI 服务未返回约定的 SSE 响应');
  }
  let requestId: string | null = null;
  let finalMessage: AiChatMessage | null = null;
  await readSseStream(response.body, frame => {
    if (token !== localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)) throw new Error('登录会话已变更，请重新提问');
    let payload: unknown;
    try { payload = JSON.parse(frame.data); } catch { throw new Error('AI 流式事件 JSON 无效'); }
    const event = normalizeStreamEvent(payload, request.conversationId);
    if (frame.event !== 'message' && frame.event !== event.eventType) throw new Error('AI 流式事件名称不一致');
    if (requestId && requestId !== event.requestId) throw new Error('AI 流式请求标识不一致');
    requestId = event.requestId;
    if (finalMessage) throw new Error('AI 最终消息后仍有数据事件');
    if (event.eventType === 'ERROR') throw new Error(event.errorMessage);
    if (event.eventType === 'MESSAGE_DELTA') onDelta(event.contentDelta);
    if (event.eventType === 'MESSAGE_FINAL') finalMessage = event.message;
  }, signal);
  if (!finalMessage) throw new Error('AI 响应中断，未收到完整回复，请重试');
  return { conversationId: request.conversationId, message: finalMessage };
}

export async function createAiConversation() {
  return http.post<Result<AiConversationSummary>>('/ai/assistant/conversations').then(response => normalizeConversation(response.data.data));
}

export async function updateAiConversation(conversationId: string, payload: AiConversationUpdateRequest) {
  return http.patch<Result<AiConversationSummary>>(`/ai/assistant/conversations/${conversationId}`, payload).then(response => normalizeConversation(response.data.data));
}

export async function deleteAiConversation(conversationId: string) {
  await http.delete(`/ai/assistant/conversations/${conversationId}`);
}

// 页面组件暂存供以后接回；后端任务能力未实现前，不发送不存在的接口或生成假报告。
export async function getAiScheduledTaskPage(): Promise<AiScheduledTaskPage> { throw new Error('经营任务暂未开放'); }
export async function runAiScheduledTask(_taskId: string): Promise<AiScheduledTaskRunAccepted> { throw new Error('经营任务暂未开放'); }
export async function updateAiScheduledTaskStatus(_taskId: string, _status: AiScheduledTaskStatus): Promise<AiScheduledTask> { throw new Error('经营任务暂未开放'); }
export async function createAiScheduledTask(_payload: AiScheduledTaskUpdateRequest): Promise<AiScheduledTask> { throw new Error('经营任务暂未开放'); }
export async function updateAiScheduledTask(_taskId: string, _payload: AiScheduledTaskUpdateRequest): Promise<AiScheduledTask> { throw new Error('经营任务暂未开放'); }
