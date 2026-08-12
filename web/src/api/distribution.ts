/**
 * 外发审批 API
 */
import { api } from './client';

export interface Approval {
  id: number;
  doc_id: number;
  applicant_id: number;
  external_recipient: {
    name: string;
    email: string;
    org?: string;
  };
  reason: string;
  valid_hours: number;
  max_open_count: number;
  allow_forward: boolean;
  allow_print: boolean;
  status: 'pending' | 'approved' | 'rejected' | 'expired' | 'revoked';
  current_open_count: number;
  created_at: string;
  decided_at?: string;
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

export const distributionApi = {
  list: (params: { page?: number; page_size?: number; status?: string; applicant_id?: number }) =>
    api.get<PageResult<Approval>>('/distribution/approvals', params),

  get: (id: number) => api.get<Approval>(`/distribution/approvals/${id}`),

  create: (data: {
    doc_id: number;
    external_recipient: { name: string; email: string; org?: string };
    reason: string;
    valid_hours: number;
    max_open_count?: number;
    allow_forward?: boolean;
    allow_print?: boolean;
  }) => api.post<Approval>('/distribution/approvals', data),

  decide: (id: number, decision: 'approve' | 'reject', comment?: string) =>
    api.post<{ status: string }>(`/distribution/approvals/${id}/decide`, { decision, comment }),

  revoke: (id: number) => api.post<void>(`/distribution/approvals/${id}/revoke`),
};