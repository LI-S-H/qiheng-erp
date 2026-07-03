<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { CollapsibleContent, CollapsibleRoot } from 'reka-ui';
import { toast } from 'vue-sonner';
import {
  Bot,
  CalendarDays,
  ChevronDown,
  ChevronRight,
  ClipboardCheck,
  Database,
  Edit3,
  FileText,
  History,
  LineChart,
  PackageCheck,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  Save,
  Send,
  ShoppingCart,
  Sparkles,
  Trash2,
  UserRound,
} from 'lucide-vue-next';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import RemoteSearchSelect, { type RemoteSearchOption } from '@/components/common/RemoteSearchSelect.vue';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Textarea } from '@/components/ui/textarea';
import { createPurchaseOrder, listEnabledWarehouseOptions, searchSupplierOptions } from '@/modules/purchase/api';
import type { PurchaseOrderFormPayload } from '@/modules/purchase/types';
import { listProducts } from '@/modules/product/products/api';
import { createStockBill } from '@/modules/warehouse/stock-bills/api';
import type { StockBillCreatePayload } from '@/modules/warehouse/stock-bills/types';
import { listWarehouses } from '@/modules/warehouse/warehouses/api';
import AiChartCard from '../components/AiChartCard.vue';
import AiFadeSwitch from '../components/AiFadeSwitch.vue';
import {
  createAiConversation,
  deleteAiConversation,
  getAiAssistantOverview,
  sendAiAssistantMessage,
  updateAiConversation,
} from '../api';
import type { AiActionCard, AiAssistantOverview, AiChartSpec, AiChatMessage, AiContextSource, AiPromptField, AiQuickPrompt, AiTaskCard } from '../types';

interface BusinessPromptAction {
  label: string;
  description: string;
  route: string | null;
  previewTitle: string;
  steps: string[];
}

interface WorkbenchLine {
  lineId: string;
  productId: string;
  productName: string;
  supplierProductId?: string | null;
  unitPrice?: number;
  selectedSupplierScore?: number;
  warehouseId: string;
  warehouseName: string;
  targetWarehouseId?: string;
  targetWarehouseName?: string;
  suggestedQty: string;
  supplierId?: string;
  supplierName?: string;
  sourceNo?: string;
  reason: string;
}

interface AssistantWorkbench {
  workbenchId: string;
  title: string;
  description: string;
  workbenchType: 'PURCHASE_DRAFT' | 'TRANSFER_DRAFT' | 'LOCK_RELEASE' | 'STOCK_FILTER';
  status: '待确认' | '已修改' | '草稿已创建';
  route: string | null;
  generatedNos: string[];
  lines: WorkbenchLine[];
  sections: Array<{
    title: string;
    items: string[];
  }>;
}

const router = useRouter();
const loading = ref(false);
const transitionLoading = ref(false);
const sending = ref(false);
const overview = ref<AiAssistantOverview | null>(null);
const inputMessage = ref('');
const conversationId = ref<string | null>('conv-today');
const sidebarCollapsed = ref(false);
const conversationListExpanded = ref(false);
const conversationListCollapsed = ref(false);
const selectedConversationDate = ref<string | null>(null);
const promptListExpanded = ref(true);
const switchingConversationId = ref<string | null>(null);
const promptDialogOpen = ref(false);
const selectedPrompt = ref<AiQuickPrompt | null>(null);
const renameDialogOpen = ref(false);
const renamingConversationId = ref<string | null>(null);
const renameTitle = ref('');
const productPickerValue = ref('');
const warehousePickerValue = ref('');
const productPickerKey = ref(0);
const warehousePickerKey = ref(0);
const actionPreviewOpen = ref(false);
const actionPreviewTitle = ref('');
const actionPreviewDescription = ref('');
const actionPreviewRoute = ref<string | null>(null);
const actionPreviewSteps = ref<string[]>([]);
const activeWorkbench = ref<AssistantWorkbench | null>(null);
const workbenchCollapsed = ref(false);
const workbenchWidth = ref(360);
const resizingWorkbench = ref(false);
const workbenchSubmitting = ref(false);
let conversationTransitionTimer: number | null = null;

const chartPalette = ['#2563eb', '#059669', '#d97706', '#7c3aed'];
const chartFrame = {
  width: 700,
  height: 260,
  left: 34,
  right: 622,
  topPadding: 18,
  plotHeight: 210,
  valueHeight: 168,
  labelY: 238,
  tickX: 638,
} as const;

const purchaseDemoCharts: AiChartSpec[] = [
  {
    chartId: 'demo-purchase-demand',
    type: 'line',
    title: '补货商品近 7 日需求趋势',
    description: '这类图由后端返回字段映射和数据行，前端统一渲染样式。',
    xField: 'date',
    yFields: ['usbDemand', 'labelDemand'],
    fieldLabels: { usbDemand: 'USB-C扩展坞需求', labelDemand: '热敏标签纸需求' },
    yUnit: '件',
    nameField: null,
    valueField: null,
    data: [
      { date: '06-25', usbDemand: 4, labelDemand: 18 },
      { date: '06-26', usbDemand: 5, labelDemand: 21 },
      { date: '06-27', usbDemand: 7, labelDemand: 20 },
      { date: '06-28', usbDemand: 8, labelDemand: 24 },
      { date: '06-29', usbDemand: 10, labelDemand: 28 },
      { date: '06-30', usbDemand: 12, labelDemand: 31 },
      { date: '07-01', usbDemand: 14, labelDemand: 35 },
    ],
  },
  {
    chartId: 'demo-purchase-gap',
    type: 'bar',
    title: '安全库存缺口',
    description: '缺口越高，越适合进入右侧采购草稿工作框复核。',
    xField: 'productName',
    yFields: ['gapQty'],
    fieldLabels: { gapQty: '安全库存缺口' },
    yUnit: '件',
    nameField: null,
    valueField: null,
    data: [
      { productName: 'USB-C扩展坞', gapQty: 8 },
      { productName: '热敏标签纸', gapQty: 40 },
      { productName: 'A4复印纸', gapQty: 78 },
    ],
  },
];

const daysOptions = [
  { value: '7', label: '7 天' },
  { value: '14', label: '14 天' },
  { value: '30', label: '30 天' },
  { value: '60', label: '60 天' },
];

const promptActionMap: Record<string, BusinessPromptAction> = {
  risk: {
    label: '库存筛选结果',
    description: '完成分析后跳到库存余额并带着风险范围复核',
    route: '/warehouse/stocks',
    previewTitle: '库存风险处理预览',
    steps: ['确认本次巡检的商品和仓库范围', '打开库存余额列表核对可用库存、锁定库存和安全库存', '人工筛选后再决定补货、调拨或释放锁定库存'],
  },
  purchase: {
    label: '采购草稿预览',
    description: '先生成采购建议卡片，再由用户确认草稿',
    route: '/purchase/orders',
    previewTitle: '采购建议确认预览',
    steps: ['核对销量预测、库存缺口和采购在途', '预览建议补货商品、数量和供应商', '生成采购草稿，人工确认后再提交正式采购订单'],
  },
  transfer: {
    label: '调拨草稿工作框',
    description: '基于销量预测和库存缺口生成可修改调拨明细',
    route: '/warehouse/outbound-bills',
    previewTitle: '调拨建议确认预览',
    steps: ['读取已完成的销量预测、库存缺口和跨仓可用库存', '预览调出仓、调入仓、调拨数量和原因', '后端提供调拨单接口后生成正式调拨草稿'],
  },
  release: {
    label: '锁定释放工作框',
    description: '把可释放锁定库存整理成待复核清单',
    route: '/warehouse/stocks',
    previewTitle: '锁定库存释放预览',
    steps: ['核对销售订单锁定来源和未出库状态', '修改释放数量和复核原因', '人工确认后再进入库存或销售单据处理'],
  },
  sales: {
    label: '预测报告卡片',
    description: '沉淀为一张可跟进的销量预测报告',
    route: '/ai/tasks',
    previewTitle: '销量预测报告预览',
    steps: ['确认预测商品、仓库和时间窗口', '生成趋势结论、缺货影响和风险商品', '归档报告卡片，后续在任务中心跟踪变化'],
  },
};

const promptForm = reactive<Record<string, string | string[]>>({});
const messagesByConversation = reactive<Record<string, AiChatMessage[]>>({
  'conv-today': [buildWelcomeMessage('welcome')],
  'conv-replenish': buildPurchaseDemoMessages(),
  'conv-inventory': buildTransferDemoMessages(),
  'conv-supplier': buildSupplierDemoMessages(),
});

const activeConversation = computed(() => overview.value?.conversations.find(item => item.conversationId === conversationId.value) || overview.value?.conversations[0]);
const conversationDateGroups = computed(() => {
  const groups = new Map<string, number>();
  (overview.value?.conversations || []).forEach(conversation => {
    const date = conversation.updatedAt.slice(0, 10);
    groups.set(date, (groups.get(date) || 0) + 1);
  });
  return Array.from(groups.entries())
    .sort(([left], [right]) => right.localeCompare(left))
    .map(([date, count]) => ({ date, count }));
});
const selectedConversationDateLabel = computed(() => selectedConversationDate.value || '全部日期');
const filteredConversations = computed(() => {
  const conversations = overview.value?.conversations || [];
  if (!selectedConversationDate.value) return conversations;
  return conversations.filter(item => item.updatedAt.startsWith(selectedConversationDate.value || ''));
});
const pinnedConversations = computed(() => filteredConversations.value.slice(0, 5));
const extraConversations = computed(() => filteredConversations.value.slice(5));
const hiddenConversationCount = computed(() => extraConversations.value.length);
const currentMessages = computed(() => {
  const id = conversationId.value || 'conv-today';
  return messagesByConversation[id] || [];
});
const isWelcomeStage = computed(() => currentMessages.value.length <= 1 && !sending.value);
const selectedPromptAction = computed(() => selectedPrompt.value ? promptActionMap[selectedPrompt.value.promptId] : null);

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function chartFields(chart: AiChartSpec) {
  if (chart.yFields.length > 0) return chart.yFields;
  return chart.valueField ? [chart.valueField] : [];
}

function chartColor(index: number) {
  return chartPalette[index % chartPalette.length];
}

