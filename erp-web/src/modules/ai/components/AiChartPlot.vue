<script setup lang="ts">
import { computed } from 'vue';
import type { AiChartRow, AiChartSpec } from '../types';

const props = withDefaults(defineProps<{ chart: AiChartSpec; large?: boolean }>(), {
  large: false,
});

const palette = ['#2563eb', '#059669', '#d97706', '#7c3aed', '#dc2626', '#0891b2'];
const vertical = { width: 720, height: 270, left: 62, right: 668, top: 32, bottom: 214, labelY: 246 };
const horizontal = { width: 720, height: 270, left: 160, right: 668, top: 28, bottom: 242 };
const pieFrame = { cx: 285, cy: 135, r: 118 };

const data = computed(() => props.chart.data ?? []);
const fields = computed(() => {
  if (props.chart.yFields.length > 0) return props.chart.yFields;
  return props.chart.valueField ? [props.chart.valueField] : [];
});
const valueField = computed(() => props.chart.valueField || fields.value[0] || null);
const labelField = computed(() => props.chart.nameField || props.chart.xField || null);
const isCartesian = computed(() => ['line', 'bar', 'stackedBar'].includes(props.chart.type));

function chartNumber(row: AiChartRow, field: string | null | undefined) {
  if (!field) return 0;
  const value = row[field];
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function chartLabel(row: AiChartRow) {
  const value = labelField.value ? row[labelField.value] : '';
  return value == null ? '' : String(value);
}

function color(index: number) {
  return palette[index % palette.length];
}

function seriesName(field: string) {
  return props.chart.fieldLabels?.[field] || field;
}

function formatNumber(value: number) {
  if (Math.abs(value) >= 10000) return `${(value / 10000).toFixed(1).replace(/\.0$/, '')}万`;
  if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '');
}

function valueText(value: number) {
  const formatted = formatNumber(value);
  return yUnit.value ? `${formatted}${yUnit.value}` : formatted;
}

const yUnit = computed(() => props.chart.yUnit || inferUnit());

function inferUnit() {
  const text = `${props.chart.title} ${props.chart.description} ${fields.value.join(' ')}`;
  if (text.includes('率') || text.includes('占比') || text.includes('比例')) return '%';
  if (text.includes('金额') || text.includes('销售额') || text.includes('采购额')) return '元';
  if (text.includes('销量') || text.includes('需求') || text.includes('补货') || text.includes('库存') || text.includes('缺口')) return '件';
  return '';
}

/** 坐标图最大值：堆叠图按每行合计，普通图按单系列最大值。 */
const maxValue = computed(() => {
  const values = data.value.flatMap(row => fields.value.map(field => chartNumber(row, field)));
  const max = props.chart.type === 'stackedBar'
    ? Math.max(...data.value.map(row => fields.value.reduce((sum, field) => sum + Math.max(chartNumber(row, field), 0), 0)), 1)
    : Math.max(...values, 1);
  return yUnit.value === '%' ? Math.max(max, 100) : max;
});

const ticks = computed(() => {
  const max = maxValue.value;
  return [max, max * 0.75, max * 0.5, max * 0.25, 0];
});

function yFor(value: number) {
  return vertical.bottom - (value / maxValue.value) * (vertical.bottom - vertical.top);
}

function xStep() {
  return (vertical.right - vertical.left) / Math.max(data.value.length, 1);
}

function xFor(index: number) {
  return vertical.left + xStep() * index + xStep() / 2;
}

function tickText(value: number) {
  return valueText(value);
}

function linePoints(field: string) {
  return data.value.map((row, index) => `${xFor(index).toFixed(2)},${yFor(chartNumber(row, field)).toFixed(2)}`).join(' ');
}

function groupWidth() {
  return Math.min(xStep() * 0.72, 72);
}

function seriesWidth() {
  return groupWidth() / Math.max(fields.value.length, 1);
}

function barX(index: number, seriesIndex: number) {
  return xFor(index) - groupWidth() / 2 + seriesWidth() * seriesIndex;
}

