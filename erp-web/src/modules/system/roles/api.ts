import { getResult, postResult, http } from '@/api/http';
import type {
  PageResult,
  RoleBatchIdsPayload,
  RoleBatchStatusPayload,
  RolePermissionPayload,
  RoleStatus,
  SystemRoleFormPayload,
  SystemRoleListItem,
  SystemRoleQuery,
} from './types';

export function listSystemRoles(params: SystemRoleQuery) {
  return getResult<PageResult<SystemRoleListItem>>('/system/roles', { ...params });
}

export function getSystemRole(roleId: string) {
  return getResult<SystemRoleListItem>(`/system/roles/${roleId}`);
}

export function createSystemRole(payload: SystemRoleFormPayload) {
  return postResult<SystemRoleListItem, SystemRoleFormPayload>('/system/roles', payload);
}

export async function updateSystemRole(roleId: string, payload: SystemRoleFormPayload) {
  const response = await http.put(`/system/roles/${roleId}`, payload);
  return response.data.data as SystemRoleListItem;
}

export async function updateSystemRoleStatus(roleId: string, status: RoleStatus) {
  const response = await http.patch(`/system/roles/${roleId}/status`, { status });
  return response.data.data as null;
}

export async function updateSystemRolePermissions(roleId: string, payload: RolePermissionPayload) {
  const response = await http.patch(`/system/roles/${roleId}/permissions`, payload);
  return response.data.data as null;
}

export async function deleteSystemRole(roleId: string) {
  const response = await http.delete(`/system/roles/${roleId}`);
  return response.data.data as null;
}

export async function batchUpdateSystemRoleStatus(payload: RoleBatchStatusPayload) {
  const response = await http.patch('/system/roles/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteSystemRoles(payload: RoleBatchIdsPayload) {
  return postResult<null, RoleBatchIdsPayload>('/system/roles/batch/delete', payload);
}
