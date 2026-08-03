<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import BusinessDetailFacts from '@/components/common/BusinessDetailFacts.vue';
import BusinessDetailHero from '@/components/common/BusinessDetailHero.vue';
import BusinessDetailSection from '@/components/common/BusinessDetailSection.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListFilterActions from '@/components/common/ListFilterActions.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import ListSummaryStrip from '@/components/common/ListSummaryStrip.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import {
  batchDeleteCustomers,
  batchUpdateCustomerStatus,
  createCustomer,
  deleteCustomer,
  listCustomers,
  updateCustomer,
  updateCustomerStatus,
} from '../../api';
import type { CustomerFormPayload, CustomerListItem, CustomerQuery } from '../../types';

interface CustomerFormModel extends CustomerFormPayload {
  customerCode: string;
}

const statusOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

const customers = ref<CustomerListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingCustomerId = ref('');
const editingOriginalStatus = ref<0 | 1>(1);
const detailVisible = ref(false);
const detailRow = ref<CustomerListItem | null>(null);
let requestSequence = 0;

const query = reactive<CustomerQuery>({
  customerCode: '',
  customerName: '',
  contactName: '',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});

const form = reactive<CustomerFormModel>({
  customerCode: '',
  customerName: '',
  contactName: '',
  contactPhone: '',
  address: '',
  creditLimit: 0,
  status: 1,
  remark: '',
});
const formErrors = reactive<Record<string, string>>({});
const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'warning' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as () => void | Promise<void>,
});

const queryBusy = computed(() => loading.value || queryPending.value);
const enabledCount = computed(() => customers.value.filter(item => item.status === 1).length);
const disabledCount = computed(() => customers.value.filter(item => item.status === 0).length);
const creditTotal = computed(() => customers.value.reduce((sum, item) => sum + item.creditLimit, 0));
const highCreditCount = computed(() => customers.value.filter(item => item.creditLimit >= 100000).length);
const summaryItems = computed(() => [
  { key: 'enabled', label: '本页启用', value: enabledCount.value, tone: 'positive' as const },
  { key: 'disabled', label: '本页停用', value: disabledCount.value },
  { key: 'high-credit', label: '高信用客户', value: highCreditCount.value },
  { key: 'credit-total', label: '本页信用额度', value: formatMoney(creditTotal.value) },
]);
const allSelected = computed(() => customers.value.length > 0 && customers.value.every(item => selectedIds.value.has(item.customerId)));