function barHeight(row: AiChartRow, field: string) {
  const value = Math.max(chartNumber(row, field), 0);
  if (value <= 0) return 0;
  return Math.max(2, vertical.bottom - yFor(value));
}

/** 堆叠柱：同一行内按系列从底部累积。 */
function stackedBarY(row: AiChartRow, field: string) {
  const fieldIndex = fields.value.indexOf(field);
  let below = 0;
  for (let index = 0; index < fieldIndex; index++) {
    below += Math.max(chartNumber(row, fields.value[index]), 0);
  }
  return vertical.bottom - (below + Math.max(chartNumber(row, field), 0)) / maxValue.value
    * (vertical.bottom - vertical.top);
}

function stackedBarHeight(row: AiChartRow, field: string) {
  const value = Math.max(chartNumber(row, field), 0);
  return value <= 0 ? 0 : Math.max(2, value / maxValue.value * (vertical.bottom - vertical.top));
}

const horizontalMax = computed(() => Math.max(
  ...data.value.map(row => chartNumber(row, valueField.value)), 1,
));

const horizontalRowHeight = computed(() => (
  (horizontal.bottom - horizontal.top) / Math.max(data.value.length, 1)
));

const horizontalBarHeight = computed(() => Math.min(24, horizontalRowHeight.value * 0.62));

function horizontalY(index: number) {
  return horizontal.top + horizontalRowHeight.value * index
    + (horizontalRowHeight.value - horizontalBarHeight.value) / 2;
}

function horizontalWidth(row: AiChartRow) {
  const value = Math.max(chartNumber(row, valueField.value), 0);
  return value / horizontalMax.value * (horizontal.right - horizontal.left);
}

const pieTotal = computed(() => data.value.reduce(
  (sum, row) => sum + Math.max(chartNumber(row, valueField.value), 0), 0,
));

const pieSlices = computed(() => {
  const total = pieTotal.value;
  let angle = -Math.PI / 2;
  return data.value.map((row, index) => {
    const value = Math.max(chartNumber(row, valueField.value), 0);
    const sweep = total <= 0 ? 0 : value / total * Math.PI * 2;
    const start = angle;
    const end = angle + sweep;
    angle = end;
    return {
      key: `${chartLabel(row)}-${index}`,
      label: chartLabel(row),
      value,
      percent: total <= 0 ? 0 : value / total * 100,
      color: color(index),
      path: sweep >= Math.PI * 2 - 0.0001
        ? `M ${pieFrame.cx} ${pieFrame.cy - pieFrame.r} A ${pieFrame.r} ${pieFrame.r} 0 1 1 ${pieFrame.cx - 0.01} ${pieFrame.cy - pieFrame.r} Z`
        : arcPath(start, end),
    };
  });
});

function arcPath(start: number, end: number) {
  const x1 = pieFrame.cx + pieFrame.r * Math.cos(start);
  const y1 = pieFrame.cy + pieFrame.r * Math.sin(start);
  const x2 = pieFrame.cx + pieFrame.r * Math.cos(end);
  const y2 = pieFrame.cy + pieFrame.r * Math.sin(end);
  const large = end - start > Math.PI ? 1 : 0;
  return `M ${pieFrame.cx} ${pieFrame.cy} L ${x1.toFixed(2)} ${y1.toFixed(2)} `
    + `A ${pieFrame.r} ${pieFrame.r} 0 ${large} 1 ${x2.toFixed(2)} ${y2.toFixed(2)} Z`;
}

function percentText(value: number) {
  return `${value.toFixed(value >= 10 ? 0 : 1).replace(/\.0$/, '')}%`;
}

function truncate(value: string, limit: number) {
  return value.length <= limit ? value : `${value.slice(0, limit)}…`;
}
</script>

