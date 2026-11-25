/** 当前业务日，格式 yyyy-MM-dd，按浏览器本地时区计算。 */
export function todayDateString(): string {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 10);
}

/** 判断业务日期是否早于今天；空值表示未填写，不拦截。 */
export function isPastDate(value: string | null | undefined): boolean {
  if (!value) {
    return false;
  }
  return value < todayDateString();
}