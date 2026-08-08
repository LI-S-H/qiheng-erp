<script setup lang="ts">
import ReturnOrderManagePage from '@/modules/returns/views/ReturnOrderManagePage.vue';
import type { ReturnOrderPageConfig } from '@/modules/returns/types';
import {
  approvePurchaseReturn,
  cancelPurchaseReturn,
  createPurchaseReturn,
  deletePurchaseReturn,
  getPurchaseReturnDetail,
  listPurchaseReturns,
  listPurchaseReturnSourceItems,
  searchPurchaseReturnSourceOrders,
  searchPurchaseReturnSupplierOptions,
  searchPurchaseReturnWarehouseOptions,
  submitPurchaseReturn,
  updatePurchaseReturn,
} from '@/modules/purchase/returns/api';

const config: ReturnOrderPageConfig = {
  returnType: 'PURCHASE_RETURN',
  title: '采购退回',
  description: '基于已实际入库采购单登记退供应商申请，并跟踪审核与退货出库进度',
  listTitle: '采购退回列表',
  emptyText: '暂无采购退回单',
  partyLabel: '供应商',
  partyAllLabel: '全部供应商',
  partySearchPlaceholder: '输入供应商编码或名称',
  warehouseLabel: '退货出库仓库',
  returnNoPlaceholder: '如 PR202607001',
  sourceOrderLabel: '原采购单',
  sourceOrderPlaceholder: '请选择原采购单',
  sourceOrderSearchPlaceholder: '输入采购单号',
  fulfilledQuantityLabel: '已入库',
  executionDateLabel: '预计退货日期',
  createTitle: '新增采购退回草稿',
  createButtonLabel: '新增采购退回',
  editTitle: '编辑采购退回',
  detailTitle: '采购退回详情',
  approvedStatusLabel: '待退货出库',
  partialStatusLabel: '退货出库中',
  approvedHint: '等待仓库出库',
  partialHint: '退货出库处理中',
  approvalResultDescription: '审核通过后将生成待确认采购退货出库单，不直接扣减库存。',
  permissions: {
    query: 'return:query',
    create: 'purchase:create',
    manage: 'purchase:manage',
  },
  service: {
    listReturns: listPurchaseReturns,
    getReturnDetail: getPurchaseReturnDetail,
    createReturn: createPurchaseReturn,
    updateReturn: updatePurchaseReturn,
    deleteReturn: deletePurchaseReturn,
    submitReturn: submitPurchaseReturn,
    approveReturn: approvePurchaseReturn,
    cancelReturn: cancelPurchaseReturn,
    searchPartyOptions: searchPurchaseReturnSupplierOptions,
    searchWarehouseOptions: searchPurchaseReturnWarehouseOptions,
    searchSourceOrders: searchPurchaseReturnSourceOrders,
    listSourceItems: listPurchaseReturnSourceItems,
  },
};
</script>

<template>
  <ReturnOrderManagePage :config="config" />
</template>
