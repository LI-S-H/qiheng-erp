<script setup lang="ts">
import { Columns3, Eye, Pin, RotateCcw } from "lucide-vue-next";
import { computed } from "vue";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Switch } from "@/components/ui/switch";

export interface TableColumnPreferenceCanvasItem {
  key: string;
  label: string;
  visible: boolean;
  pinned?: boolean;
  pinAllowed?: boolean;
}

const props = withDefaults(
  defineProps<{
    open: boolean;
    columns: readonly TableColumnPreferenceCanvasItem[];
    pinningEnabled?: boolean;
  }>(),
  { pinningEnabled: false },
);

const emit = defineEmits<{
  "update:open": [open: boolean];
  "toggle-visible": [key: string, visible: boolean];
  "toggle-pinned": [key: string, pinned: boolean];
  reset: [];
}>();

const visibleCount = computed(
  () => props.columns.filter((column) => column.visible).length,
);
const pinnedCount = computed(
  () => props.columns.filter((column) => column.pinned).length,
);
</script>

<template>
  <Popover :open="open" @update:open="emit('update:open', $event)">
    <PopoverTrigger as-child><slot name="trigger" /></PopoverTrigger>
    <PopoverContent
      align="end"
      :side-offset="8"
      class="column-preferences-canvas !w-[min(360px,calc(100vw-1rem))] !max-h-[min(520px,calc(100dvh-9rem))] !gap-0 !overflow-hidden !rounded-xl !p-0"
      aria-label="表格列设置"
    >
      <header class="column-preferences-canvas__header">
        <div class="flex min-w-0 items-start gap-3">
          <div class="column-preferences-canvas__icon"><Columns3 /></div>
          <div class="min-w-0">
            <h2>表格列设置</h2>
            <p>按需显示字段，固定选择仅影响所选列。</p>
          </div>
        </div>
      </header>

      <div class="column-preferences-canvas__summary">
        <div class="column-preferences-canvas__stat">
          <Eye aria-hidden="true" />
          <span>显示字段</span><strong>{{ visibleCount }}/{{ columns.length }}</strong>
        </div>
        <div v-if="pinningEnabled" class="column-preferences-canvas__stat">
          <Pin aria-hidden="true" />
          <span>固定列</span><strong>{{ pinnedCount }}</strong>
        </div>
      </div>

      <div class="column-preferences-canvas__body">
        <section class="column-preferences-canvas__section">
          <div class="column-preferences-canvas__section-heading">
            <div><h3>显示字段</h3><p>关闭后，该字段会从当前表格隐藏。</p></div>
          </div>
          <div class="column-preferences-canvas__list">
            <div v-for="column in columns" :key="column.key" class="column-preferences-canvas__row">
              <span>{{ column.label }}</span>
              <Switch
                size="sm"
                :model-value="column.visible"
                :aria-label="`切换${column.label}显示`"
                @update:model-value="emit('toggle-visible', column.key, Boolean($event))"
              />
            </div>
          </div>
        </section>

        <section v-if="pinningEnabled" class="column-preferences-canvas__section">
          <div class="column-preferences-canvas__section-heading">
            <div><h3>固定列</h3><p>固定列停留在原表格位置；相邻固定列只在组合两端显示阴影。</p></div>
          </div>
          <div class="column-preferences-canvas__list">
            <div v-for="column in columns" :key="column.key" class="column-preferences-canvas__row" :class="{ 'is-disabled': !column.visible || !column.pinAllowed }">
              <div><span>{{ column.label }}</span><small v-if="!column.visible">请先显示此列</small></div>
              <Switch
                size="sm"
                :model-value="Boolean(column.pinned)"
                :disabled="!column.visible || !column.pinAllowed"
                :aria-label="`切换${column.label}列固定`"
                @update:model-value="emit('toggle-pinned', column.key, Boolean($event))"
              />
            </div>
          </div>
        </section>

        <div class="column-preferences-canvas__note"><Pin aria-hidden="true" /><span>操作列始终固定在右侧，不参与此设置。</span></div>
      </div>

      <footer class="column-preferences-canvas__footer">
        <Button variant="outline" class="w-full" @click="emit('reset')"><RotateCcw class="size-4" />恢复默认设置</Button>
      </footer>
    </PopoverContent>
  </Popover>
</template>

<style scoped>
.column-preferences-canvas { display: flex; flex-direction: column; background: var(--background); box-shadow: 0 16px 36px rgb(15 23 42 / 16%); }
.column-preferences-canvas__header { display: flex; align-items: flex-start; gap: 12px; border-bottom: 1px solid var(--border); padding: 17px 16px 14px; }
.column-preferences-canvas__header h2 { margin: 0; color: var(--foreground); font-size: 15px; font-weight: 650; line-height: 21px; }
.column-preferences-canvas__header p { margin: 2px 0 0; color: var(--muted-foreground); font-size: 12px; line-height: 18px; }
.column-preferences-canvas__icon { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border: 1px solid color-mix(in srgb, var(--primary) 22%, var(--border)); border-radius: 9px; background: color-mix(in srgb, var(--primary) 9%, var(--background)); color: var(--primary); }
.column-preferences-canvas__icon :deep(svg) { width: 17px; height: 17px; }
.column-preferences-canvas__summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--border); background: color-mix(in srgb, var(--muted) 42%, var(--background)); }
.column-preferences-canvas__stat { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 7px; min-width: 0; padding: 10px; border: 1px solid var(--border); border-radius: 9px; background: var(--card); color: var(--muted-foreground); font-size: 12px; }
.column-preferences-canvas__stat :deep(svg) { width: 14px; height: 14px; color: var(--primary); }
.column-preferences-canvas__stat strong { color: var(--foreground); font-size: 14px; font-variant-numeric: tabular-nums; }
.column-preferences-canvas__body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 16px; }
.column-preferences-canvas__section + .column-preferences-canvas__section { margin-top: 20px; }
.column-preferences-canvas__section-heading { margin-bottom: 10px; }
.column-preferences-canvas__section-heading h3 { margin: 0; color: var(--foreground); font-size: 14px; font-weight: 650; line-height: 20px; }
.column-preferences-canvas__section-heading p { margin: 3px 0 0; color: var(--muted-foreground); font-size: 12px; line-height: 18px; }
.column-preferences-canvas__list { overflow: hidden; border: 1px solid var(--border); border-radius: 10px; background: var(--card); }
.column-preferences-canvas__row { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 47px; padding: 9px 13px; color: var(--foreground); font-size: 13px; }
.column-preferences-canvas__row + .column-preferences-canvas__row { border-top: 1px solid color-mix(in srgb, var(--border) 72%, transparent); }
.column-preferences-canvas__row > div { display: grid; gap: 1px; min-width: 0; }
.column-preferences-canvas__row small { color: var(--muted-foreground); font-size: 11px; line-height: 16px; }
.column-preferences-canvas__row.is-disabled { color: var(--muted-foreground); background: color-mix(in srgb, var(--muted) 36%, transparent); }
.column-preferences-canvas__note { display: flex; align-items: flex-start; gap: 8px; margin-top: 18px; padding: 11px 12px; border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--border)); border-radius: 9px; background: color-mix(in srgb, var(--primary) 5%, var(--background)); color: var(--muted-foreground); font-size: 12px; line-height: 18px; }
.column-preferences-canvas__note :deep(svg) { flex: 0 0 auto; width: 14px; height: 14px; margin-top: 2px; color: var(--primary); }
.column-preferences-canvas__footer { flex: 0 0 auto; border-top: 1px solid var(--border); padding: 12px 16px; background: var(--background); }
</style>
