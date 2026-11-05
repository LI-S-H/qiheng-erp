<script setup lang="ts">
import { computed, ref, watch, type CSSProperties } from 'vue';
import { useEventListener, useNow } from '@vueuse/core';
import {
  ChevronDown,
  ArrowUpRight,
  MessageSquarePlus,
  PanelLeftClose,
  PanelLeftOpen,
  Pencil,
  Trash2,
  SquarePen,
  LoaderCircle,
} from 'lucide-vue-next';
import CollapseReveal from '@/components/common/CollapseReveal.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import type { AiConversationSummary } from '../types';
import { conversationDateGroup } from '../conversation-time';

const props = defineProps<{
  conversations: AiConversationSummary[];
  activeId: string | null;
  loading: boolean;
  /** 只在创建尚未取得 ID 或切换读取时暂时锁定；后台运行不锁住会话列表。 */
  busy: boolean;
  runningIds?: string[];
  /** 历史读取仍保留操作锁，但不把稳定的管理区呈现成正在刷新的状态。 */
  switching?: boolean;
  collapsed: boolean;
  dialogStyle?: CSSProperties;
  hasMore?: boolean;
  loadingMore?: boolean;
  loadMoreError?: boolean;
  loadMoreRevision?: number;
}>();

const emit = defineEmits<{
  create: [];
  select: [conversationId: string];
  rename: [conversationId: string, title: string];
  remove: [conversationId: string];
  toggleCollapse: [];
  loadMore: [];
}>();

/** 会话按最近活动时间分组，顺序固定为从近到远。 */
const GROUP_ORDER = ['today', 'yesterday', 'week', 'earlier'] as const;
const GROUP_LABELS: Record<string, string> = {
  today: '今天',
  yesterday: '昨天',
  week: '近 7 天',
  earlier: '更早',
};

const editingId = ref<string | null>(null);
const now = useNow({ interval: 60_000 });
// 后台标签页的计时器可能被节流，回到页面时立即按当前日期重新分组。
useEventListener(document, 'visibilitychange', () => {
  if (document.visibilityState === 'visible') now.value = new Date();
});
const editingTitle = ref('');
const renameError = ref('');
const openGroups = ref<Record<string, boolean>>({});
const visibleCounts = ref<Record<string, number>>({});
const pendingGroup = ref<string | null>(null);
/** 确认弹窗的载荷：AlertDialog 会先关闭再触发 confirm，不能在中途把目标清空。 */
const removeState = ref<{ open: boolean; conversationId: string; title: string }>({
  open: false,
  conversationId: '',
  title: '',
});

const groups = computed(() => {
  const buckets: Record<string, AiConversationSummary[]> = {
    today: [],
    yesterday: [],
    week: [],
    earlier: [],
  };
  props.conversations.forEach((item) => buckets[bucketOf(item.updatedAt)].push(item));
  return GROUP_ORDER.map((key) => ({ key, label: GROUP_LABELS[key], items: buckets[key] })).filter(
    (group) => group.items.length > 0,
  );
});

// 账号切换或清空会话后，新的列表重新从每组五条开始。
watch(() => props.conversations.length, count => {
  if (count === 0) {
    visibleCounts.value = {};
    pendingGroup.value = null;
    openGroups.value = {};
  }
});

/** 比较本地日历日期，避免整点消息和夏令时导致日期分组偏移。 */
function bucketOf(updatedAt: string): string {
  return conversationDateGroup(updatedAt, now.value);
}

function isOpen(key: string) {
  return openGroups.value[key] === true;
}

function toggleGroup(key: string) {
  openGroups.value = { ...openGroups.value, [key]: !isOpen(key) };
}

/** 先展开已加载的会话；最后一组用完时再读取下一批。 */
function showMore(key: string) {
  const group = groups.value.find(item => item.key === key);
  if (!group || props.loadingMore) return;
  const count = visibleCounts.value[key] || 5;
  if (count < group.items.length) {
    visibleCounts.value = { ...visibleCounts.value, [key]: Math.min(count + 5, group.items.length) };
  } else if (props.hasMore) {
    pendingGroup.value = key;
    emit('loadMore');
  }
}

