import type { RouteLocationRaw } from 'vue-router';
import type { DashboardTodoItem } from './types';

/**
 * 工作台待办的前端导航预设。
 * 后端只返回业务事实，页面路径和筛选条件由前端统一维护，避免服务端耦合前端路由。
 */
const todoNavigation: Record<string, RouteLocationRaw> = {
  'todo-purchase-approve': { path: '/purchase/orders', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-sales-approve': { path: '/sales/orders', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-purchase-return-approve': { path: '/purchase/returns', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-sales-return-approve': { path: '/sales/returns', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-inbound': { path: '/warehouse/inbound-bills', query: { status: 'PENDING_CONFIRM', from: 'dashboard' } },
  'todo-outbound': { path: '/warehouse/outbound-bills', query: { status: 'PENDING_CONFIRM', from: 'dashboard' } },
  'todo-stock-risk-review': { path: '/warehouse/stocks', query: { riskOnly: 'true', from: 'dashboard' } },
};

export function resolveTodoNavigation(todo: Pick<DashboardTodoItem, 'todoId' | 'completionMode'>): RouteLocationRaw | null {
  if (todo.completionMode === 'TRACKED') return null;
  return todoNavigation[todo.todoId] ?? null;
}