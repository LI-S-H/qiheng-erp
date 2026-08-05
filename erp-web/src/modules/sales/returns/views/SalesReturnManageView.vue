<script setup lang="ts">
import ReturnOrderManagePage from '@/modules/returns/views/ReturnOrderManagePage.vue';
import type { ReturnOrderPageConfig } from '@/modules/returns/types';
import {
  approveSalesReturn,
  cancelSalesReturn,
  createSalesReturn,
  deleteSalesReturn,
  getSalesReturnDetail,
  listSalesReturns,
  listSalesReturnSourceItems,
  rejectSalesReturn,
  searchSalesReturnCustomerOptions,
  searchSalesReturnSourceOrders,
  searchSalesReturnWarehouseOptions,
  submitSalesReturn,
  updateSalesReturn,
} from '@/modules/sales/returns/api';

// 销售退货后端尚未部署，但开发环境启用 Mock 时必须允许完整演示与页面验收。
const mockEnabled = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const config: ReturnOrderPageConfig = {
  returnType: 'SALES_RETURN',
  title: '销售退货',
  description: '基于已实际出库销售单登记客户退货申请，并跟踪审核与退货入库进度',
  listTitle: '销售退货列表',
  emptyText: '暂无销售退货单',
  partyLabel: '客户',
  partyAllLabel: '全部客户',
  partySearchPlaceholder: '输入客户编码或名称',
  warehouseLabel: '退货入库仓库',
  returnNoPlaceholder: '如 SR202607001',
  sourceOrderLabel: '原销售单',
  sourceOrderPlaceholder: '请选择原销售单',
  sourceOrderSearchPlaceholder: '输入销售单号',
  fulfilledQuantityLabel: '已出库',
  executionDateLabel: '预计退货日期',
  createTitle: '新增销售退货草稿',
  createButtonLabel: '新增销售退货',
  editTitle: '编辑销售退货',
  detailTitle: '销售退货详情',
  approvedStatusLabel: '待退货入库',
  partialStatusLabel: '退货入库中',
  approvedHint: '等待仓库入库',
  partialHint: '退货入库处理中',
  approvalResultDescription: '审核通过后将生成待确认销售退货入库单，不直接增加库存。',
  backendEnabled: mockEnabled,
  backendUnavailableMessage: '销售退货来源与入库回写后端尚未部署，当前仅保留页面入口与结构，不发起列表查询，也不可新增、提交、审核或取消。',
  permissions: {
    query: 'sales:query',
    create: 'sales:create',
    manage: 'sales:manage',
  },
  service: {
    listReturns: listSalesReturns,
    getReturnDetail: getSalesReturnDetail,
    createReturn: createSalesReturn,
    updateReturn: updateSalesReturn,
    deleteReturn: deleteSalesReturn,
    submitReturn: submitSalesReturn,
    approveReturn: approveSalesReturn,
    rejectReturn: rejectSalesReturn,
    cancelReturn: cancelSalesReturn,
    searchPartyOptions: searchSalesReturnCustomerOptions,
    searchWarehouseOptions: searchSalesReturnWarehouseOptions,
    searchSourceOrders: searchSalesReturnSourceOrders,
    listSourceItems: listSalesReturnSourceItems,
  },
};
</script>

<template>
  <ReturnOrderManagePage :config="config" />
</template>
