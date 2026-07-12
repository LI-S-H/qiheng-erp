<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { toast } from 'vue-sonner';
import {
  BarChart3,
  CalendarDays,
  ClipboardList,
  Edit3,
  FileText,
  Plus,
  Play,
  RefreshCw,
} from 'lucide-vue-next';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import MultiSelect from '@/components/common/MultiSelect.vue';
import RemoteSearchSelect, { type RemoteSearchOption } from '@/components/common/RemoteSearchSelect.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Switch } from '@/components/ui/switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { listProducts } from '@/modules/product/products/api';
import { listWarehouses } from '@/modules/warehouse/warehouses/api';
import { listRoleOptions } from '@/modules/system/roles/api';
import AiChartCard from '../components/AiChartCard.vue';
import AiFadeSwitch from '../components/AiFadeSwitch.vue';
import { useAiChartGeometry } from '../composables/use-ai-chart-geometry';
import {
  createAiScheduledTask,
  getAiScheduledTaskPage,
  runAiScheduledTask,
  updateAiScheduledTask,
  updateAiScheduledTaskStatus,
} from '../api';
import type {
  AiChartSpec,
  AiScheduledTask,
  AiScheduledTaskCategory,
  AiScheduledTaskFrequency,
  AiScheduledTaskOutputFormat,
  AiScheduledTaskPage,
  AiScheduledTaskStatus,
  AiScheduledTaskTemplate,
  AiScheduledTaskUpdateRequest,
  AiTaskExecution,
} from '../types';

const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const actionTaskId = ref<string | null>(null);
const taskPage = ref<AiScheduledTaskPage | null>(null);
const selectedExecutionId = ref<string | null>(null);
const editingTask = ref<AiScheduledTask | null>(null);
const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const productPickerValue = ref('');
const warehousePickerValue = ref('');
const productPickerKey = ref(0);
const warehousePickerKey = ref(0);
const selectedArchiveDate = ref<string | null>(null);
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

const recipientOptions = ref<Array<{ value: string; label: string }>>([]);
const productLabels = ref<Record<string, string>>({ ALL: '全部商品' });
const warehouseLabels = ref<Record<string, string>>({ ALL: '全部仓库' });

const categoryOptions: Array<{ value: AiScheduledTaskCategory; label: string }> = [
  { value: 'REPORT', label: '经营报告' },
  { value: 'INVENTORY', label: '库存分析' },
  { value: 'PURCHASE', label: '采购建议' },
  { value: 'SALES', label: '销量预测' },
  { value: 'SUPPLIER', label: '供应商' },
];
const frequencyOptions: Array<{ value: AiScheduledTaskFrequency; label: string }> = [
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' },
];
const outputFormatOptions: Array<{ value: AiScheduledTaskOutputFormat; label: string }> = [
  { value: 'REPORT', label: '页面报告' },
  { value: 'CHAT_CARD', label: '对话卡片' },
];

const editForm = reactive<AiScheduledTaskUpdateRequest>({
  taskName: '',
  category: 'REPORT',
  frequency: 'DAILY',
  cronExpression: '',
  productIds: [],
  warehouseIds: [],
  recipientRoleIds: [],
  analysisGoal: '',
  outputFormat: 'REPORT',
});

const selectedExecution = computed(() => {
  if (!taskPage.value) return null;
  return taskPage.value.recentExecutions.find(item => item.executionId === selectedExecutionId.value) || taskPage.value.recentExecutions[0] || null;
});
const archiveGroups = computed(() => {
  if (!taskPage.value) return [];
  const groups = new Map<string, AiTaskExecution[]>();
  taskPage.value.recentExecutions.forEach(execution => {
    const date = execution.startedAt.slice(0, 10);
    groups.set(date, [...(groups.get(date) || []), execution]);
  });
  return Array.from(groups.entries()).map(([date, executions]) => ({
    date,
    executions,
  }));
});
const visibleArchiveGroups = computed(() => {
  if (!selectedArchiveDate.value) return archiveGroups.value;
  return archiveGroups.value.filter(group => group.date === selectedArchiveDate.value);
});
const selectedArchiveDateLabel = computed(() => selectedArchiveDate.value || '全部日期');
const archiveCalendarDays = computed(() => {
  const dates = archiveGroups.value.map(group => group.date);
  if (dates.length === 0) return [];
  const monthSet = new Set(dates.map(date => date.slice(0, 7)));
  return Array.from(monthSet).map(month => ({
    month,
    days: dates
      .filter(date => date.startsWith(month))
      .map(date => ({
        date,
        day: date.slice(8, 10),
        count: archiveGroups.value.find(group => group.date === date)?.executions.length || 0,
      })),
  }));
});

async function loadTaskPage() {
  loading.value = true;
  try {
    taskPage.value = await getAiScheduledTaskPage();
    selectedExecutionId.value = taskPage.value.recentExecutions[0]?.executionId || null;
    selectedArchiveDate.value = taskPage.value.recentExecutions[0]?.startedAt.slice(0, 10) || null;
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '经营任务中心加载失败');
  } finally {
    loading.value = false;
  }
}

function categoryText(category: AiScheduledTaskCategory) {
  return categoryOptions.find(item => item.value === category)?.label || category;
}

