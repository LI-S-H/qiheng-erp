import { defineStore } from 'pinia';
import { AUTH_TOKEN_NAME_STORAGE_KEY, AUTH_TOKEN_STORAGE_KEY } from '@/shared/constants/storage';
import { getCurrentUserApi, loginApi, logoutApi } from '../api';
import type { CurrentUser, LoginRequest } from '../types';

interface AuthState {
  token: string;
  tokenName: string;
  user: CurrentUser | null;
  initialized: boolean;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || '',
    tokenName: localStorage.getItem(AUTH_TOKEN_NAME_STORAGE_KEY) || 'satoken',
    user: null,
    initialized: false,
  }),

  getters: {
    isLoggedIn: state => Boolean(state.token),
    displayName: state => state.user?.realName || state.user?.username || '未登录',
    isAdmin: state => Boolean(state.user?.isAdmin),
  },

  actions: {
    async login(payload: LoginRequest) {
      const loginResult = await loginApi(payload);
      this.token = loginResult.token;
      this.tokenName = loginResult.tokenName || 'satoken';
      this.user = loginResult.user;
      this.initialized = true;
      localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, loginResult.token);
      localStorage.setItem(AUTH_TOKEN_NAME_STORAGE_KEY, this.tokenName);
    },

    async loadCurrentUser() {
      if (!this.token) {
        this.initialized = true;
        return;
      }

      this.user = await getCurrentUserApi();
      this.initialized = true;
    },

    async logout() {
      if (this.token) {
        await logoutApi().catch(() => undefined);
      }

      this.clearSession();
    },

    clearSession() {
      this.token = '';
      this.tokenName = 'satoken';
      this.user = null;
      this.initialized = true;
      localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
      localStorage.removeItem(AUTH_TOKEN_NAME_STORAGE_KEY);
    },

    hasPermission(permissionCode: string) {
      if (this.user?.isAdmin) {
        return true;
      }

      return Boolean(this.user?.permissionCodes.includes(permissionCode));
    },
  },
});
