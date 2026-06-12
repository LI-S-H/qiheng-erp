import type { PermissionAction, SystemPermissionListItem } from './types';

export const permissionModuleOptions = [
  { value: 'system', label: '系统权限' },
  { value: 'product', label: '产品中心' },
  { value: 'warehouse', label: '仓储库存' },
  { value: 'supplier', label: '供应商' },
  { value: 'purchase', label: '采购业务' },
  { value: 'customer', label: '客户管理' },
  { value: 'sales', label: '销售业务' },
  { value: 'ai', label: '智能助手' },
] as const;

export const permissionActionOptions: Array<{ value: PermissionAction; label: string }> = [
  { value: 'query', label: '查询' },
  { value: 'create', label: '新增' },
  { value: 'update', label: '修改' },
  { value: 'delete', label: '删除' },
  { value: 'manage', label: '管理' },
  { value: 'execute', label: '执行' },
];

const initialRows: Array<Omit<SystemPermissionListItem, 'permissionId' | 'createdAt' | 'updatedAt'>> = [
  ['system:user:query', '用户查询', 'system', 'query', 1, 10, '查看用户账号及其部门、角色信息', 2],
  ['system:user:manage', '用户维护', 'system', 'manage', 1, 20, '新增、编辑、启停和删除用户账号', 2],
  ['system:role:query', '角色查询', 'system', 'query', 1, 30, '查看角色及权限绑定信息', 2],
  ['system:role:manage', '角色维护', 'system', 'manage', 1, 40, '维护角色和角色权限', 2],
  ['product:query', '产品查询', 'product', 'query', 1, 50, '查看产品与分类信息', 3],
  ['product:manage', '产品维护', 'product', 'manage', 1, 60, '维护产品与分类信息', 1],
  ['warehouse:query', '库存查询', 'warehouse', 'query', 1, 70, '查看仓库和库存信息', 2],
  ['warehouse:manage', '库存维护', 'warehouse', 'manage', 1, 80, '执行出入库和库存调整', 2],
  ['supplier:query', '供应商查询', 'supplier', 'query', 1, 90, '查看供应商及供货产品', 2],
  ['purchase:query', '采购查询', 'purchase', 'query', 1, 100, '查看采购订单', 2],
  ['purchase:create', '采购创建', 'purchase', 'create', 1, 110, '创建采购订单', 1],
  ['customer:query', '客户查询', 'customer', 'query', 1, 120, '查看客户资料', 2],
  ['sales:query', '销售查询', 'sales', 'query', 1, 130, '查看销售订单', 2],
  ['sales:create', '销售创建', 'sales', 'create', 1, 140, '创建销售订单', 1],
  ['ai:query:stock', '库存问答', 'ai', 'query', 1, 150, '使用库存知识问答', 2],
  ['ai:query:sales', '销售问答', 'ai', 'query', 1, 160, '使用销售经营问答', 2],
  ['ai:query:purchase', '采购问答', 'ai', 'query', 1, 170, '使用采购经营问答', 2],
  ['ai:ops:suggest', '运维建议', 'ai', 'execute', 1, 180, '获取系统运维建议', 1],
  ['ai:decision:suggest', '决策建议', 'ai', 'execute', 0, 190, '获取经营决策建议', 2],
].map(([permissionCode, permissionName, moduleCode, actionType, status, sortOrder, description, roleCount]) => ({
  permissionCode: permissionCode as string,
  permissionName: permissionName as string,
  moduleCode: moduleCode as string,
  actionType: actionType as PermissionAction,
  status: status as 0 | 1,
  sortOrder: sortOrder as number,
  description: description as string,
  roleCount: roleCount as number,
}));

export function createInitialPermissions(): SystemPermissionListItem[] {
  return initialRows.map((row, index) => ({
    ...row,
    permissionId: `1900000000000002${String(index + 1).padStart(3, '0')}`,
    createdAt: '2026-06-08 10:00:00',
    updatedAt: index === initialRows.length - 1 ? '2026-06-11 16:20:00' : '2026-06-10 09:30:00',
  }));
}
