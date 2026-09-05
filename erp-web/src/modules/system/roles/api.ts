import { getResult, postResult, http } from '@/api/http';
import type {
  RoleBatchIdsPayload,
  RoleBatchStatusPayload,
  RoleOption,
  RolePermissionPayload,
  RoleStatus,
  SystemRoleFormPayload,
  SystemRoleListItem,
  SystemRoleQuery,
} from './types';
import type { PageResult } from '@/shared/types/api';
import { normalizeBinaryStatus, normalizeStringId } from '@/shared/utils/api-normalizers';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

// ── Mock data ──

let mockRoles: SystemRoleListItem[] = [
  {
    roleId: '1900000000000001001', roleCode: 'SUPER_ADMIN', roleName: '超级管理员',
    permissionCodes: ['*'], status: 1, remark: '拥有系统全部访问和维护权限',
    userCount: 1, createTime: '2026-06-05 20:30:00', updateTime: '2026-06-08 09:12:30',
  },
  {
    roleId: '1900000000000001002', roleCode: 'PURCHASE_STAFF', roleName: '采购员',
    permissionCodes: ['product:query', 'warehouse:query', 'supplier:query', 'supplier:create', 'purchase:query', 'purchase:create', 'purchase:manage', 'ai:query:purchase'],
    status: 1, remark: '负责供应商维护和采购订单操作',
    userCount: 2, createTime: '2026-06-05 20:35:00', updateTime: '2026-07-16 10:00:00',
  },
  {
    roleId: '1900000000000001003', roleCode: 'SALES_STAFF', roleName: '销售员',
    permissionCodes: ['product:query', 'warehouse:query', 'customer:query', 'customer:create', 'sales:query', 'sales:create', 'sales:manage', 'ai:query:sales'],
    status: 1, remark: '负责客户维护和销售订单操作',
    userCount: 2, createTime: '2026-06-05 20:36:00', updateTime: '2026-07-16 10:00:00',
  },
  {
    roleId: '1900000000000001004', roleCode: 'WAREHOUSE_STAFF', roleName: '仓管员',
    permissionCodes: ['product:query', 'warehouse:query', 'warehouse:manage'],
    status: 1, remark: '负责仓库管理、出入库和库存调整',
    userCount: 1, createTime: '2026-06-05 20:37:00', updateTime: '2026-06-27 14:06:10',
  },
  {
    roleId: '1900000000000001005', roleCode: 'BUSINESS_MANAGER', roleName: '业务主管',
    permissionCodes: ['product:query', 'product:manage', 'warehouse:query', 'warehouse:manage', 'supplier:query', 'supplier:create', 'purchase:query', 'purchase:create', 'purchase:manage', 'customer:query', 'customer:create', 'sales:query', 'sales:create', 'sales:manage', 'ai:query:stock', 'ai:query:sales', 'ai:query:purchase', 'ai:ops:suggest'],
    status: 1, remark: '查看全链路业务数据和AI分析建议',
    userCount: 1, createTime: '2026-06-05 20:38:00', updateTime: '2026-07-16 10:00:00',
  },
  {
    roleId: '1900000000000001006', roleCode: 'AI_ANALYST', roleName: 'AI分析师',
    permissionCodes: ['product:query', 'warehouse:query', 'supplier:query', 'purchase:query', 'customer:query', 'sales:query', 'ai:query:stock', 'ai:query:sales', 'ai:query:purchase', 'ai:ops:suggest', 'ai:decision:suggest'],
    status: 1, remark: '仅查询业务数据和AI问答，当前停用',
    userCount: 0, createTime: '2026-06-05 20:39:00', updateTime: '2026-07-03 17:00:43',
  },
];

function mockFilterRoles(params: SystemRoleQuery): PageResult<SystemRoleListItem> {
  let filtered = [...mockRoles];
  if (params.roleCode?.trim()) {
    const kw = params.roleCode.trim().toLowerCase();
    filtered = filtered.filter(r => r.roleCode.toLowerCase().includes(kw));
  }
  if (params.roleName?.trim()) {
    const kw = params.roleName.trim().toLowerCase();
    filtered = filtered.filter(r => r.roleName.toLowerCase().includes(kw));
  }
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(r => r.status === params.status);
  const total = filtered.length;
  const start = (params.pageNum - 1) * params.pageSize;
  const records = filtered.slice(start, start + params.pageSize);
  return { records, total, pageNum: params.pageNum, pageSize: params.pageSize };
}

