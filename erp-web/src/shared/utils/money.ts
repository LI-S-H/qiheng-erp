/**
 * 金额接口边界：服务端以元字符串传输，页面仍使用有限 number 展示和编辑。
 * 上限确保金额 ×100 仍不超过 JavaScript 安全整数，禁止精度静默丢失。
 */
export const MAX_SAFE_MONEY = 90_000_000_000_000;
const MONEY_PATTERN = /^\d+(?:\.\d{1,2})?$/;
const SIGNED_MONEY_PATTERN = /^-?\d+(?:\.\d{1,2})?$/;

export function normalizeMoneyNumber(value: unknown, fieldName: string, nullable = false, allowMockNumber = false, allowNegative = false): number | null {
  if (value === null || value === undefined || value === '') {
    if (nullable) return null;
    throw new Error(`接口字段 ${fieldName} 必须为金额字符串`);
  }
  if (allowMockNumber && typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= MAX_SAFE_MONEY && (allowNegative || value >= 0)) {
    return value;
  }
  const moneyPattern = allowNegative ? SIGNED_MONEY_PATTERN : MONEY_PATTERN;
  if (typeof value !== 'string' || !moneyPattern.test(value)) {
    const rule = allowNegative ? '带正负号、最多两位小数的金额字符串' : '最多两位小数的非负金额字符串';
    throw new Error(`接口字段 ${fieldName} 必须为${rule}`);
  }
  const normalized = Number(value);
  if (!Number.isFinite(normalized) || Math.abs(normalized) > MAX_SAFE_MONEY) {
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
