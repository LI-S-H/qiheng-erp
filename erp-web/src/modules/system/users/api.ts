import { getResult, postResult, http } from '@/api/http';
import type {
  DeptOption,
  RoleOption,
  SystemUserFormPayload,
  SystemUserListItem,
  SystemUserQuery,
  UserBatchIdsPayload,
  UserBatchPasswordResetPayload,
  UserBatchStatusPayload,
  UserPasswordResetPayload,
  UserRoleBindPayload,
  UserStatus,
} from './types';
import type { PageResult } from '@/shared/types/api';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

// ── Mock data ──

const mockRoleOptions: RoleOption[] = [
  { roleId: '1900000000000001001', roleCode: 'SUPER_ADMIN', roleName: '超级管理员', status: 1 },
  { roleId: '1900000000000001002', roleCode: 'SYSTEM_ADMIN', roleName: '系统管理员', status: 1 },
  { roleId: '1900000000000001003', roleCode: 'BUSINESS_MANAGER', roleName: '业务主管', status: 1 },
  { roleId: '1900000000000001004', roleCode: 'WAREHOUSE_OPERATOR', roleName: '仓库操作员', status: 1 },
];

const mockDeptOptions: DeptOption[] = [
  { deptId: '1900000000000000100', deptName: '行政部', parentId: '0', status: 1 },
  { deptId: '1900000000000000101', deptName: '财务部', parentId: '0', status: 1 },
  { deptId: '1900000000000000102', deptName: '采购部', parentId: '0', status: 1 },
  { deptId: '1900000000000000103', deptName: '销售部', parentId: '0', status: 1 },
  { deptId: '1900000000000000104', deptName: '仓储部', parentId: '0', status: 1 },
];

let mockUsers: SystemUserListItem[] = [
  {
    userId: '1900000000000000001', username: 'admin', realName: '系统管理员',
    deptId: '1900000000000000100', deptName: '行政部', isAdmin: true, status: 1,
    roleIds: ['1900000000000001001'], roleNames: ['超级管理员'],
    lastLoginAt: '2026-06-08 09:12:30', createTime: '2026-06-05 20:30:00', updateTime: '2026-06-08 09:12:30',
  },
  {
    userId: '1900000000000000002', username: 'purchase01', realName: '采购主管',
    deptId: '1900000000000000102', deptName: '采购部', isAdmin: false, status: 1,
    roleIds: ['1900000000000001003'], roleNames: ['业务主管'],
    lastLoginAt: '2026-06-07 17:24:11', createTime: '2026-06-06 10:18:22', updateTime: '2026-06-07 17:24:11',
  },
  {
    userId: '1900000000000000003', username: 'warehouse01', realName: '仓库操作员',
    deptId: '1900000000000000104', deptName: '仓储部', isAdmin: false, status: 1,
    roleIds: ['1900000000000001004'], roleNames: ['仓库操作员'],
    lastLoginAt: null, createTime: '2026-06-06 11:05:19', updateTime: '2026-06-06 11:05:19',
  },
  {
    userId: '1900000000000000004', username: 'sales_stop', realName: '停用销售账号',
    deptId: '1900000000000000103', deptName: '销售部', isAdmin: false, status: 0,
    roleIds: ['1900000000000001003'], roleNames: ['业务主管'],
    lastLoginAt: '2026-06-06 14:42:02', createTime: '2026-06-05 22:10:00', updateTime: '2026-06-07 13:00:00',
  },
];

function mockFilterUsers(params: SystemUserQuery): PageResult<SystemUserListItem> {
  let filtered = [...mockUsers];
  const username = params.username?.trim().toLowerCase();
  const realName = params.realName?.trim().toLowerCase();
  if (username) filtered = filtered.filter(u => u.username.toLowerCase().includes(username));
  if (realName) filtered = filtered.filter(u => u.realName.toLowerCase().includes(realName));
  if (params.deptId && params.deptId !== 'all') filtered = filtered.filter(u => u.deptId === params.deptId);
  if (params.roleId && params.roleId !== 'all') filtered = filtered.filter(u => u.roleIds.includes(params.roleId!));
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(u => u.status === params.status);
  const total = filtered.length;
  const start = (params.pageNum - 1) * params.pageSize;
  const records = filtered.slice(start, start + params.pageSize);
  return { records, total, pageNum: params.pageNum, pageSize: params.pageSize };
}

// ── API functions ──

