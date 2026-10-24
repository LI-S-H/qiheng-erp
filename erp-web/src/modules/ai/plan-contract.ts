import type { ClarificationQuestion, Plan, PlanStep } from './graph-types';

function contractError(field: string): never {
  throw new Error(`规划接口返回的数据不符合约定（${field}），请重试或联系管理员。`);
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
  if (!Array.isArray(value)) {
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
    if (new Set(options).size !== options.length) {
      return contractError(`${field}[${index}].options`);
    }
    return {
      id,
      question: text(item.question, `${field}[${index}].question`),
      options,
    };
  });
}

export function normalizePlan(value: unknown): Plan {
  const item = record(value, 'plan');
  if (typeof item.needs_clarification !== 'boolean' || !Array.isArray(item.steps)) {
    return contractError('plan');
  }
  const steps: PlanStep[] = item.steps.map((entry, index) => {
    const step = record(entry, `plan.steps[${index}]`);
    if (step.step !== index + 1 || (step.type !== 'SQL_QUERY' && step.type !== 'PYTHON_ANALYSIS')) {
      return contractError(`plan.steps[${index}]`);
    }
    return {
      step: index + 1,
      type: step.type,
      instruction: text(step.instruction, `plan.steps[${index}].instruction`),
    };
  });
  return {
    title: text(item.title, 'plan.title'),
    objective: text(item.objective, 'plan.objective'),
    needs_clarification: item.needs_clarification,
    clarification_questions: normalizeQuestions(
      item.clarification_questions,
      'plan.clarification_questions',
    ),
    steps,
  };
}
