export interface LoginRequest {
  username: string;
  password: string;
}

export interface CurrentUser {
  userId: string;
  username: string;
  realName: string;
  deptId: string | null;
  deptName?: string | null;
  isAdmin: boolean;
  roleCodes: string[];
  permissionCodes: string[];
  lastLoginAt?: string | null;
}

export interface LoginResponse {
  token: string;
  tokenName: string;
  user: CurrentUser;
}
