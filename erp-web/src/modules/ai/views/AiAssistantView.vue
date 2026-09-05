<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { CollapsibleContent, CollapsibleRoot } from 'reka-ui';
import { toast } from 'vue-sonner';
import {
  Bot,
  CalendarDays,
  ChevronDown,
  ChevronRight,
  ClipboardCheck,
  Database,
  Edit3,
  FileText,
  History,
  LineChart,
  LoaderCircle,
  PackageCheck,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  ShoppingCart,
  Sparkles,
  Trash2,
  UserRound,
} from 'lucide-vue-next';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import RemoteSearchSelect, { type RemoteSearchOption } from '@/components/common/RemoteSearchSelect.vue';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Textarea } from '@/components/ui/textarea';
import { listProducts } from '@/modules/product/products/api';
import { listWarehouses } from '@/modules/warehouse/warehouses/api';
import AiChartCard from '../components/AiChartCard.vue';
import AiComposerDock from '../components/AiComposerDock.vue';
import AiFadeSwitch from '../components/AiFadeSwitch.vue';
import { useAiChartGeometry } from '../composables/use-ai-chart-geometry';
import {
  createAiConversation,
  deleteAiConversation,
  getAiAssistantOverview,
  getAiConversationHistory,
  sendAiAssistantMessage,
  updateAiConversation,
} from '../api';
import type { AiActionCard, AiActionPreview, AiAssistantOverview, AiAssistantWorkbench, AiChartSpec, AiChatMessage, AiContextSource, AiPromptField, AiQuickPrompt, AiTaskCard } from '../types';

interface ConversationHistoryState {
  status: 'idle' | 'loading' | 'success' | 'error';
  error: string | null;
}

const router = useRouter();
const loading = ref(false);
const sending = ref(false);
const overview = ref<AiAssistantOverview | null>(null);
const inputMessage = ref('');
const conversationId = ref<string | null>(null);
const sidebarCollapsed = ref(false);
const conversationListExpanded = ref(false);
const conversationListCollapsed = ref(false);
const selectedConversationDate = ref<string | null>(null);
const promptListExpanded = ref(true);
const switchingConversationId = ref<string | null>(null);
const promptDialogOpen = ref(false);
const selectedPrompt = ref<AiQuickPrompt | null>(null);
const renameDialogOpen = ref(false);
const renamingConversationId = ref<string | null>(null);
const renameTitle = ref('');
const productPickerValue = ref('');
const warehousePickerValue = ref('');
const productPickerKey = ref(0);
const warehousePickerKey = ref(0);
const actionPreview = ref<AiActionPreview | null>(null);
const actionPreviewOpen = computed(() => Boolean(actionPreview.value));
const activeWorkbench = ref<AiAssistantWorkbench | null>(null);
const workbenchCollapsed = ref(false);
const workbenchWidth = ref(360);
const resizingWorkbench = ref(false);
const historyStateByConversation = reactive<Record<string, ConversationHistoryState>>({});
let historySequence = 0;
let historyController: AbortController | null = null;

const chartPalette = ['#2563eb', '#059669', '#d97706', '#7c3aed'];
const chartFrame = {
  width: 700,
  height: 260,
  left: 34,
  right: 622,
  topPadding: 18,
  plotHeight: 210,
  valueHeight: 168,
  labelY: 238,
  tickX: 638,
} as const;

const daysOptions = [
  { value: '7', label: '7 天' },
  { value: '14', label: '14 天' },
  { value: '30', label: '30 天' },
  { value: '60', label: '60 天' },
];

const promptForm = reactive<Record<string, string | string[]>>({});
const messagesByConversation = reactive<Record<string, AiChatMessage[]>>({});

const activeConversation = computed(() => overview.value?.conversations.find(item => item.conversationId === conversationId.value) || overview.value?.conversations[0]);
const conversationDateGroups = computed(() => {
  const groups = new Map<string, number>();
  (overview.value?.conversations || []).forEach(conversation => {
    const date = conversation.updatedAt.slice(0, 10);
    groups.set(date, (groups.get(date) || 0) + 1);
  });
  return Array.from(groups.entries())
    .sort(([left], [right]) => right.localeCompare(left))
    .map(([date, count]) => ({ date, count }));
});
const selectedConversationDateLabel = computed(() => selectedConversationDate.value || '全部日期');
const filteredConversations = computed(() => {
  const conversations = overview.value?.conversations || [];
  if (!selectedConversationDate.value) return conversations;
  return conversations.filter(item => item.updatedAt.startsWith(selectedConversationDate.value || ''));
});
const pinnedConversations = computed(() => filteredConversations.value.slice(0, 5));
const extraConversations = computed(() => filteredConversations.value.slice(5));
const hiddenConversationCount = computed(() => extraConversations.value.length);
const currentMessages = computed(() => {
  const id = conversationId.value;
  return id ? messagesByConversation[id] || [] : [];
});
const currentHistoryState = computed<ConversationHistoryState>(() => {
  const id = conversationId.value;
  return id ? historyStateByConversation[id] || { status: 'idle', error: null } : { status: 'idle', error: null };
});
const transitionLoading = computed(() => currentHistoryState.value.status === 'loading');
const isWelcomeStage = computed(() => currentMessages.value.length <= 1 && !sending.value);
const selectedPromptAction = computed(() => selectedPrompt.value?.actionPreview || null);

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

const {
  chartFields,
  chartColor,
  chartNumber,
  chartLabel,
  chartMax,
  chartY,
  chartTicks,
  chartGridLines,
  chartX,
  chartAxisLabels,
  chartLinePoints,
  chartBarField,
  chartBarWidth,
  chartBarX,
  chartBarHeight,
  chartBarY,
} = useAiChartGeometry(chartPalette, chartFrame);

function formatChartTick(value: number) {
  if (Math.abs(value) >= 10000) return `${(value / 10000).toFixed(1).replace(/\.0$/, '')}万`;
  if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '');
}

function syncWorkbenchFromMessages(messages: AiChatMessage[]) {
  activeWorkbench.value = [...messages].reverse().find(message => message.role === 'assistant' && message.workbench)?.workbench || null;
}

function isRequestCancelled(error: unknown) {
  return Boolean(error && typeof error === 'object' && (
    ('name' in error && (error.name === 'AbortError' || error.name === 'CanceledError'))
    || ('code' in error && error.code === 'ERR_CANCELED')
  ));
}

async function loadConversationHistory(id: string) {
  const sequence = ++historySequence;
  historyController?.abort();
  const controller = new AbortController();
  historyController = controller;
  switchingConversationId.value = id;
  historyStateByConversation[id] = { status: 'loading', error: null };
  activeWorkbench.value = null;

  try {
    const messages = await getAiConversationHistory(id, controller.signal);
    if (sequence !== historySequence || conversationId.value !== id) return;
    messagesByConversation[id] = messages;
    historyStateByConversation[id] = { status: 'success', error: null };
    syncWorkbenchFromMessages(messages);
  } catch (error) {
    if (isRequestCancelled(error) || sequence !== historySequence || conversationId.value !== id) return;
    historyStateByConversation[id] = {
      status: 'error',
      error: getApiErrorMessage(error) || '会话历史加载失败，请重试',
    };
  } finally {
    if (sequence === historySequence && conversationId.value === id) {
      switchingConversationId.value = null;
      if (historyController === controller) historyController = null;
    }
  }
}

async function switchConversation(id: string, force = false) {
  conversationId.value = id;
  const currentState = historyStateByConversation[id];
  if (!force && currentState?.status === 'success') {
    syncWorkbenchFromMessages(messagesByConversation[id] || []);
    return;
  }
  await loadConversationHistory(id);
}

async function retryConversationHistory() {
  const id = conversationId.value;
  if (id) await switchConversation(id, true);
}

async function loadOverview() {
  loading.value = true;
  try {
    overview.value = await getAiAssistantOverview();
    const firstId = overview.value.conversations[0]?.conversationId || null;
    conversationId.value = firstId;
    if (firstId) await switchConversation(firstId, true);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '智能经营助手概览加载失败');
  } finally {
    loading.value = false;
  }
}

