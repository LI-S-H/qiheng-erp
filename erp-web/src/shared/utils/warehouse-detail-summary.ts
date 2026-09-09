import { formatQtyByPrecision, normalizeQuantityPrecision } from '@/shared/utils/qty';

export interface WarehouseDetailQuantityInput {
  value: number | null | undefined;
  unitName?: string | null;
  quantityPrecision?: number | null;
}

export interface WarehouseDetailQuantitySummary {
  text: string;
  fullText: string;
  groupCount: number;
}

type QuantityTotal = { value: number; precision: number };
type SourceQuantityTotal = QuantityTotal & { sourceUnit: string; summaryUnit: string };

const DISCRETE_UNITS = new Set([
  '件', '箱', '套', '个', '只', '支', '把', '包', '盒', '瓶', '桶', '台', '张', '块',
  '袋', '捆', '卷', '盘', '根', '双', '对', '片', '枚', '条', '部', '架', '组', '本', '卡',
]);
const CONTINUOUS_UNITS = new Set([
  'kg', 'g', 'mg', 't', 'l', 'ml', 'm', 'cm', 'mm', 'km', 'm2', 'm3',
  '千克', '公斤', '克', '毫克', '吨', '斤', '两', '升', '毫升', '米', '厘米', '毫米', '千米',
  '平方米', '平方厘米', '平方毫米', '立方米', '立方厘米', '立方毫米', '㎡', '㎥', 'm²', 'm³',
]);

function normalizeUnitKey(unitName?: string | null) {
  return unitName?.trim().toLowerCase().replace(/\s+/g, '') || '';
}

function summaryUnit(unitName: string | null | undefined, quantityPrecision: number | null | undefined) {
  const unit = unitName?.trim() || '未标明单位';
  const key = normalizeUnitKey(unit);
  // 归并规则以单位语义优先：即使历史数据把“箱”等离散单位配置为小数精度，也按“件”展示。
  if (DISCRETE_UNITS.has(key) || !key) return '件';
  if (CONTINUOUS_UNITS.has(key)) return /^[a-z0-9]+$/.test(key) ? key : unit;
  const precision = normalizeQuantityPrecision(quantityPrecision);
  if (precision > 0) return unit;
  return unit;
}

function addTotal(totals: Map<string, QuantityTotal>, unit: string, value: number, precision: number) {
  const previous = totals.get(unit) || { value: 0, precision: 0 };
  totals.set(unit, {
    value: Number((previous.value + value).toFixed(8)),
    precision: Math.max(previous.precision, precision),
  });
}

function formatTotal(total: QuantityTotal, unit: string, signed: boolean) {
  const prefix = signed && total.value > 0 ? '+' : '';
  return `${prefix}${formatQtyByPrecision(total.value, total.precision, '0')} ${unit}`;
}

/**
 * 标题行汇总只作用于当前已加载的明细。离散单位按约定归并为“件”，
 * 连续和未知单位保留原单位，绝不伪造包装规格换算率或跨单位相加。
 */
export function summarizeWarehouseDetailQuantities(
  inputs: WarehouseDetailQuantityInput[],
  options: { signed?: boolean; maxVisibleGroups?: number } = {},
): WarehouseDetailQuantitySummary {
  const totals = new Map<string, QuantityTotal>();
  const sourceTotals = new Map<string, SourceQuantityTotal>();
  for (const input of inputs) {
    if (input.value === null || input.value === undefined) continue;
    const value = Number(input.value);
    if (!Number.isFinite(value)) continue;
    const precision = normalizeQuantityPrecision(input.quantityPrecision);
    const sourceUnit = input.unitName?.trim() || '未标明单位';
    const groupedUnit = summaryUnit(input.unitName, input.quantityPrecision);
    addTotal(totals, groupedUnit, value, precision);
    const sourceKey = `${groupedUnit}\u0000${sourceUnit}`;
    const sourcePrevious = sourceTotals.get(sourceKey) || { value: 0, precision: 0, sourceUnit, summaryUnit: groupedUnit };
    sourceTotals.set(sourceKey, {
      ...sourcePrevious,
      value: Number((sourcePrevious.value + value).toFixed(8)),
      precision: Math.max(sourcePrevious.precision, precision),
    });
  }

  const sourceValues = Array.from(sourceTotals.values())
    .sort((left, right) => left.sourceUnit.localeCompare(right.sourceUnit, 'zh-CN'));
  const values = Array.from(totals.entries())
    .sort(([left], [right]) => (left === '件' ? -1 : right === '件' ? 1 : left.localeCompare(right, 'zh-CN')))
    .map(([unit, total]) => {
      const text = formatTotal(total, unit, Boolean(options.signed));
      const sources = sourceValues
        .filter(source => source.summaryUnit === unit)
        .map(source => formatTotal(source, source.sourceUnit, Boolean(options.signed)));
      return unit === '件' && sources.length > 1 ? `${text}（原始：${sources.join(' · ')}）` : text;
    });
  const maxVisibleGroups = Math.max(1, options.maxVisibleGroups ?? 2);
  const visible = values.slice(0, maxVisibleGroups);
  if (values.length > maxVisibleGroups) visible.push(`+${values.length - maxVisibleGroups} 种单位`);
  return {
    text: visible.map(value => value.replace(/（原始：.*）$/, '')).join(' · ') || '-',
    fullText: values.join(' · ') || '-',
    groupCount: values.length,
  };
}