function chartNumber(row: Record<string, string | number | null>, field: string | null | undefined) {
  if (!field) return 0;
  const value = row[field];
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function chartLabel(chart: AiChartSpec, row: Record<string, string | number | null>) {
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

function formatChartTick(value: number) {
  if (Math.abs(value) >= 10000) return `${(value / 10000).toFixed(1).replace(/\.0$/, '')}万`;
  if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '');
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

function chartBarHeight(chart: AiChartSpec, row: Record<string, string | number | null>) {
  const value = chartNumber(row, chartBarField(chart));
  if (value <= 0) return 0;
  return Math.max(4, chartFrame.plotHeight - chartY(chart, value));
}

function chartBarY(chart: AiChartSpec, row: Record<string, string | number | null>) {
  return chartFrame.plotHeight - chartBarHeight(chart, row);
}

function buildWelcomeMessage(messageId: string): AiChatMessage {
  return {
    messageId,
    role: 'assistant',
    createdAt: '2026-07-01 10:18:00',
    content: '我是智能经营助手。你可以直接描述经营目标，也可以从左侧快捷分析开始，我会把问题拆给合适的智能体协同处理。',
    charts: [],
    actionCards: [],
    sources: [],
    agentTraces: [],
    taskCard: null,
  };
}

function buildPurchaseDemoMessages(): AiChatMessage[] {
  return [
    {
      messageId: 'demo-purchase-user',
      role: 'user',
      createdAt: '2026-07-01 09:42:00',
      content: '帮我看一下 USB-C扩展坞、热敏标签纸和 A4复印纸今天要不要补货。',
      charts: [],
      actionCards: [],
      sources: [],
      agentTraces: [],
      taskCard: null,
    },
    {
      messageId: 'demo-purchase-assistant',
      role: 'assistant',
      createdAt: '2026-07-01 09:42:18',
      content: '已结合销售订单、库存余额、采购在途和供应商履约评分完成补货分析。USB-C扩展坞可用库存为 0，建议先生成 8 个补货草稿；热敏标签纸安全库存缺口 40 卷，建议优先华南中心仓；A4复印纸只补华南中心仓安全库存，华东中心仓暂不补货。右侧工作框已经把草稿明细列出来，你可以直接修改数量、供应商和原因，再生成草稿。',
      charts: purchaseDemoCharts,
      actionCards: [
        { actionId: 'demo-open-purchase', title: '预览采购草稿', description: '在右侧工作框复核补货明细', actionType: 'PREVIEW', route: null, riskLevel: 'MEDIUM' },
        { actionId: 'demo-open-stock', title: '查看库存余额', description: '跳转库存余额核对可用库存', actionType: 'NAVIGATE', route: '/warehouse/stocks', riskLevel: 'LOW' },
      ],
      sources: [
        { sourceId: 'demo-sales', sourceType: 'TOOL', title: '销售订单', description: '近 7 日商品需求和未出库订单', freshness: '今日 10:00' },
        { sourceId: 'demo-stock', sourceType: 'TOOL', title: '库存余额', description: '可用库存、安全库存和锁定占用', freshness: '实时查询' },
        { sourceId: 'demo-purchase', sourceType: 'TOOL', title: '采购在途', description: '未完成采购订单和供应商交期', freshness: '今日 10:00' },
      ],
      agentTraces: [
        { traceId: 'demo-router', agentCode: 'chief-router-agent', agentName: '主控智能体', summary: '识别为补货建议任务，调度库存、销量预测和供应商评估。', status: 'DONE' },
        { traceId: 'demo-purchase-agent', agentCode: 'purchase-advice-agent', agentName: '采购建议智能体', summary: '将库存缺口、在途采购和供应商评分合并为草稿建议。', status: 'DONE' },
      ],
      taskCard: null,
    },
  ];
}

function buildTransferDemoMessages(): AiChatMessage[] {
  return [
    {
      messageId: 'demo-transfer-user',
      role: 'user',
      createdAt: '2026-06-30 16:40:00',
      content: '华南中心仓标签纸缺口比较大，帮我看下能不能从其他仓调拨。',
      charts: [],
      actionCards: [],
      sources: [],
      agentTraces: [],
      taskCard: null,
    },
    {
      messageId: 'demo-transfer-assistant',
      role: 'assistant',
      createdAt: '2026-06-30 16:40:16',
      content: '已检查库存余额、已完成销量预测结果和锁定占用。热敏标签纸建议从华东中心仓调拨 24 卷到华南中心仓；A4复印纸建议从南京备货仓调拨 18 箱到华南中心仓。右侧已生成调拨草稿工作框，你可以修改调出仓、调入仓和调拨数量。',
      charts: [
        {
          chartId: 'demo-transfer-gap',
          type: 'bar',
          title: '跨仓安全库存缺口',
          description: '用于判断是否优先调拨，而不是直接采购。',
          xField: 'productName',
          yFields: ['gapQty'],
          fieldLabels: { gapQty: '安全库存缺口' },
          yUnit: '件',
          nameField: null,
          valueField: null,
          data: [
            { productName: '热敏标签纸', gapQty: 24 },
            { productName: 'A4复印纸', gapQty: 18 },
            { productName: '中性签字笔', gapQty: 12 },
          ],
        },
      ],
      actionCards: [
        { actionId: 'demo-transfer-preview', title: '预览调拨草稿', description: '在右侧工作框复核调拨明细', actionType: 'PREVIEW', route: null, riskLevel: 'MEDIUM' },
        { actionId: 'demo-transfer-stock', title: '查看库存余额', description: '跳转库存余额核对跨仓可用库存', actionType: 'NAVIGATE', route: '/warehouse/stocks', riskLevel: 'LOW' },
      ],
      sources: [
        { sourceId: 'demo-transfer-stock', sourceType: 'TOOL', title: '库存余额', description: '跨仓可用库存、安全库存和锁定占用', freshness: '实时查询' },
        { sourceId: 'demo-transfer-sales', sourceType: 'TOOL', title: '销售订单', description: '近 7 日未出库需求', freshness: '今日 10:00' },
      ],
      agentTraces: [
        { traceId: 'demo-transfer-router', agentCode: 'chief-router-agent', agentName: '主控智能体', summary: '识别为跨仓调拨任务，调度库存分析智能体。', status: 'DONE' },
        { traceId: 'demo-transfer-stock-agent', agentCode: 'inventory-analysis-agent', agentName: '库存分析智能体', summary: '按仓库可用库存和安全库存缺口生成调拨建议。', status: 'DONE' },
      ],
      taskCard: null,
    },
  ];
}

function buildLockReleaseDemoMessages(): AiChatMessage[] {
  return [
    {
      messageId: 'demo-release-user',
      role: 'user',
      createdAt: '2026-06-29 15:12:00',
      content: '帮我找一下哪些锁定库存可以释放。',
      charts: [],
      actionCards: [],
      sources: [],
      agentTraces: [],
      taskCard: null,
    },
    {
      messageId: 'demo-release-assistant',
      role: 'assistant',
      createdAt: '2026-06-29 15:12:20',
      content: '已检查销售订单锁定、出库进度和库存余额。发现两条销售单锁定超过 48 小时且未进入出库确认，建议先复核后释放部分锁定库存。右侧已生成锁定释放工作框。',
      charts: [
        {
          chartId: 'demo-release-lock',
          type: 'bar',
          title: '可复核锁定数量',
          description: '按来源单据展示可复核数量，便于逐单判断是否释放。',
          xField: 'sourceNo',
          yFields: ['releaseQty'],
          fieldLabels: { releaseQty: '可复核锁定数量' },
          yUnit: '件',
          nameField: null,
          valueField: null,
          data: [
            { sourceNo: 'SO012', releaseQty: 12 },
            { sourceNo: 'SO018', releaseQty: 30 },
            { sourceNo: 'SO021', releaseQty: 8 },
          ],
        },
      ],
      actionCards: [
        { actionId: 'demo-release-preview', title: '预览释放清单', description: '在右侧工作框复核锁定来源', actionType: 'PREVIEW', route: null, riskLevel: 'MEDIUM' },
        { actionId: 'demo-release-stock', title: '查看库存余额', description: '跳转库存余额核对锁定库存', actionType: 'NAVIGATE', route: '/warehouse/stocks', riskLevel: 'LOW' },
      ],
      sources: [
        { sourceId: 'demo-release-sales', sourceType: 'TOOL', title: '销售订单', description: '锁定来源、审核状态和出库进度', freshness: '今日 10:00' },
        { sourceId: 'demo-release-stock', sourceType: 'TOOL', title: '库存余额', description: '锁定库存和可用库存', freshness: '实时查询' },
      ],
      agentTraces: [
        { traceId: 'demo-release-agent', agentCode: 'inventory-analysis-agent', agentName: '库存分析智能体', summary: '识别长时间未出库锁定库存，生成释放复核清单。', status: 'DONE' },
      ],
      taskCard: null,
    },
  ];
}

function buildSupplierDemoMessages(): AiChatMessage[] {
  return [
    {
      messageId: 'demo-supplier-user',
      role: 'user',
      createdAt: '2026-06-29 15:12:00',
      content: '帮我复盘一下近期供应商履约有没有异常。',
      charts: [],
      actionCards: [],
      sources: [],
      agentTraces: [],
      taskCard: null,
    },
    {
      messageId: 'demo-supplier-assistant',
      role: 'assistant',
      createdAt: '2026-06-29 15:12:20',
      content: '已按近 30 天供货记录、到货及时率和异常反馈完成履约复盘。森纸纸业集团准时率 97.4%，纸品供货仍可优先选择；拓联数码配件近两周准时率从 92.1% 降至 84.6%，建议下单前先复核延期原因和备选供方。',
      charts: [
        {
          chartId: 'demo-supplier-performance',
          type: 'bar',
          title: '供应商准时率对比',
          description: '按近 30 天到货记录计算，值越低越需要复核履约风险。',
          xField: 'supplierName',
          yFields: ['onTimeRate'],
          fieldLabels: { onTimeRate: '准时率' },
          yUnit: '%',
          nameField: null,
          valueField: null,
          data: [
            { supplierName: '森纸纸业', onTimeRate: 97.4 },
            { supplierName: '华东饮品', onTimeRate: 93.8 },
            { supplierName: '拓联数码', onTimeRate: 84.6 },
          ],
        },
      ],
      actionCards: [
        { actionId: 'demo-supplier-review', title: '查看供应商资料', description: '复核履约、价格和异常记录', actionType: 'NAVIGATE', route: '/purchase/suppliers', riskLevel: 'LOW' },
      ],
      sources: [
        { sourceId: 'demo-supplier-orders', sourceType: 'TOOL', title: '采购在途', description: '到货日期、逾期记录和供应商供货关系', freshness: '今日 10:00' },
        { sourceId: 'demo-supplier-feedback', sourceType: 'TOOL', title: '履约反馈', description: '延期、缺量和质量异常记录', freshness: '今日 10:00' },
      ],
      agentTraces: [
        { traceId: 'demo-supplier-agent', agentCode: 'supplier-evaluation-agent', agentName: '供应商评估智能体', summary: '按准时率、延期次数和异常反馈识别履约风险。', status: 'DONE' },
      ],
      taskCard: null,
    },
  ];
}

function ensureConversationMessages(id: string) {
  if (messagesByConversation[id]) return;
  messagesByConversation[id] = [{
    ...buildWelcomeMessage(`welcome-${id}`),
    createdAt: nowText(),
    content: '新的经营会话已准备好。你可以输入问题，或选择一个快捷分析填写参数后直接发起分析。',
  }];
}

function syncWorkbenchFromConversation(id: string) {
  const messages = messagesByConversation[id] || [];
  const assistantMessage = [...messages].reverse().find(message => message.role === 'assistant');
  activeWorkbench.value = assistantMessage ? buildWorkbenchFromMessage(assistantMessage, assistantMessage.taskCard) : null;
}

function clearConversationTransitionTimer() {
  if (conversationTransitionTimer !== null) {
    window.clearTimeout(conversationTransitionTimer);
    conversationTransitionTimer = null;
  }
}

function playConversationTransition(targetId: string) {
  clearConversationTransitionTimer();
  switchingConversationId.value = targetId;
  transitionLoading.value = true;
  conversationTransitionTimer = window.setTimeout(() => {
    if (switchingConversationId.value === targetId) {
      switchingConversationId.value = null;
      transitionLoading.value = false;
    }
    conversationTransitionTimer = null;
  }, 280);
}

function switchConversation(id: string) {
  conversationId.value = id;
  ensureConversationMessages(id);
  syncWorkbenchFromConversation(id);
  playConversationTransition(id);
}

async function loadOverview() {
  loading.value = true;
  try {
    overview.value = await getAiAssistantOverview();
    const firstId = overview.value.conversations[0]?.conversationId || 'conv-today';
    conversationId.value = firstId;
    ensureConversationMessages(firstId);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '智能经营助手概览加载失败');
  } finally {
    loading.value = false;
  }
}

async function selectConversation(id: string) {
  if (id === conversationId.value) return;
  switchConversation(id);
}

async function createConversation() {
  if (loading.value) return;
  loading.value = true;
  try {
    const conversation = await createAiConversation();
    if (overview.value) overview.value.conversations = [conversation, ...overview.value.conversations];
    switchConversation(conversation.conversationId);
    toast.success('新会话已创建');
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '新建会话失败');
  } finally {
    loading.value = false;
  }
}