async function selectConversation(id: string) {
  if (id === conversationId.value && historyStateByConversation[id]?.status !== 'error') return;
  await switchConversation(id, historyStateByConversation[id]?.status === 'error');
}
async function createConversation() {
  if (loading.value) return;
  loading.value = true;
  try {
    const conversation = await createAiConversation();
    if (overview.value) overview.value.conversations = [conversation, ...overview.value.conversations];
    await switchConversation(conversation.conversationId, true);
    toast.success('新会话已创建');
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '新建会话失败');
  } finally {
    loading.value = false;
  }
}

function openRename(conversationIdValue: string, currentTitle: string) {
  renamingConversationId.value = conversationIdValue;
  renameTitle.value = currentTitle;
  renameDialogOpen.value = true;
}

async function saveRename() {
  const id = renamingConversationId.value;
  const title = renameTitle.value.trim();
  if (!id || !title) {
    toast.warning('请填写会话名称');
    return;
  }
  try {
    const updated = await updateAiConversation(id, { title });
    if (overview.value) {
      overview.value.conversations = overview.value.conversations.map(item => item.conversationId === id ? updated : item);
    }
    renameDialogOpen.value = false;
    toast.success('会话名称已更新');
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '会话改名失败');
  }
}

async function removeConversation(id: string) {
  const conversation = overview.value?.conversations.find(item => item.conversationId === id);
  if (!conversation || !window.confirm(`确认删除会话「${conversation.title}」吗？`)) return;
  const deletingActiveConversation = conversationId.value === id;
  const previousHistoryState = historyStateByConversation[id];
  try {
    if (deletingActiveConversation) {
      historyController?.abort();
      historyController = null;
      historySequence += 1;
      switchingConversationId.value = null;
    }
    await deleteAiConversation(id);
    if (overview.value) overview.value.conversations = overview.value.conversations.filter(item => item.conversationId !== id);
    delete messagesByConversation[id];
    delete historyStateByConversation[id];
    const next = overview.value?.conversations[0]?.conversationId || null;
    conversationId.value = next;
    if (next) await switchConversation(next, true);
    else activeWorkbench.value = null;
    toast.success('会话已删除');
  } catch (error) {
    if (deletingActiveConversation && conversationId.value === id) {
      historyStateByConversation[id] = previousHistoryState?.status === 'success'
        ? previousHistoryState
        : { status: 'error', error: '删除失败，会话记录加载已中止，请重试' };
    }
    toast.warning(getApiErrorMessage(error) || '删除会话失败');
  }
}

function openPrompt(prompt: AiQuickPrompt) {
  selectedPrompt.value = prompt;
  productPickerValue.value = '';
  warehousePickerValue.value = '';
  productPickerKey.value += 1;
  warehousePickerKey.value += 1;
  Object.keys(promptForm).forEach(key => delete promptForm[key]);
  prompt.fields.forEach(field => {
    promptForm[field.fieldKey] = Array.isArray(field.defaultValue) ? [...field.defaultValue] : String(field.defaultValue || '');
  });
  promptDialogOpen.value = true;
}

function sourceTypeText(source: AiContextSource) {
  if (source.sourceType === 'WORKFLOW') return '工作流';
  if (source.sourceType === 'KNOWLEDGE') return '知识库';
  if (source.sourceType === 'TASK') return '任务结果';
  return '业务数据';
}

function productKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { productCode: value } : { productName: value };
}

function warehouseKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { warehouseCode: value } : { warehouseName: value };
}

async function fetchProductOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const page = await listProducts({
    status: 1,
    pageNum: 1,
    pageSize: 10,
    ...productKeywordQuery(keyword),
  }, { skipPageLoading: true });
  return page.records.map(item => ({
    value: `${item.productCode} ${item.productName}`,
    label: `${item.productCode} ${item.productName}（${item.unitName}）`,
  }));
}

async function fetchWarehouseOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const page = await listWarehouses({
    status: 1,
    pageNum: 1,
    pageSize: 10,
    ...warehouseKeywordQuery(keyword),
  }, { skipPageLoading: true });
  return page.records.map(item => ({
    value: `${item.warehouseCode} ${item.warehouseName}`,
    label: `${item.warehouseCode} ${item.warehouseName}`,
  }));
}

function fieldArrayValue(field: AiPromptField) {
  const value = promptForm[field.fieldKey];
  return Array.isArray(value) ? value : [];
}

function addUniqueFieldValue(field: AiPromptField, value: string) {
  const normalized = value.trim();
  if (!normalized) return;
  const current = fieldArrayValue(field);
  if (current.includes(normalized)) return;
  const allValue = field.fieldType === 'PRODUCT_MULTI' ? '全部商品' : '全部仓库';
  promptForm[field.fieldKey] = normalized === allValue ? [allValue] : [...current.filter(item => item !== allValue), normalized];
}

function addPromptFieldOption(field: AiPromptField, option: RemoteSearchOption) {
  addUniqueFieldValue(field, option.label);
  if (field.fieldType === 'PRODUCT_MULTI') {
    productPickerValue.value = '';
    productPickerKey.value += 1;
  } else {
    warehousePickerValue.value = '';
    warehousePickerKey.value += 1;
  }
}

function addPromptFieldAll(field: AiPromptField) {
  promptForm[field.fieldKey] = [field.fieldType === 'PRODUCT_MULTI' ? '全部商品' : '全部仓库'];
  if (field.fieldType === 'PRODUCT_MULTI') productPickerKey.value += 1;
  if (field.fieldType === 'WAREHOUSE_MULTI') warehousePickerKey.value += 1;
}

function removePromptFieldValue(field: AiPromptField, value: string) {
  promptForm[field.fieldKey] = fieldArrayValue(field).filter(item => item !== value);
}

function fieldDisplayValue(field: AiPromptField) {
  const value = promptForm[field.fieldKey];
  return Array.isArray(value) ? value.join('、') : String(value || '');
}

function buildPromptMessage(prompt: AiQuickPrompt) {
  return prompt.fields.reduce((message, field) => {
    return message.split(`{{${field.fieldKey}}}`).join(fieldDisplayValue(field) || '未指定');
  }, prompt.promptTemplate);
}

function buildPromptTaskCard(prompt: AiQuickPrompt): AiTaskCard {
  return {
    title: prompt.title,
    description: prompt.description,
    parameters: prompt.fields.map(field => ({
      label: field.label,
      value: fieldDisplayValue(field) || '未指定',
    })),
  };
}

async function submitPromptTask() {
  const prompt = selectedPrompt.value;
  if (!prompt) return;
  const invalid = prompt.fields.find(field => field.required && !fieldDisplayValue(field));
  if (invalid) {
    toast.warning(`请填写${invalid.label}`);
    return;
  }
  promptDialogOpen.value = false;
  await submitMessage(buildPromptMessage(prompt), buildPromptTaskCard(prompt));
}

async function submitMessage(messageOverride?: string, taskCard: AiTaskCard | null = null) {
  const message = (messageOverride || inputMessage.value).trim();
  if (!message || sending.value) return;
  const id = conversationId.value;
  if (!id || historyStateByConversation[id]?.status !== 'success') return;
  const previousMessages = messagesByConversation[id] || [];
  const optimisticMessage: AiChatMessage = {
    messageId: `local-user-${Date.now()}`,
    role: 'user',
    content: taskCard ? `发起任务：${taskCard.title}` : message,
    createdAt: nowText(),
    charts: [],
    actionCards: [],
    sources: [],
    agentTraces: [],
    taskCard,
    workbench: null,
  };
  messagesByConversation[id] = [...previousMessages, optimisticMessage];
  inputMessage.value = '';
  sending.value = true;

  try {
    const response = await sendAiAssistantMessage({ conversationId: id, message });
    const targetMessages = response.conversationId === id
      ? messagesByConversation[id]
      : [...(messagesByConversation[response.conversationId] || []), optimisticMessage];
    messagesByConversation[response.conversationId] = [...targetMessages, response.message];
    historyStateByConversation[response.conversationId] = { status: 'success', error: null };
    if (conversationId.value === id) {
      conversationId.value = response.conversationId;
      activeWorkbench.value = response.message.workbench;
    }
  } catch (error) {
    if (messagesByConversation[id]?.some(item => item.messageId === optimisticMessage.messageId)) {
      messagesByConversation[id] = previousMessages;
    }
    toast.warning(getApiErrorMessage(error) || '智能经营助手响应失败');
  } finally {
    sending.value = false;
  }
}