// 只有服务端续页成功后才增加可见数量，失败重试不改变当前展开数量。
watch(() => props.loadMoreRevision, () => {
  if (!pendingGroup.value) return;
  const key = pendingGroup.value;
  pendingGroup.value = null;
  const group = groups.value.find(item => item.key === key);
  if (group) visibleCounts.value = { ...visibleCounts.value, [key]: Math.min((visibleCounts.value[key] || 5) + 5, group.items.length) };
});

// 默认只展开最近有记录的那一组，其余保持收起，列表不会一屏全是旧会话
watch(
  groups,
  (value) => {
    if (Object.keys(openGroups.value).length || !value.length) {
      return;
    }
    openGroups.value = { [value[0].key]: true };
  },
  { immediate: true },
);

// 切换会话或跨日刷新分组后，当前会话不能藏在收起的分类里。
watch(
  [
    () => props.activeId,
    () => groups.value.find((group) => group.items.some((item) => item.conversationId === props.activeId))?.key,
  ],
  ([, key]) => {
    if (key) {
      openGroups.value = { ...openGroups.value, [key]: true };
      const index = groups.value.find(group => group.key === key)?.items.findIndex(item => item.conversationId === props.activeId) ?? -1;
      if (index >= (visibleCounts.value[key] || 5)) {
        visibleCounts.value = { ...visibleCounts.value, [key]: Math.ceil((index + 1) / 5) * 5 };
      }
    }
  },
  { immediate: true },
);

function startRename(conversation: AiConversationSummary) {
  if (props.busy) {
    return;
  }
  editingId.value = conversation.conversationId;
  editingTitle.value = conversation.title;
  renameError.value = '';
}

/** 保存前校验标题，取消和关闭不会提交。 */
function commitRename() {
  const conversationId = editingId.value;
  if (!conversationId) {
    return;
  }
  const title = editingTitle.value.trim();
  if (!title) {
    renameError.value = '请输入会话名称';
    return;
  }
  editingId.value = null;
  editingTitle.value = '';
  const current = props.conversations.find((item) => item.conversationId === conversationId);
  if (title && title !== current?.title) {
    emit('rename', conversationId, title);
  }
}

function cancelRename() {
  editingId.value = null;
  editingTitle.value = '';
  renameError.value = '';
}

function askRemove(conversation: AiConversationSummary) {
  if (props.busy || props.runningIds?.includes(conversation.conversationId)) {
    return;
  }
  removeState.value = {
    open: true,
    conversationId: conversation.conversationId,
    title: conversation.title,
  };
}

function confirmRemove() {
  const conversationId = removeState.value.conversationId;
  removeState.value = { ...removeState.value, open: false };
  if (conversationId && !props.runningIds?.includes(conversationId)) {
    emit('remove', conversationId);
  }
}
</script>