function openRename(conversationIdValue: string, currentTitle: string) {
  renamingConversationId.value = conversationIdValue;
  renameTitle.value = currentTitle;
  renameDialogOpen.value = true;
}

async function saveRename() {
  const id = renamingConversationId.value;
  const title = renameTitle.value.trim();
  if (!id || !title) {
    toast.warning('请填写会话名称');
    return;
  }
  try {
    const updated = await updateAiConversation(id, { title });
    if (overview.value) {
      overview.value.conversations = overview.value.conversations.map(item => item.conversationId === id ? updated : item);
    }
    renameDialogOpen.value = false;
    toast.success('会话名称已更新');
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '会话改名失败');
  }
}

async function removeConversation(id: string) {
  const conversation = overview.value?.conversations.find(item => item.conversationId === id);
  if (!conversation || !window.confirm(`确认删除会话「${conversation.title}」吗？`)) return;
  try {
    await deleteAiConversation(id);
    if (overview.value) overview.value.conversations = overview.value.conversations.filter(item => item.conversationId !== id);
    delete messagesByConversation[id];
    const next = overview.value?.conversations[0]?.conversationId || null;
    conversationId.value = next;
    if (next) ensureConversationMessages(next);
    toast.success('会话已删除');
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '删除会话失败');
  }
}

function openPrompt(prompt: AiQuickPrompt) {
  selectedPrompt.value = prompt;
  productPickerValue.value = '';
  warehousePickerValue.value = '';
  productPickerKey.value += 1;
  warehousePickerKey.value += 1;
  Object.keys(promptForm).forEach(key => delete promptForm[key]);
  prompt.fields.forEach(field => {
    promptForm[field.fieldKey] = Array.isArray(field.defaultValue) ? [...field.defaultValue] : String(field.defaultValue || '');
  });
  promptDialogOpen.value = true;
}

function sourceTypeText(source: AiContextSource) {
  if (source.sourceType === 'WORKFLOW') return '工作流';
  if (source.sourceType === 'KNOWLEDGE') return '知识库';
  if (source.sourceType === 'TASK') return '任务结果';
  return '业务数据';
}

function productKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { productCode: value } : { productName: value };
}

function warehouseKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { warehouseCode: value } : { warehouseName: value };
}

async function fetchProductOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const page = await listProducts({
    status: 1,
    pageNum: 1,
    pageSize: 10,
    ...productKeywordQuery(keyword),
  });
  return page.records.map(item => ({
    value: `${item.productCode} ${item.productName}`,
    label: `${item.productCode} ${item.productName}（${item.unitName}）`,
  }));
}

async function fetchWarehouseOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const page = await listWarehouses({
    status: 1,
    pageNum: 1,
    pageSize: 10,
    ...warehouseKeywordQuery(keyword),
  });
  return page.records.map(item => ({
    value: `${item.warehouseCode} ${item.warehouseName}`,
    label: `${item.warehouseCode} ${item.warehouseName}`,
  }));
}

async function fetchWorkbenchSupplierOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const suppliers = await searchSupplierOptions(keyword, 10);
  return suppliers.map(item => ({
    value: item.supplierId,
    label: `${item.supplierCode} ${item.supplierName}`,
    disabled: item.status === 0,
  }));
}

async function fetchWorkbenchWarehouseOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const warehouses = await listEnabledWarehouseOptions(keyword, 10);
  return warehouses.map(item => ({
    value: item.value,
    label: item.label,
  }));
}

function fieldArrayValue(field: AiPromptField) {
  const value = promptForm[field.fieldKey];
  return Array.isArray(value) ? value : [];
}

function addUniqueFieldValue(field: AiPromptField, value: string) {
  const normalized = value.trim();
  if (!normalized) return;
  const current = fieldArrayValue(field);
  if (current.includes(normalized)) return;
  const allValue = field.fieldType === 'PRODUCT_MULTI' ? '全部商品' : '全部仓库';
  promptForm[field.fieldKey] = normalized === allValue ? [allValue] : [...current.filter(item => item !== allValue), normalized];
}

function addPromptFieldOption(field: AiPromptField, option: RemoteSearchOption) {
  addUniqueFieldValue(field, option.label);
  if (field.fieldType === 'PRODUCT_MULTI') {
    productPickerValue.value = '';
    productPickerKey.value += 1;
  } else {
    warehousePickerValue.value = '';
    warehousePickerKey.value += 1;
  }
}

function addPromptFieldAll(field: AiPromptField) {
  promptForm[field.fieldKey] = [field.fieldType === 'PRODUCT_MULTI' ? '全部商品' : '全部仓库'];
  if (field.fieldType === 'PRODUCT_MULTI') productPickerKey.value += 1;
  if (field.fieldType === 'WAREHOUSE_MULTI') warehousePickerKey.value += 1;
}

function removePromptFieldValue(field: AiPromptField, value: string) {
  promptForm[field.fieldKey] = fieldArrayValue(field).filter(item => item !== value);
}

function fieldDisplayValue(field: AiPromptField) {
  const value = promptForm[field.fieldKey];
  return Array.isArray(value) ? value.join('、') : String(value || '');
}

function buildPromptMessage(prompt: AiQuickPrompt) {
  return prompt.fields.reduce((message, field) => {
    return message.split(`{{${field.fieldKey}}}`).join(fieldDisplayValue(field) || '未指定');
  }, prompt.promptTemplate);
}

function buildPromptTaskCard(prompt: AiQuickPrompt): AiTaskCard {
  return {
    title: prompt.title,
    description: prompt.description,
    parameters: prompt.fields.map(field => ({
      label: field.label,
      value: fieldDisplayValue(field) || '未指定',
    })),
  };
}

function buildWorkbenchFromMessage(message: AiChatMessage, taskCard: AiTaskCard | null): AssistantWorkbench | null {
  const taskTitle = taskCard?.title || '';
  const content = `${taskTitle} ${message.content}`;
  if (content.includes('调拨')) {
    return {
      workbenchId: `workbench-transfer-${Date.now()}`,
      title: '调拨草稿工作框',
      description: '来自 AI 库存分析的跨仓调拨建议。当前先生成调出/调入库存草稿，独立调拨单接口接入后可切换为正式调拨草稿。',
      workbenchType: 'TRANSFER_DRAFT',
      status: '待确认',
      route: '/warehouse/outbound-bills',
      generatedNos: [],
      lines: [
        { lineId: 'transfer-label', productId: '1920000000000000008', productName: '热敏标签纸', warehouseId: '1930000000000000001', warehouseName: 'WH001 华东中心仓', targetWarehouseId: '1930000000000000002', targetWarehouseName: 'WH002 华南中心仓', suggestedQty: '24', reason: '华南中心仓低于安全库存，华东中心仓可用库存充足' },
        { lineId: 'transfer-a4', productId: '1920000000000000007', productName: 'A4复印纸', warehouseId: '1930000000000000008', warehouseName: 'WH008 南京备货仓', targetWarehouseId: '1930000000000000002', targetWarehouseName: 'WH002 华南中心仓', suggestedQty: '18', reason: '已完成销量预测显示华南中心仓存在缺口，南京备货仓周转偏慢' },
      ],
      sections: [
        { title: '调拨条件', items: ['调出仓必须有足够可用库存', '调入仓低于安全库存或未来销量预测存在缺口'] },
        { title: '接口状态', items: ['当前先生成调出/调入库存草稿', '独立调拨单接口接入后可直接创建正式调拨草稿'] },
      ],
    };
  }
  if (content.includes('释放') || content.includes('锁定')) {
    return {
      workbenchId: `workbench-release-${Date.now()}`,
      title: '锁定库存释放工作框',
      description: '来自 AI 库存巡检的释放建议。先复核来源单据，再决定是否进入库存余额或销售订单处理。',
      workbenchType: 'LOCK_RELEASE',
      status: '待确认',
      route: '/warehouse/stocks',
      generatedNos: [],
      lines: [
        { lineId: 'release-so-001', productId: '1920000000000000007', productName: 'A4复印纸', warehouseId: '1930000000000000002', warehouseName: 'WH002 华南中心仓', suggestedQty: '12', sourceNo: 'SO202606260012', reason: '销售单审核后 72 小时未进入出库确认，可先复核客户交期' },
        { lineId: 'release-so-002', productId: '1920000000000000005', productName: '中性签字笔', warehouseId: '1930000000000000001', warehouseName: 'WH001 华东中心仓', suggestedQty: '30', sourceNo: 'SO202606270018', reason: '客户改期且锁定库存占用安全库存，建议释放或调整交期' },
      ],
      sections: [
        { title: '处理边界', items: ['AI 只标记可释放候选，不直接改库存', '正式释放仍需人工复核销售订单和库存余额'] },
      ],
    };
  }
  if (content.includes('补货') || content.includes('采购')) {
    return {
      workbenchId: `workbench-purchase-${Date.now()}`,
      title: '采购草稿工作框',
      description: '来自 AI 采购建议的草稿预览，可先修改数量、供应商和原因，再进入采购订单生成正式草稿。',
      workbenchType: 'PURCHASE_DRAFT',
      status: '待确认',
      route: '/purchase/orders',
      generatedNos: [],
      lines: [
        { lineId: 'line-a4', productId: '1920000000000000007', productName: 'A4复印纸', supplierProductId: '1941000000000000006', unitPrice: 89.4, selectedSupplierScore: 94.8, warehouseId: '1930000000000000002', warehouseName: 'WH002 华南中心仓', suggestedQty: '78', supplierId: '1940000000000000005', supplierName: 'S005 森纸纸业集团', reason: '补足安全库存，华东中心仓暂不补货' },
        { lineId: 'line-pen', productId: '1920000000000000005', productName: '中性签字笔', supplierProductId: '1941000000000000005', unitPrice: 13.8, selectedSupplierScore: 87.2, warehouseId: '1930000000000000001', warehouseName: 'WH001 华东中心仓', suggestedQty: '30', supplierId: '1940000000000000004', supplierName: 'S004 文仪办公渠道', reason: '办公用品订单上升，建议补足最小起订量' },
        { lineId: 'line-coffee', productId: '1920000000000000002', productName: '速溶黑咖啡', supplierProductId: '1941000000000000002', unitPrice: 40.5, selectedSupplierScore: 88.9, warehouseId: '1930000000000000001', warehouseName: 'WH001 华东中心仓', suggestedQty: '16', supplierId: '1940000000000000002', supplierName: 'S002 晨岛咖啡贸易', reason: '近 7 日销量抬升，当前在途不足以覆盖 14 天预测' },
      ],
      sections: [
        { title: '数据范围', items: ['销售订单、库存余额、采购在途', '截至今日 10:30', '华东中心仓、华南中心仓，未来 14 天预测'] },
        { title: '安全边界', items: ['AI 只生成草稿建议，不直接写入正式采购订单', '正式单据需要人工在采购模块确认'] },
      ],
    };
  }
  if (content.includes('销量') || content.includes('预测')) {
    return null;
  }
  if (content.includes('库存') || content.includes('风险')) {
    return {
      workbenchId: `workbench-stock-${Date.now()}`,
      title: '库存风险筛选工作框',
      description: 'AI 返回风险范围后，右侧保留可复核的筛选条件，再跳转库存余额列表处理。',
      workbenchType: 'STOCK_FILTER',
      status: '待确认',
      route: '/warehouse/stocks',
      generatedNos: [],
      lines: [],
      sections: [
        { title: '筛选范围', items: ['仓库：华东中心仓、华南中心仓、南京备货仓', '维度：可用库存、安全库存、锁定库存'] },
        { title: '下一步', items: ['跳转库存余额筛选结果', '由用户确认补货、调拨或释放锁定库存'] },
      ],
    };
  }
  return null;
}

