import { getResult, postResult, http } from '@/api/http';
import type {
  RoleBatchIdsPayload,
  RoleBatchStatusPayload,
  RolePermissionPayload,
  RoleStatus,
  SystemRoleFormPayload,
  SystemRoleListItem,
  SystemRoleQuery,
} from './types';
import type { PageResult } from '@/shared/types/api';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

// ── Mock data ──

let mockRoles: SystemRoleListItem[] = [
  {
    roleId: '1900000000000001001', roleCode: 'SUPER_ADMIN', roleName: '超级管理员',
    permissionCodes: ['*'], status: 1, remark: '拥有系统全部访问和维护权限',
    userCount: 1, createdAt: '2026-06-05 20:30:00', updatedAt: '2026-06-08 09:12:30',
  },
  {
    roleId: '1900000000000001002', roleCode: 'SYSTEM_ADMIN', roleName: '系统管理员',
    permissionCodes: ['system:user:query', 'system:user:manage', 'system:role:query', 'system:role:manage'],
    status: 1, remark: '维护账号、角色和基础权限配置',
    userCount: 0, createdAt: '2026-06-05 21:00:00', updatedAt: '2026-06-07 16:20:00',
  },
  {
    roleId: '1900000000000001003', roleCode: 'BUSINESS_MANAGER', roleName: '业务主管',
    permissionCodes: ['product:query', 'supplier:query', 'purchase:query', 'customer:query', 'sales:query'],
    status: 1, remark: '查看产品、采购和销售主线数据',
    userCount: 2, createdAt: '2026-06-06 10:18:22', updatedAt: '2026-06-07 13:00:00',
  },
  {
    roleId: '1900000000000001004', roleCode: 'WAREHOUSE_OPERATOR', roleName: '仓库操作员',
    permissionCodes: ['product:query', 'warehouse:query', 'warehouse:manage', 'ai:query:stock'],
    status: 1, remark: '处理仓储库存查询和出入库相关操作',
    userCount: 1, createdAt: '2026-06-06 11:05:19', updatedAt: '2026-06-06 11:05:19',
  },
  {
    roleId: '1900000000000001005', roleCode: 'AI_ANALYST', roleName: '智能分析员',
    permissionCodes: ['ai:query:stock', 'ai:query:sales', 'ai:query:purchase', 'ai:decision:suggest'],
    status: 0, remark: '用于后续智能经营分析试点',
    userCount: 0, createdAt: '2026-06-07 09:40:00', updatedAt: '2026-06-07 09:40:00',
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

// ── API functions ──

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
      createdAt: now,
      updatedAt: now,
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
        remark: payload.remark, updatedAt: now,
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
    mockRoles = mockRoles.map(r => r.roleId === roleId ? { ...r, status, updatedAt: now } : r);
    return null;
  }
  const response = await http.patch(`/system/roles/${roleId}/status`, { status });
  return response.data.data as null;
}

export async function updateSystemRolePermissions(roleId: string, payload: RolePermissionPayload) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockRoles = mockRoles.map(r =>
      r.roleId === roleId ? { ...r, permissionCodes: [...payload.permissionCodes], updatedAt: now } : r,
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
      payload.roleIds.includes(r.roleId) ? { ...r, status: payload.status, updatedAt: now } : r,
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