function openWorkbenchRoute() {
  if (activeWorkbench.value?.route) router.push(activeWorkbench.value.route);
}

function startWorkbenchResize(event: MouseEvent | PointerEvent) {
  if (workbenchCollapsed.value || resizingWorkbench.value) return;
  event.preventDefault();
  resizingWorkbench.value = true;
  const startX = event.clientX;
  const startWidth = workbenchWidth.value;

  const handleMove = (moveEvent: MouseEvent | PointerEvent) => {
    const delta = startX - moveEvent.clientX;
    workbenchWidth.value = Math.min(560, Math.max(280, startWidth + delta));
  };

  const handleUp = () => {
    resizingWorkbench.value = false;
    window.removeEventListener('pointermove', handleMove);
    window.removeEventListener('mousemove', handleMove);
  };

  window.addEventListener('pointermove', handleMove);
  window.addEventListener('mousemove', handleMove);
  window.addEventListener('pointerup', handleUp, { once: true });
  window.addEventListener('mouseup', handleUp, { once: true });
}

function openActionPreview(preview: AiActionPreview | null) {
  if (!preview) return;
  actionPreview.value = preview;
}

function closeActionPreview() {
  actionPreview.value = null;
}

function previewPromptAction(prompt: AiQuickPrompt) {
  openActionPreview(prompt.actionPreview);
}

function handleAction(card: AiActionCard) {
  if (card.preview) {
    openActionPreview(card.preview);
    return;
  }
  if (card.actionType === 'NAVIGATE' && card.route) router.push(card.route);
}

function confirmPreviewAction() {
  const route = actionPreview.value?.route || null;
  closeActionPreview();
  if (route) {
    router.push(route);
  }
}

onMounted(loadOverview);
onBeforeUnmount(() => {
  historyController?.abort();
  historySequence += 1;
});
</script>