async function submitPromptTask() {
  const prompt = selectedPrompt.value;
  if (!prompt) return;
  const invalid = prompt.fields.find(field => field.required && !fieldDisplayValue(field));
  if (invalid) {
    toast.warning(`请填写${invalid.label}`);
    return;
  }
  promptDialogOpen.value = false;
  await submitMessage(buildPromptMessage(prompt), buildPromptTaskCard(prompt));
}

async function submitMessage(messageOverride?: string, taskCard: AiTaskCard | null = null) {
  const message = (messageOverride || inputMessage.value).trim();
  if (!message || sending.value) return;
  const id = conversationId.value || 'conv-today';
  ensureConversationMessages(id);

  messagesByConversation[id] = [...messagesByConversation[id], {
    messageId: `user-${Date.now()}`,
    role: 'user',
    content: taskCard ? `发起任务：${taskCard.title}` : message,
    createdAt: nowText(),
    charts: [],
    actionCards: [],
    sources: [],
    agentTraces: [],
    taskCard,
  }];
  inputMessage.value = '';
  sending.value = true;

  try {
    const response = await sendAiAssistantMessage({ conversationId: id, message });
    conversationId.value = response.conversationId;
    ensureConversationMessages(response.conversationId);
    messagesByConversation[response.conversationId] = [...messagesByConversation[response.conversationId], response.message];
    activeWorkbench.value = buildWorkbenchFromMessage(response.message, taskCard);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '智能经营助手响应失败');
  } finally {
    sending.value = false;
  }
}

function updateWorkbenchLine(lineId: string, field: keyof WorkbenchLine, value: string) {
  if (!activeWorkbench.value) return;
  activeWorkbench.value.lines = activeWorkbench.value.lines.map(line => line.lineId === lineId ? { ...line, [field]: value } : line);
  activeWorkbench.value.status = '已修改';
}

function selectWorkbenchSupplier(lineId: string, option: RemoteSearchOption) {
  if (!activeWorkbench.value) return;
  activeWorkbench.value.lines = activeWorkbench.value.lines.map(line => line.lineId === lineId
    ? { ...line, supplierId: String(option.value), supplierName: option.label, supplierProductId: null }
    : line);
  activeWorkbench.value.status = '已修改';
}

function selectWorkbenchWarehouse(lineId: string, option: RemoteSearchOption) {
  if (!activeWorkbench.value) return;
  activeWorkbench.value.lines = activeWorkbench.value.lines.map(line => line.lineId === lineId
    ? { ...line, warehouseId: String(option.value), warehouseName: option.label }
    : line);
  activeWorkbench.value.status = '已修改';
}

function selectWorkbenchTargetWarehouse(lineId: string, option: RemoteSearchOption) {
  if (!activeWorkbench.value) return;
  activeWorkbench.value.lines = activeWorkbench.value.lines.map(line => line.lineId === lineId
    ? { ...line, targetWarehouseId: String(option.value), targetWarehouseName: option.label }
    : line);
  activeWorkbench.value.status = '已修改';
}

function purchaseDraftGroups(lines: WorkbenchLine[]) {
  const groups = new Map<string, WorkbenchLine[]>();
  lines.forEach(line => {
    const key = `${line.supplierId || ''}__${line.warehouseId}`;
    groups.set(key, [...(groups.get(key) || []), line]);
  });
  return Array.from(groups.values());
}

function buildPurchaseDraftPayload(lines: WorkbenchLine[]): PurchaseOrderFormPayload {
  const first = lines[0];
  return {
    supplierId: first.supplierId || '',
    warehouseId: first.warehouseId,
    expectedArrivalDate: null,
    remark: '由智能经营助手生成的采购草稿，正式提交前请人工复核数量、价格和供应商。',
    items: lines.map(line => ({
      supplierProductId: line.supplierProductId || null,
      productId: line.productId,
      quantity: Number(line.suggestedQty),
      unitPrice: Number(line.unitPrice || 0),
      selectedSupplierScore: Number(line.selectedSupplierScore || 0),
      remark: line.reason,
    })),
  };
}

function buildTransferStockBillPayload(type: 'ADJUST_OUT' | 'ADJUST_IN', warehouseId: string, lines: WorkbenchLine[]): StockBillCreatePayload {
  return {
    billType: type,
    sourceNo: '',
    warehouseId,
    manualReason: type === 'ADJUST_OUT'
      ? 'AI 调拨建议生成的调出草稿，正式确认前需人工复核。'
      : 'AI 调拨建议生成的调入草稿，正式确认前需人工复核。',
    remark: '由智能经营助手根据销量预测、库存缺口和跨仓可用库存生成。',
    items: lines.map(line => ({
      productId: line.productId,
      quantity: Number(line.suggestedQty),
      qualifiedQty: 0,
      defectiveQty: 0,
      remark: line.reason,
    })),
  };
}

function transferDraftGroups(lines: WorkbenchLine[]) {
  const outbound = new Map<string, WorkbenchLine[]>();
  const inbound = new Map<string, WorkbenchLine[]>();
  lines.forEach(line => {
    outbound.set(line.warehouseId, [...(outbound.get(line.warehouseId) || []), line]);
    if (line.targetWarehouseId) inbound.set(line.targetWarehouseId, [...(inbound.get(line.targetWarehouseId) || []), line]);
  });
  return { outbound: Array.from(outbound.entries()), inbound: Array.from(inbound.entries()) };
}

function saveWorkbench() {
  if (!activeWorkbench.value) return;
  activeWorkbench.value.status = '已修改';
  toast.success('工作框内容已暂存，正式业务数据尚未写入');
}

async function createWorkbenchDraft() {
  const workbench = activeWorkbench.value;
  if (!workbench || workbenchSubmitting.value) return;
  if (workbench.workbenchType === 'TRANSFER_DRAFT') {
    const invalidLine = workbench.lines.find(line => !line.productId || !line.warehouseId || !line.targetWarehouseId || line.warehouseId === line.targetWarehouseId || !Number.isFinite(Number(line.suggestedQty)) || Number(line.suggestedQty) <= 0);
    if (invalidLine) {
      toast.warning(`请先补全「${invalidLine.productName}」的调出仓、调入仓和调拨数量，且两个仓库不能相同`);
      return;
    }
    workbenchSubmitting.value = true;
    try {
      const groups = transferDraftGroups(workbench.lines);
      const createdBills = [];
      for (const [warehouseId, lines] of groups.outbound) {
        createdBills.push(await createStockBill(buildTransferStockBillPayload('ADJUST_OUT', warehouseId, lines)));
      }
      for (const [warehouseId, lines] of groups.inbound) {
        createdBills.push(await createStockBill(buildTransferStockBillPayload('ADJUST_IN', warehouseId, lines)));
      }
      workbench.generatedNos = createdBills.map(bill => bill.billNo);
      workbench.status = '草稿已创建';
      toast.success(`已生成 ${createdBills.length} 张调拨相关库存草稿：${workbench.generatedNos.join('、')}`);
    } catch (error) {
      toast.warning(getApiErrorMessage(error) || '生成调拨草稿失败，请复核仓库和调拨数量');
    } finally {
      workbenchSubmitting.value = false;
    }
    return;
  }
  if (workbench.workbenchType !== 'PURCHASE_DRAFT') {
    workbench.status = '草稿已创建';
    toast.success('已生成可复核的草稿预览，正式业务写入需要后端提供对应单据接口');
    return;
  }
  const invalidLine = workbench.lines.find(line => !line.productId || !line.supplierId || !line.warehouseId || !Number.isFinite(Number(line.suggestedQty)) || Number(line.suggestedQty) <= 0);
  if (invalidLine) {
    toast.warning(`请先补全「${invalidLine.productName}」的商品、供应商、仓库和数量`);
    return;
  }
  workbenchSubmitting.value = true;
  try {
    const createdOrders = [];
    for (const group of purchaseDraftGroups(workbench.lines)) {
      createdOrders.push(await createPurchaseOrder(buildPurchaseDraftPayload(group)));
    }
    workbench.generatedNos = createdOrders.map(order => order.purchaseNo);
    workbench.status = '草稿已创建';
    toast.success(`已生成 ${createdOrders.length} 张采购草稿：${workbench.generatedNos.join('、')}`);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '生成采购草稿失败，请复核供应商供货关系');
  } finally {
    workbenchSubmitting.value = false;
  }
}

function openWorkbenchRoute() {
  if (activeWorkbench.value?.route) router.push(activeWorkbench.value.route);
}

function startWorkbenchResize(event: MouseEvent | PointerEvent) {
  if (workbenchCollapsed.value || resizingWorkbench.value) return;
  event.preventDefault();
  resizingWorkbench.value = true;
  const startX = event.clientX;
  const startWidth = workbenchWidth.value;

  const handleMove = (moveEvent: MouseEvent | PointerEvent) => {
    const delta = startX - moveEvent.clientX;
    workbenchWidth.value = Math.min(560, Math.max(280, startWidth + delta));
  };

  const handleUp = () => {
    resizingWorkbench.value = false;
    window.removeEventListener('pointermove', handleMove);
    window.removeEventListener('mousemove', handleMove);
  };

  window.addEventListener('pointermove', handleMove);
  window.addEventListener('mousemove', handleMove);
  window.addEventListener('pointerup', handleUp, { once: true });
  window.addEventListener('mouseup', handleUp, { once: true });
}

function openActionPreview(payload: {
  title: string;
  description: string;
  route: string | null;
  steps: string[];
}) {
  actionPreviewTitle.value = payload.title;
  actionPreviewDescription.value = payload.description;
  actionPreviewRoute.value = payload.route;
  actionPreviewSteps.value = payload.steps;
  actionPreviewOpen.value = true;
}

function previewPromptAction(prompt: AiQuickPrompt) {
  const action = promptActionMap[prompt.promptId];
  if (!action) return;
  openActionPreview({
    title: action.previewTitle,
    description: action.description,
    route: action.route,
    steps: action.steps,
  });
}

function handleAction(card: AiActionCard) {
  activeWorkbench.value = buildWorkbenchFromMessage({
    messageId: `action-${card.actionId}`,
    role: 'assistant',
    content: `${card.title} ${card.description}`,
    createdAt: nowText(),
    charts: [],
    actionCards: [],
    sources: [],
    agentTraces: [],
    taskCard: null,
  }, null);
  const steps = card.actionType === 'NAVIGATE'
    ? ['查看 AI 回答中引用的数据范围', '进入对应业务列表筛选和核对明细', '由用户决定是否继续生成草稿或处理单据']
    : ['复核 AI 建议的范围、数据来源和风险等级', '预览将要生成的业务草稿或报告卡片', '人工确认后再进入正式业务流程'];
  openActionPreview({
    title: card.title,
    description: card.description,
    route: card.route,
    steps,
  });
}

function confirmPreviewAction() {
  const route = actionPreviewRoute.value;
  actionPreviewOpen.value = false;
  if (route) {
    router.push(route);
    return;
  }
  toast.success('已生成待确认草稿预览');
}

onMounted(loadOverview);
</script>

