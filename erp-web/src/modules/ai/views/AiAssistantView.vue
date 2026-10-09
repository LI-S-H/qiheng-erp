<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { Bot, CalendarDays, ChevronDown, ChevronsDown, ChevronsUp, History, LoaderCircle, PanelLeftClose, PanelLeftOpen, Plus, UserRound } from 'lucide-vue-next';
import { CollapsibleContent, CollapsibleRoot } from 'reka-ui';
import { toast } from 'vue-sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import RowActionsMenu from '@/components/common/RowActionsMenu.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import { getApiErrorMessage } from '@/api/http';
import AiChartCard from '../components/AiChartCard.vue';
import AiComposerDock from '../components/AiComposerDock.vue';
import AiFadeSwitch from '../components/AiFadeSwitch.vue';
import AiMarkdownContent from '../components/AiMarkdownContent.vue';
import { createAiConversation, deleteAiConversation, getAiAssistantOverview, getAiConversationHistory, sendAiAssistantMessage, updateAiConversation } from '../api';
import type { AiAssistantOverview, AiChatMessage } from '../types';

interface ConversationHistoryState {
  status: 'idle' | 'loading' | 'success' | 'error';
  error: string | null;
}
const loading = ref(false);
const sending = ref(false);
const overview = ref<AiAssistantOverview | null>(null);
const overviewError = ref('');
const streamError = ref('');
const inputMessage = ref('');
const conversationId = ref<string | null>(null);
const sidebarCollapsed = ref(false);
const conversationListExpanded = ref(false);
const conversationListCollapsed = ref(false);
const selectedConversationDate = ref<string | null>(null);
const switchingConversationId = ref<string | null>(null);
const renameDialogOpen = ref(false);
const renamingConversationId = ref<string | null>(null);
const renameTitle = ref('');
const renameError = ref('');
const renaming = ref(false);
const deletingConversationId = ref<string | null>(null);
const deleteDialogOpen = ref(false);
const pendingDeleteConversation = ref<{ id: string; title: string } | null>(null);
const messageScroll = ref<HTMLElement | null>(null);
const messagesByConversation = reactive<Record<string, AiChatMessage[]>>({});
const historyStateByConversation = reactive<Record<string, ConversationHistoryState>>({});
let historySequence = 0;
let historyController: AbortController | null = null;
let generationSequence = 0;
let generationController: AbortController | null = null;
let generatingConversationId: string | null = null;
let disposed = false;

const conversationDateGroups = computed(() => {
  const groups = new Map<string, number>();
  (overview.value?.conversations || []).forEach(conversation => {
    const date = conversation.updatedAt.slice(0, 10);
    groups.set(date, (groups.get(date) || 0) + 1);
  });
  return Array.from(groups.entries()).sort(([left], [right]) => right.localeCompare(left)).map(([date, count]) => ({ date, count }));
});
const selectedConversationDateLabel = computed(() => selectedConversationDate.value || '全部日期');
const filteredConversations = computed(() => (overview.value?.conversations || []).filter(item => !selectedConversationDate.value || item.updatedAt.startsWith(selectedConversationDate.value)));
const pinnedConversations = computed(() => filteredConversations.value.slice(0, 5));
const extraConversations = computed(() => filteredConversations.value.slice(5));
const hiddenConversationCount = computed(() => extraConversations.value.length);
const currentMessages = computed(() => conversationId.value ? messagesByConversation[conversationId.value] || [] : []);
const currentHistoryState = computed<ConversationHistoryState>(() => conversationId.value ? historyStateByConversation[conversationId.value] || { status: 'idle', error: null } : { status: 'idle', error: null });
const transitionLoading = computed(() => currentHistoryState.value.status === 'loading');