export function listSystemUsers(params: SystemUserQuery) {
  if (useMockApi) return Promise.resolve(mockFilterUsers(params));
  const { username, realName, deptId, roleId, status, ...rest } = params;
  const effectiveRoleId = roleId && roleId !== 'all' ? String(roleId) : '';
  return getResult<PageResult<SystemUserListItem>>('/system/users', {
    ...rest,
    ...(username?.trim() ? { username: username.trim() } : {}),
    ...(realName?.trim() ? { realName: realName.trim() } : {}),
    ...(deptId && deptId !== 'all' ? { deptId } : {}),
    ...(effectiveRoleId ? { roleId: effectiveRoleId } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  });
}

export function getSystemUser(userId: string) {
  if (useMockApi) return Promise.resolve(mockUsers.find(u => u.userId === userId) || null);
  return getResult<SystemUserListItem>(`/system/users/${userId}`);
}

export function createSystemUser(payload: SystemUserFormPayload) {
  if (useMockApi) {
    if (mockUsers.some(user => user.username.toLowerCase() === payload.username.toLowerCase())) {
      return Promise.reject(new Error('登录账号已存在'));
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    const dept = mockDeptOptions.find(d => d.deptId === payload.deptId);
    const roles = mockRoleOptions.filter(r => payload.roleIds.includes(r.roleId));
    const newUser: SystemUserListItem = {
      userId: `${Date.now()}`,
      username: payload.username,
      realName: payload.realName,
      deptId: payload.deptId,
      deptName: dept?.deptName || '',
      isAdmin: payload.isAdmin,
      status: payload.status,
      roleIds: [...payload.roleIds],
      roleNames: roles.map(r => r.roleName),
      lastLoginAt: null,
      createTime: now,
      updateTime: now,
    };
    mockUsers = [newUser, ...mockUsers];
    return Promise.resolve(newUser);
  }
  return postResult<SystemUserListItem, SystemUserFormPayload>('/system/users', payload);
}

export async function updateSystemUser(userId: string, payload: SystemUserFormPayload) {
  if (useMockApi) {
    if (mockUsers.some(user => user.userId !== userId && user.username.toLowerCase() === payload.username.toLowerCase())) {
      throw new Error('登录账号已存在');
    }
    const dept = mockDeptOptions.find(d => d.deptId === payload.deptId);
    const roles = mockRoleOptions.filter(r => payload.roleIds.includes(r.roleId));
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockUsers = mockUsers.map(u =>
      u.userId === userId ? {
        ...u, username: payload.username, realName: payload.realName,
        deptId: payload.deptId, deptName: dept?.deptName || '',
        isAdmin: payload.isAdmin, status: payload.status,
        roleIds: [...payload.roleIds], roleNames: roles.map(r => r.roleName), updateTime: now,
      } : u,
    );
    return mockUsers.find(u => u.userId === userId) as SystemUserListItem;
  }
  const response = await http.put(`/system/users/${userId}`, payload);
  return response.data.data as SystemUserListItem;
}

export async function updateSystemUserStatus(userId: string, status: UserStatus) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockUsers = mockUsers.map(u => u.userId === userId ? { ...u, status, updateTime: now } : u);
    return null;
  }
  const response = await http.patch(`/system/users/${userId}/status`, { status });
  return response.data.data as null;
}

export async function resetSystemUserPassword(userId: string, payload: UserPasswordResetPayload) {
  if (useMockApi) return null;
  const response = await http.patch(`/system/users/${userId}/password`, payload);
  return response.data.data as null;
}

export async function bindSystemUserRoles(userId: string, payload: UserRoleBindPayload) {
  if (useMockApi) {
    const roles = mockRoleOptions.filter(r => payload.roleIds.includes(r.roleId));
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockUsers = mockUsers.map(u =>
      u.userId === userId ? { ...u, roleIds: [...payload.roleIds], roleNames: roles.map(r => r.roleName), updateTime: now } : u,
    );
    return null;
  }
  const response = await http.put(`/system/users/${userId}/roles`, payload);
  return response.data.data as null;
}

export async function deleteSystemUser(userId: string) {
  if (useMockApi) {
    mockUsers = mockUsers.filter(u => u.userId !== userId);
    return null;
  }
  const response = await http.delete(`/system/users/${userId}`);
  return response.data.data as null;
}

export async function batchUpdateSystemUserStatus(payload: UserBatchStatusPayload) {
  if (useMockApi) {
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    mockUsers = mockUsers.map(u =>
      payload.userIds.includes(u.userId) ? { ...u, status: payload.status, updateTime: now } : u,
    );
    return null;
  }
  const response = await http.patch('/system/users/batch/status', payload);
  return response.data.data as null;
}

export async function batchResetSystemUserPassword(payload: UserBatchPasswordResetPayload) {
  if (useMockApi) return null;
  const response = await http.patch('/system/users/batch/password', payload);
  return response.data.data as null;
}

export function batchDeleteSystemUsers(payload: UserBatchIdsPayload) {
  if (useMockApi) {
    const ids = new Set(payload.userIds);
    mockUsers = mockUsers.filter(u => !ids.has(u.userId));
    return Promise.resolve(null);
  }
  return postResult<null, UserBatchIdsPayload>('/system/users/batch/delete', payload);
}
