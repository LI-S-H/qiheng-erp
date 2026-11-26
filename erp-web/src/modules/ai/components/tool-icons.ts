import type { Component } from 'vue';
import {
  Bot,
  Database,
  FileSpreadsheet,
  FileText,
  FolderOpen,
  FolderSearch,
  PackageOpen,
  Terminal,
  Wrench,
} from 'lucide-vue-next';

/** 不同工具使用不同图标，让用户一眼区分步骤类型；未知工具回退到通用图标。 */
export function toolIcon(name?: string): Component {
  switch (name) {
    case 'analyzeData':
      return Bot;
    case 'query_sql_data':
      return Database;
    case 'listDatasets':
      return FolderSearch;
    case 'readDataset':
      return FileSpreadsheet;
    case 'prepareAnalysisData':
      return PackageOpen;
    case 'executePython':
      return Terminal;
    case 'listAnalysisResults':
      return FolderOpen;
    case 'readAnalysisResult':
      return FileText;
    case 'prepare_sales_order_draft':
    case 'update_sales_order_draft':
    case 'submit_sales_order':
    case 'cancel_sales_order':
    case 'prepare_return_order_draft':
    case 'update_return_order_draft':
    case 'submit_return_order':
    case 'cancel_return_order':
      return FileText;
    default:
      return Wrench;
  }
}