<template>
  <section
    class="ai-agent-shell"
    :class="{ 'is-collapsed': sidebarCollapsed, 'is-workbench-collapsed': workbenchCollapsed, 'is-resizing-workbench': resizingWorkbench }"
    :style="{ '--ai-workbench-width': `${workbenchWidth}px` }"
  >
    <div class="ai-agent-body">
      <aside class="ai-agent-sidebar">
        <div class="ai-agent-sidebar__head">
          <div v-if="!sidebarCollapsed">
            <strong>会话管理</strong>
          </div>
          <Button size="sm" variant="outline" class="ai-icon-button" :aria-label="sidebarCollapsed ? '展开会话' : '折叠会话'" @click="sidebarCollapsed = !sidebarCollapsed">
            <PanelLeftOpen v-if="sidebarCollapsed" class="h-4 w-4" />
            <PanelLeftClose v-else class="h-4 w-4" />
          </Button>
        </div>

        <Button v-if="!sidebarCollapsed" size="sm" class="ai-new-chat" @click="createConversation">
          <Plus class="h-4 w-4" />
          新建会话
        </Button>
        <Button v-else size="sm" variant="outline" class="ai-icon-button" aria-label="新建会话" @click="createConversation">
          <Plus class="h-4 w-4" />
        </Button>

        <CollapsibleRoot :open="!conversationListCollapsed" :unmount-on-hide="false" class="ai-conversation-list" :class="{ 'is-conversation-collapsed': conversationListCollapsed }">
          <div v-if="!sidebarCollapsed" class="ai-conversation-list__toolbar">
            <button class="ai-conversation-list__toggle" type="button" :aria-expanded="!conversationListCollapsed" @click="conversationListCollapsed = !conversationListCollapsed">
              <span>会话记录</span>
              <ChevronDown class="h-3.5 w-3.5" />
            </button>
            <Popover>
              <PopoverTrigger as-child>
                <Button type="button" size="sm" variant="outline" class="ai-conversation-date">
                  <CalendarDays class="h-3.5 w-3.5" />
                  {{ selectedConversationDateLabel }}
                </Button>
              </PopoverTrigger>
              <PopoverContent align="start" class="w-64 p-2">
                <div class="ai-conversation-calendar">
                  <button type="button" :class="{ 'is-active': !selectedConversationDate }" @click="selectedConversationDate = null">
                    <span>全部日期</span>
                    <small>{{ overview?.conversations.length || 0 }} 条</small>
                  </button>
                  <button
                    v-for="group in conversationDateGroups"
                    :key="group.date"
                    type="button"
                    :class="{ 'is-active': selectedConversationDate === group.date }"
                    @click="selectedConversationDate = group.date"
                  >
                    <span>{{ group.date }}</span>
                    <small>{{ group.count }} 条</small>
                  </button>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <button v-else type="button" class="ai-conversation-row" aria-label="展开会话记录" @click="conversationListCollapsed = !conversationListCollapsed">
            <History class="h-4 w-4" />
          </button>

          <CollapsibleContent class="ai-conversation-list__drawer">
            <AiFadeSwitch :switch-key="selectedConversationDate || 'all'" content-class="ai-conversation-list__items">
              <button
                v-for="conversation in pinnedConversations"
                :key="conversation.conversationId"
                type="button"
                class="ai-conversation-row"
                :class="{ 'is-active': conversationId === conversation.conversationId, 'is-switching': switchingConversationId === conversation.conversationId }"
                @click="selectConversation(conversation.conversationId)"
              >
                <History class="h-4 w-4" />
                <span v-if="!sidebarCollapsed">
                  <strong>{{ conversation.title }}</strong>
                  <small>{{ conversation.updatedAt.slice(0, 16) }} · {{ conversation.description }}</small>
                </span>
                <span v-if="!sidebarCollapsed" class="ai-row-actions">
                  <button type="button" aria-label="修改会话名称" @click.stop="openRename(conversation.conversationId, conversation.title)">
                    <Edit3 class="h-3.5 w-3.5" />
                  </button>
                  <button type="button" aria-label="删除会话" @click.stop="removeConversation(conversation.conversationId)">
                    <Trash2 class="h-3.5 w-3.5" />
                  </button>
                </span>
              </button>
              <CollapsibleRoot
                v-if="hiddenConversationCount > 0 && !sidebarCollapsed"
                :open="conversationListExpanded"
                :unmount-on-hide="false"
                class="ai-conversation-extra"
              >
                <CollapsibleContent class="ai-conversation-extra__drawer">
                  <div class="ai-conversation-extra__items">
                    <button
                      v-for="conversation in extraConversations"
                      :key="conversation.conversationId"
                      type="button"
                      class="ai-conversation-row"
                      :class="{ 'is-active': conversationId === conversation.conversationId, 'is-switching': switchingConversationId === conversation.conversationId }"
                      @click="selectConversation(conversation.conversationId)"
                    >
                      <History class="h-4 w-4" />
                      <span v-if="!sidebarCollapsed">
                        <strong>{{ conversation.title }}</strong>
                        <small>{{ conversation.updatedAt.slice(0, 16) }} · {{ conversation.description }}</small>
                      </span>
                      <span v-if="!sidebarCollapsed" class="ai-row-actions">
                        <button type="button" aria-label="修改会话名称" @click.stop="openRename(conversation.conversationId, conversation.title)">
                          <Edit3 class="h-3.5 w-3.5" />
                        </button>
                        <button type="button" aria-label="删除会话" @click.stop="removeConversation(conversation.conversationId)">
                          <Trash2 class="h-3.5 w-3.5" />
                        </button>
                      </span>
                    </button>
                  </div>
                </CollapsibleContent>
              </CollapsibleRoot>
            </AiFadeSwitch>
            <button
              v-if="hiddenConversationCount > 0 && !sidebarCollapsed"
              type="button"
              class="ai-conversation-more"
              @click="conversationListExpanded = !conversationListExpanded"
            >
              {{ conversationListExpanded ? '收起到最近 5 条' : `展开全部（还有 ${hiddenConversationCount} 条）` }}
            </button>
          </CollapsibleContent>
        </CollapsibleRoot>

        <CollapsibleRoot v-if="!sidebarCollapsed" :open="promptListExpanded" :unmount-on-hide="false" class="ai-prompt-list" :class="{ 'is-prompt-collapsed': !promptListExpanded }">
          <button class="ai-prompt-list__toggle" type="button" :aria-expanded="promptListExpanded" @click="promptListExpanded = !promptListExpanded">
            <span>快捷分析</span>
            <ChevronDown class="h-3.5 w-3.5" />
          </button>
          <CollapsibleContent class="ai-prompt-list__drawer">
            <AiFadeSwitch switch-key="quick-prompts" content-class="ai-prompt-list__items">
              <button v-for="prompt in overview?.quickPrompts || []" :key="prompt.promptId" class="ai-prompt-card" type="button" @click="openPrompt(prompt)">
                <Sparkles class="h-3.5 w-3.5" />
                <span>
                  <strong>{{ prompt.title }}</strong>
                  <small>{{ prompt.description }}</small>
                  <em v-if="promptActionMap[prompt.promptId]">
                    <ClipboardCheck class="h-3 w-3" />
                    {{ promptActionMap[prompt.promptId].label }}
                  </em>
                </span>
              </button>
            </AiFadeSwitch>
          </CollapsibleContent>
        </CollapsibleRoot>
      </aside>

      <main class="ai-agent-main">
      <section class="ai-chat-stage" :class="{ 'is-welcome-stage': isWelcomeStage, 'is-switching': transitionLoading }">
        <div class="ai-chat-scroll">
        <Transition name="ai-conversation-panel" mode="out-in">
        <div :key="conversationId || 'empty-conversation'" class="ai-chat-stream">
          <article v-for="message in currentMessages" :key="message.messageId" class="ai-chat-message" :class="`is-${message.role}`">
            <div class="ai-chat-avatar">
              <UserRound v-if="message.role === 'user'" class="h-4 w-4" />
              <Bot v-else class="h-4 w-4" />
            </div>
            <div class="ai-chat-bubble">
              <div class="ai-chat-meta">
                <strong>{{ message.role === 'user' ? '我' : '智能经营助手' }}</strong>
                <span>{{ message.createdAt }}</span>
              </div>
              <p v-if="message.content">{{ message.content }}</p>

              <div v-if="message.taskCard" class="ai-task-message-card">
                <div>
                  <Badge variant="outline">任务</Badge>
                  <strong>{{ message.taskCard.title }}</strong>
                  <span>{{ message.taskCard.description }}</span>
                </div>
                <dl>
                  <template v-for="param in message.taskCard.parameters" :key="param.label">
                    <dt>{{ param.label }}</dt>
                    <dd>{{ param.value }}</dd>
                  </template>
                </dl>
              </div>

              <div v-if="message.charts.length" class="ai-message-charts">
                <AiChartCard v-for="chart in message.charts" :key="chart.chartId" :chart="chart" />
              </div>

              <div v-if="false && message.charts.length" class="ai-message-charts">
                <article v-for="chart in message.charts" :key="chart.chartId" class="ai-message-chart">
                  <div class="ai-message-chart__head">
                    <LineChart v-if="chart.type === 'line'" class="h-4 w-4 text-primary" />
                    <PackageCheck v-else class="h-4 w-4 text-primary" />
                    <strong>{{ chart.title }}</strong>
                    <Badge variant="outline">{{ chart.type === 'line' ? '折线图' : chart.type === 'bar' ? '柱状图' : '饼图' }}</Badge>
                  </div>
                  <p v-if="chart.description" class="ai-message-chart__desc">{{ chart.description }}</p>
                  <div v-if="chart.type === 'line'" class="ai-chat-line-chart">
                    <svg :viewBox="`0 0 ${chartFrame.width} ${chartFrame.height}`" role="img" :aria-label="chart.title">
                      <g class="ai-chart-grid-lines">
                        <line v-for="(lineY, index) in chartGridLines(chart)" :key="`grid-${index}`" :x1="chartFrame.left" :y1="lineY" :x2="chartFrame.right" :y2="lineY" />
                      </g>
                      <g class="ai-chart-y-axis">
                        <text v-for="(tick, index) in chartTicks(chart)" :key="`tick-${index}`" :x="chartFrame.tickX" :y="chartGridLines(chart)[index] + 4">{{ formatChartTick(tick) }}</text>
                      </g>
                      <g class="ai-chart-line-layer">
                        <polyline
                          v-for="(field, index) in chartFields(chart)"
                          :key="field"
                          class="ai-chart-line"
                          fill="none"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          :stroke="chartColor(index)"
                          :points="chartLinePoints(chart, field)"
                        />
                        <g v-for="(field, seriesIndex) in chartFields(chart)" :key="`points-${field}`" class="ai-chart-line-points">
                          <circle
                            v-for="(row, index) in chart.data"
                            :key="`${field}-${chartLabel(chart, row)}`"
                            :cx="chartX(chart, index)"
                            :cy="chartY(chart, chartNumber(row, field))"
                            r="2.8"
                            :fill="chartColor(seriesIndex)"
                          />
                        </g>
                      </g>
                      <g class="ai-chart-x-axis">
                        <text v-for="label in chartAxisLabels(chart)" :key="label.key" :x="label.x" :y="chartFrame.labelY" text-anchor="middle">{{ label.label }}</text>
                      </g>
                    </svg>
                    <div v-if="chartFields(chart).length > 1" class="ai-chart-legend">
                      <span v-for="(field, index) in chartFields(chart)" :key="field">
                        <i :style="{ background: chartColor(index) }" />
                        {{ field }}
                      </span>
                    </div>
                  </div>
                  <div v-else-if="chart.type === 'bar'" class="ai-chat-bar-chart">
                    <svg :viewBox="`0 0 ${chartFrame.width} ${chartFrame.height}`" role="img" :aria-label="chart.title">
                      <g class="ai-chart-grid-lines">
                        <line v-for="(lineY, index) in chartGridLines(chart)" :key="`grid-${index}`" :x1="chartFrame.left" :y1="lineY" :x2="chartFrame.right" :y2="lineY" />
                      </g>
                      <g class="ai-chart-y-axis">
                        <text v-for="(tick, index) in chartTicks(chart)" :key="`tick-${index}`" :x="chartFrame.tickX" :y="chartGridLines(chart)[index] + 4">{{ formatChartTick(tick) }}</text>
                      </g>
                      <g class="ai-chart-bar-layer">
                        <rect
                          v-for="(row, index) in chart.data"
                          :key="chartLabel(chart, row)"
                          :x="chartBarX(chart, index)"
                          :y="chartBarY(chart, row)"
                          :width="chartBarWidth(chart)"
                          :height="chartBarHeight(chart, row)"
                          rx="3"
                          :fill="chartColor(index)"
                        />
                      </g>
                      <g class="ai-chart-x-axis">
                        <text v-for="label in chartAxisLabels(chart)" :key="label.key" :x="label.x" :y="chartFrame.labelY" text-anchor="middle">{{ label.label }}</text>
                      </g>
                    </svg>
                  </div>
                  <div v-else class="ai-chat-pie-chart">
                    <span v-for="row in chart.data" :key="chartLabel(chart, row)">
                      <strong>{{ chartNumber(row, chart.valueField) }}</strong>
                      <small>{{ chartLabel(chart, row) }}</small>
                    </span>
                  </div>
                </article>
              </div>

              <div v-if="message.agentTraces.length" class="ai-agent-trace">
                <div v-for="trace in message.agentTraces" :key="trace.traceId">
                  <span>{{ trace.agentName }}</span>
                  <p>{{ trace.summary }}</p>
                </div>
              </div>

              <div v-if="false && message.sources.length" class="ai-source-strip">
                <div class="ai-source-strip__head">
                  <Database class="h-3.5 w-3.5" />
                  <span>引用数据来源</span>
                </div>
                <div class="ai-source-list">
                  <span v-for="source in message.sources" :key="source.sourceId">
                    <Badge variant="outline">{{ sourceTypeText(source) }}</Badge>
                    <strong>{{ source.title }}</strong>
                    <small>{{ source.description }} · {{ source.freshness }}</small>
                  </span>
                </div>
              </div>

              <div v-if="message.actionCards.length" class="ai-action-group">
                <button v-for="card in message.actionCards" :key="card.actionId" type="button" @click="handleAction(card)">
                  <span>
                    <strong>{{ card.title }}</strong>
                    <small>{{ card.description }}</small>
                  </span>
                  <ChevronRight class="h-4 w-4" />
                </button>
              </div>
            </div>
          </article>

          <article v-if="sending" key="assistant-thinking" class="ai-chat-message is-assistant">
            <div class="ai-chat-avatar"><Bot class="h-4 w-4" /></div>
            <div class="ai-chat-bubble">
              <div class="ai-thinking">
                <span /><span /><span />
                正在分析
              </div>
            </div>
          </article>
        </div>
        </Transition>
        </div>

      <form class="ai-composer" @submit.prevent="submitMessage()">
        <Textarea
          v-model="inputMessage"
          class="ai-composer__input"
          placeholder="输入经营问题，例如：分析 A4复印纸未来 14 天补货建议"
          :disabled="sending"
          @keydown.enter.exact.prevent="submitMessage()"
        />
        <Button class="ai-composer__send" type="submit" size="sm" aria-label="发送消息" :disabled="sending || !inputMessage.trim()">
          <Send class="h-4 w-4" />
        </Button>
      </form>
      </section>
      </main>

      <aside class="ai-workbench">
        <button
          v-if="!workbenchCollapsed"
          type="button"
          class="ai-workbench-resizer"
          aria-label="调整工作框宽度"
          @pointerdown="startWorkbenchResize"
          @mousedown="startWorkbenchResize"
        />
        <div class="ai-workbench__head">
          <span v-if="!workbenchCollapsed">动态工作框</span>
          <Button
            class="ai-icon-button"
            type="button"
            size="icon"
            variant="ghost"
            :aria-label="workbenchCollapsed ? '展开工作框' : '收起工作框'"
            @click="workbenchCollapsed = !workbenchCollapsed"
          >
            <PanelRightOpen v-if="workbenchCollapsed" class="h-4 w-4" />
            <PanelRightClose v-else class="h-4 w-4" />
          </Button>
        </div>

        <div v-if="workbenchCollapsed" class="ai-workbench-collapsed">
          <span>工作框</span>
        </div>

        <div v-else-if="!activeWorkbench" class="ai-workbench-empty">
          <PackageCheck class="h-5 w-5 text-primary" />
          <strong>等待可操作结果</strong>
          <p>后端返回采购草稿、库存筛选条件等可操作数据时，会在这里生成可修改的工作框。</p>
        </div>

        <template v-else-if="activeWorkbench">
          <div class="ai-workbench-title">
            <component :is="activeWorkbench.workbenchType === 'PURCHASE_DRAFT' ? ShoppingCart : PackageCheck" class="h-4 w-4 text-primary" />
            <div>
              <div class="ai-workbench-title__row">
                <strong>{{ activeWorkbench.title }}</strong>
                <span class="ai-workbench-status">{{ activeWorkbench.status }}</span>
              </div>
              <small>{{ activeWorkbench.description }}</small>
              <div v-if="activeWorkbench.generatedNos.length" class="ai-workbench-generated">
                <Badge v-for="no in activeWorkbench.generatedNos" :key="no" variant="outline">{{ no }}</Badge>
              </div>
            </div>
          </div>

          <div v-if="activeWorkbench.lines.length" class="ai-workbench-lines">
            <article v-for="line in activeWorkbench.lines" :key="line.lineId">
              <div class="ai-workbench-line__top">
                <strong>{{ line.productName }}</strong>
                <Badge variant="outline">{{ activeWorkbench.workbenchType === 'PURCHASE_DRAFT' ? '采购' : activeWorkbench.workbenchType === 'TRANSFER_DRAFT' ? '调拨' : '释放' }}</Badge>
              </div>
              <div class="ai-workbench-fields">
                <div class="ai-field">
                  <Label>{{ activeWorkbench.workbenchType === 'LOCK_RELEASE' ? '释放数量' : activeWorkbench.workbenchType === 'TRANSFER_DRAFT' ? '调拨数量' : '建议数量' }}</Label>
                  <Input :model-value="line.suggestedQty" @update:model-value="value => updateWorkbenchLine(line.lineId, 'suggestedQty', String(value))" />
                </div>
                <div class="ai-field">
                  <Label>{{ activeWorkbench.workbenchType === 'TRANSFER_DRAFT' ? '调出仓库' : activeWorkbench.workbenchType === 'LOCK_RELEASE' ? '锁定仓库' : '入库仓库' }}</Label>
                  <RemoteSearchSelect
                    compact
                    :model-value="line.warehouseId"
                    :selected-label="line.warehouseName"
                    :fetch-options="fetchWorkbenchWarehouseOptions"
                    placeholder="请选择仓库"
                    search-placeholder="输入仓库编码或名称"
                    empty-text="暂无匹配仓库"
                    @select="option => selectWorkbenchWarehouse(line.lineId, option)"
                  />
                </div>
                <div v-if="activeWorkbench.workbenchType === 'TRANSFER_DRAFT'" class="ai-field ai-workbench-field-wide">
                  <Label>调入仓库</Label>
                  <RemoteSearchSelect
                    compact
                    :model-value="line.targetWarehouseId"
                    :selected-label="line.targetWarehouseName"
                    :fetch-options="fetchWorkbenchWarehouseOptions"
                    placeholder="请选择调入仓库"
                    search-placeholder="输入仓库编码或名称"
                    empty-text="暂无匹配仓库"
                    @select="option => selectWorkbenchTargetWarehouse(line.lineId, option)"
                  />
                </div>
                <div v-if="activeWorkbench.workbenchType === 'PURCHASE_DRAFT'" class="ai-field ai-workbench-field-wide">
                  <Label>供应商</Label>
                  <RemoteSearchSelect
                    compact
                    :model-value="line.supplierId"
                    :selected-label="line.supplierName"
                    :fetch-options="fetchWorkbenchSupplierOptions"
                    placeholder="请选择供应商"
                    search-placeholder="输入供应商编码或名称"
                    empty-text="暂无匹配供应商"
                    @select="option => selectWorkbenchSupplier(line.lineId, option)"
                  />
                </div>
                <div v-if="activeWorkbench.workbenchType === 'LOCK_RELEASE'" class="ai-field ai-workbench-field-wide">
                  <Label>来源单号</Label>
                  <Input :model-value="line.sourceNo" @update:model-value="value => updateWorkbenchLine(line.lineId, 'sourceNo', String(value))" />
                </div>
              </div>
              <div class="ai-field">
                <Label>{{ activeWorkbench.workbenchType === 'LOCK_RELEASE' ? '复核原因' : '建议原因' }}</Label>
                <Textarea :model-value="line.reason" class="min-h-[62px]" @update:model-value="value => updateWorkbenchLine(line.lineId, 'reason', String(value))" />
              </div>
            </article>
          </div>

          <div class="ai-workbench-sections">
            <section v-for="section in activeWorkbench.sections" :key="section.title">
              <h3>{{ section.title }}</h3>
              <ul>
                <li v-for="item in section.items" :key="item">{{ item }}</li>
              </ul>
            </section>
          </div>

          <div class="ai-workbench-actions">
            <Button size="sm" variant="outline" @click="saveWorkbench">
              <Save class="mr-1 h-3.5 w-3.5" />
              暂存修改
            </Button>
            <Button v-if="activeWorkbench.workbenchType !== 'STOCK_FILTER'" size="sm" :disabled="workbenchSubmitting" @click="createWorkbenchDraft">
              {{ workbenchSubmitting ? '生成中...' : activeWorkbench.workbenchType === 'PURCHASE_DRAFT' ? '生成采购草稿' : activeWorkbench.workbenchType === 'TRANSFER_DRAFT' ? '生成调拨草稿' : '生成释放预览' }}
            </Button>
            <Button size="sm" variant="outline" :disabled="!activeWorkbench.route" @click="openWorkbenchRoute">
              进入业务页
            </Button>
          </div>
        </template>
      </aside>
    </div>

    <Dialog v-model:open="promptDialogOpen">
      <DialogContent
        class="ai-prompt-dialog"
        :inert="actionPreviewOpen ? true : undefined"
        :style="actionPreviewOpen ? { pointerEvents: 'none', filter: 'blur(1px)' } : undefined"
      >
        <DialogHeader>
          <DialogTitle>{{ selectedPrompt?.title }}</DialogTitle>
          <DialogDescription>{{ selectedPrompt?.description }}</DialogDescription>
        </DialogHeader>
        <div v-if="selectedPrompt" class="ai-prompt-form">
          <div v-if="selectedPromptAction" class="ai-business-action-preview">
            <div>
              <PackageCheck class="h-4 w-4 text-primary" />
              <span>
                <strong>{{ selectedPromptAction.label }}</strong>
                <small>{{ selectedPromptAction.description }}</small>
              </span>
            </div>
            <Button type="button" size="sm" variant="outline" @click="previewPromptAction(selectedPrompt)">查看流程</Button>
          </div>
          <div v-for="field in selectedPrompt.fields" :key="field.fieldKey" class="ai-field">
            <Label :for="field.fieldKey">{{ field.label }} <span v-if="field.required" class="text-destructive">*</span></Label>
            <div v-if="field.fieldType === 'PRODUCT_MULTI' || field.fieldType === 'WAREHOUSE_MULTI'" class="ai-search-picker">
              <div class="ai-selected-tags">
                <Badge v-for="item in fieldArrayValue(field)" :key="item" variant="outline">
                  {{ item }}
                  <button type="button" :aria-label="`移除${field.label}`" @click="removePromptFieldValue(field, item)">×</button>
                </Badge>
                <span v-if="fieldArrayValue(field).length === 0">{{ field.placeholder }}</span>
              </div>
              <div class="ai-search-picker__row">
                <RemoteSearchSelect
                  v-if="field.fieldType === 'PRODUCT_MULTI'"
                  :key="productPickerKey"
                  v-model="productPickerValue"
                  placeholder="输入商品编码或名称搜索"
                  search-placeholder="输入商品编码或名称"
                  empty-text="暂无匹配商品"
                  :fetch-options="fetchProductOptions"
                  @select="option => addPromptFieldOption(field, option)"
                />
                <RemoteSearchSelect
                  v-else
                  :key="warehousePickerKey"
                  v-model="warehousePickerValue"
                  placeholder="输入仓库编码或名称搜索"
                  search-placeholder="输入仓库编码或名称"
                  empty-text="暂无匹配仓库"
                  :fetch-options="fetchWarehouseOptions"
                  @select="option => addPromptFieldOption(field, option)"
                />
                <Button type="button" variant="outline" class="ai-search-picker__all" @click="addPromptFieldAll(field)">
                  {{ field.fieldType === 'PRODUCT_MULTI' ? '全部商品' : '全部仓库' }}
                </Button>
              </div>
            </div>
            <AnchoredSelect
              v-else-if="field.fieldType === 'DAYS'"
              v-model="promptForm[field.fieldKey] as string"
              :options="daysOptions"
              :placeholder="field.placeholder"
            />
            <Textarea
              v-else
              :id="field.fieldKey"
              v-model="promptForm[field.fieldKey] as string"
              class="min-h-[82px]"
              :placeholder="field.placeholder"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="promptDialogOpen = false">取消</Button>
          <Button :disabled="sending" @click="submitPromptTask">发起任务</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <div v-if="actionPreviewOpen" class="ai-modal-click-shield" aria-hidden="true" />
    <AlertDialog :open="actionPreviewOpen" @update:open="actionPreviewOpen = $event">
      <AlertDialogContent class="ai-action-preview-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{{ actionPreviewTitle }}</AlertDialogTitle>
          <AlertDialogDescription>{{ actionPreviewDescription }}</AlertDialogDescription>
        </AlertDialogHeader>
        <div class="ai-action-preview">
          <div class="ai-action-preview__hero">
            <div>
              <FileText class="h-5 w-5" />
            </div>
            <span>
              <strong>建议确认模式</strong>
              <small>AI 只生成建议和草稿预览，正式单据仍需人工确认。</small>
            </span>
          </div>
          <div class="ai-action-preview__steps">
            <span v-for="(step, index) in actionPreviewSteps" :key="step">
              <i>{{ index + 1 }}</i>
              <b>{{ step }}</b>
            </span>
          </div>
        </div>
        <AlertDialogFooter>
          <AlertDialogCancel>取消</AlertDialogCancel>
          <AlertDialogAction @click="confirmPreviewAction">{{ actionPreviewRoute ? '进入业务页面' : '生成草稿预览' }}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>

    <Dialog v-model:open="renameDialogOpen">
      <DialogContent class="ai-rename-dialog">
        <DialogHeader>
          <DialogTitle>修改会话名称</DialogTitle>
          <DialogDescription>只修改当前会话在左侧列表中的显示名称。</DialogDescription>
        </DialogHeader>
        <div class="ai-field">
          <Label for="renameTitle">会话名称 <span class="text-destructive">*</span></Label>
          <Input id="renameTitle" v-model="renameTitle" maxlength="40" />
        </div>
        <DialogFooter>
          <Button variant="outline" @click="renameDialogOpen = false">取消</Button>
          <Button @click="saveRename">保存</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </section>
