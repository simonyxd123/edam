/**
 * 文档相关 API
 */
import { api } from './client';

export interface Document {
  id: number;
  title: string;
  file_type: 'docx' | 'pdf' | 'xlsx' | 'pptx' | 'image';
  file_hash: string;
  size_bytes: number;
  classification_lv: 'L1' | 'L2' | 'L3' | 'L4';
  uploader_id: number;
  upload_time: string;
  watermark_status: 'pending' | 'processing' | 'ready' | 'failed' | 'skipped';
  preview_status: 'pending' | 'processing' | 'ready' | 'failed';
}

export interface PageResult<T> {
  items: T[];
  pagination: {
    page: number;
    page_size: number;
    total: number;
    total_pages: number;
  };
}

export const documentApi = {
  list: (params: { page?: number; page_size?: number; classification_lv?: string; file_type?: string }) =>
    api.get<PageResult<Document>>('/documents', params),

  get: (id: number) => api.get<Document>(`/documents/${id}`),

  upload: (formData: FormData) =>
    api.post<{ doc_id: number; file_hash: string }>('/documents', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    } as any),

  delete: (id: number) => api.delete<void>(`/documents/${id}`),

  search: (params: { q: string; file_type?: string; page?: number; page_size?: number }) =>
    api.get<PageResult<Document> & { query: string; took_ms: number }>('/documents/search', params),
};