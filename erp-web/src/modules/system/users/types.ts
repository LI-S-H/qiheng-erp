export type UserStatus = 0 | 1;

export interface SystemUserListItem {
  userId: string;
  username: string;
  realName: string;
  deptId: string | null;
  deptName: string;
  isAdmin: boolean;
  status: UserStatus;
  roleIds: string[];
  roleNames: string[];
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SystemUserQuery {
  keyword?: string;
  deptId?: string;
  roleId?: string;
  status?: UserStatus | '';
  pageNum: number;
  pageSize: number;
}

export interface SystemUserFormPayload {
  username: string;
  realName: string;
  password?: string;
  deptId: string | null;
  isAdmin: boolean;
  status: UserStatus;
  roleIds: string[];
}

export interface UserRoleBindPayload {
  roleIds: string[];
}

export interface UserPasswordResetPayload {
  password: string;
}

export interface UserBatchIdsPayload {
  userIds: string[];
}

export interface UserBatchStatusPayload extends UserBatchIdsPayload {
  status: UserStatus;
}

export interface UserBatchPasswordResetPayload extends UserBatchIdsPayload {
  password: string;
}

export interface RoleOption {
  roleId: string;
  roleCode: string;
  roleName: string;
  status: UserStatus;
}

export interface DeptOption {
  deptId: string;
  deptName: string;
  parentId: string;
  status: UserStatus;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}
