import { getResult, postResult } from '@/api/http';
import type { CurrentUser, LoginRequest, LoginResponse } from './types';

const useMockAuth = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_AUTH === 'true';

const mockUser: CurrentUser = {
  userId: '1900000000000000001',
  username: 'admin',
  realName: '系统管理员',
  deptId: '1900000000000000100',
  deptName: '行政部',
  isAdmin: true,
  roleCodes: ['SUPER_ADMIN'],
  permissionCodes: [
    'product:query',
    'warehouse:query',
    'supplier:query',
    'customer:query',
    'purchase:query',
    'purchase:create',
    'sales:query',
    'sales:create',
    'ai:query:stock',
    'ai:query:sales',
    'ai:query:purchase',
  ],
  lastLoginAt: '2026-06-06 01:10:00',
};

export function loginApi(payload: LoginRequest) {
  if (useMockAuth) {
    if (payload.username !== 'admin' || payload.password !== '123456') {
      return Promise.reject(new Error('开发环境账号或密码错误'));
    }

    return Promise.resolve<LoginResponse>({
      token: 'mock-token-admin',
      tokenName: 'satoken',
      user: {
        ...mockUser,
        username: 'admin',
      },
    });
  }

  return postResult<LoginResponse, LoginRequest>('/auth/login', payload);
}

export function logoutApi() {
  if (useMockAuth) {
    return Promise.resolve();
  }

  return postResult<void>('/auth/logout');
}

export function getCurrentUserApi() {
  if (useMockAuth) {
    return Promise.resolve(mockUser);
  }

  return getResult<CurrentUser>('/auth/me');
}
