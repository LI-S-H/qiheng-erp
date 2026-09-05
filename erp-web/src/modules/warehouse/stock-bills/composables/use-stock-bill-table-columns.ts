import { computed, ref, watch, type MaybeRefOrGetter } from 'vue';
import { toValue } from 'vue';
import type { StockBillDirection } from '../types';

export type StockBillOptionalColumnKey =
  | 'entryMode'
  | 'sourceType'
  | 'sourceNo'
  | 'party'
  | 'warehouse'
  | 'responsible'
  | 'createTime';

export type StockBillListColumnKey =
  | 'billNo'
  | 'billType'
  | StockBillOptionalColumnKey
  | 'quantity'
  | 'status'
  | 'actions';

interface StockBillListColumnDefinition {
  key: StockBillListColumnKey;
  label: string;
  width: number;
  required: boolean;
}

const STORAGE_VERSION = 1;
const STORAGE_PREFIX = 'erp.warehouse.stock-bills.table-columns';
export const stockBillListColumns: readonly StockBillListColumnDefinition[] = [
  { key: 'billNo', label: '单号与展开', width: 320, required: true },
  { key: 'billType', label: '类型', width: 140, required: true },
  { key: 'entryMode', label: '录入方式', width: 120, required: false },
  { key: 'sourceType', label: '来源类型', width: 130, required: false },
  { key: 'sourceNo', label: '来源单号', width: 170, required: false },
  { key: 'party', label: '往来单位', width: 170, required: false },
  { key: 'warehouse', label: '仓库', width: 150, required: false },
  { key: 'quantity', label: '数量', width: 120, required: true },
  { key: 'status', label: '状态', width: 110, required: true },
  { key: 'responsible', label: '负责人', width: 130, required: false },
  { key: 'createTime', label: '创建时间', width: 180, required: false },
  { key: 'actions', label: '操作', width: 132, required: true },
] as const;

export const stockBillOptionalColumns = stockBillListColumns.filter(
  (column): column is StockBillListColumnDefinition & { key: StockBillOptionalColumnKey; required: false } => !column.required,
);

type ColumnVisibility = Record<StockBillOptionalColumnKey, boolean>;

function createDefaultVisibility(): ColumnVisibility {
  return Object.fromEntries(stockBillOptionalColumns.map(column => [column.key, true])) as ColumnVisibility;
}

function storageKey(direction: StockBillDirection) {
  return `${STORAGE_PREFIX}.${direction.toLowerCase()}.v${STORAGE_VERSION}`;
}

function readVisibility(direction: StockBillDirection): ColumnVisibility {
  const defaults = createDefaultVisibility();
  if (typeof window === 'undefined') return defaults;

  try {
    const stored = JSON.parse(window.localStorage.getItem(storageKey(direction)) ?? '{}') as Partial<ColumnVisibility>;
    for (const column of stockBillOptionalColumns) {
      const storedValue = stored[column.key];
      if (typeof storedValue === 'boolean') defaults[column.key] = storedValue;
    }
  } catch {
    // 偏好损坏时回退到默认列，避免影响业务列表使用。
  }
  return defaults;
}

export function useStockBillTableColumns(direction: MaybeRefOrGetter<StockBillDirection>) {
  const visibility = ref<ColumnVisibility>(readVisibility(toValue(direction)));

  function persist() {
    if (typeof window === 'undefined') return;
    window.localStorage.setItem(storageKey(toValue(direction)), JSON.stringify(visibility.value));
  }

  function isListColumnVisible(key: StockBillListColumnKey) {
    const column = stockBillListColumns.find(item => item.key === key);
    return Boolean(column?.required || visibility.value[key as StockBillOptionalColumnKey]);
  }

  function setVisible(key: StockBillOptionalColumnKey, visible: boolean) {
    visibility.value = { ...visibility.value, [key]: visible };
    persist();
  }

  function reset() {
    visibility.value = createDefaultVisibility();
    if (typeof window !== 'undefined') window.localStorage.removeItem(storageKey(toValue(direction)));
  }

  watch(
    () => toValue(direction),
    nextDirection => {
      visibility.value = readVisibility(nextDirection);
    },
  );

  const visibleOptionalCount = computed(() => stockBillOptionalColumns.filter(column => isListColumnVisible(column.key)).length);
  const visibleColumnCount = computed(() => stockBillListColumns.filter(column => isListColumnVisible(column.key)).length);
  const tableMinWidth = computed(() => stockBillListColumns.reduce(
    (width, column) => width + (isListColumnVisible(column.key) ? column.width : 0),
    0,
  ));

  return {
    isVisible: isListColumnVisible,
    reset,
    setVisible,
    tableMinWidth,
    visibleColumnCount,
    visibleOptionalCount,
  };
}