<template>
  <section
    class="ai-agent-shell"
    :class="{ 'is-collapsed': sidebarCollapsed, 'is-workbench-collapsed': workbenchCollapsed, 'is-resizing-workbench': resizingWorkbench }"
    :style="{ '--ai-workbench-width': `${workbenchWidth}px` }"
  >
    <div class="ai-agent-body">
      <aside class="ai-agent-sidebar">
        <div class="ai-agent-sidebar__head">
          <div v-if="!sidebarCollapsed">
            <strong>会话管理</strong>
          </div>
          <Button size="sm" variant="outline" class="ai-icon-button" :aria-label="sidebarCollapsed ? '展开会话' : '折叠会话'" @click="sidebarCollapsed = !sidebarCollapsed">
            <PanelLeftOpen v-if="sidebarCollapsed" class="h-4 w-4" />
            <PanelLeftClose v-else class="h-4 w-4" />
          </Button>
        </div>

        <Button v-if="!sidebarCollapsed" size="sm" class="ai-new-chat" :disabled="loading" @click="createConversation">
          <LoaderCircle v-if="loading" class="h-4 w-4 animate-spin" />
          <Plus v-else class="h-4 w-4" />
          {{ loading ? '创建中' : '新建会话' }}
        </Button>
        <Button v-else size="sm" variant="outline" class="ai-icon-button" :aria-label="loading ? '正在创建会话' : '新建会话'" :disabled="loading" @click="createConversation">
          <LoaderCircle v-if="loading" class="h-4 w-4 animate-spin" />
          <Plus v-else class="h-4 w-4" />
        </Button>

        <CollapsibleRoot :open="!conversationListCollapsed" :unmount-on-hide="false" class="ai-conversation-list" :class="{ 'is-conversation-collapsed': conversationListCollapsed }">
          <div v-if="!sidebarCollapsed" class="ai-conversation-list__toolbar">
            <button class="ai-conversation-list__toggle" type="button" :aria-expanded="!conversationListCollapsed" @click="conversationListCollapsed = !conversationListCollapsed">
              <span>会话记录</span>
              <ChevronDown class="h-3.5 w-3.5" />
            </button>
            <Popover>
              <PopoverTrigger as-child>
                <Button type="button" size="sm" variant="outline" class="ai-conversation-date">
                  <CalendarDays class="h-3.5 w-3.5" />
                  {{ selectedConversationDateLabel }}
                </Button>
              </PopoverTrigger>
              <PopoverContent align="start" class="w-64 p-2">
                <div class="ai-conversation-calendar">
                  <button type="button" :class="{ 'is-active': !selectedConversationDate }" @click="selectedConversationDate = null">
                    <span>全部日期</span>
                    <small>{{ overview?.conversations.length || 0 }} 条</small>
                  </button>
                  <button
                    v-for="group in conversationDateGroups"
                    :key="group.date"
                    type="button"
                    :class="{ 'is-active': selectedConversationDate === group.date }"
                    @click="selectedConversationDate = group.date"
                  >
                    <span>{{ group.date }}</span>
                    <small>{{ group.count }} 条</small>
                  </button>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <button v-else type="button" class="ai-conversation-row" aria-label="展开会话记录" @click="conversationListCollapsed = !conversationListCollapsed">
            <History class="h-4 w-4" />
          </button>

          <CollapsibleContent class="ai-conversation-list__drawer">
            <AiFadeSwitch :switch-key="selectedConversationDate || 'all'" content-class="ai-conversation-list__items">
              <button
                v-for="conversation in pinnedConversations"
                :key="conversation.conversationId"
                type="button"
                class="ai-conversation-row"
                :class="{ 'is-active': conversationId === conversation.conversationId, 'is-switching': switchingConversationId === conversation.conversationId }"
                @click="selectConversation(conversation.conversationId)"
              >
                <History class="h-4 w-4" />
                <span v-if="!sidebarCollapsed">
                  <strong>{{ conversation.title }}</strong>
                  <small>{{ conversation.updatedAt.slice(0, 16) }} · {{ conversation.description }}</small>
                </span>
                <span v-if="!sidebarCollapsed" class="ai-row-actions">
                  <button type="button" aria-label="修改会话名称" @click.stop="openRename(conversation.conversationId, conversation.title)">
                    <Edit3 class="h-3.5 w-3.5" />
                  </button>
                  <button type="button" aria-label="删除会话" @click.stop="removeConversation(conversation.conversationId)">
                    <Trash2 class="h-3.5 w-3.5" />
                  </button>
                </span>
              </button>
              <CollapsibleRoot
                v-if="hiddenConversationCount > 0 && !sidebarCollapsed"
                :open="conversationListExpanded"
                :unmount-on-hide="false"
                class="ai-conversation-extra"
              >
                <CollapsibleContent class="ai-conversation-extra__drawer">
                  <div class="ai-conversation-extra__items">
                    <button
                      v-for="conversation in extraConversations"
                      :key="conversation.conversationId"
                      type="button"
                      class="ai-conversation-row"
                      :class="{ 'is-active': conversationId === conversation.conversationId, 'is-switching': switchingConversationId === conversation.conversationId }"
                      @click="selectConversation(conversation.conversationId)"
                    >
                      <History class="h-4 w-4" />
                      <span v-if="!sidebarCollapsed">
                        <strong>{{ conversation.title }}</strong>
                        <small>{{ conversation.updatedAt.slice(0, 16) }} · {{ conversation.description }}</small>
                      </span>
                      <span v-if="!sidebarCollapsed" class="ai-row-actions">
                        <button type="button" aria-label="修改会话名称" @click.stop="openRename(conversation.conversationId, conversation.title)">
                          <Edit3 class="h-3.5 w-3.5" />
                        </button>
                        <button type="button" aria-label="删除会话" @click.stop="removeConversation(conversation.conversationId)">
                          <Trash2 class="h-3.5 w-3.5" />
                        </button>
                      </span>
                    </button>
                  </div>
                </CollapsibleContent>
              </CollapsibleRoot>
            </AiFadeSwitch>
            <button
              v-if="hiddenConversationCount > 0 && !sidebarCollapsed"
              type="button"
              class="ai-conversation-more"
              @click="conversationListExpanded = !conversationListExpanded"
            >
              {{ conversationListExpanded ? '收起到最近 5 条' : `展开全部（还有 ${hiddenConversationCount} 条）` }}
            </button>
          </CollapsibleContent>
        </CollapsibleRoot>

        <CollapsibleRoot v-if="!sidebarCollapsed" :open="promptListExpanded" :unmount-on-hide="false" class="ai-prompt-list" :class="{ 'is-prompt-collapsed': !promptListExpanded }">
          <button class="ai-prompt-list__toggle" type="button" :aria-expanded="promptListExpanded" @click="promptListExpanded = !promptListExpanded">
            <span>快捷分析</span>
            <ChevronDown class="h-3.5 w-3.5" />
          </button>
          <CollapsibleContent class="ai-prompt-list__drawer">
            <AiFadeSwitch switch-key="quick-prompts" content-class="ai-prompt-list__items">
              <button v-for="prompt in overview?.quickPrompts || []" :key="prompt.promptId" class="ai-prompt-card" type="button" @click="openPrompt(prompt)">
                <Sparkles class="h-3.5 w-3.5" />
                <span>
                  <strong>{{ prompt.title }}</strong>
                  <small>{{ prompt.description }}</small>
                  <em v-if="prompt.actionPreview">
                    <ClipboardCheck class="h-3 w-3" />
                    {{ prompt.actionPreview.confirmLabel }}
                  </em>
                </span>
              </button>
            </AiFadeSwitch>
          </CollapsibleContent>
        </CollapsibleRoot>
      </aside>

      <main class="ai-agent-main">
      <section class="ai-chat-stage" :class="{ 'is-welcome-stage': isWelcomeStage, 'is-switching': transitionLoading }" :aria-busy="sending || transitionLoading">
        <div class="ai-chat-scroll">
        <Transition name="ai-conversation-panel" mode="out-in">
        <div :key="conversationId || 'empty-conversation'" class="ai-chat-stream" aria-live="polite" aria-relevant="additions text">
          <div v-if="currentHistoryState.status === 'loading'" class="ai-history-state" role="status">
            <LoaderCircle class="h-5 w-5 animate-spin" />
            <span>正在加载会话记录...</span>
          </div>
          <div v-else-if="currentHistoryState.status === 'error'" class="ai-history-state is-error" role="alert">
            <span>{{ currentHistoryState.error || '会话记录加载失败' }}</span>
            <Button size="sm" variant="outline" @click="retryConversationHistory">重试</Button>
          </div>
          <div v-else-if="currentMessages.length === 0" class="ai-history-state is-empty">
            <Bot class="h-5 w-5" />
            <span>暂无消息，可以开始提问。</span>
          </div>

          <article v-for="message in currentHistoryState.status === 'success' ? currentMessages : []" :key="message.messageId" class="ai-chat-message" :class="`is-${message.role}`">
            <div class="ai-chat-avatar">
              <UserRound v-if="message.role === 'user'" class="h-4 w-4" />
              <Bot v-else class="h-4 w-4" />
            </div>
            <div class="ai-chat-bubble">
              <div class="ai-chat-meta">
                <strong>{{ message.role === 'user' ? '我' : '智能经营助手' }}</strong>
                <span>{{ message.createdAt }}</span>
              </div>
              <p v-if="message.content">{{ message.content }}</p>

              <div v-if="message.taskCard" class="ai-task-message-card">
                <div>
                  <Badge variant="outline">任务</Badge>
                  <strong>{{ message.taskCard.title }}</strong>
                  <span>{{ message.taskCard.description }}</span>
                </div>
                <dl>
                  <template v-for="param in message.taskCard.parameters" :key="param.label">
                    <dt>{{ param.label }}</dt>
                    <dd>{{ param.value }}</dd>
                  </template>
                </dl>
              </div>

              <div v-if="message.charts.length" class="ai-message-charts">
                <AiChartCard v-for="chart in message.charts" :key="chart.chartId" :chart="chart" />
              </div>

              <div v-if="false && message.charts.length" class="ai-message-charts">
                <article v-for="chart in message.charts" :key="chart.chartId" class="ai-message-chart">
                  <div class="ai-message-chart__head">
                    <LineChart v-if="chart.type === 'line'" class="h-4 w-4 text-primary" />
                    <PackageCheck v-else class="h-4 w-4 text-primary" />
                    <strong>{{ chart.title }}</strong>
                    <Badge variant="outline">{{ chart.type === 'line' ? '折线图' : chart.type === 'bar' ? '柱状图' : '饼图' }}</Badge>
                  </div>
                  <p v-if="chart.description" class="ai-message-chart__desc">{{ chart.description }}</p>
                  <div v-if="chart.type === 'line'" class="ai-chat-line-chart">
                    <svg :viewBox="`0 0 ${chartFrame.width} ${chartFrame.height}`" role="img" :aria-label="chart.title">
                      <g class="ai-chart-grid-lines">
                        <line v-for="(lineY, index) in chartGridLines(chart)" :key="`grid-${index}`" :x1="chartFrame.left" :y1="lineY" :x2="chartFrame.right" :y2="lineY" />
                      </g>
                      <g class="ai-chart-y-axis">
                        <text v-for="(tick, index) in chartTicks(chart)" :key="`tick-${index}`" :x="chartFrame.tickX" :y="chartGridLines(chart)[index] + 4">{{ formatChartTick(tick) }}</text>
                      </g>
                      <g class="ai-chart-line-layer">
                        <polyline
                          v-for="(field, index) in chartFields(chart)"
                          :key="field"
                          class="ai-chart-line"
                          fill="none"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          :stroke="chartColor(index)"
                          :points="chartLinePoints(chart, field)"
                        />
                        <g v-for="(field, seriesIndex) in chartFields(chart)" :key="`points-${field}`" class="ai-chart-line-points">
                          <circle
                            v-for="(row, index) in chart.data"
                            :key="`${field}-${chartLabel(chart, row)}`"
                            :cx="chartX(chart, index)"
                            :cy="chartY(chart, chartNumber(row, field))"
                            r="2.8"
                            :fill="chartColor(seriesIndex)"
                          />
                        </g>
                      </g>
                      <g class="ai-chart-x-axis">
                        <text v-for="label in chartAxisLabels(chart)" :key="label.key" :x="label.x" :y="chartFrame.labelY" text-anchor="middle">{{ label.label }}</text>
                      </g>
                    </svg>
                    <div v-if="chartFields(chart).length > 1" class="ai-chart-legend">
                      <span v-for="(field, index) in chartFields(chart)" :key="field">
                        <i :style="{ background: chartColor(index) }" />
                        {{ field }}
                      </span>
                    </div>
                  </div>
                  <div v-else-if="chart.type === 'bar'" class="ai-chat-bar-chart">
                    <svg :viewBox="`0 0 ${chartFrame.width} ${chartFrame.height}`" role="img" :aria-label="chart.title">
                      <g class="ai-chart-grid-lines">
                        <line v-for="(lineY, index) in chartGridLines(chart)" :key="`grid-${index}`" :x1="chartFrame.left" :y1="lineY" :x2="chartFrame.right" :y2="lineY" />
                      </g>
                      <g class="ai-chart-y-axis">
                        <text v-for="(tick, index) in chartTicks(chart)" :key="`tick-${index}`" :x="chartFrame.tickX" :y="chartGridLines(chart)[index] + 4">{{ formatChartTick(tick) }}</text>
                      </g>
                      <g class="ai-chart-bar-layer">
                        <rect
                          v-for="(row, index) in chart.data"
                          :key="chartLabel(chart, row)"
                          :x="chartBarX(chart, index)"
                          :y="chartBarY(chart, row)"
                          :width="chartBarWidth(chart)"
                          :height="chartBarHeight(chart, row)"
                          rx="3"
                          :fill="chartColor(index)"
                        />
                      </g>
                      <g class="ai-chart-x-axis">
                        <text v-for="label in chartAxisLabels(chart)" :key="label.key" :x="label.x" :y="chartFrame.labelY" text-anchor="middle">{{ label.label }}</text>
                      </g>
                    </svg>
                  </div>
                  <div v-else class="ai-chat-pie-chart">
                    <span v-for="row in chart.data" :key="chartLabel(chart, row)">
                      <strong>{{ chartNumber(row, chart.valueField) }}</strong>
                      <small>{{ chartLabel(chart, row) }}</small>
                    </span>
                  </div>
                </article>
              </div>

              <div v-if="message.agentTraces.length" class="ai-agent-trace">
                <div v-for="trace in message.agentTraces" :key="trace.traceId">
                  <span>{{ trace.agentName }}</span>
                  <p>{{ trace.summary }}</p>
                </div>
              </div>

              <div v-if="false && message.sources.length" class="ai-source-strip">
                <div class="ai-source-strip__head">
                  <Database class="h-3.5 w-3.5" />
                  <span>引用数据来源</span>
                </div>
                <div class="ai-source-list">
                  <span v-for="source in message.sources" :key="source.sourceId">
                    <Badge variant="outline">{{ sourceTypeText(source) }}</Badge>
                    <strong>{{ source.title }}</strong>
                    <small>{{ source.description }} · {{ source.freshness }}</small>
                  </span>
                </div>
              </div>

              <div v-if="message.actionCards.length" class="ai-action-group">
                <button v-for="card in message.actionCards" :key="card.actionId" type="button" @click="handleAction(card)">
                  <span>
                    <strong>{{ card.title }}</strong>
                    <small>{{ card.description }}</small>
                  </span>
                  <ChevronRight class="h-4 w-4" />
                </button>
              </div>
            </div>
          </article>

          <article v-if="sending" key="assistant-thinking" class="ai-chat-message is-assistant">
            <div class="ai-chat-avatar"><Bot class="h-4 w-4" /></div>
            <div class="ai-chat-bubble">
              <div class="ai-thinking" role="status">
                <span /><span /><span />
                正在分析
              </div>
            </div>
          </article>
        </div>
        </Transition>
        </div>

      <AiComposerDock v-model="inputMessage" :sending="sending" :disabled="currentHistoryState.status !== 'success'" @submit="submitMessage()" />
      </section>
      </main>

      <aside class="ai-workbench">
        <button
          v-if="!workbenchCollapsed"
          type="button"
          class="ai-workbench-resizer"
          aria-label="调整工作框宽度"
          @pointerdown="startWorkbenchResize"
          @mousedown="startWorkbenchResize"
        />
        <div class="ai-workbench__head">
          <span v-if="!workbenchCollapsed">动态工作框</span>
          <Button
            class="ai-icon-button"
            type="button"
            size="icon"
            variant="ghost"
            :aria-label="workbenchCollapsed ? '展开工作框' : '收起工作框'"
            @click="workbenchCollapsed = !workbenchCollapsed"
          >
            <PanelRightOpen v-if="workbenchCollapsed" class="h-4 w-4" />
            <PanelRightClose v-else class="h-4 w-4" />
          </Button>
        </div>

        <div v-if="workbenchCollapsed" class="ai-workbench-collapsed">
          <span>工作框</span>
        </div>

        <div v-else-if="!activeWorkbench" class="ai-workbench-empty">
          <PackageCheck class="h-5 w-5 text-primary" />
          <strong>等待可操作结果</strong>
          <p>后端返回采购草稿、库存筛选条件等可操作数据时，会在这里生成可修改的工作框。</p>
        </div>

        <template v-else-if="activeWorkbench">
          <div class="ai-workbench-title">
            <component :is="activeWorkbench.workbenchType === 'PURCHASE_DRAFT' ? ShoppingCart : PackageCheck" class="h-4 w-4 text-primary" />
            <div>
              <div class="ai-workbench-title__row">
                <strong>{{ activeWorkbench.title }}</strong>
                <Badge variant="outline">只读建议</Badge>
              </div>
              <small>{{ activeWorkbench.description }}</small>
            </div>
          </div>

          <div v-if="activeWorkbench.lines.length" class="ai-workbench-lines">
            <article v-for="line in activeWorkbench.lines" :key="line.lineId">
              <div class="ai-workbench-line__top">
                <strong>{{ line.productName }}</strong>
                <Badge variant="outline">{{ activeWorkbench.workbenchType === 'PURCHASE_DRAFT' ? '采购' : activeWorkbench.workbenchType === 'TRANSFER_DRAFT' ? '调拨' : '释放' }}</Badge>
              </div>
              <div class="ai-workbench-fields">
                <div class="ai-field">
                  <Label>{{ activeWorkbench.workbenchType === 'LOCK_RELEASE' ? '释放数量' : activeWorkbench.workbenchType === 'TRANSFER_DRAFT' ? '调拨数量' : '建议数量' }}</Label>
                  <span>{{ line.suggestedQty }}</span>
                </div>
                <div class="ai-field">
                  <Label>{{ activeWorkbench.workbenchType === 'TRANSFER_DRAFT' ? '调出仓库' : activeWorkbench.workbenchType === 'LOCK_RELEASE' ? '锁定仓库' : '入库仓库' }}</Label>
                  <span>{{ line.warehouseName }}</span>
                </div>
                <div v-if="activeWorkbench.workbenchType === 'TRANSFER_DRAFT'" class="ai-field ai-workbench-field-wide">
                  <Label>调入仓库</Label>
                  <span>{{ line.targetWarehouseName }}</span>
                </div>
                <div v-if="activeWorkbench.workbenchType === 'PURCHASE_DRAFT'" class="ai-field ai-workbench-field-wide">
                  <Label>供应商</Label>
                  <span>{{ line.supplierName }}</span>
                </div>
                <div v-if="activeWorkbench.workbenchType === 'LOCK_RELEASE'" class="ai-field ai-workbench-field-wide">
                  <Label>来源单号</Label>
                  <span>{{ line.sourceNo }}</span>
                </div>
              </div>
              <div class="ai-field">
                <Label>{{ activeWorkbench.workbenchType === 'LOCK_RELEASE' ? '复核原因' : '建议原因' }}</Label>
                <span>{{ line.reason }}</span>
              </div>
            </article>
          </div>

          <div class="ai-workbench-sections">
            <section v-for="section in activeWorkbench.sections" :key="section.title">
              <h3>{{ section.title }}</h3>
              <ul>
                <li v-for="item in section.items" :key="item">{{ item }}</li>
              </ul>
            </section>
          </div>

          <div class="ai-workbench-actions">
            <Button size="sm" variant="outline" :disabled="!activeWorkbench.route" @click="openWorkbenchRoute">
              进入业务页
            </Button>
          </div>
        </template>
      </aside>
    </div>

    <Dialog v-model:open="promptDialogOpen">
      <DialogContent
        class="ai-prompt-dialog"
        :inert="actionPreviewOpen ? true : undefined"
        :style="actionPreviewOpen ? { pointerEvents: 'none', filter: 'blur(1px)' } : undefined"
      >
        <DialogHeader>
          <DialogTitle>{{ selectedPrompt?.title }}</DialogTitle>
          <DialogDescription>{{ selectedPrompt?.description }}</DialogDescription>
        </DialogHeader>
        <div v-if="selectedPrompt" class="ai-prompt-form">
          <div v-if="selectedPromptAction" class="ai-business-action-preview">
            <div>
              <PackageCheck class="h-4 w-4 text-primary" />
              <span>
                <strong>{{ selectedPromptAction.title }}</strong>
                <small>{{ selectedPromptAction.description }}</small>
              </span>
            </div>
            <Button type="button" size="sm" variant="outline" @click="previewPromptAction(selectedPrompt)">查看流程</Button>
          </div>
          <div v-for="field in selectedPrompt.fields" :key="field.fieldKey" class="ai-field">
            <Label :for="field.fieldKey">{{ field.label }} <span v-if="field.required" class="text-destructive">*</span></Label>
            <div v-if="field.fieldType === 'PRODUCT_MULTI' || field.fieldType === 'WAREHOUSE_MULTI'" class="ai-search-picker">
              <div class="ai-selected-tags">
                <Badge v-for="item in fieldArrayValue(field)" :key="item" variant="outline">
                  {{ item }}
                  <button type="button" :aria-label="`移除${field.label}`" @click="removePromptFieldValue(field, item)">×</button>
                </Badge>
                <span v-if="fieldArrayValue(field).length === 0">{{ field.placeholder }}</span>
              </div>
              <div class="ai-search-picker__row">
                <RemoteSearchSelect
                  v-if="field.fieldType === 'PRODUCT_MULTI'"
                  :key="productPickerKey"
                  v-model="productPickerValue"
                  placeholder="输入商品编码或名称搜索"
                  search-placeholder="输入商品编码或名称"
                  empty-text="暂无匹配商品"
                  :fetch-options="fetchProductOptions"
                  @select="option => addPromptFieldOption(field, option)"
                />
                <RemoteSearchSelect
                  v-else
                  :key="warehousePickerKey"
                  v-model="warehousePickerValue"
                  placeholder="输入仓库编码或名称搜索"
                  search-placeholder="输入仓库编码或名称"
                  empty-text="暂无匹配仓库"
                  :fetch-options="fetchWarehouseOptions"
                  @select="option => addPromptFieldOption(field, option)"
                />
                <Button type="button" variant="outline" class="ai-search-picker__all" @click="addPromptFieldAll(field)">
                  {{ field.fieldType === 'PRODUCT_MULTI' ? '全部商品' : '全部仓库' }}
                </Button>
              </div>
            </div>
            <AnchoredSelect
              v-else-if="field.fieldType === 'DAYS'"
              v-model="promptForm[field.fieldKey] as string"
              :options="daysOptions"
              :placeholder="field.placeholder"
            />
            <Textarea
              v-else
              :id="field.fieldKey"
              v-model="promptForm[field.fieldKey] as string"
              class="min-h-[82px]"
              :placeholder="field.placeholder"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="promptDialogOpen = false">取消</Button>
          <Button :disabled="sending" @click="submitPromptTask">发起任务</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <div v-if="actionPreviewOpen" class="ai-modal-click-shield" aria-hidden="true" />
    <AlertDialog :open="actionPreviewOpen" @update:open="value => { if (!value) closeActionPreview(); }">
      <AlertDialogContent class="ai-action-preview-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{{ actionPreview?.title }}</AlertDialogTitle>
          <AlertDialogDescription>{{ actionPreview?.description }}</AlertDialogDescription>
        </AlertDialogHeader>
        <div class="ai-action-preview">
          <div class="ai-action-preview__hero">
            <div>
              <FileText class="h-5 w-5" />
            </div>
            <span>
              <strong>建议确认模式</strong>
              <small>AI 只生成建议和草稿预览，正式单据仍需人工确认。</small>
            </span>
          </div>
          <div class="ai-action-preview__steps">
            <span v-for="(step, index) in actionPreview?.steps || []" :key="step">
              <i>{{ index + 1 }}</i>
              <b>{{ step }}</b>
            </span>
          </div>
        </div>
        <AlertDialogFooter>
          <AlertDialogCancel @click="closeActionPreview">取消</AlertDialogCancel>
          <AlertDialogAction @click="confirmPreviewAction">{{ actionPreview?.confirmLabel || '确认' }}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>

    <Dialog v-model:open="renameDialogOpen">
      <DialogContent class="ai-rename-dialog">
        <DialogHeader>
          <DialogTitle>修改会话名称</DialogTitle>
          <DialogDescription>只修改当前会话在左侧列表中的显示名称。</DialogDescription>
        </DialogHeader>
        <div class="ai-field">
          <Label for="renameTitle">会话名称 <span class="text-destructive">*</span></Label>
          <Input id="renameTitle" v-model="renameTitle" maxlength="40" />
        </div>
        <DialogFooter>
          <Button variant="outline" @click="renameDialogOpen = false">取消</Button>
          <Button @click="saveRename">保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </section>
