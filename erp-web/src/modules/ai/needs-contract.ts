import type { Need } from './graph-types';

function invalid(field: string): never {
  throw new Error(`需求接口返回的数据不符合约定（${field}）`);
}
function object(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid(field);
  return value as Record<string, unknown>;
}
function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim()) return invalid(field);
  return value.trim();
}
function list(value: unknown, field: string): unknown[] {
  if (!Array.isArray(value)) return invalid(field);
  return value;
}
function strings(value: unknown, field: string): string[] {
  const values = list(value, field).map((entry, index) => text(entry, `${field}[${index}]`));
  if (new Set(values).size !== values.length) return invalid(field);
  return values;
}
function enumeration<T extends string>(value: unknown, values: readonly T[], field: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) return invalid(field);
  return value as T;
}

/** 仅验证公开 DTO 的形状，条件求值与完成判定仍由服务端执行。 */
export function normalizeNeeds(value: unknown): Need[] {
  const ids = new Set<string>();
  return list(value, 'needs').map((entry, index) => {
    const field = `needs[${index}]`;
    const item = object(entry, field);
    const id = text(item.id, `${field}.id`);
    if (ids.has(id)) return invalid(`${field}.id`);
    ids.add(id);
    let condition: Need['condition'] = null;
    // 服务端全局 non_null 会省略未设置的条件。
    if (item.condition !== null && item.condition !== undefined) {
      const raw = object(item.condition, `${field}.condition`);
      const right = raw.right;
      if (!['string', 'number', 'boolean'].includes(typeof right) || (typeof right === 'number' && !Number.isFinite(right))) {
        return invalid(`${field}.condition.right`);
      }
      condition = {
        left: text(raw.left, `${field}.condition.left`),
        op: enumeration(raw.op, ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NEQ'] as const, `${field}.condition.op`),
        right: right as string | number | boolean,
      };
    }
    const rawEvidence = list(item.evidence_plan, `${field}.evidence_plan`);
    if (!rawEvidence.length) return invalid(`${field}.evidence_plan`);
    const evidenceIds = new Set<string>();
    return {
      id,
      intent: enumeration(item.intent, ['DATA', 'ADVICE'] as const, `${field}.intent`),
      need: text(item.need, `${field}.need`),
      status: enumeration(item.status, ['PENDING', 'RUNNING', 'DONE', 'SKIPPED', 'BLOCKED', 'INFEASIBLE'] as const, `${field}.status`),
      depends: strings(item.depends, `${field}.depends`),
      condition,
      evidence_plan: rawEvidence.map((raw, evidenceIndex) => {
        const evidenceField = `${field}.evidence_plan[${evidenceIndex}]`;
        const evidence = object(raw, evidenceField);
        const evidenceId = text(evidence.id, `${evidenceField}.id`);
        if (evidenceIds.has(evidenceId)) return invalid(`${evidenceField}.id`);
        evidenceIds.add(evidenceId);
        return {
          id: evidenceId,
          label: text(evidence.label, `${evidenceField}.label`),
          instruction: text(evidence.instruction, `${evidenceField}.instruction`),
          source: enumeration(evidence.source, ['SQL', 'PYTHON', 'PROPOSAL'] as const, `${evidenceField}.source`),
          depends: strings(evidence.depends, `${evidenceField}.depends`),
        };
      }),
    };
  });
}
