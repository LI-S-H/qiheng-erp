export type RoleStatus = 0 | 1;

export interface SystemRoleListItem {
  roleId: string;
  roleCode: string;
  roleName: string;
  permissionCodes: string[];
  status: RoleStatus;
  remark: string;
  userCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface SystemRoleQuery {
  roleCode?: string;
  roleName?: string;
  status?: RoleStatus | '';
  pageNum: number;
  pageSize: number;
}

export interface SystemRoleFormPayload {
  roleCode: string;
  roleName: string;
  permissionCodes: string[];
  status: RoleStatus;
  remark: string;
}

export interface RoleBatchIdsPayload {
  roleIds: string[];
}

export interface RoleBatchStatusPayload extends RoleBatchIdsPayload {
  status: RoleStatus;
}

export interface RolePermissionPayload {
  permissionCodes: string[];
}

export interface PageResult<T> {
  records: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}