<template>
  <aside class="conversation-sidebar" :class="{ 'is-collapsed': collapsed, 'is-switching': switching }" aria-label="会话列表">
    <div v-if="collapsed" class="sidebar-rail">
      <button
        type="button"
        class="rail-button"
        aria-label="展开会话列表"
        @click="emit('toggleCollapse')"
      >
        <PanelLeftOpen class="size-4" />
      </button>
      <button
        type="button"
        class="rail-button"
        aria-label="新建会话"
        :disabled="busy"
        @click="emit('create')"
      >
        <MessageSquarePlus class="size-4" />
      </button>
    </div>
    <template v-else>
      <div class="sidebar-head">
        <h2>会话</h2>
        <div class="head-actions">
          <button
            type="button"
            class="head-button"
            aria-label="收起会话列表"
            @click="emit('toggleCollapse')"
          >
            <PanelLeftClose class="size-4" />
          </button>
        </div>
      </div>
      <div class="sidebar-create">
        <button type="button" class="create-button" :disabled="busy" @click="emit('create')">
          <span class="create-symbol" aria-hidden="true"><SquarePen class="size-3.5" /></span><span>新建会话</span><ArrowUpRight class="create-arrow size-3.5" aria-hidden="true" />
        </button>
      </div>
      <div class="sidebar-list">
        <p v-if="loading" class="sidebar-note" role="status">正在加载会话…</p>
        <p v-else-if="!conversations.length" class="sidebar-note">
          还没有会话，点击上方“新建会话”开始分析。
        </p>
        <section v-for="group in groups" :key="group.key" class="conversation-group">
          <button
            type="button"
            class="group-head"
            :aria-expanded="isOpen(group.key)"
            @click="toggleGroup(group.key)"
          >
            <ChevronDown
              class="size-3.5 group-chevron"
              :class="{ 'is-collapsed': !isOpen(group.key) }"
            />
            <span>{{ group.label }}</span>
            <span class="group-count">{{ group.items.length }}</span>
          </button>
          <CollapseReveal :open="isOpen(group.key)">
            <div class="group-body">
              <div
                v-for="item in group.items.slice(0, visibleCounts[group.key] || 5)"
                :key="item.conversationId"
                class="conversation-row"
                :class="{ 'is-active': item.conversationId === activeId }"
              >
                  <button
                    type="button"
                    class="conversation-main"
                    :disabled="busy"
                    :aria-current="item.conversationId === activeId ? 'true' : undefined"
                    :title="item.title"
                    @click="emit('select', item.conversationId)"
                  >
                    <span class="conversation-title-line"><span class="conversation-title">{{ item.title }}</span><LoaderCircle v-if="runningIds?.includes(item.conversationId)" class="conversation-running animate-spin" aria-label="会话正在运行" /></span>
                    <time>{{ item.updatedAt }}</time>
                  </button>
                  <div class="conversation-actions">
                    <button
                      type="button"
                      aria-label="重命名会话"
                      :disabled="busy"
                      @click="startRename(item)"
                    >
                      <Pencil class="size-3.5" />
                    </button>
                    <button
                      type="button"
                      class="is-danger"
                      aria-label="删除会话"
                      :disabled="busy || runningIds?.includes(item.conversationId)"
                      @click="askRemove(item)"
                    >
                      <Trash2 class="size-3.5" />
                    </button>
                  </div>
              </div>
              <button v-if="group.items.length > (visibleCounts[group.key] || 5) || (hasMore && groups[groups.length - 1]?.key === group.key)"
                type="button" class="show-more" :disabled="loadingMore" @click="showMore(group.key)">
                {{ loadingMore ? '正在加载…' : loadMoreError && groups[groups.length - 1]?.key === group.key ? '加载失败，点击重试' : '显示更多' }}
              </button>
            </div>
          </CollapseReveal>
        </section>
      </div>
    </template>
    <Dialog :open="Boolean(editingId)" @update:open="value => { if (!value) cancelRename(); }">
      <DialogContent :style="dialogStyle" :show-close-button="false" class="conversation-rename-dialog" data-conversation-rename-dialog>
        <form class="rename-form" @submit.prevent="commitRename">
          <DialogTitle>重命名会话</DialogTitle>
          <DialogDescription>为这个会话设置一个容易识别的名称。</DialogDescription>
          <label for="conversation-rename-input" class="rename-label">会话名称</label>
          <Input id="conversation-rename-input" v-model="editingTitle" maxlength="40" autofocus :aria-invalid="Boolean(renameError)" @input="renameError = ''" />
          <p v-if="renameError" class="rename-error" role="alert">{{ renameError }}</p>
          <div class="rename-actions">
            <Button type="button" variant="outline" @click="cancelRename">取消</Button>
            <Button type="submit" :disabled="busy">保存</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
    <ConfirmDialog
      :open="removeState.open"
      title="删除这个会话？"
      :description="`“${removeState.title}”及其全部对话记录将被移除，且无法恢复。`"
      confirm-text="删除会话"
      variant="destructive"
      :loading="busy"
      :content-style="dialogStyle"
      @update:open="
        (value) => {
          removeState = { ...removeState, open: value };
        }
      "
      @confirm="confirmRemove"
      @cancel="removeState = { ...removeState, open: false }"
    />
  </aside>
</template>