function frequencyText(frequency: AiScheduledTaskFrequency) {
  return frequencyOptions.find(item => item.value === frequency)?.label || frequency;
}

function statusText(status: AiScheduledTaskStatus) {
  if (status === 'DISABLED') return '已停用';
  if (status === 'RUNNING') return '执行中';
  if (status === 'FAILED') return '失败';
  return '已启用';
}

function statusClass(status: AiScheduledTaskStatus) {
  if (status === 'FAILED') return 'border-rose-200 bg-rose-50 text-rose-700';
  if (status === 'DISABLED') return 'border-slate-200 bg-slate-50 text-slate-600';
  if (status === 'RUNNING') return 'border-amber-200 bg-amber-50 text-amber-700';
  return 'border-emerald-200 bg-emerald-50 text-emerald-700';
}

function metricClass(tone: string) {
  if (tone === 'risk') return 'is-risk';
  if (tone === 'watch') return 'is-watch';
  if (tone === 'good') return 'is-good';
  return 'is-neutral';
}

function executionConversationText(execution: AiTaskExecution) {
  return [
    execution.resultSummary,
    execution.findings.join(' '),
    execution.suggestions.join(' '),
  ].filter(Boolean).join(' ');
}

const {
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
} = useAiChartGeometry(chartPalette, chartFrame);

