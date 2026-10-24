import type { ClarificationQuestion } from './graph-types';

/** 快照只改善标签展示，已提交事实始终来自关联的服务端消息。 */
export function submittedAnswerTags(
  snapshot: unknown,
  questions: ClarificationQuestion[],
  serverText: string,
): string[] {
  if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) return [];
  const value = snapshot as Record<string, unknown>;
  if (value.text !== serverText || !Array.isArray(value.answers) || value.answers.length !== questions.length) return [];
  const result: string[] = [];
  for (let index = 0; index < questions.length; index += 1) {
    const row = value.answers[index];
    if (!row || typeof row !== 'object' || Array.isArray(row)) return [];
    const item = row as Record<string, unknown>;
    if (item.id !== questions[index].id || item.question !== questions[index].question || typeof item.answer !== 'string' || !item.answer.trim()) return [];
    result.push(item.answer);
  }
  const parts = questions.map((question, index) => `${question.question}\n${result[index]}`);
  if (value.note !== undefined && typeof value.note !== 'string') return [];
  if (typeof value.note === 'string' && value.note.trim()) parts.push(`其他补充：${value.note.trim()}`);
  if (parts.join('\n\n') !== serverText) return [];
  return result;
}
