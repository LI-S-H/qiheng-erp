import { getResult, postResult, http } from '@/api/http';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeStringId } from '@/shared/utils/api-normalizers';
import type {
  ProductCategoryBatchIdsPayload,
  ProductCategoryBatchStatusPayload,
  ProductCategoryFormPayload,
  ProductCategoryListItem,
  ProductCategoryStatus,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const mockFlatCategories: ProductCategoryListItem[] = [
  { categoryId: '1910000000000000100', parentId: '0', categoryName: '食品饮料', status: 1, productCount: 0, createTime: '2026-06-09 09:10:00', updateTime: '2026-06-12 14:20:00' },
  { categoryId: '1910000000000000101', parentId: '1910000000000000100', categoryName: '饮料冲调', status: 1, productCount: 18, createTime: '2026-06-09 09:20:00', updateTime: '2026-06-12 10:16:00' },
  { categoryId: '1910000000000000102', parentId: '1910000000000000100', categoryName: '休闲零食', status: 1, productCount: 26, createTime: '2026-06-09 09:25:00', updateTime: '2026-06-11 16:40:00' },
  { categoryId: '1910000000000000103', parentId: '0', categoryName: '办公用品', status: 1, productCount: 0, createTime: '2026-06-09 10:00:00', updateTime: '2026-06-12 09:30:00' },
  { categoryId: '1910000000000000104', parentId: '1910000000000000103', categoryName: '书写文具', status: 1, productCount: 12, createTime: '2026-06-09 10:10:00', updateTime: '2026-06-10 15:20:00' },
  { categoryId: '1910000000000000105', parentId: '1910000000000000103', categoryName: '办公纸品', status: 1, productCount: 9, createTime: '2026-06-09 10:15:00', updateTime: '2026-06-10 15:22:00' },
  { categoryId: '1910000000000000106', parentId: '0', categoryName: '日用百货', status: 1, productCount: 3, createTime: '2026-06-09 11:00:00', updateTime: '2026-06-12 11:45:00' },
  { categoryId: '1910000000000000107', parentId: '1910000000000000106', categoryName: '清洁用品', status: 1, productCount: 15, createTime: '2026-06-09 11:10:00', updateTime: '2026-06-12 11:46:00' },
  { categoryId: '1910000000000000108', parentId: '1910000000000000106', categoryName: '家居耗材', status: 1, productCount: 8, createTime: '2026-06-09 11:20:00', updateTime: '2026-06-11 12:00:00' },
  { categoryId: '1910000000000000109', parentId: '0', categoryName: '电子配件', status: 0, productCount: 0, createTime: '2026-06-10 09:00:00', updateTime: '2026-06-12 16:30:00' },
  { categoryId: '1910000000000000110', parentId: '1910000000000000109', categoryName: '电脑周边', status: 0, productCount: 6, createTime: '2026-06-10 09:10:00', updateTime: '2026-06-12 16:30:00' },
];

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function findCategory(categoryId: string) {
  return mockFlatCategories.find(item => item.categoryId === categoryId);
}

function getDescendantIds(categoryId: string): string[] {
  const result: string[] = [];
  const queue = [categoryId];
  while (queue.length > 0) {
    const currentId = queue.shift()!;
    mockFlatCategories.filter(item => item.parentId === currentId).forEach(child => {
      result.push(child.categoryId);
      queue.push(child.categoryId);
    });
  }
  return result;
}

export function getMockProductCategorySnapshot(categoryId: string) {
  const category = findCategory(categoryId);
  return category ? { ...category } : undefined;
}

export function getMockProductCategoryScope(categoryId: string) {
  return new Set([categoryId, ...getDescendantIds(categoryId)]);
}

function hasDisabledParent(categoryId: string, enabledIds: Set<string>) {
  let current = findCategory(categoryId);
  while (current && current.parentId !== '0') {
    const parent = findCategory(current.parentId);
    if (!parent) return false;
    if (parent.status === 0 && !enabledIds.has(parent.categoryId)) return true;
    current = parent;
  }
  return false;
}

function ensureUniqueName(parentId: string, categoryName: string, excludeCategoryId = '') {
  const normalizedName = categoryName.trim().toLocaleLowerCase();
  const duplicate = mockFlatCategories.some(item =>
    item.categoryId !== excludeCategoryId
    && item.parentId === parentId
    && item.categoryName.trim().toLocaleLowerCase() === normalizedName,
  );
  if (duplicate) throw new Error('同级分类名称已存在');
}

function ensureValidParent(categoryId: string, parentId: string) {
  if (categoryId === parentId || getDescendantIds(categoryId).includes(parentId)) {
    throw new Error('上级分类不能选择当前分类或其下级分类');
  }
}

function normalizeCategory(item: ProductCategoryListItem): ProductCategoryListItem {
  return {
    ...item,
    categoryId: normalizeStringId(item.categoryId, 'categoryId'),
    parentId: normalizeStringId(item.parentId, 'parentId'),
    status: normalizeBinaryStatus(item.status),
    productCount: normalizeFiniteNumber(item.productCount, 'productCount'),
    children: undefined,
  };
}

export function listProductCategories() {
  if (useMockApi) return Promise.resolve(mockFlatCategories.map(normalizeCategory));
  return getResult<ProductCategoryListItem[]>('/product/categories').then(items => items.map(normalizeCategory));
}

export function getProductCategory(categoryId: string) {
  if (useMockApi) {
    const category = findCategory(categoryId);
    return Promise.resolve(category ? normalizeCategory(category) : null);
  }
  return getResult<ProductCategoryListItem>(`/product/categories/${categoryId}`).then(normalizeCategory);
}

export function createProductCategory(payload: ProductCategoryFormPayload) {
  if (useMockApi) {
    ensureUniqueName(payload.parentId, payload.categoryName);
    if (payload.status === 1 && payload.parentId !== '0' && findCategory(payload.parentId)?.status === 0) {
      return Promise.reject(new Error('上级分类停用时不能新增启用的下级分类'));
    }
    const createTime = nowText();
    const newCategory: ProductCategoryListItem = {
      categoryId: `${Date.now()}`,
      parentId: payload.parentId,
      categoryName: payload.categoryName,
      status: payload.status,
      productCount: 0,
      createTime,
      updateTime: createTime,
    };
    mockFlatCategories.push(newCategory);
    return Promise.resolve(normalizeCategory(newCategory));
  }
  return postResult<ProductCategoryListItem, ProductCategoryFormPayload>('/product/categories', payload).then(normalizeCategory);
}

export async function updateProductCategory(categoryId: string, payload: ProductCategoryFormPayload) {
  if (useMockApi) {
    ensureValidParent(categoryId, payload.parentId);
    ensureUniqueName(payload.parentId, payload.categoryName, categoryId);
    if (payload.status === 1 && payload.parentId !== '0' && findCategory(payload.parentId)?.status === 0) {
      throw new Error('上级分类停用时不能启用当前分类');
    }
    const targetIds = new Set([categoryId, ...(payload.status === 0 ? getDescendantIds(categoryId) : [])]);
    mockFlatCategories.forEach((item, index) => {
      if (item.categoryId === categoryId) {
        mockFlatCategories[index] = { ...item, ...payload, updateTime: nowText() };
      } else if (targetIds.has(item.categoryId)) {
        mockFlatCategories[index] = { ...item, status: 0, updateTime: nowText() };
      }
    });
    const category = findCategory(categoryId);
    return category ? normalizeCategory(category) : null;
  }
  const response = await http.put(`/product/categories/${categoryId}`, payload);
  return normalizeCategory(response.data.data as ProductCategoryListItem);
}

export async function updateProductCategoryStatus(categoryId: string, status: ProductCategoryStatus) {
  if (useMockApi) {
    await batchUpdateProductCategoryStatus({ categoryIds: [categoryId], status });
    return null;
  }
  const response = await http.patch(`/product/categories/${categoryId}/status`, { status });
  return response.data.data as null;
}

export async function deleteProductCategory(categoryId: string) {
  if (useMockApi) {
    const target = findCategory(categoryId);
    if (!target) return null;
    if (getDescendantIds(categoryId).length > 0) throw new Error('该分类存在下级分类');
    if (target.productCount > 0) throw new Error('该分类已关联产品');
    mockFlatCategories.splice(mockFlatCategories.indexOf(target), 1);
    return null;
  }
  const response = await http.delete(`/product/categories/${categoryId}`);
  return response.data.data as null;
}

export async function batchUpdateProductCategoryStatus(payload: ProductCategoryBatchStatusPayload) {
  if (useMockApi) {
    const selectedIds = new Set(payload.categoryIds);
    if (payload.status === 1 && payload.categoryIds.some(categoryId => hasDisabledParent(categoryId, selectedIds))) {
      throw new Error('上级分类停用时不能单独启用下级分类');
    }
    const targetIds = new Set(payload.categoryIds);
    if (payload.status === 0) {
      payload.categoryIds.forEach(categoryId => getDescendantIds(categoryId).forEach(id => targetIds.add(id)));
    }
    const updateTime = nowText();
    mockFlatCategories.forEach((item, index) => {
      if (targetIds.has(item.categoryId)) mockFlatCategories[index] = { ...item, status: payload.status, updateTime };
    });
    return null;
  }
  const response = await http.patch('/product/categories/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteProductCategories(payload: ProductCategoryBatchIdsPayload) {
  if (useMockApi) {
    const targetRows = mockFlatCategories.filter(item => payload.categoryIds.includes(item.categoryId));
    if (targetRows.some(item => getDescendantIds(item.categoryId).length > 0 || item.productCount > 0)) {
      return Promise.reject(new Error('已选分类中存在下级分类或关联产品'));
    }
    const ids = new Set(payload.categoryIds);
    for (let index = mockFlatCategories.length - 1; index >= 0; index -= 1) {
      if (ids.has(mockFlatCategories[index].categoryId)) mockFlatCategories.splice(index, 1);
    }
    return Promise.resolve(null);
  }
  return postResult<null, ProductCategoryBatchIdsPayload>('/product/categories/batch/delete', payload);
}