<template>
  <div class="ai-chart-plot" :class="{ 'ai-chart-plot--large': large }">
    <svg
      v-if="isCartesian"
      :viewBox="`0 0 ${vertical.width} ${vertical.height}`"
      role="img"
      :aria-label="chart.title"
    >
      <g class="ai-chart-plot__grid">
        <line v-for="tick in ticks" :key="`grid-${tick}`" :x1="vertical.left" :y1="yFor(tick)" :x2="vertical.right" :y2="yFor(tick)" />
        <line :x1="vertical.left" :y1="vertical.bottom" :x2="vertical.right" :y2="vertical.bottom" class="ai-chart-plot__baseline" />
      </g>
      <g class="ai-chart-plot__y-axis">
        <text x="4" y="18" class="ai-chart-plot__unit">{{ yUnit ? `单位：${yUnit}` : '' }}</text>
        <text v-for="tick in ticks" :key="`tick-${tick}`" x="4" :y="yFor(tick) + 4">{{ tickText(tick) }}</text>
      </g>

      <g v-if="chart.type === 'line'" class="ai-chart-plot__line-layer">
        <polyline
          v-for="(field, seriesIndex) in fields"
          :key="field"
          fill="none"
          stroke-linecap="round"
          stroke-linejoin="round"
          :stroke="color(seriesIndex)"
          :points="linePoints(field)"
        />
        <g v-for="(field, seriesIndex) in fields" :key="`points-${field}`">
          <circle
            v-for="(row, index) in data"
            :key="`${field}-${chartLabel(row)}-${index}`"
            :cx="xFor(index)"
            :cy="yFor(chartNumber(row, field))"
            r="3.2"
            :fill="color(seriesIndex)"
          />
        </g>
      </g>

      <g v-else-if="chart.type === 'stackedBar'" class="ai-chart-plot__bar-layer">
        <template v-for="(row, index) in data" :key="`stack-${chartLabel(row)}-${index}`">
          <rect
            v-for="(field, seriesIndex) in fields"
            :key="`${field}-${index}`"
            :x="barX(index, 0)"
            :y="stackedBarY(row, field)"
            :width="groupWidth()"
            :height="stackedBarHeight(row, field)"
            rx="2"
            :fill="color(seriesIndex)"
          />
        </template>
      </g>

      <g v-else class="ai-chart-plot__bar-layer">
        <template v-for="(row, index) in data" :key="`bar-${chartLabel(row)}-${index}`">
          <rect
            v-for="(field, seriesIndex) in fields"
            :key="`${field}-${index}`"
            :x="barX(index, seriesIndex)"
            :y="yFor(chartNumber(row, field))"
            :width="Math.max(seriesWidth() - 2, 2)"
            :height="barHeight(row, field)"
            rx="2"
            :fill="color(seriesIndex)"
          />
        </template>
      </g>

      <g class="ai-chart-plot__x-axis">
        <text
          v-for="(row, index) in data"
          :key="`label-${chartLabel(row)}-${index}`"
          :x="xFor(index)"
          :y="vertical.labelY"
          text-anchor="middle"
        >{{ truncate(chartLabel(row), 10) }}</text>
      </g>
    </svg>

    <svg
      v-else-if="chart.type === 'horizontalBar'"
      :viewBox="`0 0 ${horizontal.width} ${horizontal.height}`"
      role="img"
      :aria-label="chart.title"
    >
      <g class="ai-chart-plot__horizontal">
        <template v-for="(row, index) in data" :key="`h-${chartLabel(row)}-${index}`">
          <text
            :x="horizontal.left - 10"
            :y="horizontalY(index) + horizontalBarHeight / 2 + 4"
            text-anchor="end"
          >{{ truncate(chartLabel(row), 18) }}</text>
          <rect
            :x="horizontal.left"
            :y="horizontalY(index)"
            :width="horizontalWidth(row)"
            :height="horizontalBarHeight"
            rx="3"
            :fill="color(index)"
          />
          <text
            :x="horizontal.left + horizontalWidth(row) + 8"
            :y="horizontalY(index) + horizontalBarHeight / 2 + 4"
            class="ai-chart-plot__value"
          >{{ valueText(chartNumber(row, valueField)) }}</text>
        </template>
      </g>
    </svg>

    <div v-else class="ai-chart-plot__pie">
      <svg :viewBox="`0 0 ${vertical.width} ${vertical.height}`" role="img" :aria-label="chart.title">
        <g v-if="pieTotal <= 0">
          <circle :cx="pieFrame.cx" :cy="pieFrame.cy" :r="pieFrame.r" fill="#e5e7eb" />
          <text :x="pieFrame.cx" :y="pieFrame.cy + 4" text-anchor="middle" class="ai-chart-plot__empty">暂无数据</text>
        </g>
        <template v-else>
          <path
            v-for="slice in pieSlices"
            :key="slice.key"
            :d="slice.path"
            :fill="slice.color"
            stroke="white"
            stroke-width="1.5"
          />
        </template>
      </svg>
      <div class="ai-chart-plot__legend ai-chart-plot__legend--pie">
        <span v-for="slice in pieSlices" :key="`legend-${slice.key}`">
          <i :style="{ background: slice.color }" />
          <b>{{ truncate(slice.label, 16) }}</b>
          <em>{{ valueText(slice.value) }} · {{ percentText(slice.percent) }}</em>
        </span>
      </div>
    </div>

    <div v-if="!isCartesian && chart.type !== 'pie' && fields.length > 1" class="ai-chart-plot__legend">
      <span v-for="(field, index) in fields" :key="`legend-${field}`">
        <i :style="{ background: color(index) }" />
        {{ seriesName(field) }}
      </span>
    </div>

    <div v-else-if="isCartesian && fields.length > 1" class="ai-chart-plot__legend">
      <span v-for="(field, index) in fields" :key="`legend-${field}`">
        <i :style="{ background: color(index) }" />
        {{ seriesName(field) }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.ai-chart-plot {
  display: grid;
  gap: 8px;
  min-width: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chart-plot svg {
  width: 100%;
  height: 235px;
  overflow: visible;
  background: white;
}

.ai-chart-plot--large svg {
  height: min(58vh, 460px);
}

.ai-chart-plot__grid line {
  stroke: #e5e7eb;
  stroke-width: 1;
}

.ai-chart-plot__grid .ai-chart-plot__baseline {
  stroke: #94a3b8;
  stroke-width: 1.4;
}

.ai-chart-plot__y-axis text,
.ai-chart-plot__x-axis text,
.ai-chart-plot__horizontal text {
  fill: #475467;
  font-size: 11px;
  font-weight: 600;
}

.ai-chart-plot__y-axis .ai-chart-plot__unit {
  fill: #667085;
  font-size: 10px;
  font-weight: 500;
}

.ai-chart-plot__horizontal .ai-chart-plot__value {
  fill: #172033;
  font-size: 11px;
  font-weight: 700;
}

.ai-chart-plot__line-layer polyline {
  stroke-width: 3;
}

.ai-chart-plot__line-layer circle {
  stroke: white;
  stroke-width: 2;
}

.ai-chart-plot__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  color: #475467;
  font-size: 11px;
}

.ai-chart-plot__legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ai-chart-plot__legend i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex: none;
}

.ai-chart-plot__legend--pie {
  display: grid;
  gap: 8px;
  padding-right: 4px;
}

.ai-chart-plot__legend--pie span {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  color: #475467;
  font-size: 11px;
  line-height: 1.45;
}

.ai-chart-plot__legend--pie b {
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-chart-plot__legend--pie em {
  color: #98a2b3;
  font-size: 11px;
  font-style: normal;
  white-space: nowrap;
}

.ai-chart-plot__pie {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(190px, 0.85fr);
  gap: 16px;
  align-items: center;
}

.ai-chart-plot__empty {
  fill: #667085;
  font-size: 12px;
}

@media (max-width: 640px) {
  .ai-chart-plot__pie {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>