export type ConversationDateGroup = 'today' | 'yesterday' | 'week' | 'earlier';

/** 无时区的服务端时间按本地日历解释；ISO 时间保留原有偏移，禁止统一替换时区。 */
export function conversationTimestamp(value: string): number | null {
  if (!value) return null;
  const normalized = value.trim().replace(' ', 'T');
  const plain = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?)?$/.exec(normalized);
  if (plain) {
    const [, y, m, d, h = '0', minute = '0', second = '0', fraction = '0'] = plain;
    const date = new Date(Number(y), Number(m) - 1, Number(d), Number(h), Number(minute), Number(second), Number(fraction.padEnd(3, '0')));
    return date.getFullYear() === Number(y) && date.getMonth() === Number(m) - 1 && date.getDate() === Number(d) &&
      date.getHours() === Number(h) && date.getMinutes() === Number(minute) && date.getSeconds() === Number(second)
      ? date.getTime() : null;
  }
  const parsed = Date.parse(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

export function localActivityTime(date = new Date()): string {
  const two = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())} ${two(date.getHours())}:${two(date.getMinutes())}:${two(date.getSeconds())}`;
}

export function conversationDateGroup(value: string, now = new Date()): ConversationDateGroup {
  const timestamp = conversationTimestamp(value);
  if (timestamp === null) return 'earlier';
  const day = (date: Date) => Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / 86_400_000;
  const days = day(now) - day(new Date(timestamp));
  return days <= 0 ? 'today' : days === 1 ? 'yesterday' : days < 7 ? 'week' : 'earlier';
}

export function compareConversationActivity(
  a: { conversationId: string; updatedAt: string },
  b: { conversationId: string; updatedAt: string },
): number {
  const left = conversationTimestamp(a.updatedAt) ?? -Infinity;
  const right = conversationTimestamp(b.updatedAt) ?? -Infinity;
  if (left !== right) return left > right ? -1 : 1;
  if (/^\d+$/.test(a.conversationId) && /^\d+$/.test(b.conversationId)) {
    return BigInt(a.conversationId) > BigInt(b.conversationId) ? -1 : BigInt(a.conversationId) < BigInt(b.conversationId) ? 1 : 0;
  }
  return b.conversationId.localeCompare(a.conversationId);
}