</template>

<style scoped>
.ai-agent-shell {
  position: relative;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  height: calc(100dvh - 64px);
  min-height: 0;
  overflow: hidden;
  background: #f4f6f8;
}

.ai-agent-body {
  display: grid;
  grid-template-columns: 286px minmax(0, 1fr) var(--ai-workbench-width, 360px);
  min-height: 0;
  transition: grid-template-columns var(--motion-duration-base) var(--motion-ease-standard);
}

.ai-agent-shell.is-collapsed .ai-agent-body {
  grid-template-columns: 60px minmax(0, 1fr) var(--ai-workbench-width, 360px);
}

.ai-agent-shell.is-workbench-collapsed .ai-agent-body {
  grid-template-columns: 286px minmax(0, 1fr) 48px;
}

.ai-agent-shell.is-collapsed.is-workbench-collapsed .ai-agent-body {
  grid-template-columns: 60px minmax(0, 1fr) 48px;
}

.ai-agent-shell.is-resizing-workbench {
  cursor: col-resize;
  user-select: none;
}

.ai-agent-shell.is-resizing-workbench .ai-agent-body {
  transition: none;
}

.ai-agent-sidebar {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  overflow-y: auto;
  border-right: 1px solid var(--border);
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  padding: 16px 12px;
}

.ai-agent-sidebar__head,
.ai-prompt-list__toggle span {
  color: #667085;
  font-size: 12px;
}

