<script setup lang="ts">
import { computed, ref } from 'vue';
import { BarChart3, LineChart, Maximize2, PieChart } from 'lucide-vue-next';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import type { AiChartSpec } from '../types';

const props = defineProps<{
  chart: AiChartSpec;
}>();

const expanded = ref(false);
const palette = ['#2563eb', '#059669', '#d97706', '#7c3aed', '#dc2626'];
const frame = {
  width: 720,
  height: 270,
  left: 62,
  right: 668,
  top: 32,
  bottom: 214,
  labelY: 246,
} as const;

const fields = computed(() => {
  if (props.chart.yFields.length > 0) return props.chart.yFields;
  return props.chart.valueField ? [props.chart.valueField] : [];
});

const maxValue = computed(() => {
  const values = props.chart.data.flatMap(row => fields.value.map(field => chartNumber(row, field)));
  const max = Math.max(...values, 1);
  return props.chart.yUnit === '%' ? Math.max(max, 100) : max;
});

const ticks = computed(() => {
  const max = maxValue.value;
  return [max, max * 0.75, max * 0.5, max * 0.25, 0];
});

const yUnit = computed(() => props.chart.yUnit || inferUnit());

function chartColor(index: number) {
  return palette[index % palette.length];
}

function chartNumber(row: Record<string, string | number | null>, field: string | null | undefined) {
  if (!field) return 0;
  const value = row[field];
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function chartLabel(row: Record<string, string | number | null>) {
  const field = props.chart.nameField || props.chart.xField;
  const value = field ? row[field] : '';
  return value == null ? '' : String(value);
}

function yFor(value: number) {
  const range = frame.bottom - frame.top;
  return frame.bottom - (value / maxValue.value) * range;
}

function xStep() {
  return (frame.right - frame.left) / Math.max(props.chart.data.length, 1);
}

function xFor(index: number) {
  return frame.left + xStep() * index + xStep() / 2;
}

function linePoints(field: string) {
  return props.chart.data.map((row, index) => {
    const x = xFor(index);
    const y = yFor(chartNumber(row, field));
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(' ');
}

function barField() {
  return fields.value[0] || props.chart.valueField || null;
}

function barWidth() {
  return Math.min(64, Math.max(30, xStep() * 0.56));
}

function barX(index: number) {
  return xFor(index) - barWidth() / 2;
}

function barY(row: Record<string, string | number | null>) {
  return yFor(Math.max(chartNumber(row, barField()), 0));
}

function barHeight(row: Record<string, string | number | null>) {
  const value = Math.max(chartNumber(row, barField()), 0);
  if (value <= 0) return 0;
  return Math.max(4, frame.bottom - yFor(value));
}

function tickText(value: number) {
  const formatted = formatNumber(value);
  return yUnit.value ? `${formatted}${yUnit.value}` : formatted;
}

function valueText(value: number) {
  const formatted = formatNumber(value);
  return yUnit.value ? `${formatted}${yUnit.value}` : formatted;
}

function formatNumber(value: number) {
  if (Math.abs(value) >= 10000) return `${(value / 10000).toFixed(1).replace(/\.0$/, '')}万`;
  if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '');
}

function chartTypeText() {
  if (props.chart.type === 'line') return '折线图';
  if (props.chart.type === 'bar') return '柱状图';
  return '占比图';
}

function seriesName(field: string) {
  return props.chart.fieldLabels?.[field] || fallbackFieldLabels[field] || field;
}

function inferUnit() {
  const text = `${props.chart.title} ${props.chart.description} ${fields.value.join(' ')}`;
  if (text.includes('率') || text.includes('占比') || text.includes('比例')) return '%';
  if (text.includes('金额') || text.includes('销售额') || text.includes('采购额')) return '元';
  if (text.includes('销量') || text.includes('需求') || text.includes('补货') || text.includes('库存') || text.includes('缺口')) return '件';
  return '数量';
}

const fallbackFieldLabels: Record<string, string> = {
  usbDemand: 'USB-C扩展坞需求',
  labelDemand: '热敏标签纸需求',
  a4Demand: 'A4复印纸需求',
  forecastQty: '预测销量',
  safetyStockQty: '安全库存',
  gapQty: '安全库存缺口',
  lockQty: '锁定数量',
  releaseQty: '可释放数量',
  onTimeRate: '准时率',
  demandQty: '需求量',
};
</script>

<template>
  <article class="ai-chart-card" data-ai-chart-card>
    <div class="ai-chart-card__head">
      <LineChart v-if="chart.type === 'line'" class="h-4 w-4 text-primary" />
      <BarChart3 v-else-if="chart.type === 'bar'" class="h-4 w-4 text-primary" />
      <PieChart v-else class="h-4 w-4 text-primary" />
      <strong>{{ chart.title }}</strong>
      <Badge variant="outline">{{ chartTypeText() }}</Badge>
      <Button type="button" size="sm" variant="ghost" class="ai-chart-card__zoom" @click="expanded = true">
        <Maximize2 class="h-3.5 w-3.5" />
        放大查看
      </Button>
    </div>
    <p v-if="chart.description" class="ai-chart-card__desc">{{ chart.description }}</p>

    <div v-if="chart.type === 'line' || chart.type === 'bar'" class="ai-chart-card__plot">
      <svg :viewBox="`0 0 ${frame.width} ${frame.height}`" role="img" :aria-label="chart.title">
        <g class="ai-chart-card__grid">
          <line v-for="tick in ticks" :key="`grid-${tick}`" :x1="frame.left" :y1="yFor(tick)" :x2="frame.right" :y2="yFor(tick)" />
          <line :x1="frame.left" :y1="frame.bottom" :x2="frame.right" :y2="frame.bottom" class="ai-chart-card__baseline" />
        </g>
        <g class="ai-chart-card__y-axis">
          <text x="4" y="18" class="ai-chart-card__unit">单位：{{ yUnit }}</text>
          <text v-for="tick in ticks" :key="`tick-${tick}`" x="4" :y="yFor(tick) + 4">{{ tickText(tick) }}</text>
        </g>
        <g v-if="chart.type === 'line'" class="ai-chart-card__line-layer">
          <polyline
            v-for="(field, index) in fields"
            :key="field"
            fill="none"
            stroke-linecap="round"
            stroke-linejoin="round"
            :stroke="chartColor(index)"
            :points="linePoints(field)"
          />
          <g v-for="(field, seriesIndex) in fields" :key="`points-${field}`">
            <circle
              v-for="(row, index) in chart.data"
              :key="`${field}-${chartLabel(row)}-${index}`"
              :cx="xFor(index)"
              :cy="yFor(chartNumber(row, field))"
              r="3.2"
              :fill="chartColor(seriesIndex)"
            />
          </g>
        </g>
        <g v-else class="ai-chart-card__bar-layer">
          <rect
            v-for="(row, index) in chart.data"
            :key="`${chartLabel(row)}-${index}`"
            :x="barX(index)"
            :y="barY(row)"
            :width="barWidth()"
            :height="barHeight(row)"
            rx="3"
            :fill="chartColor(index)"
          />
        </g>
        <g class="ai-chart-card__x-axis">
          <text v-for="(row, index) in chart.data" :key="`${chartLabel(row)}-${index}`" :x="xFor(index)" :y="frame.labelY" text-anchor="middle">
            {{ chartLabel(row) }}
          </text>
        </g>
      </svg>
      <div class="ai-chart-card__legend">
        <span v-for="(field, index) in fields" :key="field">
          <i :style="{ background: chartColor(index) }" />
          {{ seriesName(field) }}
        </span>
      </div>
    </div>

    <div v-else class="ai-chart-card__tiles">
      <span v-for="(row, index) in chart.data" :key="`${chartLabel(row)}-${index}`">
        <strong>{{ valueText(chartNumber(row, chart.valueField)) }}</strong>
        <small>{{ chartLabel(row) }}</small>
      </span>
    </div>
  </article>

  <Dialog v-model:open="expanded">
    <DialogContent
      placement="app-content"
      class="ai-chart-dialog !w-[min(980px,calc(100vw-var(--app-shell-sidebar-width)-2rem))] !max-w-[min(980px,calc(100vw-var(--app-shell-sidebar-width)-2rem))]"
    >
      <DialogHeader>
        <DialogTitle>{{ chart.title }}</DialogTitle>
        <DialogDescription>{{ chart.description || '图表放大查看' }}</DialogDescription>
      </DialogHeader>
      <section class="ai-chart-card ai-chart-card--large" data-ai-chart-expanded>
        <div v-if="chart.type === 'line' || chart.type === 'bar'" class="ai-chart-card__plot">
          <svg :viewBox="`0 0 ${frame.width} ${frame.height}`" role="img" :aria-label="chart.title">
            <g class="ai-chart-card__grid">
              <line v-for="tick in ticks" :key="`large-grid-${tick}`" :x1="frame.left" :y1="yFor(tick)" :x2="frame.right" :y2="yFor(tick)" />
              <line :x1="frame.left" :y1="frame.bottom" :x2="frame.right" :y2="frame.bottom" class="ai-chart-card__baseline" />
            </g>
            <g class="ai-chart-card__y-axis">
              <text x="4" y="18" class="ai-chart-card__unit">单位：{{ yUnit }}</text>
              <text v-for="tick in ticks" :key="`large-tick-${tick}`" x="4" :y="yFor(tick) + 4">{{ tickText(tick) }}</text>
            </g>
            <g v-if="chart.type === 'line'" class="ai-chart-card__line-layer">
              <polyline
                v-for="(field, index) in fields"
                :key="`large-${field}`"
                fill="none"
                stroke-linecap="round"
                stroke-linejoin="round"
                :stroke="chartColor(index)"
                :points="linePoints(field)"
              />
              <g v-for="(field, seriesIndex) in fields" :key="`large-points-${field}`">
                <circle
                  v-for="(row, index) in chart.data"
                  :key="`large-${field}-${chartLabel(row)}-${index}`"
                  :cx="xFor(index)"
                  :cy="yFor(chartNumber(row, field))"
                  r="3.2"
                  :fill="chartColor(seriesIndex)"
                />
              </g>
            </g>
            <g v-else class="ai-chart-card__bar-layer">
              <rect
                v-for="(row, index) in chart.data"
                :key="`large-${chartLabel(row)}-${index}`"
                :x="barX(index)"
                :y="barY(row)"
                :width="barWidth()"
                :height="barHeight(row)"
                rx="3"
                :fill="chartColor(index)"
              />
            </g>
            <g class="ai-chart-card__x-axis">
              <text v-for="(row, index) in chart.data" :key="`large-label-${chartLabel(row)}-${index}`" :x="xFor(index)" :y="frame.labelY" text-anchor="middle">
                {{ chartLabel(row) }}
              </text>
            </g>
          </svg>
          <div class="ai-chart-card__legend">
            <span v-for="(field, index) in fields" :key="`large-legend-${field}`">
              <i :style="{ background: chartColor(index) }" />
              {{ seriesName(field) }}
            </span>
          </div>
        </div>
        <div v-else class="ai-chart-card__tiles">
          <span v-for="(row, index) in chart.data" :key="`large-tile-${chartLabel(row)}-${index}`">
            <strong>{{ valueText(chartNumber(row, chart.valueField)) }}</strong>
            <small>{{ chartLabel(row) }}</small>
          </span>
        </div>
      </section>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
.ai-chart-card {
  display: grid;
  gap: 10px;
  min-width: 0;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 10px;
}

.ai-chart-card__head {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
}

.ai-chart-card__head strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-chart-card__zoom {
  height: 28px;
  gap: 5px;
  padding-inline: 8px;
  font-size: 12px;
}

.ai-chart-card__desc {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-chart-card__plot {
  display: grid;
  gap: 8px;
  min-width: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chart-card__plot svg {
  width: 100%;
  height: 235px;
  overflow: visible;
  background: white;
}

.ai-chart-card--large .ai-chart-card__plot svg {
  height: min(58vh, 460px);
}

.ai-chart-card__grid line {
  stroke: #e5e7eb;
  stroke-width: 1;
}

.ai-chart-card__grid .ai-chart-card__baseline {
  stroke: #94a3b8;
  stroke-width: 1.4;
}

.ai-chart-card__y-axis text,
.ai-chart-card__x-axis text {
  fill: #475467;
  font-size: 11px;
  font-weight: 600;
}

.ai-chart-card__y-axis .ai-chart-card__unit {
  fill: #667085;
  font-size: 10px;
  font-weight: 500;
}

.ai-chart-card__line-layer polyline {
  stroke-width: 3;
}

.ai-chart-card__line-layer circle {
  stroke: white;
  stroke-width: 2;
}

.ai-chart-card__bar-layer rect {
  shape-rendering: geometricPrecision;
}

.ai-chart-card__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  color: #475467;
  font-size: 11px;
}

.ai-chart-card__legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ai-chart-card__legend i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
}

.ai-chart-card__tiles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-chart-card__tiles span {
  display: grid;
  gap: 4px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chart-card__tiles strong {
  color: #172033;
  font-size: 17px;
}

.ai-chart-card__tiles small {
  overflow: hidden;
  color: #667085;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-chart-dialog {
  max-height: calc(100dvh - var(--app-shell-header-height) - 2rem);
  overflow: auto;
}

@media (max-width: 640px) {
  .ai-chart-card__head {
    grid-template-columns: 18px minmax(0, 1fr) auto;
  }

  .ai-chart-card__zoom {
    grid-column: 2 / -1;
    justify-self: start;
  }

  .ai-chart-card__tiles {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
