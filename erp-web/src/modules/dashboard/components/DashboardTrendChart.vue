<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { DashboardTrendPoint } from '../types';

type SeriesKey = 'salesAmount' | 'purchaseAmount' | 'grossMarginAmount';
const props = defineProps<{ points: DashboardTrendPoint[]; series: readonly { key: SeriesKey; label: string }[] }>();
const host = ref<HTMLElement | null>(null);
const width = ref(640);
const height = 258;
const cursor = ref<{ index: number; x: number; y: number } | null>(null);
let observer: ResizeObserver | undefined;
const colors: Record<SeriesKey, string> = { salesAmount: '#3977e6', purchaseAmount: '#d89b39', grossMarginAmount: '#16a18c' };
const amount = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2 }).format(value);
const scale = computed(() => {
  const values = props.points.flatMap(point => props.series.map(series => point[series.key]));
  const low = Math.min(0, ...values), high = Math.max(1, ...values), raw = (high - low) / 4;
  const base = 10 ** Math.floor(Math.log10(raw)), normalized = raw / base;
  const step = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * base;
  return { min: Math.floor(low / step) * step, max: Math.ceil(high / step) * step, step };
});
const divisor = computed(() => Math.max(Math.abs(scale.value.min), Math.abs(scale.value.max)) >= 10000 ? 10000 : 1);
const left = 48, top = 24, bottom = height - 34;
const right = computed(() => Math.max(left + 20, width.value - 22));
const x = (index: number) => left + (right.value - left) * index / Math.max(props.points.length - 1, 1);
const y = (value: number) => bottom - (value - scale.value.min) / (scale.value.max - scale.value.min) * (bottom - top);
const ticks = computed(() => Array.from({ length: Math.round((scale.value.max - scale.value.min) / scale.value.step) + 1 }, (_, index) => scale.value.min + index * scale.value.step));
// 日期数量由真实绘图区与字宽决定；默认七天在桌面完整标注，窄屏保留首末点。
const labels = computed(() => {
  const count = props.points.length;
  if (!count) return [];
  const available = Math.max(2, Math.floor((right.value - left) / 58) + 1);
  const slots = Math.min(count, available);
  return Array.from({ length: slots }, (_, index) => Math.round(index * (count - 1) / Math.max(slots - 1, 1)))
    .map(index => ({ index, text: props.points[index].date.slice(-5) }));
});
const selected = computed(() => cursor.value ? props.points[Math.min(cursor.value.index, props.points.length - 1)] : null);
const tooltipStyle = computed(() => {
  if (!cursor.value) return {};
  const tipWidth = 205, tipHeight = 126;
  let tipX = cursor.value.x + 15, tipY = cursor.value.y + 15;
  if (tipX + tipWidth > width.value - 4) tipX = cursor.value.x - tipWidth - 15;
  if (tipY + tipHeight > height - 4) tipY = cursor.value.y - tipHeight - 15;
  return { left: `${Math.max(4, Math.min(tipX, width.value - tipWidth - 4))}px`, top: `${Math.max(4, tipY)}px` };
});
function move(event: MouseEvent) {
  if (!host.value || !props.points.length) return;
  const rect = host.value.getBoundingClientRect();
  const pointX = Math.max(left, Math.min(right.value, event.clientX - rect.left));
  cursor.value = { index: Math.round((pointX - left) / (right.value - left) * (props.points.length - 1)), x: event.clientX - rect.left, y: event.clientY - rect.top };
}
function key(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || !props.points.length) return;
  event.preventDefault();
  const index = event.key === 'Home' ? 0 : event.key === 'End' ? props.points.length - 1 : Math.max(0, Math.min(props.points.length - 1, (cursor.value?.index ?? 0) + (event.key === 'ArrowRight' ? 1 : -1)));
  cursor.value = { index, x: x(index), y: 46 };
}
onMounted(() => {
  observer = new ResizeObserver(entries => { width.value = Math.max(180, entries[0].contentRect.width); cursor.value = null; });
  if (host.value) observer.observe(host.value);
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <div ref="host" class="dashboard-trend-chart-wrap" tabindex="0" role="group" aria-label="经营趋势图，使用左右方向键查看每日数据" @mousemove="move" @mouseleave="cursor = null" @blur="cursor = null" @keydown="key">
    <svg class="dashboard-trend-chart" :viewBox="`0 0 ${width} ${height}`" :width="width" :height="height" role="img" aria-label="销售、采购和毛利每日变化">
      <g class="dashboard-grid-lines"><line v-for="tick in ticks" :key="tick" :x1="left" :x2="right" :y1="y(tick)" :y2="y(tick)" /><line data-dashboard-trend-zero-axis :x1="left" :x2="right" :y1="y(0)" :y2="y(0)" /></g>
      <g class="dashboard-trend-axis"><text :x="left - 10" y="13" text-anchor="end">{{ divisor === 10000 ? '万元' : '元' }}</text><text v-for="tick in ticks" :key="tick" :x="left - 10" :y="y(tick) + 4" text-anchor="end">{{ +(tick / divisor).toFixed(2) }}</text></g>
      <polyline v-for="series in props.series" :key="series.key" :stroke="colors[series.key]" :points="props.points.map((point, index) => `${x(index)},${y(point[series.key])}`).join(' ')" />
      <g class="dashboard-trend-labels"><text v-for="label in labels" :key="label.index" :x="x(label.index)" :y="height - 9" text-anchor="middle">{{ label.text }}</text></g>
      <g v-if="cursor && selected" class="dashboard-chart-cursor"><line :x1="x(cursor.index)" :x2="x(cursor.index)" :y1="top" :y2="bottom" /><circle v-for="series in props.series" :key="series.key" :cx="x(cursor.index)" :cy="y(selected[series.key])" r="4.5" :fill="colors[series.key]" /></g>
    </svg>
    <div v-if="selected" class="dashboard-trend-tooltip" data-dashboard-trend-tooltip :style="tooltipStyle" role="tooltip"><strong>{{ selected.date }}</strong><span v-for="series in props.series" :key="series.key"><i :style="{ background: colors[series.key] }" />{{ series.label }}<b>{{ amount(selected[series.key]) }}</b></span></div>
  </div>
</template>