.ai-agent-sidebar__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
}

.ai-agent-sidebar__head strong {
  display: block;
  color: #172033;
  font-size: 15px;
}

.ai-icon-button {
  width: 34px;
  height: 34px;
  padding: 0;
}

.ai-new-chat {
  justify-content: center;
  gap: 7px;
  height: 36px;
  box-shadow: 0 6px 14px rgb(37 99 235 / 14%);
}

.ai-conversation-list,
.ai-prompt-list {
  display: grid;
  gap: 6px;
}

.ai-conversation-list {
  gap: 7px;
}

.ai-conversation-list__toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  align-items: center;
}

.ai-conversation-list__toggle {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  border: 0;
  background: transparent;
  padding: 0 2px;
  color: #667085;
  font-size: 12px;
  cursor: pointer;
}

.ai-conversation-list__toggle svg,
.ai-prompt-list__toggle svg {
  color: #667085;
  transition: transform 180ms cubic-bezier(0.16, 1, 0.3, 1);
}

.ai-conversation-list.is-conversation-collapsed .ai-conversation-list__toggle svg,
.ai-prompt-list.is-prompt-collapsed .ai-prompt-list__toggle svg {
  transform: rotate(-90deg);
}

.ai-conversation-date {
  max-width: 118px;
  height: 27px;
  gap: 5px;
  overflow: hidden;
  padding-inline: 7px;
  font-size: 11px;
}

.ai-conversation-calendar {
  display: grid;
  gap: 5px;
}

.ai-conversation-calendar button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: white;
  padding: 7px 8px;
  color: #475467;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 150ms ease, background 150ms ease, color 150ms ease, transform 150ms ease;
}

