/**
 * 视频相关 API
 */
import { api } from './client';

export interface Video {
  id: number;
  title: string;
  description?: string;
  file_hash: string;
  duration_sec: number;
  size_bytes: number;
  classification_lv: 'L1' | 'L2' | 'L3' | 'L4';
  uploader_id: number;
  upload_time: string;
  hls_status: 'pending' | 'processing' | 'ready' | 'failed';
  fingerprint_status: 'pending' | 'processing' | 'ready' | 'failed';
  tags?: string[];
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

export const videoApi = {
  list: (params: { page?: number; page_size?: number; classification_lv?: string }) =>
    api.get<PageResult<Video>>('/videos', params),

  get: (id: number) => api.get<Video>(`/videos/${id}`),

  getPlaybackToken: (id: number) =>
    api.post<{ m3u8_url: string; token: string; session_id: string; key_url: string }>(
      `/playback/${id}/token`
    ),

  upload: (formData: FormData) =>
    api.post<{ video_id: number; file_hash: string }>('/videos', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    } as any),

  delete: (id: number) => api.delete<void>(`/videos/${id}`),
};