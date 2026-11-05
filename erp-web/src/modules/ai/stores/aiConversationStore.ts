import { defineStore } from 'pinia';
import { AUTH_TOKEN_STORAGE_KEY } from '@/shared/constants/storage';
import {
  createAiConversation,
  deleteAiConversation,
  getAiConversationPage,
  getAiConversationDetail,
  getAiConversationHistory,
  getAiConversationMessagePage,
  renameAiConversation,
} from '../api';
import { compareMessages } from '../contracts/message-contract';
import { compareConversationActivity, conversationTimestamp, localActivityTime } from '../conversation-time';
import type { AiConversationDetail, AiConversationMessage, AiConversationSummary } from '../types';

let listController: AbortController | null = null;
let historyController: AbortController | null = null;
let olderController: AbortController | null = null;
let moreListController: AbortController | null = null;
function token() {
  return localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
}

/** 所有异步回写都验证账号与读取版本；旧请求不能将已切走的会话写回来。 */
export const useAiConversationStore = defineStore('aiConversation', {
  state: () => ({
    conversations: [] as AiConversationSummary[],
    activeId: null as string | null,
    detail: null as AiConversationDetail | null,
    messages: [] as AiConversationMessage[],
    listLoading: false,
    moreListLoading: false,
    moreListError: false,
    hasMoreConversations: false,
    nextConversationCursor: null as { time: string; id: string } | null,
    loadedMoreConversations: false,
    moreListRevision: 0,
    historyLoading: false,
    olderLoading: false,
    oldestPage: 0,
    loadSeq: 0,
    historyGeneration: 0,
    listSeq: 0,
    accountEpoch: 0,
    activitySeq: 0,
    confirmedActivity: {} as Record<string, string>,
    pendingActivity: {} as Record<string, { at: string; baseline: string; version: number; touchedAt: number }>,
  }),
  getters: {
    activeSummary: (state) =>
      state.conversations.find((item) => item.conversationId === state.activeId) || null,
    hasOlder: (state) => state.oldestPage > 1,
  },
  actions: {
    async loadConversations() {
      listController?.abort();
      moreListController?.abort();
      this.moreListLoading = false;
      const controller = new AbortController();
      listController = controller;
      const version = ++this.listSeq;
      const account = this.accountEpoch;
      const auth = token();
      const activityVersion = this.activitySeq;
      this.listLoading = true;
      try {
        const page = await getAiConversationPage(undefined, controller.signal);
        if (version !== this.listSeq || account !== this.accountEpoch || auth !== token()) {
          return;
        }
        const rows = page.records.map(item => this.mergeActivity(item, activityVersion));
        const included = new Set(rows.map(item => item.conversationId));
        // 已加载的更早会话保留在列表中；刷新首页不能丢掉用户展开的历史。
        rows.push(...this.conversations.filter(item => !included.has(item.conversationId)));
        this.conversations = rows.sort(compareConversationActivity);
        if (!this.loadedMoreConversations) {
          this.nextConversationCursor = page.nextTime && page.nextId ? { time: page.nextTime, id: page.nextId } : null;
          this.hasMoreConversations = page.hasMore;
        }
        this.moreListError = false;
      } finally {
        if (version === this.listSeq && account === this.accountEpoch) {
          this.listLoading = false;
        }
      }
    },

    /** 加载下一批历史会话，旧账号和过期请求不能写回当前列表。 */
    async loadMoreConversations() {
      if (!this.hasMoreConversations || !this.nextConversationCursor || this.moreListLoading) return;
      const controller = new AbortController();
      moreListController = controller;
      const version = this.listSeq;
      const account = this.accountEpoch;
      const auth = token();
      const activityVersion = this.activitySeq;
      this.moreListLoading = true;
      this.moreListError = false;
      try {
        const page = await getAiConversationPage(this.nextConversationCursor, controller.signal);
        if (version !== this.listSeq || account !== this.accountEpoch || auth !== token()) return;
        for (const item of page.records) this.upsertConversationSummary(item, activityVersion);
        this.loadedMoreConversations = true;
        this.moreListRevision += 1;
        this.hasMoreConversations = page.hasMore;
        this.nextConversationCursor = page.nextTime && page.nextId ? { time: page.nextTime, id: page.nextId } : null;
      } catch (error) {
        if (!controller.signal.aborted && version === this.listSeq && account === this.accountEpoch) this.moreListError = true;
      } finally {
        if (version === this.listSeq && account === this.accountEpoch) this.moreListLoading = false;
      }
    },

    async loadConversation(conversationId: string, options?: { silent?: boolean }) {
      historyController?.abort();
      const controller = new AbortController();
      historyController = controller;
      const switched = this.activeId !== conversationId;
      const silent = Boolean(options?.silent && !switched && this.detail);
      if (!silent) {
        olderController?.abort();
        this.historyGeneration += 1;
        this.olderLoading = false;
      }
      this.activeId = conversationId;
      if (switched) {
        this.detail = null;
        this.messages = [];
      }
      const version = ++this.loadSeq;
      const account = this.accountEpoch;
      const auth = token();
      const activityVersion = this.activitySeq;
      // 同会话后台同步沿用已有内容；首次加载和切换仍显示遮罩并锁定操作。
      this.historyLoading = !silent;
      try {
        const [detail, page] = await Promise.all([
          getAiConversationDetail(conversationId, controller.signal),
          getAiConversationHistory(conversationId, controller.signal),
        ]);
        if (
          version !== this.loadSeq ||
          account !== this.accountEpoch ||
          auth !== token() ||
          this.activeId !== conversationId
        ) {
          return false;
        }
        this.detail = detail;
        this.upsertConversationSummary(detail, activityVersion);
        // 同会话刷新只合并最近页，保留用户已经向上加载的旧记录。
        const existing = switched ? [] : this.messages;
        this.messages = this.mergeMessages(existing, page.records);
        this.oldestPage =
          switched || this.oldestPage === 0
            ? page.pageNum
            : Math.min(this.oldestPage, page.pageNum);
        return true;
      } finally {
        if (version === this.loadSeq && account === this.accountEpoch) {
          this.historyLoading = false;
        }
      }
    },

    async loadOlder() {
      if (!this.activeId || this.oldestPage <= 1 || this.olderLoading || this.historyLoading) {
        return;
      }
      const id = this.activeId;
      const version = this.historyGeneration;
      const account = this.accountEpoch;
      const auth = token();
      const controller = new AbortController();
      olderController = controller;
      this.olderLoading = true;
      try {
        const page = await getAiConversationMessagePage(
          id,
          this.oldestPage - 1,
          50,
          controller.signal,
        );
        if (
          id !== this.activeId ||
          version !== this.historyGeneration ||
          account !== this.accountEpoch ||
          auth !== token()
        ) {
          return;
        }
        this.messages = this.mergeMessages(this.messages, page.records);
        this.oldestPage = page.pageNum;
      } catch (error) {
        if (!controller.signal.aborted) throw error;
      } finally {
        if (version === this.historyGeneration && account === this.accountEpoch) {
          this.olderLoading = false;
        }
      }
    },

    mergeMessages(current: AiConversationMessage[], incoming: AiConversationMessage[]) {
      const byId = new Map(current.map((message) => [message.messageId, message]));
      incoming.forEach((message) => byId.set(message.messageId, message));
      return Array.from(byId.values()).sort(compareMessages);
    },

    upsertMessage(message: AiConversationMessage) {
      this.messages = this.mergeMessages(this.messages, [message]);
    },

    /** 发送立即进入今天；旧响应不能覆盖这次活动，服务端确认后恢复以服务端时间为准。 */
    touchActivity(conversationId: string, at = localActivityTime()) {
      if (conversationTimestamp(at) === null) return;
      const item = this.conversations.find(row => row.conversationId === conversationId);
      if (!item) return;
      this.pendingActivity[conversationId] = {
        at,
        baseline: this.pendingActivity[conversationId]?.baseline || this.confirmedActivity[conversationId] || item.updatedAt,
        version: ++this.activitySeq,
        touchedAt: Date.now(),
      };
      item.updatedAt = at;
      this.conversations.sort(compareConversationActivity);
    },

    mergeActivity(item: AiConversationSummary, activityVersion: number): AiConversationSummary {
      const id = item.conversationId;
      const previous = this.confirmedActivity[id];
      const incomingTime = conversationTimestamp(item.updatedAt);
      const previousTime = conversationTimestamp(previous || '');
      const serverTime = previousTime !== null && (incomingTime === null || previousTime > incomingTime) ? previous! : item.updatedAt;
      this.confirmedActivity[id] = serverTime;
      const pending = this.pendingActivity[id];
      if (pending && activityVersion >= pending.version &&
        ((conversationTimestamp(serverTime) ?? -Infinity) > (conversationTimestamp(pending.baseline) ?? -Infinity) ||
          Date.now() - pending.touchedAt >= 60_000)) {
        // 不能永久保留客户端较快的时钟；活动确认或短期乐观窗口到期即交回服务端。
        delete this.pendingActivity[id];
      }
      return { ...item, updatedAt: this.pendingActivity[id]?.at || serverTime };
    },

    upsertConversationSummary(item: AiConversationSummary, activityVersion?: number) {
      const merged = this.mergeActivity(item, activityVersion ?? this.activitySeq);
      const index = this.conversations.findIndex(row => row.conversationId === item.conversationId);
      if (index === -1) this.conversations.push(merged);
      else this.conversations[index] = merged;
      this.conversations.sort(compareConversationActivity);
    },

    async createConversation() {
      this.invalidateListRead();
      const account = this.accountEpoch;
      const auth = token();
      const created = await createAiConversation();
      if (account !== this.accountEpoch || auth !== token()) {
        throw new Error('登录会话已变更');
      }
      this.clearActive();
      this.upsertConversationSummary(created);
      this.activeId = created.conversationId;
      this.detail = created;
      return created;
    },

    async renameConversation(conversationId: string, title: string) {
      this.invalidateListRead();
      const account = this.accountEpoch;
      const auth = token();
      await renameAiConversation(conversationId, { title });
      if (account !== this.accountEpoch || auth !== token()) {
        return;
      }
      const summary = this.conversations.find((item) => item.conversationId === conversationId);
      if (summary) {
        summary.title = title;
      }
      if (this.detail?.conversationId === conversationId) {
        this.detail = { ...this.detail, title };
      }
    },

    async removeConversation(conversationId: string) {
      this.invalidateListRead();
      const account = this.accountEpoch;
      const auth = token();
      await deleteAiConversation(conversationId);
      if (account !== this.accountEpoch || auth !== token()) {
        return;
      }
      this.conversations = this.conversations.filter(
        (item) => item.conversationId !== conversationId,
      );
      delete this.pendingActivity[conversationId];
      delete this.confirmedActivity[conversationId];
      if (this.activeId === conversationId) {
        this.clearActive();
      }
    },

    invalidateListRead() {
      listController?.abort();
      moreListController?.abort();
      this.moreListLoading = false;
      this.listSeq += 1;
      this.listLoading = false;
    },

    clearActive() {
      historyController?.abort();
      olderController?.abort();
      this.activeId = null;
      this.detail = null;
      this.messages = [];
      this.oldestPage = 0;
      this.loadSeq += 1;
      this.historyGeneration += 1;
      this.historyLoading = false;
      this.olderLoading = false;
    },

    reset() {
      listController?.abort();
      moreListController?.abort();
      this.accountEpoch += 1;
      this.listSeq += 1;
      this.conversations = [];
      this.hasMoreConversations = false;
      this.nextConversationCursor = null;
      this.loadedMoreConversations = false;
      this.moreListRevision = 0;
      this.moreListLoading = false;
      this.moreListError = false;
      this.pendingActivity = {};
      this.confirmedActivity = {};
      this.activitySeq = 0;
      this.listLoading = false;
      this.clearActive();
    },
  },
});
