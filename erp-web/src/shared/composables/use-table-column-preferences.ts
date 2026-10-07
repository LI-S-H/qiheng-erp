import { computed, ref } from 'vue';

export type TableColumnPin = 'left' | 'none';

export interface TableColumnPreferenceDefinition {
  key: string;
  label: string;
  width: number;
  /** 操作列等必需列不能被隐藏。 */
  required?: boolean;
  /** 只有业务列可由用户固定到左侧。 */
  pinAllowed?: boolean;
}

interface StoredColumnPreference {
  visible?: Record<string, boolean>;
  pinned?: Record<string, TableColumnPin>;
}

/**
 * 列显示与固定偏好。固定列需要按可见列宽度累计 left，避免多个 sticky 单元格重叠。
 */
export function useTableColumnPreferences(storageKey: string, definitions: readonly TableColumnPreferenceDefinition[]) {
  const defaults = (): StoredColumnPreference => ({
    visible: Object.fromEntries(definitions.map(column => [column.key, true])),
    pinned: Object.fromEntries(definitions.map(column => [column.key, 'none'])),
  });

  function readPreferences(): StoredColumnPreference {
    const fallback = defaults();
    if (typeof window === 'undefined') return fallback;
    try {
      const parsed = JSON.parse(window.localStorage.getItem(storageKey) ?? '{}') as StoredColumnPreference;
      for (const column of definitions) {
        if (typeof parsed.visible?.[column.key] === 'boolean' && !column.required) {
          fallback.visible![column.key] = parsed.visible[column.key];
        }
        if (column.pinAllowed && parsed.pinned?.[column.key] === 'left') {
          fallback.pinned![column.key] = 'left';
        }
      }
    } catch {
      // 偏好数据损坏时回退默认状态，不能影响列表正常使用。
    }
    return fallback;
  }

  const preference = ref<StoredColumnPreference>(readPreferences());

  function persist() {
    if (typeof window !== 'undefined') window.localStorage.setItem(storageKey, JSON.stringify(preference.value));
  }

  function definition(key: string) {
    return definitions.find(column => column.key === key);
  }

  function isVisible(key: string) {
    const column = definition(key);
    return Boolean(column?.required || preference.value.visible?.[key] !== false);
  }

  function isPinnedLeft(key: string) {
    return isVisible(key) && preference.value.pinned?.[key] === 'left';
  }

  function setVisible(key: string, visible: boolean) {
    const column = definition(key);
    if (!column || column.required) return;
    preference.value = {
      visible: { ...preference.value.visible, [key]: visible },
      pinned: { ...preference.value.pinned, ...(visible ? {} : { [key]: 'none' }) },
    };
    persist();
  }

  function setPinnedLeft(key: string, pinned: boolean) {
    const column = definition(key);
    if (!column?.pinAllowed) return;
    preference.value = {
      visible: { ...preference.value.visible, ...(pinned ? { [key]: true } : {}) },
      pinned: { ...preference.value.pinned, [key]: pinned ? 'left' : 'none' },
    };
    persist();
  }

  function reset() {
    preference.value = defaults();
    if (typeof window !== 'undefined') window.localStorage.removeItem(storageKey);
  }

  const leftOffsets = computed(() => {
    const offsets: Record<string, number> = {};
    let offset = 0;
    for (const column of definitions) {
      if (!isPinnedLeft(column.key)) continue;
      offsets[column.key] = offset;
      offset += column.width;
    }
    return offsets;
  });

  const tableMinWidth = computed(() => definitions.reduce(
    (total, column) => total + (isVisible(column.key) ? column.width : 0),
    0,
  ));

  return {
    isPinnedLeft,
    isVisible,
    leftOffsets,
    reset,
    setPinnedLeft,
    setVisible,
    tableMinWidth,
  };
}
