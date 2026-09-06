/**
 * 数量展示边界：所有数量字段在数据库按 100 倍整数存储，接口返回业务真实值，
 * 页面按产品档案的 `quantityPrecision`（0-2）决定显示位数。
 *
 * 离散单位（个、箱、盒）精度为 0，显示整数；称重类（kg）精度为 2，显示两位小数。
 * 统一在此处收敛，避免各页面各写一份格式化规则导致同一产品在不同页面显示位数不一致。
 */

/** 产品数量精度的合法区间，与 `product.quantity_precision` 的 CHECK 约束一致。 */
const MIN_QUANTITY_PRECISION = 0;
const MAX_QUANTITY_PRECISION = 2;

/**
 * 将精度归一到 0-2 的整数。非法值回退为 0，保证渲染不因脏数据中断。
 */
export function normalizeQuantityPrecision(precision: unknown): number {
  const value = Number(precision);
  if (!Number.isInteger(value) || value < MIN_QUANTITY_PRECISION || value > MAX_QUANTITY_PRECISION) {
    return MIN_QUANTITY_PRECISION;
  }
  return value;
}

/**
 * 严格校验接口返回的数量精度，越界抛错。
 *
 * 精度是渲染小数位的依据，脏数据必须暴露而不是静默回退，否则同一产品在不同页面显示位数会不一致。
 */
export function assertQuantityPrecision(value: unknown, fieldName = 'quantityPrecision'): number {
  const precision = Number(value);
  if (!Number.isInteger(precision) || precision < MIN_QUANTITY_PRECISION || precision > MAX_QUANTITY_PRECISION) {
    throw new Error(`接口字段 ${fieldName} 必须为 ${MIN_QUANTITY_PRECISION} 到 ${MAX_QUANTITY_PRECISION} 的整数`);
  }
  return precision;
}

/**
 * 按产品数量精度格式化数量，固定保留 precision 位小数并按千分位分组。
 *
 * 固定位数而非去尾零，同一列的小数点才能对齐；配合 `tabular-nums` 保证数字等宽。
 */
export function formatQtyByPrecision(value: number | null | undefined, precision: unknown, fallback = '-'): string {
  if (value === null || value === undefined) return fallback;
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return fallback;
  const fractionDigits = normalizeQuantityPrecision(precision);
  return numeric.toLocaleString('zh-CN', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
}

/**
 * 校验数量的小数位是否在产品精度以内，用于表单提交前拦截。
 */
export function matchesQuantityPrecision(value: number, precision: unknown): boolean {
  if (!Number.isFinite(value)) return false;
  const fractionDigits = normalizeQuantityPrecision(precision);
  return Number(value.toFixed(fractionDigits)) === value;
}

/**
 * 数量输入框的步进值：精度 0 步进 1，精度 1 步进 0.1，精度 2 步进 0.01。
 */
export function quantityStepFor(precision: unknown): string {
  const fractionDigits = normalizeQuantityPrecision(precision);
  return fractionDigits === 0 ? '1' : (1 / 10 ** fractionDigits).toFixed(fractionDigits);
}
