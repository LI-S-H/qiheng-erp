import type { ClarificationQuestion } from './clarification-types';

function contractError(field: string): never {
  throw new Error(`追问接口返回的数据不符合约定（${field}），请重试或联系管理员。`);
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return contractError(field);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim()) {
    return contractError(field);
  }
  return value.trim();
}

export function normalizeQuestions(value: unknown, field: string): ClarificationQuestion[] {
  if (!Array.isArray(value) || !value.length) {
    return contractError(field);
  }
  const ids = new Set<string>();
  return value.map((entry, index) => {
    const item = record(entry, `${field}[${index}]`);
    const id = text(item.id, `${field}[${index}].id`);
    if (ids.has(id) || !Array.isArray(item.options)) {
      return contractError(`${field}[${index}]`);
    }
    ids.add(id);
    const options = item.options.map((option, optionIndex) =>
      text(option, `${field}[${index}].options[${optionIndex}]`),
    );
    if (new Set(options).size !== options.length || !['TEXT', 'SINGLE_CHOICE', 'MULTIPLE_CHOICE'].includes(String(item.type)) || (item.type !== 'TEXT' && !options.length)) {
      return contractError(`${field}[${index}].options`);
    }
    return {
      id,
      question: text(item.question, `${field}[${index}].question`),
      type: item.type === 'MULTIPLE_CHOICE' || item.type === 'SINGLE_CHOICE' || item.type === 'TEXT' ? item.type : undefined,
      options,
    };
  });
}
