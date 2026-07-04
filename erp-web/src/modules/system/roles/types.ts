export type RoleStatus = 0 | 1;

export interface SystemRoleListItem {
  roleId: string;
  roleCode: string;
  roleName: string;
  permissionCodes: string[];
  status: RoleStatus;
  remark: string;
  userCount: number;
  createTime: string;
  updateTime: string;
}

export interface SystemRoleQuery {
  roleCode?: string;
  roleName?: string;
  status?: RoleStatus | '' | 'all';
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

export interface RoleOption {
  roleId: string;
  roleCode: string;
  roleName: string;
  status: RoleStatus;
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
