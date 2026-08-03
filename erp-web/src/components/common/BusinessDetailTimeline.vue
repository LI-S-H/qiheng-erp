<script setup lang="ts">
export interface BusinessDetailTimelineItem {
  id: string;
  action: string;
  type: string;
  tone?: 'default' | 'review' | 'warehouse';
  operatorName?: string | null;
  occurredAt?: string | null;
  referenceLabel?: string;
  referenceNo?: string | null;
}

defineProps<{
  items: BusinessDetailTimelineItem[];
  ariaLabel?: string;
}>();
</script>

<template>
  <ol class="business-detail-timeline" :aria-label="ariaLabel || '业务流程记录'">
    <li v-for="item in items" :key="item.id">
      <i aria-hidden="true" />
      <div class="business-detail-timeline__body">
        <div class="business-detail-timeline__title"><strong>{{ item.action }}</strong><span class="business-detail-timeline__type" :class="`is-${item.tone || 'default'}`">{{ item.type }}</span></div>
        <div class="business-detail-timeline__meta">
          <span v-if="item.operatorName"><small>操作人</small>{{ item.operatorName }}</span>
          <span v-if="item.referenceNo" class="business-detail-timeline__reference"><small>{{ item.referenceLabel || '关联单据' }}</small><code>{{ item.referenceNo }}</code></span>
          <span v-if="item.occurredAt" class="business-detail-timeline__time"><small>操作时间</small>{{ item.occurredAt }}</span>
        </div>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.business-detail-timeline { position: relative; margin: 0; padding: 3px 0; list-style: none; overflow: hidden; border: 1px solid var(--border); border-radius: calc(var(--radius) - 2px); }
.business-detail-timeline li { position: relative; display: grid; grid-template-columns: 16px minmax(0, 1fr); gap: 10px; padding: 11px 14px; }
.business-detail-timeline li + li { border-top: 1px solid var(--border); }
.business-detail-timeline li::before { position: absolute; top: 0; bottom: 0; left: 20px; width: 1px; transform: translateX(-50%); background: var(--border); content: ''; }
.business-detail-timeline li:first-child::before { top: 20px; }
.business-detail-timeline li:last-child::before { bottom: 20px; }
.business-detail-timeline li > i { position: relative; z-index: 1; display: block; width: 12px; height: 12px; margin-top: 3px; border: 3px solid color-mix(in srgb, var(--primary) 18%, var(--card)); border-radius: 50%; background: var(--primary); }
.business-detail-timeline__body { min-width: 0; }
.business-detail-timeline__title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.business-detail-timeline__title strong { color: var(--foreground); font-size: 13px; font-weight: 650; line-height: 18px; }
.business-detail-timeline__type { display: inline-flex; align-items: center; min-height: 18px; padding: 1px 6px; border: 1px solid var(--border); border-radius: 999px; background: var(--muted); color: var(--muted-foreground); font-size: 11px; font-weight: 600; line-height: 14px; }
.business-detail-timeline__type.is-review { border-color: #bfdbfe; background: #eff6ff; color: #1d4ed8; }
.business-detail-timeline__type.is-warehouse { border-color: #bbf7d0; background: #f0fdf4; color: #15803d; }
.business-detail-timeline__meta { display: flex; flex-wrap: wrap; align-items: center; gap: 7px 22px; margin-top: 6px; color: var(--muted-foreground); font-size: 12px; line-height: 16px; }
.business-detail-timeline__meta > span { display: inline-flex; align-items: center; gap: 6px; min-width: 0; }
.business-detail-timeline__meta small { color: color-mix(in srgb, var(--muted-foreground) 76%, transparent); font-size: 11px; white-space: nowrap; }
.business-detail-timeline__reference code { padding: 1px 5px; border-radius: 4px; background: var(--muted); color: var(--muted-foreground); font-size: 11px; }
.business-detail-timeline__time { margin-left: auto; font-size: 11px; white-space: nowrap; }

@media (max-width: 640px) {
  .business-detail-timeline__time { margin-left: 0; }
}
</style>