<style scoped>
.conversation-sidebar {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  min-height: 0;
  min-width: 0;
  border-right: 1px solid var(--border);
  background: var(--card);
}
.show-more { width: 100%; padding: 7px 8px; color: #64748b; font-size: 13px; text-align: left; cursor: pointer; }
.show-more:hover:not(:disabled) { color: #2563eb; }
.show-more:focus-visible { outline: 2px solid #7191c0; outline-offset: -2px; border-radius: 4px; }
.show-more:disabled { cursor: wait; }
.conversation-sidebar.is-collapsed {
  grid-template-rows: minmax(0, 1fr);
}
.sidebar-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px 0;
}
.rail-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 8px;
  color: #7c8798;
  cursor: pointer;
  transition:
    background-color var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease;
}
.rail-button:hover {
  background: #eef3fb;
  color: var(--primary);
}
.rail-button:disabled {
  cursor: wait;
  opacity: 0.5;
}
.sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 56px;
  border-bottom: 1px solid var(--border);
  padding: 0 10px 0 16px;
}
.sidebar-head h2 {
  color: #475467;
  font-size: 13px;
  font-weight: 500;
}
.head-actions {
  display: flex;
  gap: 2px;
}
.head-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 7px;
  color: #7c8798;
  cursor: pointer;
  transition:
    background-color var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease;
}
.head-button:hover {
  background: #eef3fb;
  color: var(--primary);
}
.head-button:disabled {
  cursor: wait;
  opacity: 0.5;
}
.sidebar-list {
  min-height: 0;
  overflow-y: auto;
  padding: 8px 8px 16px;
  scrollbar-width: thin;
}
.sidebar-create {
  padding: 12px 12px 4px;
}
.create-button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-height: 36px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  color: var(--foreground);
  font-size: 13px;
  cursor: pointer;
}
.create-button:hover {
  background: var(--accent);
}
.create-button:disabled {
  cursor: wait;
  opacity: 0.5;
}
.is-switching .create-button:disabled,
.is-switching .rail-button:disabled,
.is-switching .conversation-main:disabled,
.is-switching .conversation-actions button:disabled {
  /* 切换只读取右侧内容，左侧按钮保留原样，disabled 继续阻止重复操作。 */
  cursor: default;
  opacity: 1;
}
.create-button:focus-visible,
.rail-button:focus-visible,
.head-button:focus-visible,
.group-head:focus-visible,
.conversation-main:focus-visible,
.conversation-actions button:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: -2px;
}
.sidebar-note {
  padding: 14px 8px;
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.8;
}
.conversation-group + .conversation-group {
  margin-top: 4px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 8px;
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: color var(--motion-duration-fast) ease;
}
.group-head:hover {
  color: #475467;
}
.group-chevron {
  flex-shrink: 0;
  transition: transform var(--motion-duration-base) var(--motion-ease-standard);
}
.group-chevron.is-collapsed {
  transform: rotate(-90deg);
}
.group-count {
  margin-left: auto;
  color: var(--muted-foreground);
  font-variant-numeric: tabular-nums;
}
.group-body {
  display: grid;
  gap: 1px;
  padding: 2px 0;
}
.conversation-row {
  display: flex;
  align-items: center;
  gap: 4px;
  border-radius: 7px;
  transition: background-color var(--motion-duration-fast) ease;
}
.conversation-row:hover {
  background: #f2f5fa;
}
.conversation-row.is-active {
  background: #eef3fb;
}
.conversation-main {
  display: grid;
  gap: 3px;
  min-width: 0;
  flex: 1;
  padding: 8px 10px;
  text-align: left;
  cursor: pointer;
}
.conversation-main:disabled {
  cursor: wait;
}
.conversation-title {
  overflow: hidden;
  color: #344054;
  font-size: 13px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conversation-title-line { display: flex; align-items: center; gap: 6px; min-width: 0; }
.conversation-title-line .conversation-title { min-width: 0; flex: 1; }
.conversation-running { width: 12px; height: 12px; flex-shrink: 0; color: var(--primary); }
.conversation-row.is-active .conversation-title {
  color: var(--primary);
  font-weight: 500;
}
.conversation-main time {
  color: var(--muted-foreground);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.conversation-actions {
  display: none;
  gap: 2px;
  padding-right: 6px;
}
.conversation-row:hover .conversation-actions,
.conversation-row:focus-within .conversation-actions {
  display: flex;
}
.conversation-actions button {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 5px;
  color: var(--muted-foreground);
  cursor: pointer;
  transition:
    background-color var(--motion-duration-fast) ease,
    color var(--motion-duration-fast) ease;
}
.conversation-actions button:hover {
  background: #e7ecf4;
  color: #475467;
}
.conversation-actions button.is-danger:hover {
  background: #fdeceb;
  color: var(--destructive);
}
.conversation-actions button:disabled {
  cursor: wait;
  opacity: 0.5;
}
.rename-form { display: grid; gap: 10px; }
.rename-label { margin-top: 6px; color: #344054; font-size: 14px; font-weight: 500; }
.rename-error { color: #b42318; font-size: 13px; }
.rename-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
@media (hover: none) {
  .conversation-actions {
    display: flex;
  }
}
</style>
