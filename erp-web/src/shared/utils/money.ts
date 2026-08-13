/**
 * 金额接口边界：服务端以元字符串传输，页面仍使用有限 number 展示和编辑。
 * 上限确保金额 ×100 仍不超过 JavaScript 安全整数，禁止精度静默丢失。
 */
export const MAX_SAFE_MONEY = 90_000_000_000_000;
const MONEY_PATTERN = /^\d+(?:\.\d{1,2})?$/;

export function normalizeMoneyNumber(value: unknown, fieldName: string, nullable = false, allowMockNumber = false): number | null {
  if (value === null || value === undefined || value === '') {
    if (nullable) return null;
    throw new Error(`接口字段 ${fieldName} 必须为金额字符串`);
  }
  if (allowMockNumber && typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= MAX_SAFE_MONEY) {
    return value;
  }
  if (typeof value !== 'string' || !MONEY_PATTERN.test(value)) {
    throw new Error(`接口字段 ${fieldName} 必须为最多两位小数的非负金额字符串`);
  }
  const normalized = Number(value);
  if (!Number.isFinite(normalized) || normalized > MAX_SAFE_MONEY) {
    throw new Error(`接口字段 ${fieldName} 超出前端安全金额范围`);
  }
  return normalized;
}

export function serializeMoney(value: number | null | undefined, fieldName: string, nullable = false): string | null {
  if (value === null || value === undefined) {
    if (nullable) return null;
    throw new Error(`${fieldName}不能为空`);
  }
  if (!Number.isFinite(value) || value < 0 || value > MAX_SAFE_MONEY) {
    throw new Error(`${fieldName}必须为不超过 ${MAX_SAFE_MONEY.toFixed(2)} 的非负金额`);
  }
  return value.toFixed(2);
}