</template>

<style scoped>
.ai-agent-shell {
  position: relative;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  height: calc(100dvh - 64px);
  min-height: 0;
  overflow: hidden;
  background: #f4f6f8;
}

.ai-agent-body {
  display: grid;
  grid-template-columns: 286px minmax(0, 1fr) var(--ai-workbench-width, 360px);
  min-height: 0;
  transition: grid-template-columns 180ms ease;
}

.ai-agent-shell.is-collapsed .ai-agent-body {
  grid-template-columns: 60px minmax(0, 1fr) var(--ai-workbench-width, 360px);
}

.ai-agent-shell.is-workbench-collapsed .ai-agent-body {
  grid-template-columns: 286px minmax(0, 1fr) 48px;
}

.ai-agent-shell.is-collapsed.is-workbench-collapsed .ai-agent-body {
  grid-template-columns: 60px minmax(0, 1fr) 48px;
}

.ai-agent-shell.is-resizing-workbench {
  cursor: col-resize;
  user-select: none;
}

.ai-agent-shell.is-resizing-workbench .ai-agent-body {
  transition: none;
}

.ai-agent-sidebar {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  overflow-y: auto;
  border-right: 1px solid var(--border);
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  padding: 16px 12px;
}

.ai-agent-sidebar__head,
.ai-prompt-list__toggle span {
  color: #667085;
  font-size: 12px;
}

