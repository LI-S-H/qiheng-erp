import { getResult, postResult, http } from '@/api/http';
import type {
  DeptBatchIdsPayload,
  DeptBatchStatusPayload,
  DeptStatus,
  SystemDeptFormPayload,
  SystemDeptListItem,
  SystemDeptQuery,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

// ── Mock data ──

const mockFlatDepts: SystemDeptListItem[] = [
  { deptId: '1900000000000000100', parentId: '0', ancestors: '0', deptName: '行政部', status: 1, userCount: 1, createTime: '2026-06-05 20:10:00', updateTime: '2026-06-08 09:10:00' },
  { deptId: '1900000000000000101', parentId: '0', ancestors: '0', deptName: '财务部', status: 1, userCount: 0, createTime: '2026-06-05 20:12:00', updateTime: '2026-06-07 15:00:00' },
  { deptId: '1900000000000000102', parentId: '0', ancestors: '0', deptName: '采购部', status: 1, userCount: 1, createTime: '2026-06-05 20:13:00', updateTime: '2026-06-07 17:24:11' },
  { deptId: '1900000000000000103', parentId: '0', ancestors: '0', deptName: '销售部', status: 1, userCount: 1, createTime: '2026-06-05 20:14:00', updateTime: '2026-06-07 13:00:00' },
  { deptId: '1900000000000000104', parentId: '0', ancestors: '0', deptName: '仓储部', status: 1, userCount: 1, createTime: '2026-06-05 20:15:00', updateTime: '2026-06-06 11:05:19' },
  { deptId: '1900000000000000105', parentId: '0', ancestors: '0', deptName: '产品部', status: 1, userCount: 0, createTime: '2026-06-06 09:20:00', updateTime: '2026-06-07 12:00:00' },
  { deptId: '1900000000000000106', parentId: '1900000000000000102', ancestors: '0,1900000000000000102', deptName: '供应商维护组', status: 1, userCount: 0, createTime: '2026-06-07 09:30:00', updateTime: '2026-06-07 09:30:00' },
  { deptId: '1900000000000000107', parentId: '1900000000000000102', ancestors: '0,1900000000000000102', deptName: '采购跟单组', status: 1, userCount: 0, createTime: '2026-06-07 09:40:00', updateTime: '2026-06-07 09:40:00' },
  { deptId: '1900000000000000108', parentId: '1900000000000000103', ancestors: '0,1900000000000000103', deptName: '华东销售组', status: 1, userCount: 1, createTime: '2026-06-07 10:20:00', updateTime: '2026-06-07 10:20:00' },
  { deptId: '1900000000000000109', parentId: '1900000000000000103', ancestors: '0,1900000000000000103', deptName: '华南销售组', status: 0, userCount: 0, createTime: '2026-06-07 10:30:00', updateTime: '2026-06-07 10:30:00' },
  { deptId: '1900000000000000110', parentId: '1900000000000000104', ancestors: '0,1900000000000000104', deptName: '入库作业组', status: 1, userCount: 0, createTime: '2026-06-07 11:00:00', updateTime: '2026-06-07 11:00:00' },
  { deptId: '1900000000000000111', parentId: '1900000000000000104', ancestors: '0,1900000000000000104', deptName: '出库复核组', status: 1, userCount: 0, createTime: '2026-06-07 11:10:00', updateTime: '2026-06-07 11:10:00' },
];

function getDescendantIds(deptId: string): string[] {
  const result: string[] = [];
  const queue = [deptId];
  while (queue.length > 0) {
    const currentId = queue.shift()!;
    const children = mockFlatDepts.filter(item => item.parentId === currentId);
    children.forEach(child => {
      result.push(child.deptId);
      queue.push(child.deptId);
    });
  }
  return result;
}

function hasDisabledParent(deptId: string, enabledIds: Set<string>): boolean {
  let current = mockFlatDepts.find(item => item.deptId === deptId);
  while (current && current.parentId !== '0') {
    const parent = mockFlatDepts.find(item => item.deptId === current!.parentId);
    if (!parent) return false;
    if (parent.status === 0 && !enabledIds.has(parent.deptId)) return true;
    current = parent;
  }
  return false;
}

function ensureValidParent(deptId: string, parentId: string) {
  if (deptId === parentId || getDescendantIds(deptId).includes(parentId)) {
    throw new Error('上级部门不能选择当前部门或其下级部门');
  }
}

// ── API functions ──

export function listSystemDepts(params: SystemDeptQuery) {
  if (useMockApi) {
    let result = mockFlatDepts.map(item => ({ ...item, children: undefined }));
    if (params.deptName || params.status !== '' && params.status !== 'all' && params.status !== undefined) {
      if (params.deptName?.trim()) {
        const kw = params.deptName.trim().toLowerCase();
        result = result.filter(d => d.deptName.toLowerCase().includes(kw));
      }
      if (params.status !== '' && params.status !== 'all' && params.status !== undefined) result = result.filter(d => d.status === params.status);
    }
    return Promise.resolve(result);
  }
  const { deptName, status, ...rest } = params;
  return getResult<SystemDeptListItem[]>('/system/depts', {
    ...rest,
    ...(deptName?.trim() ? { deptName: deptName.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  });
}

export function getSystemDept(deptId: string) {
  if (useMockApi) return Promise.resolve(mockFlatDepts.find(d => d.deptId === deptId) || null);
  return getResult<SystemDeptListItem>(`/system/depts/${deptId}`);
}

export function createSystemDept(payload: SystemDeptFormPayload) {
  if (useMockApi) {
    if (payload.status === 1 && payload.parentId !== '0') {
      const parent = mockFlatDepts.find(item => item.deptId === payload.parentId);
      if (parent?.status === 0) return Promise.reject(new Error('上级部门停用时不能新增启用的下级部门'));
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    const newDept: SystemDeptListItem = {
      deptId: `${Date.now()}`, parentId: payload.parentId,
      ancestors: payload.parentId === '0' ? '0' : `0,${payload.parentId}`,
      deptName: payload.deptName, status: payload.status,
      userCount: 0, createTime: now, updateTime: now,
    };
    mockFlatDepts.push(newDept);
    return Promise.resolve(newDept);
  }
  return postResult<SystemDeptListItem, SystemDeptFormPayload>('/system/depts', payload);
}

export async function updateSystemDept(deptId: string, payload: SystemDeptFormPayload) {
  if (useMockApi) {
    ensureValidParent(deptId, payload.parentId);
    if (payload.status === 1 && payload.parentId !== '0') {
      const parent = mockFlatDepts.find(item => item.deptId === payload.parentId);
      if (parent?.status === 0) throw new Error('上级部门停用时不能启用当前部门');
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    const idx = mockFlatDepts.findIndex(d => d.deptId === deptId);
    if (idx !== -1) {
      mockFlatDepts[idx] = {
        ...mockFlatDepts[idx],
        parentId: payload.parentId,
        ancestors: payload.parentId === '0' ? '0' : `0,${payload.parentId}`,
        deptName: payload.deptName,
        status: payload.status,
        updateTime: now,
      };
      if (payload.status === 0) {
        const descendantIds = new Set(getDescendantIds(deptId));
        mockFlatDepts.forEach((dept, index) => {
          if (descendantIds.has(dept.deptId)) mockFlatDepts[index] = { ...dept, status: 0, updateTime: now };
        });
      }
      return mockFlatDepts[idx];
    }
    return null;
  }
  const response = await http.put(`/system/depts/${deptId}`, payload);
  return response.data.data as SystemDeptListItem;
}

export async function updateSystemDeptStatus(deptId: string, status: DeptStatus) {
  if (useMockApi) {
    if (status === 1 && hasDisabledParent(deptId, new Set())) {
      return Promise.reject(new Error('上级部门停用时不能单独启用下级部门'));
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    const targetIds = new Set([deptId, ...(status === 0 ? getDescendantIds(deptId) : [])]);
    mockFlatDepts.forEach((dept, index) => {
      if (targetIds.has(dept.deptId)) mockFlatDepts[index] = { ...dept, status, updateTime: now };
    });
    return null;
  }
  const response = await http.patch(`/system/depts/${deptId}/status`, { status });
  return response.data.data as null;
}

export async function deleteSystemDept(deptId: string) {
  if (useMockApi) {
    const idx = mockFlatDepts.findIndex(d => d.deptId === deptId);
    if (idx === -1) return null;
    if (getDescendantIds(deptId).length > 0) throw new Error('该部门存在下级部门');
    if (mockFlatDepts[idx].userCount > 0) throw new Error('该部门已有员工归属');
    mockFlatDepts.splice(idx, 1);
    return null;
  }
  const response = await http.delete(`/system/depts/${deptId}`);
  return response.data.data as null;
}

export async function batchUpdateSystemDeptStatus(payload: DeptBatchStatusPayload) {
  if (useMockApi) {
    const selectedIds = new Set(payload.deptIds);
    if (payload.status === 1 && payload.deptIds.some(deptId => hasDisabledParent(deptId, selectedIds))) {
      return Promise.reject(new Error('上级部门停用时不能单独启用下级部门'));
    }
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
    const targetIds = new Set(payload.deptIds);
    if (payload.status === 0) {
      payload.deptIds.forEach(deptId => getDescendantIds(deptId).forEach(childId => targetIds.add(childId)));
    }
    mockFlatDepts.forEach((d, i) => {
      if (targetIds.has(d.deptId)) {
        mockFlatDepts[i] = { ...d, status: payload.status, updateTime: now };
      }
    });
    return null;
  }
  const response = await http.patch('/system/depts/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteSystemDepts(payload: DeptBatchIdsPayload) {
  if (useMockApi) {
    const targetRows = mockFlatDepts.filter(item => payload.deptIds.includes(item.deptId));
    if (targetRows.some(item => getDescendantIds(item.deptId).length > 0 || item.userCount > 0)) {
      return Promise.reject(new Error('已选部门中存在下级部门或员工归属'));
    }
    const ids = new Set(payload.deptIds);
    for (let i = mockFlatDepts.length - 1; i >= 0; i--) {
      if (ids.has(mockFlatDepts[i].deptId)) mockFlatDepts.splice(i, 1);
    }
    return Promise.resolve(null);
  }
  return postResult<null, DeptBatchIdsPayload>('/system/depts/batch/delete', payload);
}
