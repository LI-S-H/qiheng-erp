import type { AxiosRequestConfig } from 'axios';

/**
 * 对话内确认浮层的业务写请求配置。
 *
 * 这类请求发生在弹窗打开期间，不能触发全页加载遮罩，否则对话背景会被整块刷白，
 * 看起来像页面重新加载。需要全页加载反馈的普通管理页不要复用本配置。
 */
export const ORDER_CONFIRM_REQUEST_CONFIG = { skipPageLoading: true } as const satisfies AxiosRequestConfig;