.ai-conversation-calendar button:hover,
.ai-conversation-calendar button.is-active {
  transform: translateY(-1px);
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.ai-conversation-calendar small {
  color: #667085;
  font-size: 11px;
}

.ai-conversation-list__drawer,
.ai-prompt-list__drawer,
.ai-conversation-extra__drawer {
  overflow: hidden;
  will-change: height;
}

.ai-conversation-list__drawer[data-state="open"],
.ai-prompt-list__drawer[data-state="open"],
.ai-conversation-extra__drawer[data-state="open"] {
  animation: ai-sidebar-collapsible-down 220ms cubic-bezier(0.16, 1, 0.3, 1);
}

.ai-conversation-list__drawer[data-state="closed"],
.ai-prompt-list__drawer[data-state="closed"],
.ai-conversation-extra__drawer[data-state="closed"] {
  animation: ai-sidebar-collapsible-up 180ms cubic-bezier(0.4, 0, 1, 1);
}

.ai-conversation-list__items {
  display: grid;
  gap: 7px;
  padding-top: 6px;
}

.ai-conversation-extra__items {
  display: grid;
  gap: 7px;
  padding-top: 7px;
}

.ai-conversation-row,
.ai-prompt-card {
  display: grid;
  width: 100%;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  padding: 10px 9px;
  color: #475467;
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.ai-conversation-row:hover,
.ai-prompt-card:hover {
  transform: translateY(-1px);
  border-color: #d1d5db;
  box-shadow: 0 8px 18px rgb(15 23 42 / 6%);
}

.is-collapsed .ai-conversation-row {
  grid-template-columns: 20px;
  justify-content: center;
  padding: 9px 0;
}

.ai-conversation-row.is-active {
  border-color: color-mix(in srgb, var(--primary) 20%, var(--border));
  background: linear-gradient(180deg, #f2f7ff 0%, #eaf3ff 100%);
  box-shadow: inset 3px 0 0 var(--primary), 0 8px 18px rgb(37 99 235 / 8%);
}

.ai-conversation-row.is-switching {
  transform: translateX(2px);
}

.ai-conversation-row strong,
.ai-prompt-list strong {
  display: block;
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-conversation-row small,
.ai-prompt-list small {
  display: block;
  overflow: hidden;
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-prompt-list em {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  margin-top: 6px;
  border: 1px solid #dbeafe;
  border-radius: 999px;
  background: #eff6ff;
  padding: 2px 7px;
  color: #1d4ed8;
  font-size: 11px;
  font-style: normal;
}

.ai-row-actions {
  display: inline-flex;
  gap: 2px;
  opacity: 0;
}

.ai-conversation-row:hover .ai-row-actions,
.ai-conversation-row.is-active .ai-row-actions {
  opacity: 1;
}

.ai-row-actions button {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #667085;
  cursor: pointer;
}

.ai-row-actions button:hover {
  background: white;
  color: #172033;
}

.ai-conversation-more {
  width: 100%;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  padding: 8px;
  color: #475467;
  font-size: 12px;
  cursor: pointer;
}

.ai-conversation-more:hover {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.ai-prompt-list {
  margin-top: 18px;
}

.ai-prompt-list__toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  border: 0;
  background: transparent;
  padding: 0 2px 2px;
  cursor: pointer;
}

.ai-prompt-list__items {
  display: grid;
  gap: 6px;
  padding-top: 6px;
}

.ai-prompt-card {
  grid-template-columns: 18px minmax(0, 1fr);
  border-color: var(--border);
  background: white;
}

.ai-agent-main {
  position: relative;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
  min-width: 0;
  max-width: 1120px;
  width: 100%;
  margin: 0 auto;
  padding: 18px 28px 20px;
}

.ai-chat-stage {
  position: relative;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: linear-gradient(180deg, rgb(255 255 255 / 97%), rgb(250 252 255 / 96%));
}

.ai-chat-scroll {
  min-height: 0;
  overflow-y: auto;
  padding: 22px;
  scroll-padding-bottom: 22px;
}

.ai-chat-stream {
  display: grid;
  align-content: start;
  gap: 18px;
  min-height: 100%;
  transition: padding-top var(--motion-duration-base) var(--motion-ease-standard);
}

.ai-chat-stage.is-welcome-stage .ai-chat-stream {
  padding-top: 0;
}

.ai-conversation-panel-enter-active,
.ai-conversation-panel-leave-active {
  transition: opacity var(--motion-duration-slow) var(--motion-ease-standard);
}

.ai-conversation-panel-enter-from,
.ai-conversation-panel-leave-to {
  opacity: 0;
}

.ai-chat-message {
  display: flex;
  gap: 10px;
}

.ai-chat-message.is-user {
  flex-direction: row-reverse;
}

.ai-chat-avatar {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  color: var(--primary);
}

.ai-chat-message.is-user .ai-chat-avatar {
  background: #111827;
  color: white;
}

.ai-chat-bubble {
  max-width: min(780px, 84%);
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  box-shadow: 0 12px 28px rgb(15 23 42 / 5%);
  padding: 13px 15px;
}

.ai-chat-message.is-user .ai-chat-bubble {
  border-color: #bfdbfe;
  background: #eff6ff;
  box-shadow: none;
}

.ai-chat-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #667085;
  font-size: 12px;
}

.ai-chat-bubble > p {
  margin: 8px 0 0;
  color: #344054;
  line-height: 1.8;
}

.ai-task-message-card {
  display: grid;
  gap: 10px;
  margin-top: 10px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: white;
  padding: 12px;
}

.ai-task-message-card strong,
.ai-task-message-card span {
  display: block;
}

.ai-task-message-card strong {
  margin-top: 7px;
  color: #172033;
}

.ai-task-message-card span {
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
}

.ai-task-message-card dl {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0;
  font-size: 12px;
}

.ai-task-message-card dt {
  color: #667085;
}

.ai-task-message-card dd {
  margin: 0;
  color: #344054;
}

.ai-message-charts {
  display: grid;
  gap: 10px;
  margin-top: 10px;
}

.ai-message-chart {
  display: grid;
  gap: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 10px;
}

.ai-message-chart__head {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.ai-message-chart__head strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-message-chart__desc {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-chat-line-chart {
  display: grid;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chat-line-chart svg {
  width: 100%;
  height: 190px;
  overflow: visible;
  background: white;
}

.ai-chart-legend {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-chart-legend {
  flex-wrap: wrap;
  color: #475467;
  font-size: 11px;
}

.ai-chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ai-chart-legend i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
}

.ai-chart-grid-lines line {
  stroke: #e5e7eb;
  stroke-width: 1;
}

.ai-chart-y-axis text,
.ai-chart-x-axis text {
  fill: #475467;
  font-size: 10px;
  font-weight: 600;
}

.ai-chart-line {
  stroke-width: 3;
}

.ai-chart-line-points circle {
  stroke: white;
  stroke-width: 2;
}

.ai-chat-bar-chart {
  display: grid;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chat-bar-chart svg {
  width: 100%;
  height: 190px;
  overflow: visible;
  background: white;
}

.ai-chart-bar-layer rect {
  shape-rendering: geometricPrecision;
}

.ai-chat-pie-chart {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-chat-pie-chart span {
  display: grid;
  gap: 4px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chat-pie-chart strong {
  color: #172033;
  font-size: 17px;
}

.ai-chat-pie-chart small {
  color: #667085;
  font-size: 11px;
}

.ai-agent-trace {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  border-left: 2px solid #bfdbfe;
  padding-left: 10px;
}

.ai-agent-trace span {
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}

.ai-agent-trace p {
  margin: 0;
  color: #667085;
  font-size: 12px;
}

.ai-source-strip {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px;
}

.ai-source-strip__head {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #667085;
  font-size: 12px;
  font-weight: 700;
}

.ai-source-list {
  display: grid;
  gap: 7px;
}

.ai-source-list > span {
  display: grid;
  grid-template-columns: auto minmax(92px, auto) minmax(0, 1fr);
  gap: 7px;
  align-items: center;
  color: #475467;
  font-size: 12px;
}

.ai-source-list strong {
  overflow: hidden;
  color: #172033;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-source-list small {
  overflow: hidden;
  color: #667085;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-action-group {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.ai-action-group button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #f8fafc;
  padding: 10px;
  text-align: left;
  cursor: pointer;
}

.ai-action-group small {
  display: block;
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
}

.ai-thinking {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #667085;
  font-size: 13px;
}

.ai-thinking span {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--primary);
  animation: ai-thinking 900ms infinite ease-in-out;
}

.ai-thinking span:nth-child(2) {
  animation-delay: 120ms;
}

.ai-thinking span:nth-child(3) {
  animation-delay: 240ms;
}

.ai-prompt-dialog {
  width: min(720px, calc(100vw - 2rem));
  max-width: min(720px, calc(100vw - 2rem));
}

.ai-prompt-dialog.is-confirming {
  pointer-events: none;
  filter: blur(1px);
}

.ai-modal-click-shield {
  position: fixed;
  inset: 0;
  z-index: 72;
  background: rgb(15 23 42 / 20%);
  backdrop-filter: blur(2px);
  pointer-events: auto;
}

.ai-prompt-form {
  display: grid;
  gap: 14px;
}

.ai-business-action-preview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
  padding: 10px 12px;
}

.ai-business-action-preview > div {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.ai-business-action-preview strong,
.ai-business-action-preview small {
  display: block;
}

.ai-business-action-preview strong {
  color: #172033;
  font-size: 13px;
}

.ai-business-action-preview small {
  color: #667085;
  font-size: 12px;
}

.ai-rename-dialog {
  width: min(420px, calc(100vw - 2rem));
  max-width: min(420px, calc(100vw - 2rem));
}

.ai-action-preview-dialog {
  z-index: 90 !important;
  width: min(560px, calc(100vw - 2rem));
  max-width: min(560px, calc(100vw - 2rem));
  border-radius: 14px;
}

.ai-action-preview {
  display: grid;
  gap: 14px;
}

.ai-action-preview__hero {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: linear-gradient(135deg, #eff6ff, #f8fbff);
  padding: 12px;
}

.ai-action-preview__hero > div {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 10px;
  background: #2563eb;
  color: white;
}

.ai-action-preview__hero strong,
.ai-action-preview__hero small {
  display: block;
}

.ai-action-preview__hero strong {
  color: #172033;
  font-size: 14px;
}

.ai-action-preview__hero small {
  margin-top: 3px;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-action-preview__steps {
  display: grid;
  gap: 8px;
}

.ai-action-preview__steps span {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 9px;
  align-items: start;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 9px 10px;
}

.ai-action-preview__steps i {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 999px;
  background: #f1f5f9;
  color: #2563eb;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
}

.ai-action-preview__steps b {
  color: #475467;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.7;
}

.ai-field {
  display: grid;
  gap: 7px;
}

.ai-search-picker {
  display: grid;
  gap: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px;
}

.ai-selected-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 36px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: white;
  padding: 6px 8px;
}

.ai-selected-tags span {
  color: #667085;
  font-size: 13px;
}

.ai-selected-tags [data-slot="badge"] {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: min(100%, 260px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-selected-tags button {
  display: inline-grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #667085;
  cursor: pointer;
  line-height: 1;
}

.ai-selected-tags button:hover {
  background: #e2e8f0;
  color: #172033;
}

.ai-search-picker__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px;
  gap: 8px;
}

.ai-search-picker__all {
  justify-content: center;
}

.ai-workbench {
  position: relative;
  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
  overflow-y: auto;
  border-left: 1px solid var(--border);
  background: #f8fafc;
  padding: 16px 14px;
}

.ai-workbench-resizer {
  position: absolute;
  top: 0;
  bottom: 0;
  left: -5px;
  z-index: 3;
  width: 10px;
  border: 0;
  background: transparent;
  cursor: col-resize;
}

.ai-workbench-resizer::after {
  position: absolute;
  top: 18px;
  bottom: 18px;
  left: 4px;
  width: 2px;
  border-radius: 999px;
  background: transparent;
  content: '';
}

.ai-workbench-resizer:hover::after,
.is-resizing-workbench .ai-workbench-resizer::after {
  background: color-mix(in srgb, var(--primary) 42%, #cbd5e1);
}

.ai-workbench__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: #667085;
  font-size: 12px;
}

.is-workbench-collapsed .ai-workbench {
  overflow: hidden;
  padding: 14px 6px;
}

.is-workbench-collapsed .ai-workbench__head {
  justify-content: center;
}

.ai-workbench-collapsed {
  display: grid;
  place-items: center;
  min-height: 160px;
  color: #667085;
  font-size: 12px;
  writing-mode: vertical-rl;
}

.ai-workbench-empty,
.ai-workbench-title,
.ai-workbench-lines article,
.ai-workbench-sections section {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.ai-workbench-empty {
  display: grid;
  gap: 8px;
  padding: 14px;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-workbench-empty strong {
  color: #172033;
  font-size: 14px;
}

.ai-workbench-empty p {
  margin: 0;
}

.ai-workbench-title {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 8px;
  padding: 12px;
}

.ai-workbench-title__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.ai-workbench-title__row strong {
  min-width: 0;
}

.ai-workbench-status {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  border: 1px solid #bbf7d0;
  border-radius: 6px;
  background: #f0fdf4;
  padding: 2px 6px;
  color: #047857;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
}

.ai-workbench-generated {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 7px;
}

.ai-workbench-generated [data-slot="badge"] {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #047857;
}

.ai-workbench-title strong,
.ai-workbench-title small {
  display: block;
}

.ai-workbench-title strong {
  color: #172033;
  font-size: 14px;
}

.ai-workbench-title small {
  margin-top: 4px;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.ai-workbench-lines,
.ai-workbench-sections {
  display: grid;
  gap: 10px;
}

.ai-workbench-lines article {
  display: grid;
  gap: 10px;
  padding: 12px;
}

.ai-workbench-line__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ai-workbench-line__top strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-workbench-fields {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 8px;
}

.ai-workbench-field-wide {
  grid-column: 1 / -1;
}

.ai-workbench .ai-field {
  gap: 5px;
}

.ai-workbench .ai-field label {
  color: #667085;
  font-size: 12px;
}

.ai-workbench .ai-field input,
.ai-workbench .ai-field textarea {
  font-size: 12px;
}

.ai-workbench .ai-field input {
  height: 32px;
}

.ai-workbench-sections section {
  display: grid;
  gap: 10px;
  padding: 12px;
}

.ai-mini-line-chart {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 96px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px;
}

.ai-mini-line-chart span {
  position: relative;
  width: 100%;
  min-width: 0;
  border-radius: 999px 999px 4px 4px;
  background: #2563eb;
}

.ai-mini-line-chart em {
  position: absolute;
  right: 50%;
  bottom: -20px;
  transform: translateX(50%);
  color: #667085;
  font-size: 10px;
  font-style: normal;
}

.ai-mini-pie-chart {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-mini-pie-chart span {
  display: grid;
  gap: 3px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfcfe;
  padding: 9px;
}

.ai-mini-pie-chart strong {
  color: #172033;
  font-size: 16px;
}

.ai-mini-pie-chart small {
  color: #667085;
  font-size: 11px;
}

.ai-workbench-sections h3 {
  margin: 0;
  color: #172033;
  font-size: 13px;
}

.ai-workbench-sections ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  color: #475467;
  font-size: 12px;
  line-height: 1.6;
}

.ai-workbench-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-workbench-actions [data-slot="button"] {
  min-width: 0;
  padding-inline: 8px;
  white-space: nowrap;
}

@keyframes ai-thinking {
  0%,
  80%,
  100% {
    opacity: 0.35;
    transform: translateY(0);
  }

  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

@keyframes ai-sidebar-collapsible-down {
  from {
    height: 0;
  }

  to {
    height: var(--reka-collapsible-content-height);
  }
}

@keyframes ai-sidebar-collapsible-up {
  from {
    height: var(--reka-collapsible-content-height);
  }

  to {
    height: 0;
  }
}

@media (max-width: 980px) {
  .ai-agent-body,
  .ai-agent-shell.is-collapsed .ai-agent-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .ai-agent-sidebar {
    display: none;
  }

  .ai-agent-main {
    padding: 20px;
  }

  .ai-chat-bubble {
    max-width: calc(100% - 42px);
  }

  .ai-workbench {
    border-top: 1px solid var(--border);
    border-left: 0;
  }
}
</style>