async function fetchCustomers() {
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const page = await listCustomers({ ...query });
    if (sequence !== requestSequence) return;
    customers.value = page.records;
    total.value = page.total;
    selectedIds.value = new Set();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '客户列表加载失败');
  } finally {
    if (sequence === requestSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

const {
  handleSearch,
  handleReset,
  handlePageChange,
  handlePageSizeChange,
  refreshList,
} = usePagedQuery({
  query,
  busy: queryBusy,
  pending: queryPending,
  load: fetchCustomers,
  resetFilters: () => {
    Object.assign(query, { customerCode: '', customerName: '', contactName: '', status: 'all' });
  },
});

function toggleSelectAll(value: boolean | 'indeterminate') {
  selectedIds.value = value === true ? new Set(customers.value.map(item => item.customerId)) : new Set();
}

function toggleSelect(customerId: string, value: boolean | 'indeterminate') {
  const next = new Set(selectedIds.value);
  value === true ? next.add(customerId) : next.delete(customerId);
  selectedIds.value = next;
}

function selectedVersionMap() {
  return Object.fromEntries(
    customers.value
      .filter(item => selectedIds.value.has(item.customerId))
      .map(item => [item.customerId, item.version]),
  );
}

function resetForm() {
  Object.assign(form, {
    customerCode: '',
    customerName: '',
    contactName: '',
    contactPhone: '',
    address: '',
    creditLimit: 0,
    status: 1,
    remark: '',
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  editingCustomerId.value = '';
  editingOriginalStatus.value = 1;
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: CustomerListItem) {
  dialogMode.value = 'edit';
  editingCustomerId.value = row.customerId;
  editingOriginalStatus.value = row.status;
  Object.assign(form, row);
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  dialogVisible.value = true;
}

function openDetail(row: CustomerListItem) {
  detailRow.value = row;
  detailVisible.value = true;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (!form.customerName.trim()) formErrors.customerName = '请输入客户名称';
  else if (form.customerName.trim().length > 200) formErrors.customerName = '客户名称不能超过 200 个字符';
  if (form.contactName.trim().length > 100) formErrors.contactName = '联系人不能超过 100 个字符';
  if (form.contactPhone.trim().length > 32) formErrors.contactPhone = '联系电话不能超过 32 个字符';
  if (form.address.trim().length > 255) formErrors.address = '地址不能超过 255 个字符';
  if (!Number.isFinite(Number(form.creditLimit)) || Number(form.creditLimit) < 0) formErrors.creditLimit = '信用额度不能小于 0';
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

function toPayload(): CustomerFormPayload {
  return {
    customerName: form.customerName.trim(),
    contactName: form.contactName.trim(),
    contactPhone: form.contactPhone.trim(),
    address: form.address.trim(),
    creditLimit: Number(form.creditLimit),
    status: form.status,
    ...(dialogMode.value === 'edit' ? { version: Number(form.version) } : {}),
    remark: form.remark.trim(),
  };
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  Object.assign(confirmState, { open: true, title, description, confirmText, variant, onConfirm });
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  const payload = toPayload();
  if (dialogMode.value === 'edit' && editingOriginalStatus.value === 1 && payload.status === 0) {
    showConfirm('确认停用客户', '停用后，该客户不会出现在新建销售订单的客户下拉中，也不能作为销售订单客户提交；已有销售订单保留客户快照，出库和追溯不受影响。是否继续？', '确认停用', 'warning', () => persistForm(payload));
    return;
  }
  await persistForm(payload);
}

async function persistForm(payload: CustomerFormPayload) {
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      await createCustomer(payload);
      toast.success('客户已创建');
    } else {
      await updateCustomer(editingCustomerId.value, payload);
      toast.success('客户已更新');
    }
    dialogVisible.value = false;
    await fetchCustomers();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '客户保存失败');
  } finally {
    formSubmitting.value = false;
  }
}

async function runConfirmAction() {
  if (actionSubmitting.value) return;
  actionSubmitting.value = true;
  try {
    await confirmState.onConfirm();
    confirmState.open = false;
  } finally {
    actionSubmitting.value = false;
  }
}

function confirmStatus(row: CustomerListItem, status: 0 | 1) {
  showConfirm(status === 1 ? '启用客户' : '停用客户', status === 1 ? '启用后可重新出现在新建销售订单客户下拉中，并允许作为订单客户提交。' : '停用后不会出现在新建销售订单客户下拉中，也不能作为订单客户提交；历史订单保留客户快照。', status === 1 ? '启用' : '停用', status === 1 ? 'default' : 'warning', async () => {
    await updateCustomerStatus(row.customerId, status, row.version);
    toast.success(status === 1 ? '客户已启用' : '客户已停用');
    await fetchCustomers();
  });
}

function confirmDelete(row: CustomerListItem) {
  showConfirm('删除客户', '已被销售订单引用的客户会被后端拒绝删除。是否继续？', '删除', 'destructive', async () => {
    await deleteCustomer(row.customerId, row.version);
    toast.success('客户已删除');
    await fetchCustomers();
  });
}

function confirmBatchStatus(status: 0 | 1) {
  if (selectedIds.value.size === 0) return;
  showConfirm(status === 1 ? '批量启用客户' : '批量停用客户', status === 1 ? `将处理 ${selectedIds.value.size} 个客户。启用后可重新用于新建销售订单。` : `将处理 ${selectedIds.value.size} 个客户。停用后这些客户不再出现在新建销售订单客户下拉中，也不能作为订单客户提交；历史订单保留客户快照。`, status === 1 ? '启用' : '停用', status === 1 ? 'default' : 'warning', async () => {
    await batchUpdateCustomerStatus({ customerIds: [...selectedIds.value], versionByCustomerId: selectedVersionMap(), status });
    toast.success('批量状态已更新');
    await fetchCustomers();
  });
}

function confirmBatchDelete() {
  if (selectedIds.value.size === 0) return;
  showConfirm('批量删除客户', '已被销售订单引用的客户会被后端拒绝删除，存在任一冲突时整批不应删除。', '删除', 'destructive', async () => {
    await batchDeleteCustomers({ customerIds: [...selectedIds.value], versionByCustomerId: selectedVersionMap() });
    toast.success('客户已批量删除');
    await fetchCustomers();
  });
}

function formatMoney(value: number) {
  return `￥${value.toFixed(2)}`;
}

onMounted(fetchCustomers);
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">客户管理</h1>
        <p class="page-description">维护客户基础资料、联系方式、信用额度和启用状态，作为销售订单的客户来源</p>
      </div>
    </div>

    <ListSummaryStrip :items="summaryItems" aria-label="客户数据汇总" />

    <ListFilterPanel layout="content" aria-label="客户筛选">
        <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">客户编码</Label><Input v-model="query.customerCode" placeholder="如 C001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">客户名称</Label><Input v-model="query.customerName" placeholder="请输入名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">联系人</Label><Input v-model="query.contactName" placeholder="请输入联系人" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" /></div>
      <template #actions>
        <ListFilterActions :busy="queryBusy" @query="handleSearch" @reset="handleReset" />
      </template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">客户列表</strong><span class="text-xs text-muted-foreground">客户编码由后端生成，页面不提交编码字段</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchStatus(1)">批量启用</Button></span></TooltipTrigger><TooltipContent>先选择客户</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchStatus(0)">批量停用</Button></span></TooltipTrigger><TooltipContent>停用后不再作为销售下单客户</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span><Button size="sm" variant="outline" class="text-destructive hover:text-destructive" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchDelete">批量删除</Button></span></TooltipTrigger><TooltipContent>存在销售订单引用时后端应返回 409</TooltipContent></Tooltip>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button>
          <Button size="sm" @click="openCreateDialog">新增客户</Button>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="business-data-table min-w-[1089px] table-fixed">
          <colgroup><col class="w-[44px]" /><col class="w-[105px]" /><col class="w-[200px]" /><col class="w-[160px]" /><col class="w-[130px]" /><col class="w-[80px]" /><col class="w-[150px]" /><col class="w-[220px]" /></colgroup>
          <TableHeader><TableRow><TableHead><Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" /></TableHead><TableHead>编码</TableHead><TableHead>客户</TableHead><TableHead>联系人</TableHead><TableHead class="text-right">信用额度</TableHead><TableHead class="text-center">状态</TableHead><TableHead>更新时间</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && customers.length === 0"><TableCell colspan="8" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="customers.length === 0"><TableCell colspan="8" class="h-28 text-center text-muted-foreground">暂无客户</TableCell></TableRow>
            <TableRow v-for="row in customers" v-else :key="row.customerId">
              <TableCell><Checkbox :model-value="selectedIds.has(row.customerId)" @update:model-value="value => toggleSelect(row.customerId, value)" /></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.customerCode }}</code></TableCell>
              <TableCell><div class="truncate font-medium" :title="row.customerName">{{ row.customerName }}</div><div class="truncate text-xs text-muted-foreground" :title="row.address">{{ row.address || '未维护地址' }}</div></TableCell>
              <TableCell><div>{{ row.contactName || '未维护' }}</div><div class="text-xs text-muted-foreground">{{ row.contactPhone || '无电话' }}</div></TableCell>
              <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.creditLimit) }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-600'">{{ row.status === 1 ? '启用' : '停用' }}</Badge></TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ row.updateTime }}</TableCell>
              <TableCell class="text-right">
                <Button variant="ghost" size="sm" class="text-cyan-700 hover:text-cyan-800" @click="openDetail(row)">详情</Button>
                <Button variant="ghost" size="sm" @click="openEditDialog(row)">编辑</Button>
                <Button variant="ghost" size="sm" :class="row.status === 1 ? 'text-amber-700 hover:text-amber-800' : 'text-primary hover:text-primary'" @click="confirmStatus(row, row.status === 1 ? 0 : 1)">{{ row.status === 1 ? '停用' : '启用' }}</Button>
                <Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" @click="confirmDelete(row)">删除</Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="dialogVisible">
      <DialogContent class="flex h-[min(680px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-3xl">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增客户' : '编辑客户' }}</DialogTitle><DialogDescription>客户档案会被销售订单引用，请准确维护联系方式、信用额度和启停状态。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="grid grid-cols-2 gap-4 py-2 max-sm:grid-cols-1">
            <div class="space-y-1"><Label>客户编码</Label><Input v-model="form.customerCode" readonly class="bg-muted/55 text-muted-foreground" placeholder="保存后由系统生成" /><p class="text-xs text-muted-foreground">系统生成，创建后不可修改</p></div>
            <div class="space-y-1"><Label>客户名称 <span class="text-destructive">*</span></Label><Input v-model="form.customerName" :aria-invalid="Boolean(formErrors.customerName)" /><p v-if="formErrors.customerName" class="text-xs text-destructive">{{ formErrors.customerName }}</p></div>
            <div class="space-y-1"><Label>联系人</Label><Input v-model="form.contactName" /><p v-if="formErrors.contactName" class="text-xs text-destructive">{{ formErrors.contactName }}</p></div>
            <div class="space-y-1"><Label>联系电话</Label><Input v-model="form.contactPhone" /><p v-if="formErrors.contactPhone" class="text-xs text-destructive">{{ formErrors.contactPhone }}</p></div>
            <div class="space-y-1"><Label>信用额度</Label><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="form.creditLimit" type="number" min="0" step="0.01" class="pl-8" /></div><p v-if="formErrors.creditLimit" class="text-xs text-destructive">{{ formErrors.creditLimit }}</p></div>
            <div class="space-y-1"><Label>状态</Label><AnchoredSelect v-model="form.status" :options="statusOptions.filter(item => item.value !== 'all')" /></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>地址</Label><Input v-model="form.address" /><p v-if="formErrors.address" class="text-xs text-destructive">{{ formErrors.address }}</p></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>备注</Label><Textarea v-model="form.remark" rows="3" /><p v-if="formErrors.remark" class="text-xs text-destructive">{{ formErrors.remark }}</p></div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="dialogVisible = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : '保存' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailVisible">
      <DialogContent class="flex h-[min(620px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-4xl">
        <DialogHeader><DialogTitle>客户详情</DialogTitle><DialogDescription>核对客户主数据、信用额度和销售订单引用状态。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div v-if="detailRow" class="space-y-6 p-1">
            <BusinessDetailHero
              eyebrow="客户档案"
              :title="detailRow.customerCode"
              :subtitle="detailRow.customerName"
              :status-label="detailRow.status === 1 ? '启用' : '停用'"
              :status-class="detailRow.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-600'"
            >
              <template #metrics>
                <div class="business-detail-hero__metric"><span>信用额度</span><strong>{{ formatMoney(detailRow.creditLimit) }}</strong></div>
                <div class="business-detail-hero__metric"><span>档案状态</span><strong>{{ detailRow.status === 1 ? '可下单' : '已停用' }}</strong></div>
                <div class="business-detail-hero__metric"><span>最后更新</span><strong>{{ detailRow.updateTime }}</strong></div>
                <div class="business-detail-hero__metric"><span>联系人</span><strong>{{ detailRow.contactName || '未维护' }}</strong></div>
              </template>
            </BusinessDetailHero>
            <BusinessDetailSection title="档案与结算" description="客户联系资料、信用额度与维护状态。"><BusinessDetailFacts><div class="business-detail-fact"><dt>客户编码</dt><dd><code>{{ detailRow.customerCode }}</code></dd></div><div class="business-detail-fact"><dt>联系人</dt><dd><strong>{{ detailRow.contactName || '未维护' }}</strong><small>{{ detailRow.contactPhone || '无电话' }}</small></dd></div><div class="business-detail-fact"><dt>信用额度</dt><dd><strong>{{ formatMoney(detailRow.creditLimit) }}</strong></dd></div><div class="business-detail-fact"><dt>最后维护人</dt><dd><strong>{{ detailRow.updatedByName || '未记录' }}</strong><small>{{ detailRow.updateTime }}</small></dd></div></BusinessDetailFacts></BusinessDetailSection>
            <BusinessDetailSection title="联系与备注" description="联系地址与内部维护说明。"><BusinessDetailFacts :columns="2"><div class="business-detail-fact"><dt>地址</dt><dd><strong>{{ detailRow.address || '未维护' }}</strong></dd></div><div class="business-detail-fact"><dt>备注</dt><dd><strong>{{ detailRow.remark || '未维护' }}</strong></dd></div></BusinessDetailFacts></BusinessDetailSection>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" @click="detailVisible = false">关闭</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" cancel-text="取消" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>
