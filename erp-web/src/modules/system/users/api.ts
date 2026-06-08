import { getResult, postResult, http } from '@/api/http';
import type {
  DeptOption,
  PageResult,
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

export function listSystemUsers(params: SystemUserQuery) {
  return getResult<PageResult<SystemUserListItem>>('/system/users', { ...params });
}

export function getSystemUser(userId: string) {
  return getResult<SystemUserListItem>(`/system/users/${userId}`);
}

export function createSystemUser(payload: SystemUserFormPayload) {
  return postResult<SystemUserListItem, SystemUserFormPayload>('/system/users', payload);
}

export async function updateSystemUser(userId: string, payload: SystemUserFormPayload) {
  const response = await http.put(`/system/users/${userId}`, payload);
  return response.data.data as SystemUserListItem;
}

export async function updateSystemUserStatus(userId: string, status: UserStatus) {
  const response = await http.patch(`/system/users/${userId}/status`, { status });
  return response.data.data as null;
}

export async function resetSystemUserPassword(userId: string, payload: UserPasswordResetPayload) {
  const response = await http.patch(`/system/users/${userId}/password`, payload);
  return response.data.data as null;
}

export async function bindSystemUserRoles(userId: string, payload: UserRoleBindPayload) {
  const response = await http.put(`/system/users/${userId}/roles`, payload);
  return response.data.data as null;
}

export async function deleteSystemUser(userId: string) {
  const response = await http.delete(`/system/users/${userId}`);
  return response.data.data as null;
}

export async function batchUpdateSystemUserStatus(payload: UserBatchStatusPayload) {
  const response = await http.patch('/system/users/batch/status', payload);
  return response.data.data as null;
}

export async function batchResetSystemUserPassword(payload: UserBatchPasswordResetPayload) {
  const response = await http.patch('/system/users/batch/password', payload);
  return response.data.data as null;
}

export function batchDeleteSystemUsers(payload: UserBatchIdsPayload) {
  return postResult<null, UserBatchIdsPayload>('/system/users/batch/delete', payload);
}

export function listRoleOptions() {
  return getResult<RoleOption[]>('/system/roles/options');
}

export function listDeptOptions() {
  return getResult<DeptOption[]>('/system/depts/options');
}
