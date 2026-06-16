export type UserStatus = 0 | 1;

export interface SystemUserListItem {
  userId: string;
  username: string;
  realName: string;
  deptId: string;
  deptName: string;
  isAdmin: boolean;
  status: UserStatus;
  roleIds: string[];
  roleNames: string[];
  lastLoginAt: string | null;
  createTime: string;
  updateTime: string;
}

export interface SystemUserQuery {
  username?: string;
  realName?: string;
  deptId?: string;
  roleId?: string;
  status?: UserStatus | '' | 'all';
  pageNum: number;
  pageSize: number;
}

export interface SystemUserFormPayload {
  username: string;
  realName: string;
  password?: string;
  deptId: string;
  isAdmin: boolean;
  status: UserStatus;
  roleIds: string[];
}

export interface SystemUserFormModel extends Omit<SystemUserFormPayload, 'deptId'> {
  deptId: string | null;
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
