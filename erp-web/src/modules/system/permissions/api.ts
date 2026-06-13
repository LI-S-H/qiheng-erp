import { getResult, postResult, http } from '@/api/http';
import type { PageResult } from '@/shared/types/api';
import { createInitialPermissions, permissionModuleOptions } from './catalog';
import type {
  PermissionBatchIdsPayload,
  PermissionBatchStatusPayload,
  PermissionOptionGroup,
  PermissionStatus,
  SystemPermissionFormPayload,
  SystemPermissionListItem,
  SystemPermissionQuery,
  SystemPermissionUpdatePayload,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';
let mockPermissions = createInitialPermissions();

function filterPermissions(params: SystemPermissionQuery): PageResult<SystemPermissionListItem> {
  let filtered = [...mockPermissions];
  const permissionCode = params.permissionCode?.trim().toLowerCase();
  const permissionName = params.permissionName?.trim().toLowerCase();
  if (permissionCode) filtered = filtered.filter(item => item.permissionCode.toLowerCase().includes(permissionCode));
  if (permissionName) filtered = filtered.filter(item => item.permissionName.toLowerCase().includes(permissionName));
  if (params.moduleCode && params.moduleCode !== 'all') filtered = filtered.filter(item => item.moduleCode === params.moduleCode);
  if (params.actionType && params.actionType !== 'all') filtered = filtered.filter(item => item.actionType === params.actionType);
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => a.sortOrder - b.sortOrder || a.permissionCode.localeCompare(b.permissionCode));
  const total = filtered.length;
  const start = (params.pageNum - 1) * params.pageSize;
  return { records: filtered.slice(start, start + params.pageSize), total, pageNum: params.pageNum, pageSize: params.pageSize };
}

export function listSystemPermissions(params: SystemPermissionQuery) {
  if (useMockApi) return Promise.resolve(filterPermissions(params));
  const { permissionCode, permissionName, status, moduleCode, actionType, ...rest } = params;
  return getResult<PageResult<SystemPermissionListItem>>('/system/permissions', {
    ...rest,
    ...(permissionCode?.trim() ? { permissionCode: permissionCode.trim() } : {}),
    ...(permissionName?.trim() ? { permissionName: permissionName.trim() } : {}),
    ...(moduleCode && moduleCode !== 'all' ? { moduleCode } : {}),
    ...(actionType && actionType !== 'all' ? { actionType } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  });
}

export function listPermissionOptions() {
  if (useMockApi) {
    const groups = permissionModuleOptions.map(module => ({
      group: module.label,
      codes: mockPermissions
        .filter(item => item.moduleCode === module.value && item.status === 1)
        .sort((a, b) => a.sortOrder - b.sortOrder)
        .map(item => ({ code: item.permissionCode, label: item.permissionName })),
    })).filter(group => group.codes.length > 0);
    return Promise.resolve(groups as PermissionOptionGroup[]);
  }
  return getResult<PermissionOptionGroup[]>('/system/permissions/options');
}

export function getSystemPermission(permissionId: string) {
  if (useMockApi) return Promise.resolve(mockPermissions.find(item => item.permissionId === permissionId) || null);
  return getResult<SystemPermissionListItem>(`/system/permissions/${permissionId}`);
}

export function createSystemPermission(payload: SystemPermissionFormPayload) {
  if (useMockApi) {
    if (mockPermissions.some(item => item.permissionCode.toLowerCase() === payload.permissionCode.toLowerCase())) {
      return Promise.reject(new Error('权限码已存在'));
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    const created: SystemPermissionListItem = {
      permissionId: String(Date.now()), ...payload,
      roleCount: 0, createdAt: now, updatedAt: now,
    };
    mockPermissions = [...mockPermissions, created];
    return Promise.resolve(created);
  }
  return postResult<SystemPermissionListItem, SystemPermissionFormPayload>('/system/permissions', payload);
}

export async function updateSystemPermission(permissionId: string, payload: SystemPermissionUpdatePayload) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockPermissions = mockPermissions.map(item => item.permissionId === permissionId
      ? { ...item, ...payload, updatedAt: now }
      : item);
    return mockPermissions.find(item => item.permissionId === permissionId) as SystemPermissionListItem;
  }
  const response = await http.put(`/system/permissions/${permissionId}`, payload);
  return response.data.data as SystemPermissionListItem;
}

export async function updateSystemPermissionStatus(permissionId: string, status: PermissionStatus) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockPermissions = mockPermissions.map(item => item.permissionId === permissionId ? { ...item, status, updatedAt: now } : item);
    return null;
  }
  const response = await http.patch(`/system/permissions/${permissionId}/status`, { status });
  return response.data.data as null;
}

export async function deleteSystemPermission(permissionId: string) {
  if (useMockApi) {
    const target = mockPermissions.find(item => item.permissionId === permissionId);
    if (target?.roleCount) throw new Error('权限码已被角色引用，无法删除');
    mockPermissions = mockPermissions.filter(item => item.permissionId !== permissionId);
    return null;
  }
  const response = await http.delete(`/system/permissions/${permissionId}`);
  return response.data.data as null;
}

export async function batchUpdateSystemPermissionStatus(payload: PermissionBatchStatusPayload) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockPermissions = mockPermissions.map(item => payload.permissionIds.includes(item.permissionId)
      ? { ...item, status: payload.status, updatedAt: now } : item);
    return null;
  }
  const response = await http.patch('/system/permissions/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteSystemPermissions(payload: PermissionBatchIdsPayload) {
  if (useMockApi) {
    const selected = mockPermissions.filter(item => payload.permissionIds.includes(item.permissionId));
    if (selected.some(item => item.roleCount > 0)) return Promise.reject(new Error('所选权限码中存在已被角色引用的数据'));
    const ids = new Set(payload.permissionIds);
    mockPermissions = mockPermissions.filter(item => !ids.has(item.permissionId));
    return Promise.resolve(null);
  }
  return postResult<null, PermissionBatchIdsPayload>('/system/permissions/batch/delete', payload);
}
