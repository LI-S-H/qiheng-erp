import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { ElMessage } from 'element-plus';
import { AUTH_TOKEN_STORAGE_KEY } from '@/shared/constants/storage';
import type { ApiErrorPayload, Result } from '@/shared/types/api';

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api';

export const http = axios.create({
  baseURL,
  timeout: 15000,
});

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY);

  if (token) {
    config.headers.set('satoken', token);
  }

  return config;
});

http.interceptors.response.use(
  response => {
    const result = response.data as Result<unknown>;

    if (typeof result?.code === 'number' && result.code !== 0) {
      ElMessage.error(result.message || '请求处理失败');
      return Promise.reject(result);
    }

    return response;
  },
  (error: AxiosError<ApiErrorPayload>) => {
    const message = error.response?.data?.message || error.message || '网络请求异常';
    ElMessage.error(message);

    if (error.response?.status === 401) {
      localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY);
      window.location.href = '/login';
    }

    return Promise.reject(error);
  },
);

export async function getResult<T>(url: string, params?: Record<string, unknown>) {
  const response = await http.get<Result<T>>(url, { params });
  return response.data.data;
}

export async function postResult<T, D = unknown>(url: string, data?: D) {
  const response = await http.post<Result<T>>(url, data);
  return response.data.data;
}
