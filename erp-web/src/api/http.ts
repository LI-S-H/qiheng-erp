import axios, { type AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { toast } from 'vue-sonner';
import { AUTH_TOKEN_NAME_STORAGE_KEY, AUTH_TOKEN_STORAGE_KEY } from '@/shared/constants/storage';
import type { ApiErrorPayload, Result } from '@/shared/types/api';
import { beginPageLoading } from '@/shared/utils/page-loading';

/** 页面首次读取超过该时长即视为不可用，避免全局遮罩长期阻塞业务界面。 */
const PAGE_READ_TIMEOUT = 5_000;

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 后台刷新等不阻塞当前页面内容的请求可跳过全局加载遮罩。 */
    skipPageLoading?: boolean;
    /** 调用方自行呈现错误状态时，禁止重复弹出全局错误提示。 */
    suppressErrorToast?: boolean;
  }
}

interface LoadingRequestConfig extends InternalAxiosRequestConfig {
  finishPageLoading?: () => void;
}

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api';

export const http = axios.create({
  baseURL,
  timeout: 15000,
});

export function getApiErrorMessage(error: unknown): string {
  if (axios.isAxiosError<ApiErrorPayload>(error)) {
    return error.response?.data?.message || error.message;
  }
  if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string') {
    return error.message;
  }
  return '';
}

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const loadingConfig = config as LoadingRequestConfig;
  if (!loadingConfig.skipPageLoading) {
    loadingConfig.finishPageLoading = beginPageLoading();
    // 仅约束会驱动页面渲染的读取请求；保存、审核等写操作仍保留默认超时，避免误中断业务事务。
    if (config.method?.toUpperCase() === 'GET' && config.timeout === http.defaults.timeout) {
      config.timeout = PAGE_READ_TIMEOUT;
    }
  }

  const token = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);
  const tokenName = localStorage.getItem(AUTH_TOKEN_NAME_STORAGE_KEY) || 'satoken';

  if (token) {
    config.headers.set(tokenName, token);
  }

  return config;
});

http.interceptors.response.use(
  response => {
    const loadingConfig = response.config as LoadingRequestConfig;
    loadingConfig.finishPageLoading?.();
    const result = response.data as Result<unknown>;

    if (typeof result?.code === 'number' && result.code !== 0) {
      if (!response.config.suppressErrorToast) toast.error(result.message || '请求处理失败');
      return Promise.reject(result);
    }

    return response;
  },
  (error: AxiosError<ApiErrorPayload>) => {
    (error.config as LoadingRequestConfig | undefined)?.finishPageLoading?.();
    const message = error.code === 'ECONNABORTED'
      ? '页面加载超时，请重试'
      : error.response?.data?.message || error.message || '网络请求异常';
    if (!error.config?.suppressErrorToast) toast.error(message);

    if (error.response?.status === 401) {
      localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
      localStorage.removeItem(AUTH_TOKEN_NAME_STORAGE_KEY);
      window.location.href = '/login';
    }

    return Promise.reject(error);
  },
);

export async function getResult<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) {
  const response = await http.get<Result<T>>(url, { ...config, params });
  return response.data.data;
}

export async function postResult<T, D = unknown>(url: string, data?: D) {
  const response = await http.post<Result<T>>(url, data);
  return response.data.data;
}
