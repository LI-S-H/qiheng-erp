export function normalizeStringId(value: unknown, fieldName: string): string {
  if (typeof value !== 'string' || !value) {
    throw new Error(`接口字段 ${fieldName} 必须使用非空字符串传输`);
  }
  return value;
}

export function normalizeNullableStringId(value: unknown, fieldName: string): string | null {
  if (value === null || value === undefined || value === '') return null;
  return normalizeStringId(value, fieldName);
}

export function normalizeBinaryStatus(value: unknown, fieldName = 'status'): 0 | 1 {
  const normalized = Number(value);
  if (normalized !== 0 && normalized !== 1) {
    throw new Error(`接口字段 ${fieldName} 必须为 0 或 1`);
  }
  return normalized;
}

export function normalizeFiniteNumber(value: unknown, fieldName: string): number {
  const normalized = Number(value);
  if (!Number.isFinite(normalized)) {
    throw new Error(`接口字段 ${fieldName} 必须为有效数值`);
  }
  return normalized;
}
