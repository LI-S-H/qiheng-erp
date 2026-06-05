export interface Result<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  pageNo: number;
  pageSize: number;
}

export interface ApiErrorPayload {
  code: number;
  message: string;
}
