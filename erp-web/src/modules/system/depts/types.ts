export type DeptStatus = 0 | 1;

export interface SystemDeptListItem {
  deptId: string;
  parentId: string;
  ancestors: string;
  deptName: string;
  status: DeptStatus;
  userCount: number;
  children?: SystemDeptListItem[];
  createTime: string;
  updateTime: string;
}

export interface SystemDeptQuery {
  deptName?: string;
  status?: DeptStatus | '' | 'all';
}

export interface SystemDeptFormPayload {
  parentId: string;
  deptName: string;
  status: DeptStatus;
}

export interface DeptOption {
  deptId: string;
  deptName: string;
  parentId: string;
  status: DeptStatus;
}

export interface DeptBatchIdsPayload {
  deptIds: string[];
}

export interface DeptBatchStatusPayload extends DeptBatchIdsPayload {
  status: DeptStatus;
}
