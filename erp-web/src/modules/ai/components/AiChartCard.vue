<script setup lang="ts">
import { ref } from 'vue';
import { BarChart3, LineChart, Maximize2, PieChart } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import AiChartPlot from './AiChartPlot.vue';
import type { AiChartSpec } from '../types';

const props = defineProps<{
  chart: AiChartSpec;
}>();

const expanded = ref(false);
</script>

<template>
  <article class="ai-chart-card" data-ai-chart-card>
    <div class="ai-chart-card__head">
      <LineChart v-if="chart.type === 'line'" class="h-4 w-4 text-primary" />
      <BarChart3
        v-else-if="chart.type === 'bar' || chart.type === 'horizontalBar' || chart.type === 'stackedBar'"
        class="h-4 w-4 text-primary"
      />
      <PieChart v-else class="h-4 w-4 text-primary" />
      <strong>{{ chart.title }}</strong>
      <Button type="button" size="sm" variant="ghost" class="ai-chart-card__zoom" @click="expanded = true">
        <Maximize2 class="h-3.5 w-3.5" />
        放大查看
      </Button>
    </div>
    <p v-if="chart.description" class="ai-chart-card__desc">{{ chart.description }}</p>

    <AiChartPlot :chart="chart" />

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
          <AiChartPlot :chart="chart" large />
        </section>
      </DialogContent>
    </Dialog>
  </article>
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
  grid-template-columns: 18px minmax(0, 1fr) auto;
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
  height: 24px;
  gap: 4px;
  padding: 0 4px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  box-shadow: none;
  color: #667085;
  font-size: 12px;
  font-weight: 500;
}

.ai-chart-card__zoom:hover {
  background: color-mix(in srgb, var(--primary) 7%, transparent);
  color: var(--primary);
}

.ai-chart-card__zoom svg {
  width: 14px;
  height: 14px;
}

.ai-chart-card__desc {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
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
}
</style>