function formatChartTick(value: number) {
  if (Math.abs(value) >= 10000) return `${(value / 10000).toFixed(1).replace(/\.0$/, '')}万`;
  if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(1).replace(/\.0$/, '')}k`;
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '');
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
    value: item.productId,
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
    value: item.warehouseId,
    label: `${item.warehouseCode} ${item.warehouseName}`,
  }));
}

function addUniqueValue(list: string[], value: string) {
  const normalized = value.trim();
  if (!normalized || list.includes(normalized)) return list;
  if (normalized !== 'ALL' && list.includes('ALL')) return [normalized];
  return [...list, normalized];
}

function addProduct(option: RemoteSearchOption) {
  const productId = String(option.value);
  productLabels.value[productId] = option.label;
  editForm.productIds = addUniqueValue(editForm.productIds, productId);
  productPickerValue.value = '';
  productPickerKey.value += 1;
}

function addWarehouse(option: RemoteSearchOption) {
  const warehouseId = String(option.value);
  warehouseLabels.value[warehouseId] = option.label;
  editForm.warehouseIds = addUniqueValue(editForm.warehouseIds, warehouseId);
  warehousePickerValue.value = '';
  warehousePickerKey.value += 1;
}

function addAllProducts() {
  editForm.productIds = ['ALL'];
  productPickerKey.value += 1;
}

function addAllWarehouses() {
  editForm.warehouseIds = ['ALL'];
  warehousePickerKey.value += 1;
}

function removeProduct(product: string) {
  editForm.productIds = editForm.productIds.filter(item => item !== product);
}

function removeWarehouse(warehouse: string) {
  editForm.warehouseIds = editForm.warehouseIds.filter(item => item !== warehouse);
}

function resetForm() {
  editForm.taskName = '';
  editForm.category = 'REPORT';
  editForm.frequency = 'DAILY';
  editForm.cronExpression = '0 0 9 * * ?';
  editForm.productIds = ['ALL'];
  editForm.warehouseIds = ['ALL'];
  editForm.recipientRoleIds = recipientOptions.value.slice(0, 1).map(item => item.value);
  editForm.analysisGoal = '';
  editForm.outputFormat = 'REPORT';
}

function fillFromTemplate(template: AiScheduledTaskTemplate) {
  editForm.taskName = template.taskName;
  editForm.category = template.category;
  editForm.frequency = template.frequency;
  editForm.cronExpression = template.defaultCronExpression;
  editForm.productIds = ['ALL'];
  editForm.warehouseIds = ['ALL'];
  editForm.recipientRoleIds = recipientOptions.value.slice(0, 1).map(item => item.value);
  editForm.analysisGoal = template.defaultAnalysisGoal;
  editForm.outputFormat = template.outputFormat;
}

function openCreate(template?: AiScheduledTaskTemplate) {
  formMode.value = 'create';
  editingTask.value = null;
  resetForm();
  if (template) fillFromTemplate(template);
  formVisible.value = true;
}

function openEdit(task: AiScheduledTask) {
  formMode.value = 'edit';
  editingTask.value = task;
  editForm.taskName = task.taskName;
  editForm.category = task.category;
  editForm.frequency = task.frequency;
  editForm.cronExpression = task.cronExpression;
  editForm.productIds = [...task.productIds];
  editForm.warehouseIds = [...task.warehouseIds];
  editForm.recipientRoleIds = [...task.recipientRoleIds];
  task.productIds.forEach((id, index) => {
    productLabels.value[id] = task.productScope[index] || productLabels.value[id] || id;
  });
  task.warehouseIds.forEach((id, index) => {
    warehouseLabels.value[id] = task.warehouseScope[index] || warehouseLabels.value[id] || id;
  });
  editForm.analysisGoal = task.analysisGoal;
  editForm.outputFormat = task.outputFormat;
  formVisible.value = true;
}

function closeForm() {
  if (saving.value) return;
  formVisible.value = false;
}

function validateForm() {
  if (!editForm.taskName.trim()) return '请填写任务名称';
  if (!editForm.cronExpression.trim()) return '请填写 Cron 表达式';
  if (editForm.productIds.length === 0) return '请选择关注商品';
  if (editForm.warehouseIds.length === 0) return '请选择关注仓库';
  if (editForm.recipientRoleIds.length === 0) return '请选择接收角色';
  if (!editForm.analysisGoal.trim()) return '请填写分析目标';
  return '';
}

async function saveForm() {
  const error = validateForm();
  if (error) {
    toast.warning(error);
    return;
  }
  const payload: AiScheduledTaskUpdateRequest = {
    ...editForm,
    taskName: editForm.taskName.trim(),
    cronExpression: editForm.cronExpression.trim(),
    analysisGoal: editForm.analysisGoal.trim(),
  };

  saving.value = true;
  try {
    const updated = formMode.value === 'create'
      ? await createAiScheduledTask(payload)
      : await updateAiScheduledTask(editingTask.value?.taskId || '', payload);
    if (taskPage.value) {
      if (formMode.value === 'create') {
        taskPage.value.tasks = [updated, ...taskPage.value.tasks];
      } else {
        taskPage.value.tasks = taskPage.value.tasks.map(item => item.taskId === updated.taskId ? updated : item);
      }
      taskPage.value.summary.totalCount = taskPage.value.tasks.length;
      taskPage.value.summary.enabledCount = taskPage.value.tasks.filter(item => item.status === 'ENABLED' || item.status === 'RUNNING').length;
      taskPage.value.summary.nextRunCount = taskPage.value.tasks.filter(item => item.status === 'ENABLED').length;
    }
    toast.success(formMode.value === 'create' ? '任务已新增' : '任务配置已更新');
    formVisible.value = false;
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '任务保存失败');
  } finally {
    saving.value = false;
  }
}

async function toggleTask(task: AiScheduledTask, checked: boolean) {
  const nextStatus: AiScheduledTaskStatus = checked ? 'ENABLED' : 'DISABLED';
  actionTaskId.value = task.taskId;
  try {
    const updated = await updateAiScheduledTaskStatus(task.taskId, nextStatus);
    if (taskPage.value) {
      taskPage.value.tasks = taskPage.value.tasks.map(item => item.taskId === updated.taskId ? updated : item);
      taskPage.value.summary.enabledCount = taskPage.value.tasks.filter(item => item.status === 'ENABLED' || item.status === 'RUNNING').length;
      taskPage.value.summary.nextRunCount = taskPage.value.tasks.filter(item => item.status === 'ENABLED').length;
    }
    toast.success(`${updated.taskName}已${checked ? '启用' : '停用'}`);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '任务状态更新失败');
  } finally {
    actionTaskId.value = null;
  }
}

async function runTask(task: AiScheduledTask) {
  actionTaskId.value = task.taskId;
  try {
    await runAiScheduledTask(task.taskId);
    await loadTaskPage();
    toast.success(`${task.taskName}已执行`);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '任务执行失败');
  } finally {
    actionTaskId.value = null;
  }
}

function selectLatestResult(task: AiScheduledTask) {
  const execution = taskPage.value?.recentExecutions.find(item => item.taskId === task.taskId);
  if (execution) {
    selectedExecutionId.value = execution.executionId;
    selectedArchiveDate.value = execution.startedAt.slice(0, 10);
    toast.success(`已定位到 ${execution.startedAt.slice(0, 10)} 的${execution.taskName}结果`);
    return;
  }
  toast.info('这个任务还没有执行结果');
}

function selectArchiveExecution(execution: AiTaskExecution) {
  selectedExecutionId.value = execution.executionId;
  selectedArchiveDate.value = execution.startedAt.slice(0, 10);
}

function navigate(route: string | null) {
  if (route) router.push(route);
}

onMounted(async () => {
  try {
    recipientOptions.value = (await listRoleOptions()).map(role => ({ value: role.roleId, label: role.roleName }));
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '接收角色加载失败');
  }
  await loadTaskPage();
});
</script>

<template>
  <section class="page-shell ai-task-page space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">经营任务中心</h1>
        <p class="page-description">配置多智能体定时任务，支持自定义新增、指定商品仓库，并在页面内阅读完整执行报告</p>
      </div>
      <div class="ai-heading-actions">
        <Button size="sm" variant="outline" :disabled="loading" @click="loadTaskPage">
          <RefreshCw class="mr-2 h-4 w-4" :class="{ 'animate-spin': loading }" />
          刷新
        </Button>
        <Button size="sm" @click="openCreate()">
          <Plus class="mr-2 h-4 w-4" />
          新增任务
        </Button>
      </div>
    </div>

    <div class="relative">
      <ListLoadingOverlay :visible="loading" />

      <div v-if="taskPage" class="ai-task-workspace">
        <div class="summary-strip">
          <div class="summary-item">
            <span>任务总数</span>
            <strong>{{ taskPage.summary.totalCount }}</strong>
          </div>
          <div class="summary-item">
            <span>已启用</span>
            <strong>{{ taskPage.summary.enabledCount }}</strong>
          </div>
          <div class="summary-item">
            <span>待执行</span>
            <strong>{{ taskPage.summary.nextRunCount }}</strong>
          </div>
          <div class="summary-item">
            <span>失败任务</span>
            <strong>{{ taskPage.summary.failedCount }}</strong>
          </div>
        </div>

        <div class="ai-template-row">
          <article v-for="template in taskPage.templates" :key="template.templateId">
            <div>
              <Badge variant="outline">{{ categoryText(template.category) }}</Badge>
              <strong>{{ template.taskName }}</strong>
              <span>{{ template.description }}</span>
            </div>
            <Button size="sm" variant="outline" @click="openCreate(template)">使用模板</Button>
          </article>
        </div>

        <div class="data-panel">
          <div class="table-toolbar">
            <div class="table-toolbar__title">
              <ClipboardList class="h-4 w-4 text-primary" />
              <strong>任务配置</strong>
            </div>
            <span class="text-xs text-muted-foreground">采购、库存、销量任务建议指定商品和仓库范围</span>
          </div>

          <div class="ai-task-table-wrap">
            <Table class="business-data-table">
              <colgroup>
                <col style="width: 16%" />
                <col style="width: 8%" />
                <col style="width: 8%" />
                <col style="width: 19%" />
                <col style="width: 17%" />
                <col style="width: 13%" />
                <col style="width: 19%" />
              </colgroup>
              <TableHeader>
                <TableRow>
                  <TableHead>任务</TableHead>
                  <TableHead>类型</TableHead>
                  <TableHead>频率</TableHead>
                  <TableHead>商品范围</TableHead>
                  <TableHead>仓库范围</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow v-for="task in taskPage.tasks" :key="task.taskId">
                  <TableCell>
                    <Tooltip>
                      <TooltipTrigger as-child>
                        <button type="button" class="ai-task-title">
                          {{ task.taskName }}
                        </button>
                      </TooltipTrigger>
                      <TooltipContent class="max-w-[360px]">
                        <p>{{ task.analysisGoal }}</p>
                      </TooltipContent>
                    </Tooltip>
                  </TableCell>
                  <TableCell>{{ categoryText(task.category) }}</TableCell>
                  <TableCell>{{ frequencyText(task.frequency) }}</TableCell>
                  <TableCell>
                    <div class="ai-tag-list">
                      <Badge v-for="product in task.productScope.slice(0, 3)" :key="product" variant="outline">{{ product }}</Badge>
                      <small v-if="task.productScope.length > 3">+{{ task.productScope.length - 3 }}</small>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div class="ai-tag-list">
                      <Badge v-for="warehouse in task.warehouseScope.slice(0, 2)" :key="warehouse" variant="outline">{{ warehouse }}</Badge>
                      <small v-if="task.warehouseScope.length > 2">+{{ task.warehouseScope.length - 2 }}</small>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div class="ai-status-cell">
                      <Badge variant="outline" :class="statusClass(task.status)">{{ statusText(task.status) }}</Badge>
                      <Switch
                        :model-value="task.status === 'ENABLED' || task.status === 'RUNNING'"
                        :disabled="actionTaskId === task.taskId"
                        @update:model-value="toggleTask(task, Boolean($event))"
                      />
                    </div>
                  </TableCell>
                  <TableCell>
                    <div class="ai-task-actions">
                      <Button size="sm" variant="outline" :disabled="!task.editable" @click="openEdit(task)">
                        <Edit3 class="mr-1 h-3.5 w-3.5" />
                        编辑
                      </Button>
                      <Button size="sm" variant="outline" :disabled="actionTaskId === task.taskId || task.status === 'DISABLED'" @click="runTask(task)">
                        <Play class="mr-1 h-3.5 w-3.5" />
                        执行
                      </Button>
                      <Button size="sm" variant="outline" @click="selectLatestResult(task)">
                        <FileText class="mr-1 h-3.5 w-3.5" />
                        结果
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
        </div>

        <section class="ai-result-board">
          <aside class="ai-result-archive">
            <div class="ai-result-archive__head">
              <strong>任务结果记录</strong>
              <small>按日期查看每日任务和手动执行结果</small>
            </div>
            <div class="ai-date-filter">
              <Button type="button" size="sm" variant="outline" :class="{ 'is-active': !selectedArchiveDate }" @click="selectedArchiveDate = null">全部日期</Button>
              <Popover>
                <PopoverTrigger as-child>
                  <Button type="button" size="sm" variant="outline" class="ai-date-filter__trigger">
                    <CalendarDays class="h-4 w-4" />
                    {{ selectedArchiveDateLabel }}
                  </Button>
                </PopoverTrigger>
                <PopoverContent align="start" class="w-72 p-3">
                  <div class="ai-calendar-panel">
                    <section v-for="month in archiveCalendarDays" :key="month.month">
                      <strong>{{ month.month }}</strong>
                      <div class="ai-calendar-grid">
                        <button
                          v-for="day in month.days"
                          :key="day.date"
                          type="button"
                          :class="{ 'is-active': selectedArchiveDate === day.date }"
                          @click="selectedArchiveDate = day.date"
                        >
                          <span>{{ day.day }}</span>
                          <small>{{ day.count }} 条</small>
                        </button>
                      </div>
                    </section>
                  </div>
                </PopoverContent>
              </Popover>
            </div>
            <div v-if="false" class="ai-date-filter">
              <button type="button" :class="{ 'is-active': !selectedArchiveDate }" @click="selectedArchiveDate = null">全部日期</button>
              <button
                v-for="group in archiveGroups"
                :key="group.date"
                type="button"
                :class="{ 'is-active': selectedArchiveDate === group.date }"
                @click="selectedArchiveDate = group.date"
              >
                {{ group.date }}
              </button>
            </div>
            <AiFadeSwitch :switch-key="selectedArchiveDate || 'all'" content-class="ai-archive-list">
              <section v-for="group in visibleArchiveGroups" :key="group.date">
                <h3>{{ group.date }}</h3>
                <button
                  v-for="execution in group.executions"
                  :key="execution.executionId"
                  type="button"
                  :class="{ 'is-active': selectedExecutionId === execution.executionId }"
                  @click="selectArchiveExecution(execution)"
                >
                  <FileText class="h-4 w-4" />
                  <span>
                    <strong>{{ execution.taskName }}</strong>
                    <small>{{ execution.startedAt.slice(11, 19) }} · {{ execution.status === 'SUCCESS' ? '成功' : execution.status === 'RUNNING' ? '执行中' : '失败' }}</small>
                  </span>
                </button>
              </section>
            </AiFadeSwitch>
          </aside>

          <article class="ai-result-card">
            <Transition name="ai-result-switch" mode="out-in">
            <div :key="selectedExecution?.executionId || 'empty'" class="ai-result-card__inner">
            <div class="ai-result-card__head">
              <div>
                <span>任务结果详情</span>
                <strong>{{ selectedExecution?.taskName || '暂无执行结果' }}</strong>
              </div>
              <Badge v-if="selectedExecution" variant="outline" :class="selectedExecution.status === 'FAILED' ? 'border-rose-200 bg-rose-50 text-rose-700' : 'border-emerald-200 bg-emerald-50 text-emerald-700'">
                {{ selectedExecution.status === 'FAILED' ? '失败' : selectedExecution.status === 'RUNNING' ? '执行中' : '成功' }}
              </Badge>
            </div>

            <template v-if="selectedExecution">
              <div class="ai-result-metrics">
                <div v-for="metric in selectedExecution.metrics" :key="metric.label" :class="metricClass(metric.tone)">
                  <span>{{ metric.label }}</span>
                  <strong>{{ metric.value }}</strong>
                </div>
              </div>

              <section class="ai-execution-conversation">
                <div class="ai-execution-avatar">
                  <ClipboardList class="h-4 w-4" />
                </div>
                <div class="ai-execution-bubble">
                  <div class="ai-execution-bubble__meta">
                    <strong>智能体汇报</strong>
                    <span>{{ selectedExecution.startedAt }}</span>
                  </div>
                  <p>{{ executionConversationText(selectedExecution) }}</p>
                  <div v-if="selectedExecution?.charts.length" class="ai-execution-charts">
                    <AiChartCard v-for="chart in selectedExecution?.charts || []" :key="chart.chartId" :chart="chart" />
                  </div>
                  <div v-if="false" class="ai-execution-charts">
                    <article v-for="chart in selectedExecution?.charts || []" :key="chart.chartId" class="ai-execution-chart">
                      <div class="ai-execution-chart__head">
                        <BarChart3 class="h-4 w-4 text-primary" />
                        <strong>{{ chart.title }}</strong>
                        <Badge variant="outline">{{ chart.type === 'line' ? '折线图' : chart.type === 'bar' ? '柱状图' : '饼图' }}</Badge>
                      </div>
                      <p v-if="chart.description" class="ai-execution-chart__desc">{{ chart.description }}</p>
                      <div v-if="chart.type === 'line'" class="ai-execution-line-chart">
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
                      <div v-else-if="chart.type === 'bar'" class="ai-execution-bar-chart">
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
                      <div v-else class="ai-execution-pie-chart">
                        <span v-for="row in chart.data" :key="chartLabel(chart, row)">
                          <strong>{{ chartNumber(row, chart.valueField) }}</strong>
                          <small>{{ chartLabel(chart, row) }}</small>
                        </span>
                      </div>
                    </article>
                  </div>
                </div>
              </section>

              <div v-if="selectedExecution.nextActions.length" class="ai-result-actions">
                <button v-for="action in selectedExecution.nextActions" :key="action.actionId" type="button" @click="navigate(action.route)">
                  <BarChart3 class="h-4 w-4" />
                  <span>
                    <strong>{{ action.title }}</strong>
                    <small>{{ action.description }}</small>
                  </span>
                </button>
              </div>
            </template>
            <div v-else class="text-sm text-muted-foreground">暂无任务执行结果。</div>
            </div>
            </Transition>
          </article>
        </section>
      </div>
    </div>

    <Dialog :open="formVisible" @update:open="open => !open && closeForm()">
      <DialogContent class="ai-edit-dialog !w-[min(860px,calc(100vw-2rem))] !max-w-[min(860px,calc(100vw-2rem))]">
        <DialogHeader>
          <DialogTitle>{{ formMode === 'create' ? '新增经营任务' : '编辑经营任务' }}</DialogTitle>
          <DialogDescription>选择商品、仓库和接收人，任务执行后会在本页生成完整报告。</DialogDescription>
        </DialogHeader>

        <DialogScrollArea class="max-h-[min(68vh,620px)]">
          <div class="ai-edit-form">
            <div class="ai-field">
              <Label for="taskName">任务名称 <span class="text-destructive">*</span></Label>
              <Input id="taskName" v-model="editForm.taskName" />
            </div>
            <div class="ai-field">
              <Label>任务类型</Label>
              <AnchoredSelect v-model="editForm.category" :options="categoryOptions" />
            </div>
            <div class="ai-field ai-field--wide">
              <Label>关注商品 <span class="text-destructive">*</span></Label>
              <div class="ai-search-picker">
                <div class="ai-selected-tags">
                  <Badge v-for="product in editForm.productIds" :key="product" variant="outline">
                    {{ productLabels[product] || product }}
                    <button type="button" aria-label="移除商品" @click="removeProduct(product)">×</button>
                  </Badge>
                  <span v-if="editForm.productIds.length === 0">请搜索并添加商品</span>
                </div>
                <div class="ai-search-picker__row">
                  <RemoteSearchSelect
                    :key="productPickerKey"
                    v-model="productPickerValue"
                    placeholder="输入商品编码或名称搜索"
                    search-placeholder="输入商品编码或名称"
                    empty-text="暂无匹配商品"
                    :fetch-options="fetchProductOptions"
                    @select="addProduct"
                  />
                  <Button type="button" variant="outline" class="ai-search-picker__all" @click="addAllProducts">全部商品</Button>
                </div>
              </div>
            </div>
            <div class="ai-field ai-field--wide">
              <Label>关注仓库 <span class="text-destructive">*</span></Label>
              <div class="ai-search-picker">
                <div class="ai-selected-tags">
                  <Badge v-for="warehouse in editForm.warehouseIds" :key="warehouse" variant="outline">
                    {{ warehouseLabels[warehouse] || warehouse }}
                    <button type="button" aria-label="移除仓库" @click="removeWarehouse(warehouse)">×</button>
                  </Badge>
                  <span v-if="editForm.warehouseIds.length === 0">请搜索并添加仓库</span>
                </div>
                <div class="ai-search-picker__row">
                  <RemoteSearchSelect
                    :key="warehousePickerKey"
                    v-model="warehousePickerValue"
                    placeholder="输入仓库编码或名称搜索"
                    search-placeholder="输入仓库编码或名称"
                    empty-text="暂无匹配仓库"
                    :fetch-options="fetchWarehouseOptions"
                    @select="addWarehouse"
                  />
                  <Button type="button" variant="outline" class="ai-search-picker__all" @click="addAllWarehouses">全部仓库</Button>
                </div>
              </div>
            </div>
            <div class="ai-field">
              <Label>执行频率</Label>
              <AnchoredSelect v-model="editForm.frequency" :options="frequencyOptions" />
            </div>
            <div class="ai-field">
              <Label for="cronExpression">Cron 表达式 <span class="text-destructive">*</span></Label>
              <Input id="cronExpression" v-model="editForm.cronExpression" />
            </div>
            <div class="ai-field">
              <Label>接收角色 <span class="text-destructive">*</span></Label>
              <MultiSelect v-model="editForm.recipientRoleIds" :options="recipientOptions" placeholder="选择接收角色" />
            </div>
            <div class="ai-field">
              <Label>输出形式</Label>
              <AnchoredSelect v-model="editForm.outputFormat" :options="outputFormatOptions" />
            </div>
            <div class="ai-field ai-field--wide">
              <Label for="analysisGoal">分析目标 <span class="text-destructive">*</span></Label>
              <Textarea id="analysisGoal" v-model="editForm.analysisGoal" class="min-h-[112px]" />
            </div>
          </div>
        </DialogScrollArea>

        <DialogFooter>
          <Button variant="outline" :disabled="saving" @click="closeForm">取消</Button>
          <Button :disabled="saving" @click="saveForm">{{ saving ? '保存中' : '保存' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </section>
</template>

<style scoped>
.ai-heading-actions {
  display: inline-flex;
  gap: 10px;
}

.ai-task-workspace {
  display: grid;
  gap: 16px;
}

.ai-template-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.ai-template-row article,
.ai-result-board,
.ai-result-card {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.ai-template-row article {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
}

.ai-template-row article > div {
  display: grid;
  gap: 7px;
  min-width: 0;
}

.ai-template-row strong {
  color: #172033;
}

.ai-template-row span {
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.ai-task-table-wrap {
  max-height: 330px;
  overflow: auto;
}

.ai-task-table-wrap [data-slot="table"] {
  min-width: 1180px;
  table-layout: fixed;
}

.ai-task-table-wrap [data-slot="table-head"],
.ai-task-table-wrap [data-slot="table-cell"] {
  height: 48px;
  padding: 8px 10px;
  font-size: 12px;
  text-align: center;
  vertical-align: middle;
}

.ai-task-title {
  display: inline-block;
  max-width: 100%;
  border: 0;
  background: transparent;
  color: #172033;
  font-size: 13px;
  font-weight: 700;
  text-align: center;
  cursor: help;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-task-title:hover {
  color: var(--primary);
}

.ai-tag-list,
.ai-status-cell,
.ai-task-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  flex-wrap: wrap;
  max-width: 100%;
}

.ai-tag-list [data-slot="badge"] {
  max-width: 156px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-tag-list small {
  color: #667085;
  font-size: 12px;
}

.ai-task-actions {
  flex-wrap: nowrap;
  gap: 6px;
  min-width: max-content;
}

.ai-task-actions [data-slot="button"] {
  height: 30px;
  min-width: 52px;
  padding-inline: 8px;
  white-space: nowrap;
}

.ai-result-board {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 0;
  overflow: hidden;
}

.ai-result-archive {
  display: grid;
  align-content: start;
  gap: 12px;
  border-right: 1px solid var(--border);
  background: #f8fafc;
  padding: 12px;
}

.ai-result-archive__head {
  display: grid;
  gap: 3px;
}

.ai-result-archive__head strong {
  color: #172033;
  font-size: 14px;
}

.ai-result-archive__head small {
  color: #667085;
  font-size: 12px;
}

.ai-date-filter {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.ai-date-filter button {
  flex: 0 0 auto;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: white;
  padding: 5px 9px;
  color: #475467;
  font-size: 12px;
  cursor: pointer;
}

.ai-date-filter button.is-active {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.ai-date-filter__trigger {
  gap: 6px;
  min-width: 132px;
  justify-content: flex-start;
}

.ai-calendar-panel {
  display: grid;
  gap: 12px;
  animation: ai-task-popover-in 160ms cubic-bezier(0.16, 1, 0.3, 1) both;
}

.ai-calendar-panel section {
  display: grid;
  gap: 8px;
}

.ai-calendar-panel strong {
  color: #172033;
  font-size: 13px;
}

.ai-calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}

.ai-calendar-grid button {
  display: grid;
  gap: 2px;
  min-height: 44px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  padding: 5px 3px;
  color: #475467;
  text-align: center;
  cursor: pointer;
  transition: border-color 150ms ease, background 150ms ease, color 150ms ease, transform 150ms ease;
}

.ai-calendar-grid button:hover,
.ai-calendar-grid button.is-active {
  transform: translateY(-1px);
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
}

.ai-calendar-grid span {
  font-size: 13px;
  font-weight: 700;
}

.ai-calendar-grid small {
  color: #667085;
  font-size: 10px;
}

.ai-archive-list {
  display: grid;
  gap: 12px;
  max-height: 520px;
  overflow: auto;
  padding-right: 4px;
}

.ai-archive-list section {
  display: grid;
  gap: 7px;
}

.ai-archive-list h3 {
  margin: 0;
  color: #667085;
  font-size: 12px;
}

.ai-archive-list button {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  padding: 9px;
  color: #475467;
  text-align: left;
  cursor: pointer;
}

.ai-archive-list button.is-active {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.ai-archive-list strong,
.ai-archive-list small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-archive-list strong {
  color: #172033;
  font-size: 12px;
}

.ai-archive-list small {
  color: #667085;
  font-size: 11px;
}

.ai-result-tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  border-bottom: 1px solid var(--border);
  background: #f8fafc;
  padding: 10px;
}

.ai-result-tabs button {
  display: grid;
  grid-template-columns: 18px minmax(142px, 1fr);
  gap: 8px;
  align-items: center;
  min-width: 210px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  padding: 9px;
  color: #475467;
  text-align: left;
  cursor: pointer;
}

.ai-result-tabs button.is-active {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.ai-result-tabs strong {
  display: block;
  color: #172033;
  font-size: 12px;
}

.ai-result-tabs small {
  display: block;
  color: #667085;
  font-size: 11px;
}

.ai-result-card {
  display: grid;
  gap: 14px;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  padding: 16px;
}

.ai-result-card__inner {
  display: grid;
  gap: 14px;
}

.ai-result-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ai-result-card__head span {
  display: block;
  color: #667085;
  font-size: 12px;
}

.ai-result-card__head strong {
  display: block;
  margin-top: 3px;
  color: #172033;
  font-size: 17px;
}

.ai-result-summary {
  margin: 0;
  color: #344054;
  line-height: 1.7;
}

.ai-result-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.ai-result-metrics div {
  display: grid;
  gap: 4px;
  border-right: 1px solid var(--border);
  padding: 11px 12px;
}

.ai-result-metrics div:last-child {
  border-right: 0;
}

.ai-result-metrics span {
  color: #667085;
  font-size: 12px;
}

.ai-result-metrics strong {
  color: #172033;
  font-size: 18px;
}

.ai-result-metrics .is-risk strong {
  color: #be123c;
}

.ai-result-metrics .is-watch strong {
  color: #b45309;
}

.ai-result-metrics .is-good strong {
  color: #047857;
}

.ai-execution-conversation {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 12px;
}

.ai-execution-avatar {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  background: #2563eb;
  color: white;
}

.ai-execution-bubble {
  min-width: 0;
}

.ai-execution-bubble__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ai-execution-bubble__meta strong {
  color: #172033;
  font-size: 13px;
}

.ai-execution-bubble__meta span {
  color: #667085;
  font-size: 12px;
}

.ai-execution-bubble p {
  margin: 7px 0 0;
  color: #344054;
  line-height: 1.8;
}

.ai-execution-charts {
  display: grid;
  gap: 10px;
  margin-top: 10px;
}

.ai-execution-chart {
  display: grid;
  gap: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-execution-chart__head {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.ai-execution-chart__head strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-execution-chart__desc {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}

.ai-execution-line-chart {
  display: grid;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-execution-line-chart svg {
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

.ai-execution-bar-chart {
  display: grid;
  gap: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-execution-bar-chart svg {
  width: 100%;
  height: 190px;
  overflow: visible;
  background: white;
}

.ai-chart-bar-layer rect {
  shape-rendering: geometricPrecision;
}

.ai-execution-pie-chart {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-execution-pie-chart span {
  display: grid;
  gap: 4px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 10px;
}

.ai-execution-pie-chart strong {
  color: #172033;
  font-size: 17px;
}

.ai-execution-pie-chart small {
  color: #667085;
  font-size: 11px;
}

.ai-history-panel {
  display: grid;
  gap: 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #f8fbff;
  padding: 12px;
}

.ai-history-panel__head {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.ai-history-panel__head strong,
.ai-history-panel__head small {
  display: block;
}

.ai-history-panel__head strong {
  color: #172033;
  font-size: 13px;
}

.ai-history-panel__head small {
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
}

.ai-history-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.ai-history-grid div {
  display: grid;
  gap: 4px;
  min-width: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  padding: 9px;
}

.ai-history-grid span {
  color: #667085;
  font-size: 12px;
}

.ai-history-grid p {
  display: -webkit-box;
  overflow: hidden;
  min-height: 40px;
  margin: 0;
  color: #344054;
  font-size: 12px;
  line-height: 1.7;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.ai-result-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.ai-result-actions button {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
  padding: 10px;
  text-align: left;
  cursor: pointer;
}

.ai-result-actions strong,
.ai-result-actions small {
  display: block;
}

.ai-result-actions strong {
  color: #172033;
  font-size: 13px;
}

.ai-result-actions small {
  color: #667085;
  font-size: 12px;
}

.ai-result-switch-enter-active,
.ai-result-switch-leave-active {
  transition: opacity 190ms ease, transform 190ms ease;
}

.ai-result-switch-enter-from,
.ai-result-switch-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@keyframes ai-task-popover-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.ai-suggestion-pool {
  display: grid;
  gap: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  padding: 14px;
}

.ai-suggestion-pool__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ai-suggestion-pool__head > div:first-child {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 8px;
  min-width: 0;
}

.ai-suggestion-pool__head strong,
.ai-suggestion-pool__head small {
  display: block;
}

.ai-suggestion-pool__head strong {
  color: #172033;
  font-size: 15px;
}

.ai-suggestion-pool__head small {
  margin-top: 3px;
  color: #667085;
  font-size: 12px;
}

.ai-suggestion-stats {
  display: inline-flex;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: wrap;
}

.ai-suggestion-list {
  display: grid;
  gap: 8px;
}

.ai-suggestion-list article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(340px, auto);
  gap: 10px;
  align-items: center;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fbfcfe;
  padding: 10px;
}

.ai-suggestion-main {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.ai-suggestion-main [data-slot="badge"] {
  width: fit-content;
}

.ai-suggestion-main strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-suggestion-main small {
  color: #667085;
  font-size: 12px;
}

.ai-suggestion-actions {
  display: inline-flex;
  justify-content: flex-end;
  gap: 6px;
}

.ai-suggestion-actions [data-slot="button"] {
  height: 30px;
  padding-inline: 8px;
  white-space: nowrap;
}

.ai-edit-dialog {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: min(860px, calc(100vw - 2rem)) !important;
  max-width: min(860px, calc(100vw - 2rem)) !important;
  max-height: calc(100dvh - 2rem);
  overflow: hidden;
}

.ai-edit-dialog [data-dialog-scroll-area] {
  height: min(620px, 68vh);
  min-height: 0;
  max-height: min(620px, 68vh);
}

.ai-edit-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.ai-field {
  display: grid;
  gap: 7px;
}

.ai-field--wide {
  grid-column: 1 / -1;
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

@media (max-width: 1180px) {
  .ai-template-row,
  .ai-result-metrics,
  .ai-result-actions,
  .ai-history-grid,
  .ai-result-board {
    grid-template-columns: minmax(0, 1fr);
  }

  .ai-result-archive {
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .ai-suggestion-list article {
    grid-template-columns: minmax(0, 1fr);
    align-items: stretch;
  }

  .ai-suggestion-actions {
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}

@media (max-width: 760px) {
  .ai-heading-actions,
  .ai-template-row article,
  .ai-suggestion-pool__head {
    align-items: stretch;
    flex-direction: column;
  }

  .ai-edit-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
