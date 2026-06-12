export type PermissionStatus = 0 | 1;
export type PermissionAction = 'query' | 'create' | 'update' | 'delete' | 'manage' | 'execute';

export interface SystemPermissionListItem {
  permissionId: string;
  permissionCode: string;
  permissionName: string;
  moduleCode: string;
  actionType: PermissionAction;
  status: PermissionStatus;
  sortOrder: number;
  description: string;
  roleCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface SystemPermissionQuery {
  keyword?: string;
  moduleCode?: string | 'all';
  actionType?: PermissionAction | 'all';
  status?: PermissionStatus | '' | 'all';
  pageNum: number;
  pageSize: number;
}

export interface SystemPermissionFormPayload {
  permissionCode: string;
  permissionName: string;
  moduleCode: string;
  actionType: PermissionAction;
  status: PermissionStatus;
  sortOrder: number;
  description: string;
}

export type SystemPermissionUpdatePayload = Omit<SystemPermissionFormPayload, 'permissionCode'>;

export interface PermissionBatchIdsPayload {
  permissionIds: string[];
}

export interface PermissionBatchStatusPayload extends PermissionBatchIdsPayload {
  status: PermissionStatus;
}

export interface PermissionOptionGroup {
  group: string;
  codes: Array<{ code: string; label: string }>;
}