.ai-agent-sidebar__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
}

.ai-agent-sidebar__head strong {
  display: block;
  color: #172033;
  font-size: 15px;
}

.ai-icon-button {
  width: 34px;
  height: 34px;
  padding: 0;
}

.ai-new-chat {
  justify-content: center;
  gap: 7px;
  height: 36px;
  box-shadow: 0 6px 14px rgb(37 99 235 / 14%);
}

.ai-conversation-list,
.ai-prompt-list {
  display: grid;
  gap: 6px;
}

.ai-conversation-list {
  gap: 7px;
}

.ai-conversation-list__toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  align-items: center;
}

.ai-conversation-list__toggle {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  border: 0;
  background: transparent;
  padding: 0 2px;
  color: #667085;
  font-size: 12px;
  cursor: pointer;
}

.ai-conversation-list__toggle svg,
.ai-prompt-list__toggle svg {
  color: #667085;
  transition: transform 180ms cubic-bezier(0.16, 1, 0.3, 1);
}

.ai-conversation-list.is-conversation-collapsed .ai-conversation-list__toggle svg,
.ai-prompt-list.is-prompt-collapsed .ai-prompt-list__toggle svg {
  transform: rotate(-90deg);
}

.ai-conversation-date {
  max-width: 118px;
  height: 27px;
  gap: 5px;
  overflow: hidden;
  padding-inline: 7px;
  font-size: 11px;
}

.ai-conversation-calendar {
  display: grid;
  gap: 5px;
}

.ai-conversation-calendar button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: white;
  padding: 7px 8px;
  color: #475467;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 150ms ease, background 150ms ease, color 150ms ease, transform 150ms ease;
}

