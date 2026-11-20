import type { AiChartRow, AiChartSpec } from '../types';

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
  historical?: boolean;
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

type ReportPart =
  | { key: string; text: string }
  | { key: string; report: AiReportCard }
  | { key: string; chart: AiChartSpec };

const chartTypes = new Set<AiChartSpec['type']>(['line', 'bar', 'pie', 'horizontalBar', 'stackedBar']);
const chartIdPattern = /^[A-Za-z0-9_-]{1,64}$/;
const fieldPattern = /^[A-Za-z0-9_一-鿿-]{1,64}$/;

function optionalString(value: unknown, max: number): string | null {
  return typeof value === 'string' && value.trim() ? value.trim().slice(0, max) : null;
}

function firstField(rows: AiChartRow[], kind: 'string' | 'number'): string | null {
  for (const row of rows) {
    for (const [key, value] of Object.entries(row)) {
      if (kind === 'number' ? typeof value === 'number' : typeof value === 'string' && value.trim()) {
        return key;
      }
    }
  }
  return null;
}

/** 校验报告内嵌的图表规格；任何越界或非法字段都返回 null，降级为普通文本。 */
export function normalizeChartSpec(value: unknown): AiChartSpec | null {
  try {
    const item = record(value);
    if (typeof item.type !== 'string' || !chartTypes.has(item.type as AiChartSpec['type'])) return null;
    if (typeof item.chartId !== 'string' || !chartIdPattern.test(item.chartId)) return null;
    if (typeof item.title !== 'string' || !item.title.trim() || item.title.length > 120) return null;
    const type = item.type as AiChartSpec['type'];
    const title = item.title.trim();
    const description = optionalString(item.description, 300) ?? '';
    const xField = optionalString(item.xField, 64);
    const nameField = optionalString(item.nameField, 64);
    const valueField = optionalString(item.valueField, 64);
    const yUnit = optionalString(item.yUnit, 16);
    const yFields = Array.isArray(item.yFields)
      ? item.yFields.filter((field): field is string => typeof field === 'string' && fieldPattern.test(field)).slice(0, 6)
      : [];
    const fieldLabels: Record<string, string> = {};
    if (item.fieldLabels && typeof item.fieldLabels === 'object' && !Array.isArray(item.fieldLabels)) {
      for (const [key, label] of Object.entries(item.fieldLabels)) {
        if (fieldPattern.test(key) && typeof label === 'string' && label.trim()) {
          fieldLabels[key] = label.trim().slice(0, 40);
        }
      }
    }
    const limit = type === 'pie' ? 8 : 20;
    const rows = Array.isArray(item.data) ? item.data.slice(0, limit) : [];
    const data: AiChartRow[] = [];
    for (const row of rows) {
      if (!row || typeof row !== 'object' || Array.isArray(row)) continue;
      const clean: AiChartRow = {};
      for (const [key, raw] of Object.entries(row as Record<string, unknown>)) {
        if (!fieldPattern.test(key)) continue;
        if (typeof raw === 'number' && Number.isFinite(raw)) clean[key] = raw;
        else if (typeof raw === 'string') clean[key] = raw.slice(0, 200);
        else if (raw === null) clean[key] = null;
      }
      if (Object.keys(clean).length > 0) data.push(clean);
    }
    if (data.length === 0) return null;

    const resolvedName = nameField ?? xField ?? firstField(data, 'string');
    const resolvedValue = valueField ?? yFields[0] ?? firstField(data, 'number');
    if (!resolvedName || !resolvedValue) return null;
    const resolvedFields = yFields.length > 0 ? yFields : [resolvedValue];
    if (type === 'pie' || type === 'horizontalBar') {
      return {
        chartId: item.chartId,
        type,
        title,
        description,
        xField: resolvedName,
        yFields: [resolvedValue],
        fieldLabels,
        yUnit,
        nameField: resolvedName,
        valueField: resolvedValue,
        data,
      };
    }
    return {
      chartId: item.chartId,
      type,
      title,
      description,
      xField: resolvedName,
      yFields: resolvedFields,
      fieldLabels,
      yUnit,
      nameField: resolvedName,
      valueField: resolvedValue,
      data,
    };
  } catch {
    return null;
  }
}

/** 截留半截标记，完整标记只识别独立行，代码块里的示例仍按正文展示。 */
export function splitReportMessage(content: string, cards: AiReportCard[], streaming: boolean, appendMissing: boolean): ReportPart[] {
  const byMarker = new Map(cards.map(card => [card.placeholder, card]));
  const attached = new Set<string>();
  const parts: ReportPart[] = [];
  const lines = content.split('\n');
  let prose: string[] = [];
  let proseStart = 0;
  let fence: string | undefined;
  let chartFence: string | undefined;
  let chartOpening = '';
  let chartStart = 0;
  let chartLines: string[] = [];
  const flush = () => {
    if (prose.some(line => line.trim())) {
      parts.push({ key: `text-${proseStart}`, text: prose.join('\n') });
    }
    prose = [];
  };
  lines.forEach((line, index) => {
    if (chartFence) {
      const closing = /^ {0,3}(`{3,}|~{3,})\s*$/.exec(line)?.[1];
      if (closing && closing[0] === chartFence[0] && closing.length >= chartFence.length) {
        let chart: AiChartSpec | null = null;
        try {
          chart = normalizeChartSpec(JSON.parse(chartLines.join('\n')));
        } catch {
          chart = null;
        }
        if (chart) {
          flush();
          parts.push({ key: `chart-${chartStart}`, chart });
        } else {
          if (prose.length === 0) proseStart = chartStart;
          prose.push(chartOpening, ...chartLines, line);
        }
        chartFence = undefined;
        chartOpening = '';
        chartLines = [];
        return;
      }
      chartLines.push(line);
      return;
    }
    const openingMatch = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
    if (!fence && openingMatch && openingMatch[2].trim().toLowerCase() === 'chart') {
      chartFence = openingMatch[1];
      chartOpening = line;
      chartStart = index;
      chartLines = [];
      return;
    }
    const opening = openingMatch?.[1];
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
  if (chartFence) {
    if (prose.length === 0) proseStart = chartStart;
    prose.push(chartOpening, ...chartLines);
  }
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
