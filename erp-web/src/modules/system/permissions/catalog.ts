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

type PermissionSeed = Omit<SystemPermissionListItem, 'roleCount'>;

// 与 001_mvp_system_permission.sql 和当前 MySQL dump 的 sys_permission 一致。
const initialRows: PermissionSeed[] = [
  ['2001', 'system:user:query', '用户查询', 'system', 'query', 1, 10, '查看用户账号及其部门、角色信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2002', 'system:user:manage', '用户维护', 'system', 'manage', 1, 20, '新增、编辑、启停和删除用户账号', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2020', 'system:dept:manage', '部门维护', 'system', 'manage', 1, 25, '新增、编辑、启停和删除部门', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2003', 'system:role:query', '角色查询', 'system', 'query', 1, 30, '查看角色及权限绑定信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2004', 'system:role:manage', '角色维护', 'system', 'manage', 1, 40, '维护角色和角色权限', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2021', 'system:permission:query', '权限码查询', 'system', 'query', 1, 45, '查看权限码目录、权限码详情和可授权权限码选项', '2026-06-26 13:08:01', '2026-06-26 13:08:01'],
  ['2022', 'system:permission:create', '权限码新增', 'system', 'create', 1, 46, '新增权限码目录项', '2026-06-26 13:08:01', '2026-06-26 13:08:01'],
  ['2023', 'system:permission:manage', '权限码维护', 'system', 'manage', 1, 47, '编辑、启停、删除和批量维护权限码目录项', '2026-06-26 13:08:01', '2026-06-26 13:08:01'],
  ['2005', 'product:query', '产品查询', 'product', 'query', 1, 50, '查看产品与分类信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2006', 'product:manage', '产品维护', 'product', 'manage', 1, 60, '维护产品与分类信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2007', 'warehouse:query', '仓库与库存查询', 'warehouse', 'query', 1, 70, '查看仓库信息、入库单、出库单和库存流水', '2026-06-08 10:00:00', '2026-07-16 17:13:07'],
  ['2008', 'warehouse:manage', '仓库与库存维护', 'warehouse', 'manage', 1, 80, '维护仓库信息、执行出入库操作和库存调整', '2026-06-08 10:00:00', '2026-07-16 17:13:07'],
  ['2009', 'supplier:query', '供应商查询', 'supplier', 'query', 1, 90, '查看供应商及供货产品', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2040', 'supplier:create', '供应商创建', 'supplier', 'create', 1, 91, '创建、编辑、启停和删除供应商及供货产品', '2026-07-16 10:00:00', '2026-07-16 10:00:00'],
  ['2041', 'supplier:manage', '供应商管理', 'supplier', 'manage', 1, 92, '管理供应商及供货关系', '2026-07-16 10:00:00', '2026-07-16 10:00:00'],
  ['2010', 'purchase:query', '采购查询', 'purchase', 'query', 1, 100, '查看采购订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2050', 'purchase:manage', '采购管理', 'purchase', 'manage', 1, 105, '编辑、删除和审核采购订单', '2026-07-16 10:00:00', '2026-07-16 10:00:00'],
  ['2011', 'purchase:create', '采购创建', 'purchase', 'create', 1, 110, '创建采购订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2012', 'customer:query', '客户查询', 'customer', 'query', 1, 120, '查看客户资料', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2060', 'customer:create', '客户创建', 'customer', 'create', 1, 121, '创建、编辑、启停和删除客户', '2026-07-16 10:00:00', '2026-07-16 10:00:00'],
  ['2061', 'customer:manage', '客户管理', 'customer', 'manage', 1, 122, '管理客户资料', '2026-07-16 10:00:00', '2026-07-16 10:00:00'],
  ['2013', 'sales:query', '销售查询', 'sales', 'query', 1, 130, '查看销售订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2070', 'sales:manage', '销售管理', 'sales', 'manage', 1, 135, '编辑、删除和审核销售订单', '2026-07-16 10:00:00', '2026-07-16 10:00:00'],
  ['2014', 'sales:create', '销售创建', 'sales', 'create', 1, 140, '创建销售订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2015', 'ai:query:stock', '库存问答', 'ai', 'query', 1, 150, '使用库存知识问答', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2016', 'ai:query:sales', '销售问答', 'ai', 'query', 1, 160, '使用销售经营问答', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2017', 'ai:query:purchase', '采购问答', 'ai', 'query', 1, 170, '使用采购经营问答', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2018', 'ai:ops:suggest', '运维建议', 'ai', 'execute', 1, 180, '获取系统运维建议', '2026-06-08 10:00:00', '2026-06-10 09:30:00'],
  ['2019', 'ai:decision:suggest', '决策建议', 'ai', 'execute', 1, 190, '获取经营决策建议', '2026-06-08 10:00:00', '2026-06-26 20:00:25'],
].map(([suffix, permissionCode, permissionName, moduleCode, actionType, status, sortOrder, description, createTime, updateTime]) => ({
  permissionId: `190000000000000${suffix}`,
  permissionCode: permissionCode as string,
  permissionName: permissionName as string,
  moduleCode: moduleCode as string,
  actionType: actionType as PermissionAction,
  status: status as 0 | 1,
  sortOrder: sortOrder as number,
  description: description as string,
  createTime: createTime as string,
  updateTime: updateTime as string,
}));

export function createInitialPermissions(): SystemPermissionListItem[] {
  const roleCounts: Record<string, number> = {
    'system:user:query': 1, 'system:user:manage': 1, 'system:dept:manage': 1,
    'system:role:query': 1, 'system:role:manage': 1, 'system:permission:query': 1,
    'system:permission:create': 1, 'system:permission:manage': 1,
    'product:query': 6, 'product:manage': 2, 'warehouse:query': 6, 'warehouse:manage': 3,
    'supplier:query': 4, 'supplier:create': 3, 'supplier:manage': 1,
    'purchase:query': 4, 'purchase:create': 3, 'purchase:manage': 3,
    'customer:query': 4, 'customer:create': 3, 'customer:manage': 1,
    'sales:query': 4, 'sales:create': 3, 'sales:manage': 3,
    'ai:query:stock': 3, 'ai:query:sales': 3, 'ai:query:purchase': 3,
    'ai:ops:suggest': 3, 'ai:decision:suggest': 2,
  };
  return initialRows.map(row => ({ ...row, roleCount: roleCounts[row.permissionCode] ?? 0 }));
}
