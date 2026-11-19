export interface AiReportCard {
  reportId: string;
  requestId: string;
  toolCallId: string;
  title: string;
  placeholder: string;
}

export interface AiReportDetail extends AiReportCard {
  content: string;
}

export interface AiReportStep {
  phase: 'PREPARING' | 'WRITING' | 'REVIEWING' | 'FORMAT_RETRY' | 'WRITER_FORMAT_RETRY' | 'REVIEW_FORMAT_RETRY' | 'REVIEW_RETRY' | 'REVISING' | 'SAVING' | 'FAILED';
  text: string;
  stepSeq: number;
}

export interface AiReportProcess {
  id: string;
  steps: AiReportStep[];
  report?: AiReportCard;
}

const phases = new Set(['PREPARING', 'WRITING', 'REVIEWING', 'FORMAT_RETRY', 'WRITER_FORMAT_RETRY', 'REVIEW_FORMAT_RETRY', 'REVIEW_RETRY', 'REVISING', 'SAVING', 'FAILED']);

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('报告数据结构无效');
  }
  return value as Record<string, unknown>;
}

/** 校验卡片来自后端，标题与占位符必须一一对应。 */
export function normalizeReportCard(value: unknown): AiReportCard {
  const item = record(value);
  const { reportId, requestId, toolCallId, title, placeholder } = item;
  if (typeof reportId !== 'string' || !reportId || typeof requestId !== 'string' || !requestId
    || typeof toolCallId !== 'string' || !toolCallId || typeof title !== 'string' || !title.trim()
    || /[\r\n\[\]]/.test(title) || placeholder !== `[[REPORT:${title}]]`) {
    throw new Error('报告卡片字段无效');
  }
  return { reportId, requestId, toolCallId, title, placeholder };
}

/** 正文只在打开详情时读取。 */
export function normalizeReportDetail(value: unknown): AiReportDetail {
  const item = record(value);
  if (typeof item.content !== 'string') {
    throw new Error('报告正文缺失');
  }
  return { ...normalizeReportCard(item), content: item.content };
}

/** 运行事件和重连快照共用步骤校验。 */
export function normalizeReportStep(value: unknown): AiReportStep {
  const item = record(value);
  const seq = typeof item.stepSeq === 'number' || (typeof item.stepSeq === 'string' && /^\d+$/.test(item.stepSeq))
    ? Number(item.stepSeq) : NaN;
  if (typeof item.phase !== 'string' || !phases.has(item.phase) || typeof item.text !== 'string'
    || !Number.isSafeInteger(seq) || seq < 0) {
    throw new Error('报告进度字段无效');
  }
  return { phase: item.phase as AiReportStep['phase'], text: item.text, stepSeq: seq };
}

type ReportPart = { key: string; text: string } | { key: string; report: AiReportCard };

/** 截留半截标记，完整标记只识别独立行，代码块里的示例仍按正文展示。 */
export function splitReportMessage(content: string, cards: AiReportCard[], streaming: boolean, appendMissing: boolean): ReportPart[] {
  const byMarker = new Map(cards.map(card => [card.placeholder, card]));
  const attached = new Set<string>();
  const parts: ReportPart[] = [];
  const lines = content.split('\n');
  let prose: string[] = [];
  let proseStart = 0;
  let fence: string | undefined;
  const flush = () => {
    if (prose.some(line => line.trim())) {
      parts.push({ key: `text-${proseStart}`, text: prose.join('\n') });
    }
    prose = [];
  };
  lines.forEach((line, index) => {
    const opening = /^ {0,3}(`{3,}|~{3,})/.exec(line)?.[1];
    if (opening) {
      if (!fence) {
        fence = opening;
      } else if (opening[0] === fence[0] && opening.length >= fence.length && line.trim() === opening) {
        fence = undefined;
      }
    }
    const marker = line.trim();
    if (!fence && /^\[\[REPORT:[^\[\]\r\n]+\]\]$/.test(marker)) {
      const card = byMarker.get(marker);
      if (card && !attached.has(card.reportId)) {
        flush();
        attached.add(card.reportId);
        parts.push({ key: card.reportId, report: card });
      }
      return;
    }
    if (!fence && streaming && index === lines.length - 1 && marker
      && ('[[REPORT:'.startsWith(marker) || marker.startsWith('[[REPORT:'))) {
      return;
    }
    if (prose.length === 0) {
      proseStart = index;
    }
    prose.push(line);
  });
  flush();
  if (appendMissing) {
    for (const card of cards) {
      if (!attached.has(card.reportId)) {
        attached.add(card.reportId);
        parts.push({ key: card.reportId, report: card });
      }
    }
  }
  return parts;
}