function nowText() {
  return new Date().toLocaleString('sv-SE');
}
function isRequestCancelled(error: unknown) {
  return Boolean(error && typeof error === 'object' && (
    ('name' in error && (error.name === 'AbortError' || error.name === 'CanceledError')) || ('code' in error && error.code === 'ERR_CANCELED')
  ));
}
function stopGeneration() {
  generationController?.abort();
}
function discardGeneration() {
  stopGeneration();
  if (generatingConversationId) historyStateByConversation[generatingConversationId] = { status: 'idle', error: null };
  generationSequence += 1;
  generationController = null;
  generatingConversationId = null;
  sending.value = false;
}
async function scrollToLatest() {
  await nextTick();
  if (messageScroll.value) messageScroll.value.scrollTop = messageScroll.value.scrollHeight;
}
async function loadConversationHistory(id: string) {
  const sequence = ++historySequence;
  historyController?.abort();
  const controller = new AbortController();
  historyController = controller;
  switchingConversationId.value = id;
  historyStateByConversation[id] = { status: 'loading', error: null };
  try {
    const messages = await getAiConversationHistory(id, controller.signal);
    if (disposed || sequence !== historySequence || conversationId.value !== id) return;
    messagesByConversation[id] = messages;
    historyStateByConversation[id] = { status: 'success', error: null };
    await scrollToLatest();
  } catch (error) {
    if (isRequestCancelled(error) || disposed || sequence !== historySequence || conversationId.value !== id) return;
    historyStateByConversation[id] = { status: 'error', error: getApiErrorMessage(error) || '会话历史加载失败，请重试' };
  } finally {
    if (sequence === historySequence && conversationId.value === id) {
      switchingConversationId.value = null;
      if (historyController === controller) historyController = null;
    }
  }
}
async function switchConversation(id: string, force = false) {
  if (conversationId.value !== id) discardGeneration();
  conversationId.value = id;
  streamError.value = '';
  if (!force && historyStateByConversation[id]?.status === 'success') return;
  await loadConversationHistory(id);
}
async function retryConversationHistory() {
  if (conversationId.value) await switchConversation(conversationId.value, true);
}
async function loadOverview() {
  if (loading.value) return;
  loading.value = true;
  overviewError.value = '';
  try {
    const data = await getAiAssistantOverview();
    if (disposed) return;
    overview.value = data;
    const firstId = overview.value.conversations[0]?.conversationId || null;
    conversationId.value = firstId;
    if (firstId) await switchConversation(firstId, true);
  } catch (error) {
    if (!disposed) overviewError.value = getApiErrorMessage(error) || '会话列表加载失败，请重试';
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
  discardGeneration();
  loading.value = true;
  try {
    const conversation = await createAiConversation();
    if (disposed) return;
    if (!overview.value) overview.value = { refreshedAt: nowText(), quickPrompts: [], conversations: [] };
    overview.value.conversations = [conversation, ...overview.value.conversations];
    overviewError.value = '';
    await switchConversation(conversation.conversationId, true);
    toast.success('新会话已创建');
  } catch (error) {
    if (!disposed) toast.warning(getApiErrorMessage(error) || '新建会话失败');
  } finally { loading.value = false; }
}
function openRename(id: string, title: string) {
  renamingConversationId.value = id;
  renameTitle.value = title;
  renameError.value = '';
  renameDialogOpen.value = true;
}
async function saveRename() {
  if (renaming.value) return;
  const id = renamingConversationId.value;
  const title = renameTitle.value.trim();
  if (!id || !title || title.length > 40) { renameError.value = '请填写 1 到 40 字的会话名称'; return; }
  renaming.value = true;
  renameError.value = '';
  try {
    const updated = await updateAiConversation(id, { title });
    if (disposed) return;
    if (overview.value) overview.value.conversations = overview.value.conversations.map(item => item.conversationId === id ? updated : item);
    renameDialogOpen.value = false;
    toast.success('会话名称已更新');
  } catch (error) { renameError.value = getApiErrorMessage(error) || '会话改名失败'; }
  finally { renaming.value = false; }
}
function openDeleteConfirmation(id: string) {
  if (deletingConversationId.value) return;
  const conversation = overview.value?.conversations.find(item => item.conversationId === id);
  if (!conversation) return;
  pendingDeleteConversation.value = { id, title: conversation.title };
  deleteDialogOpen.value = true;
}
function updateDeleteDialogOpen(open: boolean) {
  // 确认组件会自动请求关闭；删除完成前保持弹窗与提交锁，失败后可以重试。
  if (open) { deleteDialogOpen.value = true; return; }
  void nextTick(() => {
    if (deletingConversationId.value) return;
    deleteDialogOpen.value = false;
    pendingDeleteConversation.value = null;
  });
}
async function removeConversation() {
  if (deletingConversationId.value) return;
  const id = pendingDeleteConversation.value?.id;
  if (!id) return;
  const conversation = overview.value?.conversations.find(item => item.conversationId === id);
  if (!conversation) { updateDeleteDialogOpen(false); return; }
  if (generatingConversationId === id) discardGeneration();
  deletingConversationId.value = id;
  try {
    await deleteAiConversation(id);
    if (disposed) return;
    deleteDialogOpen.value = false;
    pendingDeleteConversation.value = null;
    if (overview.value) overview.value.conversations = overview.value.conversations.filter(item => item.conversationId !== id);
    delete messagesByConversation[id];
    delete historyStateByConversation[id];
    if (conversationId.value === id) {
      historyController?.abort();
      historySequence += 1;
      const next = overview.value?.conversations[0]?.conversationId || null;
      conversationId.value = next;
      if (next) await switchConversation(next, true);
    }
    toast.success('会话已删除');
  } catch (error) { if (!disposed) toast.warning(getApiErrorMessage(error) || '删除会话失败'); }
  finally { deletingConversationId.value = null; }
}
function localMessage(messageId: string, role: 'user' | 'assistant', content: string): AiChatMessage {
  return { messageId, role, content, createdAt: nowText(), charts: [], sources: [], actionCards: [], agentTraces: [], taskCard: null, workbench: null };
}
async function submitMessage() {
  const message = inputMessage.value.trim();
  const id = conversationId.value;
  if (!message || sending.value || !id || currentHistoryState.value.status !== 'success') return;
  if (message.length > 2000) { streamError.value = '消息不能超过 2000 字'; return; }
  generationController?.abort();
  const sequence = ++generationSequence;
  const controller = new AbortController();
  generationController = controller;
  generatingConversationId = id;
  const placeholderId = `local-assistant-${sequence}`;
  messagesByConversation[id] = [...(messagesByConversation[id] || []), localMessage(`local-user-${sequence}`, 'user', message), localMessage(placeholderId, 'assistant', '')];
  inputMessage.value = '';
  streamError.value = '';
  sending.value = true;
  await scrollToLatest();
  const isCurrent = () => !disposed && generationSequence === sequence && conversationId.value === id;
  try {
    const response = await sendAiAssistantMessage({ conversationId: id, message }, delta => {
      if (!isCurrent()) return;
      const placeholder = messagesByConversation[id]?.find(item => item.messageId === placeholderId);
      if (placeholder) placeholder.content += delta;
      void scrollToLatest();
    }, controller.signal);
    if (!isCurrent()) return;
    messagesByConversation[id] = messagesByConversation[id].map(item => item.messageId === placeholderId ? response.message : item);
    sending.value = false;
    // 最终消息替换临时气泡后再回读，取回真实用户消息 ID，刷新后的历史与当前页面一致。
    try {
      const persisted = await getAiConversationHistory(id, controller.signal);
      if (!isCurrent()) return;
      messagesByConversation[id] = persisted;
    } catch (error) {
      if (!isCurrent()) return;
      // FINAL 已确认回复成功，历史同步失败不能把已完成的提问恢复到输入框诱导重复发送。
      streamError.value = '回复已完成，但历史同步失败，可刷新页面重试同步。';
    }
    const conversation = overview.value?.conversations.find(item => item.conversationId === id);
    if (conversation) { conversation.updatedAt = response.message.createdAt; conversation.description = message.slice(0, 80); }
    await scrollToLatest();
  } catch (error) {
    if (!isCurrent()) return;
    inputMessage.value = message;
    streamError.value = isRequestCancelled(error) ? '生成已停止，可重新发送问题。' : getApiErrorMessage(error) || 'AI 响应失败，请重试';
    // 失败或停止时后端可能已经保存用户消息，按持久化历史恢复，避免乐观回滚造成错乱。
    await loadConversationHistory(id);
  } finally {
    if (generationSequence === sequence) {
      sending.value = false;
      generationController = null;
      generatingConversationId = null;
    }
  }
}
onMounted(loadOverview);
onBeforeUnmount(() => {
  disposed = true;
  discardGeneration();
  historyController?.abort();
  historySequence += 1;
});
</script>

<template>
  <section class="ai-agent-shell" :class="{ 'is-collapsed': sidebarCollapsed }">
    <div class="ai-agent-body">
      <aside class="ai-agent-sidebar">
        <div class="ai-agent-sidebar__head">
          <div v-if="!sidebarCollapsed">
            <strong>会话管理</strong>
          </div>
          <Tooltip :disable-hoverable-content="true">
            <TooltipTrigger as-child>
              <Button size="sm" variant="outline" class="ai-icon-button" :aria-label="sidebarCollapsed ? '展开会话' : '折叠会话'" @click="sidebarCollapsed = !sidebarCollapsed">
                <PanelLeftOpen v-if="sidebarCollapsed" class="h-4 w-4" />
                <PanelLeftClose v-else class="h-4 w-4" />
              </Button>
            </TooltipTrigger>
            <TooltipContent side="right">{{ sidebarCollapsed ? '展开会话' : '折叠会话' }}</TooltipContent>
          </Tooltip>
        </div>

        <Button v-if="!sidebarCollapsed" size="sm" class="ai-new-chat" :disabled="loading" @click="createConversation">
          <LoaderCircle v-if="loading" class="h-4 w-4 animate-spin" />
          <Plus v-else class="h-4 w-4" />
          {{ loading ? '创建中' : '新建会话' }}
        </Button>
        <Tooltip v-else :disable-hoverable-content="true">
          <TooltipTrigger as-child>
            <Button size="sm" variant="outline" class="ai-icon-button" :aria-label="loading ? '正在创建会话' : '新建会话'" :disabled="loading" @click="createConversation">
              <LoaderCircle v-if="loading" class="h-4 w-4 animate-spin" />
              <Plus v-else class="h-4 w-4" />
            </Button>
          </TooltipTrigger>
          <TooltipContent side="right">{{ loading ? '正在创建会话' : '新建会话' }}</TooltipContent>
        </Tooltip>

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
          <Tooltip v-else :disable-hoverable-content="true">
            <TooltipTrigger as-child>
              <button type="button" class="ai-conversation-row" :aria-label="conversationListCollapsed ? '展开会话记录' : '收起会话记录'" :aria-expanded="!conversationListCollapsed" @click="conversationListCollapsed = !conversationListCollapsed">
                <ChevronsDown v-if="conversationListCollapsed" class="h-4 w-4" />
                <ChevronsUp v-else class="h-4 w-4" />
              </button>
            </TooltipTrigger>
            <TooltipContent side="right">{{ conversationListCollapsed ? '展开会话记录' : '收起会话记录' }}</TooltipContent>
          </Tooltip>

          <CollapsibleContent class="ai-conversation-list__drawer">
            <AiFadeSwitch :switch-key="selectedConversationDate || 'all'" content-class="ai-conversation-list__items">
              <div
                v-for="conversation in pinnedConversations"
                :key="conversation.conversationId"
                class="ai-conversation-row"
                :class="{ 'is-active': conversationId === conversation.conversationId, 'is-switching': switchingConversationId === conversation.conversationId }"
              >
                <Tooltip :disabled="!sidebarCollapsed" :disable-hoverable-content="true">
                  <TooltipTrigger as-child>
                    <button type="button" class="ai-conversation-row__main" :aria-label="conversation.title" @click="selectConversation(conversation.conversationId)">
                      <History class="h-4 w-4" />
                      <span v-if="!sidebarCollapsed">
                        <strong>{{ conversation.title }}</strong>
                        <small>{{ conversation.updatedAt.slice(0, 16) }} · {{ conversation.description }}</small>
                      </span>
                    </button>
                  </TooltipTrigger>
                  <TooltipContent side="right" class="flex-col items-start break-words">
                    <strong>{{ conversation.title }}</strong>
                    <span>{{ conversation.updatedAt.slice(0, 16) }}</span>
                  </TooltipContent>
                </Tooltip>
                <span v-if="!sidebarCollapsed" class="ai-row-actions">
                  <RowActionsMenu
                    trigger-text="更多"
                    :label="`更多 ${conversation.title} 操作`"
                    :actions="[{ key: 'rename', label: '重命名' }, { key: 'delete', label: '删除会话', variant: 'destructive', separated: true }]"
                    @select="key => key === 'rename' ? openRename(conversation.conversationId, conversation.title) : openDeleteConfirmation(conversation.conversationId)"
                  />
                </span>
              </div>
              <CollapsibleRoot
                v-if="hiddenConversationCount > 0 && !sidebarCollapsed"
                :open="conversationListExpanded"
                :unmount-on-hide="false"
                class="ai-conversation-extra"
              >
                <CollapsibleContent class="ai-conversation-extra__drawer">
                  <div class="ai-conversation-extra__items">
                    <div
                      v-for="conversation in extraConversations"
                      :key="conversation.conversationId"
                      class="ai-conversation-row"
                      :class="{ 'is-active': conversationId === conversation.conversationId, 'is-switching': switchingConversationId === conversation.conversationId }"
                    >
                      <button type="button" class="ai-conversation-row__main" @click="selectConversation(conversation.conversationId)">
                        <History class="h-4 w-4" />
                        <span v-if="!sidebarCollapsed">
                          <strong>{{ conversation.title }}</strong>
                          <small>{{ conversation.updatedAt.slice(0, 16) }} · {{ conversation.description }}</small>
                        </span>
                      </button>
                      <span v-if="!sidebarCollapsed" class="ai-row-actions">
                        <RowActionsMenu
                          trigger-text="更多"
                          :label="`更多 ${conversation.title} 操作`"
                          :actions="[{ key: 'rename', label: '重命名' }, { key: 'delete', label: '删除会话', variant: 'destructive', separated: true }]"
                          @select="key => key === 'rename' ? openRename(conversation.conversationId, conversation.title) : openDeleteConfirmation(conversation.conversationId)"
                        />
                      </span>
                    </div>
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

      </aside>

      <main class="ai-agent-main">
        <div class="ai-mobile-toolbar">
          <Button size="sm" variant="outline" :disabled="loading" @click="createConversation">新建会话</Button>
        </div>
        <section class="ai-chat-stage" :class="{ 'is-switching': transitionLoading }" :aria-busy="sending || transitionLoading">
          <div ref="messageScroll" class="ai-chat-scroll">
            <div :key="conversationId || 'empty-conversation'" class="ai-chat-stream" aria-live="polite" aria-relevant="additions text">
              <div v-if="overviewError" class="ai-history-state is-error" role="alert">
                <span>{{ overviewError }}</span><Button size="sm" variant="outline" :disabled="loading" @click="loadOverview">重试</Button>
              </div>
              <div v-else-if="loading || currentHistoryState.status === 'loading'" class="ai-history-state" role="status">
                <LoaderCircle class="h-5 w-5 animate-spin" /><span>正在加载会话记录...</span>
              </div>
              <div v-else-if="currentHistoryState.status === 'error'" class="ai-history-state is-error" role="alert">
                <span>{{ currentHistoryState.error }}</span><Button size="sm" variant="outline" @click="retryConversationHistory">重试</Button>
              </div>
              <div v-else-if="currentMessages.length === 0" class="ai-history-state is-empty">
                <Bot class="h-5 w-5" /><span>{{ conversationId ? '暂无消息，可以开始提问。' : '创建会话后开始提问。' }}</span>
                <Button v-if="!conversationId" size="sm" :disabled="loading" @click="createConversation">开始新会话</Button>
              </div>
              <article v-for="message in currentHistoryState.status === 'success' ? currentMessages : []" :key="message.messageId" class="ai-chat-message" :class="`is-${message.role}`" :data-message-id="message.messageId">
                <div class="ai-chat-avatar"><UserRound v-if="message.role === 'user'" class="h-4 w-4" /><Bot v-else class="h-4 w-4" /></div>
                <div class="ai-chat-bubble">
                  <div class="ai-chat-meta"><strong>{{ message.role === 'user' ? '我' : '智能经营助手' }}</strong><span>{{ message.createdAt }}</span></div>
                  <AiMarkdownContent v-if="message.content && message.role === 'assistant'" :content="message.content" />
                  <p v-else-if="message.content">{{ message.content }}</p>
                  <div v-if="message.charts.length" class="ai-message-charts"><AiChartCard v-for="chart in message.charts" :key="chart.chartId" :chart="chart" /></div>
                </div>
              </article>
              <div v-if="sending" class="ai-generation-status" role="status"><LoaderCircle class="h-4 w-4 animate-spin" />正在生成回复...</div>
              <div v-if="streamError" class="ai-stream-error" role="alert">{{ streamError }}</div>
            </div>
          </div>
          <AiComposerDock v-model="inputMessage" :sending="sending" :disabled="loading || currentHistoryState.status !== 'success'" @submit="submitMessage" @stop="stopGeneration" />
        </section>
      </main>
    </div>
    <ConfirmDialog
      :open="deleteDialogOpen"
      title="删除会话"
      :description="`确认删除会话「${pendingDeleteConversation?.title || ''}」吗？删除后无法恢复。`"
      confirm-text="删除会话"
      placement="app-content"
      variant="destructive"
      :loading="Boolean(deletingConversationId)"
      @update:open="updateDeleteDialogOpen"
      @confirm="removeConversation"
    />
    <Dialog v-model:open="renameDialogOpen">
      <DialogContent class="ai-rename-dialog">
        <DialogHeader>
          <DialogTitle>修改会话名称</DialogTitle>
          <DialogDescription>只修改当前会话在左侧列表中的显示名称。</DialogDescription>
        </DialogHeader>
        <div class="ai-field">
          <Label for="renameTitle">会话名称 <span class="text-destructive">*</span></Label>
          <Input id="renameTitle" v-model="renameTitle" maxlength="40" :disabled="renaming" :aria-invalid="Boolean(renameError)" aria-describedby="rename-error" />
        </div>
        <p v-if="renameError" id="rename-error" class="text-sm text-destructive" role="alert">{{ renameError }}</p>
        <DialogFooter>
          <Button variant="outline" :disabled="renaming" @click="renameDialogOpen = false">取消</Button>
          <Button :disabled="renaming" @click="saveRename">{{ renaming ? '保存中' : '保存' }}</Button>
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
  grid-template-columns: 286px minmax(0, 1fr);
  min-height: 0;
  transition: grid-template-columns var(--motion-duration-base) var(--motion-ease-standard);
}

