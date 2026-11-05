import { reactive } from 'vue';
import type { ClarificationQuestion } from '../clarification/clarification-types';
import type { AiProgressStep, AiRunStatus } from '../types';

export type LiveItem =
  | { type: 'text'; id: string; text: string; completed?: boolean }
  | { type: 'clarification'; id: string; questions: ClarificationQuestion[] }
  | { type: 'tool'; id: string; label: string; durationMs: number | null; status: 'running' | 'done' | 'failed' };

export interface ConversationRun {
  querying: boolean;
  watching: boolean;
  streaming: boolean;
  version: number;
  runStatus: AiRunStatus | null;
  pendingText: string;
  liveText: string;
  liveMessageId: string;
  liveItems: LiveItem[];
  completedTextIds: Set<string>;
  eventSeq: number;
  snapshotTruncated: boolean;
  runId: string;
  error: string;
  warning: string;
  steps: AiProgressStep[];
  pendingClarification: { questionRequestId: string; toolCallId?: string; questions: ClarificationQuestion[] } | null;
  recentAnswers: Record<string, { callId: string; questions: ClarificationQuestion[]; answer: string; questionAnswers: Record<string, string>; note: string }>;
  refreshNeeded: boolean;
  settleSuccess: (() => void) | null;
}

/** 执行连接属于会话；切换页面视图不能取消另一会话的执行或丢掉未落库暂停点。 */
export function useConversationRuns() {
  const states = reactive(new Map<string, ConversationRun>());
  const controllers = new Map<string, AbortController>();

  function get(id: string | null) {
    const key = id ?? 'new';
    if (!states.has(key)) states.set(key, {
      querying: false, watching: false, streaming: false, version: 0, runStatus: null,
      pendingText: '', liveText: '', liveMessageId: '', liveItems: [], completedTextIds: new Set(), eventSeq: 0, snapshotTruncated: false, runId: '', error: '', warning: '', steps: [],
      pendingClarification: null, recentAnswers: {}, refreshNeeded: false, settleSuccess: null,
    });
    return states.get(key)!;
  }

  function connect(id: string, mode: 'querying' | 'watching') {
    const state = get(id);
    if (state.querying || state.watching) return null;
    const controller = new AbortController();
    controllers.set(id, controller);
    state.version += 1;
    state[mode] = true;
    state.streaming = true;
    return { state, controller, version: state.version };
  }

  function disconnect(id: string, version: number) {
    const state = states.get(id);
    if (!state || state.version !== version) return;
    state.querying = false;
    state.watching = false;
    state.streaming = false;
    state.pendingText = '';
    state.liveText = '';
    state.liveMessageId = '';
    state.liveItems = [];
    state.completedTextIds.clear();
    state.eventSeq = 0;
    state.snapshotTruncated = false;
    state.runId = '';
    controllers.delete(id);
  }

  function remove(id: string) {
    const state = states.get(id);
    if (state) state.version += 1;
    controllers.get(id)?.abort();
    controllers.delete(id);
    states.delete(id);
  }

  function reset() {
    for (const id of states.keys()) remove(id);
  }

  return { states, get, connect, disconnect, remove, reset };
}
