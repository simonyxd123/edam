/**
 * 水印与溯源 API
 */
import { api } from './client';

export interface WatermarkExtractResult {
  type: 'image' | 'pdf' | 'video' | 'docx' | 'xlsx';
  extracted: {
    employee_no: string;
    extract_time: string;
    fingerprint?: string;
    confidence: number;
  };
  matched_users: Array<{
    user_id: number;
    employee_no: string;
    real_name: string;
    match_score: number;
    match_time: string;
  }>;
}

export const watermarkApi = {
  extract: (formData: FormData) =>
    api.post<WatermarkExtractResult>('/watermarks/extract', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    } as any),

  getCache: (resourceId: number, resourceType: 'video' | 'document') =>
    api.get<unknown[]>(`/watermarks/cache?resource_id=${resourceId}&resource_type=${resourceType}`),

  invalidateCache: (cacheId: number) =>
    api.delete<void>(`/watermarks/cache/${cacheId}`),
};