.ai-agent-shell.is-collapsed .ai-agent-body {
  grid-template-columns: 60px minmax(0, 1fr);
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

:deep(.ai-conversation-list__items) {
  display: grid;
  gap: 10px;
  padding-top: 6px;
  user-select: none;
}

.ai-conversation-extra__items {
  display: grid;
  gap: 10px;
  padding-top: 0;
}

.ai-conversation-row,
.ai-prompt-card {
  display: grid;
  width: 100%;
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

.ai-conversation-row {
  position: relative;
  grid-template-columns: minmax(0, 1fr);
  padding: 0;
  box-shadow: none;
}

.ai-prompt-card {
  grid-template-columns: 20px minmax(0, 1fr) auto;
}

.ai-conversation-row__main {
  display: grid;
  width: 100%;
  min-height: 58px;
  min-width: 0;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  border: 0;
  border-radius: inherit;
  background: transparent;
  padding: 10px 64px 10px 9px;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.ai-conversation-row__main > span {
  min-width: 0;
}

.ai-conversation-row > .ai-row-actions {
  position: absolute;
  right: 9px;
  top: 50%;
  transform: translateY(-50%);
}

.ai-prompt-card:hover {
  border-color: #d1d5db;
  box-shadow: 0 8px 18px rgb(15 23 42 / 6%);
}

.ai-prompt-card:hover {
  transform: translateY(-1px);
}

.is-collapsed .ai-conversation-row {
  padding: 0;
  min-height: 36px;
  justify-items: center;
}

.is-collapsed .ai-conversation-row__main {
  grid-template-columns: 20px;
  justify-content: center;
  min-height: 36px;
  padding: 9px 0;
}

.ai-conversation-row.is-active {
  border-color: color-mix(in srgb, var(--primary) 20%, var(--border));
  background: linear-gradient(180deg, #f2f7ff 0%, #eaf3ff 100%);
  box-shadow: inset 3px 0 0 var(--primary);
}

.ai-conversation-row:hover,
.ai-conversation-row:focus-within {
  border-color: #98a2b3;
  background: #f2f4f7;
  box-shadow: none;
}

.ai-conversation-row.is-active:hover,
.ai-conversation-row.is-active:focus-within {
  background: linear-gradient(180deg, #f2f7ff 0%, #eaf3ff 100%);
  box-shadow: inset 3px 0 0 var(--primary);
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
  align-items: center;
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
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr) auto;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: linear-gradient(180deg, rgb(255 255 255 / 97%), rgb(250 252 255 / 96%));
}

.ai-chat-scroll {
  min-height: 0;
  min-width: 0;
  overflow-y: auto;
  padding: 22px;
  scroll-padding-bottom: 22px;
}

.ai-chat-stream {
  display: grid;
  /* 代码块的最小内容宽度不能反向撑开消息列，横向滚动交给正文组件。 */
  grid-template-columns: minmax(0, 1fr);
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
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-line;
}


.ai-history-state { display: flex; min-height: 180px; align-items: center; justify-content: center; flex-wrap: wrap; gap: 10px; color: #667085; }
.ai-history-state.is-error, .ai-stream-error { color: var(--destructive); }
.ai-generation-status { display: flex; align-items: center; gap: 8px; color: #667085; font-size: 13px; }
.ai-stream-error { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; font-size: 13px; }
.ai-message-charts { display: grid; gap: 12px; margin-top: 12px; }
.ai-chat-bubble { min-width: 0; overflow-wrap: anywhere; }
.ai-mobile-toolbar { display: none; }
@keyframes ai-sidebar-collapsible-down { from { height: 0; } to { height: var(--reka-collapsible-content-height); } }
@keyframes ai-sidebar-collapsible-up { from { height: var(--reka-collapsible-content-height); } to { height: 0; } }
@media (max-width: 980px) {
  .ai-agent-body, .ai-agent-shell.is-collapsed .ai-agent-body { grid-template-columns: minmax(0, 1fr); }
  .ai-agent-sidebar { display: none; }
  .ai-agent-main { padding: 12px; grid-template-rows: auto minmax(0, 1fr); gap: 8px; }
  .ai-mobile-toolbar { display: flex; justify-content: flex-end; }
  .ai-chat-scroll { padding: 14px; }
  .ai-chat-bubble { max-width: calc(100% - 42px); }
}
</style>