.ai-conversation-calendar button:hover,
.ai-conversation-calendar button.is-active {
  transform: translateY(-1px);
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.ai-conversation-calendar small {
  color: #667085;
  font-size: 11px;
}

.ai-conversation-list__drawer,
.ai-prompt-list__drawer,
.ai-conversation-extra__drawer {
  overflow: hidden;
  will-change: height;
}

.ai-conversation-list__drawer[data-state="open"],
.ai-prompt-list__drawer[data-state="open"],
.ai-conversation-extra__drawer[data-state="open"] {
  animation: ai-sidebar-collapsible-down 220ms cubic-bezier(0.16, 1, 0.3, 1);
}

.ai-conversation-list__drawer[data-state="closed"],
.ai-prompt-list__drawer[data-state="closed"],
.ai-conversation-extra__drawer[data-state="closed"] {
  animation: ai-sidebar-collapsible-up 180ms cubic-bezier(0.4, 0, 1, 1);
}

.ai-conversation-list__items {
  display: grid;
  gap: 7px;
  padding-top: 6px;
}

.ai-conversation-extra__items {
  display: grid;
  gap: 7px;
  padding-top: 7px;
}

.ai-conversation-row,
.ai-prompt-card {
  display: grid;
  width: 100%;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  padding: 10px 9px;
  color: #475467;
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.ai-conversation-row:hover,
.ai-prompt-card:hover {
  transform: translateY(-1px);
  border-color: #d1d5db;
  box-shadow: 0 8px 18px rgb(15 23 42 / 6%);
}

.is-collapsed .ai-conversation-row {
  grid-template-columns: 20px;
  justify-content: center;
  padding: 9px 0;
}

.ai-conversation-row.is-active {
  border-color: color-mix(in srgb, var(--primary) 20%, var(--border));
  background: linear-gradient(180deg, #f2f7ff 0%, #eaf3ff 100%);
  box-shadow: inset 3px 0 0 var(--primary), 0 8px 18px rgb(37 99 235 / 8%);
}

.ai-conversation-row.is-switching {
  transform: translateX(2px);
}

.ai-conversation-row strong,
.ai-prompt-list strong {
  display: block;
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-conversation-row small,
.ai-prompt-list small {
  display: block;
  overflow: hidden;
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-prompt-list em {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  margin-top: 6px;
  border: 1px solid #dbeafe;
  border-radius: 999px;
  background: #eff6ff;
  padding: 2px 7px;
  color: #1d4ed8;
  font-size: 11px;
  font-style: normal;
}

.ai-row-actions {
  display: inline-flex;
  gap: 2px;
  opacity: 0;
}

.ai-conversation-row:hover .ai-row-actions,
.ai-conversation-row.is-active .ai-row-actions {
  opacity: 1;
}

.ai-row-actions button {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #667085;
  cursor: pointer;
}

.ai-row-actions button:hover {
  background: white;
  color: #172033;
}

.ai-conversation-more {
  width: 100%;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  padding: 8px;
  color: #475467;
  font-size: 12px;
  cursor: pointer;
}

.ai-conversation-more:hover {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.ai-prompt-list {
  margin-top: 18px;
}

.ai-prompt-list__toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  border: 0;
  background: transparent;
  padding: 0 2px 2px;
  cursor: pointer;
}

.ai-prompt-list__items {
  display: grid;
  gap: 6px;
  padding-top: 6px;
}

.ai-prompt-card {
  grid-template-columns: 18px minmax(0, 1fr);
  border-color: var(--border);
  background: white;
}

.ai-agent-main {
  position: relative;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
  min-width: 0;
  max-width: 1120px;
  width: 100%;
  margin: 0 auto;
  padding: 18px 28px 20px;
}

.ai-chat-stage {
  position: relative;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: linear-gradient(180deg, rgb(255 255 255 / 97%), rgb(250 252 255 / 96%));
}

.ai-chat-scroll {
  min-height: 0;
  overflow-y: auto;
  padding: 22px 22px 122px;
  scroll-padding-bottom: 130px;
}

.ai-chat-stream {
  display: grid;
  align-content: start;
  gap: 18px;
  min-height: 100%;
  transition: padding-top 180ms ease;
}

.ai-chat-stage.is-welcome-stage .ai-chat-stream {
  padding-top: 0;
}

.ai-conversation-panel-enter-active,
.ai-conversation-panel-leave-active {
  transition: opacity 220ms ease;
}

.ai-conversation-panel-enter-from,
.ai-conversation-panel-leave-to {
  opacity: 0;
}

.ai-chat-message {
  display: flex;
  gap: 10px;
}

.ai-chat-message.is-user {
  flex-direction: row-reverse;
}

.ai-chat-avatar {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  color: var(--primary);
}

.ai-chat-message.is-user .ai-chat-avatar {
  background: #111827;
  color: white;
}

.ai-chat-bubble {
  max-width: min(780px, 84%);
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  box-shadow: 0 12px 28px rgb(15 23 42 / 5%);
  padding: 13px 15px;
}

.ai-chat-message.is-user .ai-chat-bubble {
  border-color: #bfdbfe;
  background: #eff6ff;
  box-shadow: none;
}

.ai-chat-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #667085;
  font-size: 12px;
}

.ai-chat-bubble > p {
  margin: 8px 0 0;
  color: #344054;
  line-height: 1.8;
}

.ai-task-message-card {
  display: grid;
  gap: 10px;
  margin-top: 10px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: white;
  padding: 12px;
}

.ai-task-message-card strong,
.ai-task-message-card span {
  display: block;
}

.ai-task-message-card strong {
  margin-top: 7px;
  color: #172033;
}

.ai-task-message-card span {
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
}

.ai-task-message-card dl {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0;
  font-size: 12px;
}

.ai-task-message-card dt {
  color: #667085;
}

.ai-task-message-card dd {
  margin: 0;
  color: #344054;
}

.ai-message-charts {
  display: grid;
  gap: 10px;
  margin-top: 10px;
}

.ai-message-chart {
  display: grid;
  gap: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 10px;
}

.ai-message-chart__head {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.ai-message-chart__head strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-message-chart__desc {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-chat-line-chart {
  display: grid;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chat-line-chart svg {
  width: 100%;
  height: 190px;
  overflow: visible;
  background: white;
}

.ai-chart-legend {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-chart-legend {
  flex-wrap: wrap;
  color: #475467;
  font-size: 11px;
}

.ai-chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ai-chart-legend i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
}

.ai-chart-grid-lines line {
  stroke: #e5e7eb;
  stroke-width: 1;
}

.ai-chart-y-axis text,
.ai-chart-x-axis text {
  fill: #475467;
  font-size: 10px;
  font-weight: 600;
}

.ai-chart-line {
  stroke-width: 3;
}

.ai-chart-line-points circle {
  stroke: white;
  stroke-width: 2;
}

.ai-chat-bar-chart {
  display: grid;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chat-bar-chart svg {
  width: 100%;
  height: 190px;
  overflow: visible;
  background: white;
}

.ai-chart-bar-layer rect {
  shape-rendering: geometricPrecision;
}

.ai-chat-pie-chart {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-chat-pie-chart span {
  display: grid;
  gap: 4px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-chat-pie-chart strong {
  color: #172033;
  font-size: 17px;
}

.ai-chat-pie-chart small {
  color: #667085;
  font-size: 11px;
}

.ai-agent-trace {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  border-left: 2px solid #bfdbfe;
  padding-left: 10px;
}

.ai-agent-trace span {
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}

.ai-agent-trace p {
  margin: 0;
  color: #667085;
  font-size: 12px;
}

.ai-source-strip {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px;
}

.ai-source-strip__head {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #667085;
  font-size: 12px;
  font-weight: 700;
}

.ai-source-list {
  display: grid;
  gap: 7px;
}

.ai-source-list > span {
  display: grid;
  grid-template-columns: auto minmax(92px, auto) minmax(0, 1fr);
  gap: 7px;
  align-items: center;
  color: #475467;
  font-size: 12px;
}

.ai-source-list strong {
  overflow: hidden;
  color: #172033;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-source-list small {
  overflow: hidden;
  color: #667085;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-action-group {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.ai-action-group button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #f8fafc;
  padding: 10px;
  text-align: left;
  cursor: pointer;
}

.ai-action-group small {
  display: block;
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
}

.ai-thinking {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #667085;
  font-size: 13px;
}

.ai-thinking span {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--primary);
  animation: ai-thinking 900ms infinite ease-in-out;
}

.ai-thinking span:nth-child(2) {
  animation-delay: 120ms;
}

.ai-thinking span:nth-child(3) {
  animation-delay: 240ms;
}

.ai-composer {
  position: absolute;
  right: 24px;
  bottom: 18px;
  left: 24px;
  z-index: 8;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 38px;
  gap: 8px;
  margin: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  box-shadow: 0 18px 42px rgb(15 23 42 / 14%);
  padding: 7px;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.ai-composer:focus-within {
  border-color: color-mix(in srgb, var(--primary) 38%, var(--border));
  box-shadow: 0 20px 46px rgb(15 23 42 / 16%), 0 0 0 3px color-mix(in srgb, var(--primary) 12%, transparent);
  transform: translateY(-1px);
}

.ai-composer__input {
  min-height: 38px;
  max-height: 96px;
  border: 0;
  resize: none;
  font-size: 13px;
  line-height: 1.5;
}

.ai-composer__send {
  width: 38px;
  height: 38px;
  padding: 0;
}

.ai-prompt-dialog {
  width: min(720px, calc(100vw - 2rem));
  max-width: min(720px, calc(100vw - 2rem));
}

.ai-prompt-dialog.is-confirming {
  pointer-events: none;
  filter: blur(1px);
}

.ai-modal-click-shield {
  position: fixed;
  inset: 0;
  z-index: 72;
  background: rgb(15 23 42 / 20%);
  backdrop-filter: blur(2px);
  pointer-events: auto;
}

.ai-prompt-form {
  display: grid;
  gap: 14px;
}

.ai-business-action-preview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
  padding: 10px 12px;
}

.ai-business-action-preview > div {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.ai-business-action-preview strong,
.ai-business-action-preview small {
  display: block;
}

.ai-business-action-preview strong {
  color: #172033;
  font-size: 13px;
}

.ai-business-action-preview small {
  color: #667085;
  font-size: 12px;
}

.ai-rename-dialog {
  width: min(420px, calc(100vw - 2rem));
  max-width: min(420px, calc(100vw - 2rem));
}

.ai-action-preview-dialog {
  z-index: 90 !important;
  width: min(560px, calc(100vw - 2rem));
  max-width: min(560px, calc(100vw - 2rem));
  border-radius: 14px;
}

.ai-action-preview {
  display: grid;
  gap: 14px;
}

.ai-action-preview__hero {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: linear-gradient(135deg, #eff6ff, #f8fbff);
  padding: 12px;
}

.ai-action-preview__hero > div {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 10px;
  background: #2563eb;
  color: white;
}

.ai-action-preview__hero strong,
.ai-action-preview__hero small {
  display: block;
}

.ai-action-preview__hero strong {
  color: #172033;
  font-size: 14px;
}

.ai-action-preview__hero small {
  margin-top: 3px;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-action-preview__steps {
  display: grid;
  gap: 8px;
}

.ai-action-preview__steps span {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 9px;
  align-items: start;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 9px 10px;
}

.ai-action-preview__steps i {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 999px;
  background: #f1f5f9;
  color: #2563eb;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
}

.ai-action-preview__steps b {
  color: #475467;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.7;
}

.ai-field {
  display: grid;
  gap: 7px;
}

.ai-search-picker {
  display: grid;
  gap: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px;
}

.ai-selected-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 36px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: white;
  padding: 6px 8px;
}

.ai-selected-tags span {
  color: #667085;
  font-size: 13px;
}

.ai-selected-tags [data-slot="badge"] {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: min(100%, 260px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-selected-tags button {
  display: inline-grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #667085;
  cursor: pointer;
  line-height: 1;
}

.ai-selected-tags button:hover {
  background: #e2e8f0;
  color: #172033;
}

.ai-search-picker__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px;
  gap: 8px;
}

.ai-search-picker__all {
  justify-content: center;
}

.ai-workbench {
  position: relative;
  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
  overflow-y: auto;
  border-left: 1px solid var(--border);
  background: #f8fafc;
  padding: 16px 14px;
}

.ai-workbench-resizer {
  position: absolute;
  top: 0;
  bottom: 0;
  left: -5px;
  z-index: 3;
  width: 10px;
  border: 0;
  background: transparent;
  cursor: col-resize;
}

.ai-workbench-resizer::after {
  position: absolute;
  top: 18px;
  bottom: 18px;
  left: 4px;
  width: 2px;
  border-radius: 999px;
  background: transparent;
  content: '';
}

.ai-workbench-resizer:hover::after,
.is-resizing-workbench .ai-workbench-resizer::after {
  background: color-mix(in srgb, var(--primary) 42%, #cbd5e1);
}

.ai-workbench__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: #667085;
  font-size: 12px;
}

.is-workbench-collapsed .ai-workbench {
  overflow: hidden;
  padding: 14px 6px;
}

.is-workbench-collapsed .ai-workbench__head {
  justify-content: center;
}

.ai-workbench-collapsed {
  display: grid;
  place-items: center;
  min-height: 160px;
  color: #667085;
  font-size: 12px;
  writing-mode: vertical-rl;
}

.ai-workbench-empty,
.ai-workbench-title,
.ai-workbench-lines article,
.ai-workbench-sections section {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.ai-workbench-empty {
  display: grid;
  gap: 8px;
  padding: 14px;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-workbench-empty strong {
  color: #172033;
  font-size: 14px;
}

.ai-workbench-empty p {
  margin: 0;
}

.ai-workbench-title {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 8px;
  padding: 12px;
}

.ai-workbench-title__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.ai-workbench-title__row strong {
  min-width: 0;
}

.ai-workbench-status {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  border: 1px solid #bbf7d0;
  border-radius: 6px;
  background: #f0fdf4;
  padding: 2px 6px;
  color: #047857;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.4;
}

.ai-workbench-generated {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 7px;
}

.ai-workbench-generated [data-slot="badge"] {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #047857;
}

.ai-workbench-title strong,
.ai-workbench-title small {
  display: block;
}

.ai-workbench-title strong {
  color: #172033;
  font-size: 14px;
}

.ai-workbench-title small {
  margin-top: 4px;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.ai-workbench-lines,
.ai-workbench-sections {
  display: grid;
  gap: 10px;
}

.ai-workbench-lines article {
  display: grid;
  gap: 10px;
  padding: 12px;
}

.ai-workbench-line__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ai-workbench-line__top strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-workbench-fields {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 8px;
}

.ai-workbench-field-wide {
  grid-column: 1 / -1;
}

.ai-workbench .ai-field {
  gap: 5px;
}

.ai-workbench .ai-field label {
  color: #667085;
  font-size: 12px;
}

.ai-workbench .ai-field input,
.ai-workbench .ai-field textarea {
  font-size: 12px;
}

.ai-workbench .ai-field input {
  height: 32px;
}

.ai-workbench-sections section {
  display: grid;
  gap: 10px;
  padding: 12px;
}

.ai-mini-line-chart {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 96px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px;
}

.ai-mini-line-chart span {
  position: relative;
  width: 100%;
  min-width: 0;
  border-radius: 999px 999px 4px 4px;
  background: #2563eb;
}

.ai-mini-line-chart em {
  position: absolute;
  right: 50%;
  bottom: -20px;
  transform: translateX(50%);
  color: #667085;
  font-size: 10px;
  font-style: normal;
}

.ai-mini-pie-chart {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-mini-pie-chart span {
  display: grid;
  gap: 3px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfcfe;
  padding: 9px;
}

.ai-mini-pie-chart strong {
  color: #172033;
  font-size: 16px;
}

.ai-mini-pie-chart small {
  color: #667085;
  font-size: 11px;
}

.ai-workbench-sections h3 {
  margin: 0;
  color: #172033;
  font-size: 13px;
}

.ai-workbench-sections ul {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 18px;
  color: #475467;
  font-size: 12px;
  line-height: 1.6;
}

.ai-workbench-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-workbench-actions [data-slot="button"] {
  min-width: 0;
  padding-inline: 8px;
  white-space: nowrap;
}

@keyframes ai-thinking {
  0%,
  80%,
  100% {
    opacity: 0.35;
    transform: translateY(0);
  }

  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

@keyframes ai-sidebar-collapsible-down {
  from {
    height: 0;
  }

  to {
    height: var(--reka-collapsible-content-height);
  }
}

@keyframes ai-sidebar-collapsible-up {
  from {
    height: var(--reka-collapsible-content-height);
  }

  to {
    height: 0;
  }
}

@media (max-width: 980px) {
  .ai-agent-body,
  .ai-agent-shell.is-collapsed .ai-agent-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .ai-agent-sidebar {
    display: none;
  }

  .ai-agent-main {
    padding: 20px;
  }

  .ai-chat-bubble {
    max-width: calc(100% - 42px);
  }

  .ai-workbench {
    border-top: 1px solid var(--border);
    border-left: 0;
  }
}
</style>
