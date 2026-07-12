import type { AiChartSpec } from '../types';

interface AiChartFrame {
  left: number;
  right: number;
  topPadding: number;
  plotHeight: number;
  valueHeight: number;
}

type AiChartRow = Record<string, string | number | null>;

/** 保持 AI 页面和任务中心使用完全一致的图表几何计算。 */
export function useAiChartGeometry(chartPalette: readonly string[], chartFrame: AiChartFrame) {
  function chartFields(chart: AiChartSpec) {
    if (chart.yFields.length > 0) return chart.yFields;
    return chart.valueField ? [chart.valueField] : [];
  }

  function chartColor(index: number) {
    return chartPalette[index % chartPalette.length];
  }

  function chartNumber(row: AiChartRow, field: string | null | undefined) {
    if (!field) return 0;
    const value = row[field];
    if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function chartLabel(chart: AiChartSpec, row: AiChartRow) {
    const field = chart.nameField || chart.xField;
    const value = field ? row[field] : '';
    return value == null ? '' : String(value);
  }

  function chartMax(chart: AiChartSpec) {
    const fields = chartFields(chart);
    const values = chart.data.flatMap(row => fields.map(field => chartNumber(row, field)));
    return Math.max(...values, 1);
  }

  function chartY(chart: AiChartSpec, value: number) {
    return chartFrame.plotHeight - (value / chartMax(chart)) * chartFrame.valueHeight - chartFrame.topPadding;
  }

  function chartTicks(chart: AiChartSpec) {
    const max = chartMax(chart);
    return [max, max * 0.75, max * 0.5, max * 0.25, 0];
  }

  function chartGridLines(chart: AiChartSpec) {
    return chartTicks(chart).map(value => chartY(chart, value));
  }

  function chartX(chart: AiChartSpec, index: number) {
    const lastIndex = Math.max(chart.data.length - 1, 1);
    return chartFrame.left + ((chartFrame.right - chartFrame.left) * index) / lastIndex;
  }

  function chartAxisLabels(chart: AiChartSpec) {
    const total = chart.data.length;
    if (total === 0) return [];
    const labelCount = total <= 7 ? total : total <= 15 ? 8 : 15;
    const lastLabelIndex = Math.max(labelCount - 1, 1);
    return Array.from({ length: labelCount }, (_, index) => {
      const pointIndex = Math.round(((total - 1) * index) / lastLabelIndex);
      const row = chart.data[pointIndex];
      return {
        key: `${chartLabel(chart, row)}-${index}`,
        label: chartLabel(chart, row),
        x: chartFrame.left + ((chartFrame.right - chartFrame.left) * index) / lastLabelIndex,
      };
    });
  }

  function chartLinePoints(chart: AiChartSpec, field: string) {
    return chart.data.map((row, index) => {
      const x = chartX(chart, index);
      const y = chartY(chart, chartNumber(row, field));
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(' ');
  }

  function chartBarField(chart: AiChartSpec) {
    return chartFields(chart)[0] || chart.valueField || null;
  }

  function chartBarWidth(chart: AiChartSpec) {
    const count = Math.max(chart.data.length, 1);
    const step = (chartFrame.right - chartFrame.left) / count;
    return Math.min(58, Math.max(28, step * 0.5));
  }

  function chartBarX(chart: AiChartSpec, index: number) {
    const count = Math.max(chart.data.length, 1);
    const step = (chartFrame.right - chartFrame.left) / count;
    return chartFrame.left + index * step + (step - chartBarWidth(chart)) / 2;
  }

  function chartBarHeight(chart: AiChartSpec, row: AiChartRow) {
    const value = chartNumber(row, chartBarField(chart));
    if (value <= 0) return 0;
    return Math.max(4, chartFrame.plotHeight - chartY(chart, value));
  }

  function chartBarY(chart: AiChartSpec, row: AiChartRow) {
    return chartFrame.plotHeight - chartBarHeight(chart, row);
  }

  return {
    chartFields,
    chartColor,
    chartNumber,
    chartLabel,
    chartMax,
    chartY,
    chartTicks,
    chartGridLines,
    chartX,
    chartAxisLabels,
    chartLinePoints,
    chartBarField,
    chartBarWidth,
    chartBarX,
    chartBarHeight,
    chartBarY,
  };
}