function normalizeRoleOption(item: RoleOption): RoleOption {
  return {
    ...item,
    roleId: normalizeStringId(item.roleId, 'roleId'),
    status: normalizeBinaryStatus(item.status),
  };
}

// ── API functions ──

export function listRoleOptions() {
  if (useMockApi) {
    return Promise.resolve(mockRoles
      .filter(role => role.status === 1)
      .map(role => normalizeRoleOption({
        roleId: role.roleId,
        roleCode: role.roleCode,
        roleName: role.roleName,
        status: role.status,
      })));
  }
  return getResult<RoleOption[]>('/system/roles/options').then(items => items.map(normalizeRoleOption));
}

export function listSystemRoles(params: SystemRoleQuery) {
  if (useMockApi) return Promise.resolve(mockFilterRoles(params));
  const { roleCode, roleName, status, ...rest } = params;
  return getResult<PageResult<SystemRoleListItem>>('/system/roles', {
    ...rest,
    ...(roleCode?.trim() ? { roleCode: roleCode.trim() } : {}),
    ...(roleName?.trim() ? { roleName: roleName.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  });
}

export function getSystemRole(roleId: string) {
  if (useMockApi) return Promise.resolve(mockRoles.find(r => r.roleId === roleId) || null);
  return getResult<SystemRoleListItem>(`/system/roles/${roleId}`);
}

export function createSystemRole(payload: SystemRoleFormPayload) {
  if (useMockApi) {
    if (mockRoles.some(role => role.roleCode.toLowerCase() === payload.roleCode.toLowerCase())) {
      return Promise.reject(new Error('角色编码已存在'));
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    const newRole: SystemRoleListItem = {
      roleId: `${Date.now()}`,
      roleCode: payload.roleCode,
      roleName: payload.roleName,
      permissionCodes: [...payload.permissionCodes],
      status: payload.status,
      remark: payload.remark,
      userCount: 0,
      createTime: now,
      updateTime: now,
    };
    mockRoles = [newRole, ...mockRoles];
    return Promise.resolve(newRole);
  }
  return postResult<SystemRoleListItem, SystemRoleFormPayload>('/system/roles', payload);
}

export async function updateSystemRole(roleId: string, payload: SystemRoleFormPayload) {
  if (useMockApi) {
    if (mockRoles.some(role => role.roleId !== roleId && role.roleCode.toLowerCase() === payload.roleCode.toLowerCase())) {
      throw new Error('角色编码已存在');
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockRoles = mockRoles.map(r =>
      r.roleId === roleId ? {
        ...r, roleCode: payload.roleCode, roleName: payload.roleName,
        permissionCodes: [...payload.permissionCodes], status: payload.status,
        remark: payload.remark, updateTime: now,
      } : r,
    );
    return mockRoles.find(r => r.roleId === roleId) as SystemRoleListItem;
  }
  const response = await http.put(`/system/roles/${roleId}`, payload);
  return response.data.data as SystemRoleListItem;
}

export async function updateSystemRoleStatus(roleId: string, status: RoleStatus) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockRoles = mockRoles.map(r => r.roleId === roleId ? { ...r, status, updateTime: now } : r);
    return null;
  }
  const response = await http.patch(`/system/roles/${roleId}/status`, { status });
  return response.data.data as null;
}

export async function updateSystemRolePermissions(roleId: string, payload: RolePermissionPayload) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockRoles = mockRoles.map(r =>
      r.roleId === roleId ? { ...r, permissionCodes: [...payload.permissionCodes], updateTime: now } : r,
    );
    return null;
  }
  const response = await http.patch(`/system/roles/${roleId}/permissions`, payload);
  return response.data.data as null;
}

export async function deleteSystemRole(roleId: string) {
  if (useMockApi) {
    mockRoles = mockRoles.filter(r => r.roleId !== roleId);
    return null;
  }
  const response = await http.delete(`/system/roles/${roleId}`);
  return response.data.data as null;
}

export async function batchUpdateSystemRoleStatus(payload: RoleBatchStatusPayload) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockRoles = mockRoles.map(r =>
      payload.roleIds.includes(r.roleId) ? { ...r, status: payload.status, updateTime: now } : r,
    );
    return null;
  }
  const response = await http.patch('/system/roles/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteSystemRoles(payload: RoleBatchIdsPayload) {
  if (useMockApi) {
    const ids = new Set(payload.roleIds);
    mockRoles = mockRoles.filter(r => !ids.has(r.roleId));
    return Promise.resolve(null);
  }
  return postResult<null, RoleBatchIdsPayload>('/system/roles/batch/delete', payload);
}
