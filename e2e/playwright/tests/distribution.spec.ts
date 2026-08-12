// ============================================================================
// E2E: 文档外发审批流程
// 覆盖：发起 → 审批 → 撤销
// ============================================================================

import { test, expect } from '@playwright/test';

let accessToken: string;
let approverToken: string;

test.beforeAll(async ({ request }) => {
  // 申请人 token
  const resp = await request.post('/auth/login', {
    data: { employee_no: 'SA0001', password: 'admin123' },
  });
  const body = await resp.json();
  accessToken = body.access_token;

  // 审批人 token
  approverToken = accessToken; // 测试中复用
});

test.describe('文档外发审批', () => {
  test('发起外发审批 → 等待审批 → 批准', async ({ request }) => {
    // 1. 上传测试文档
    const uploadResp = await request.post('/documents', {
      headers: { Authorization: `Bearer ${accessToken}` },
      multipart: {
        file: {
          name: 'confidential.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('confidential content'),
        },
        classification_lv: 'L3',
        title: 'Confidential Report',
      },
    });
    const { doc_id } = await uploadResp.json();
    expect(doc_id).toBeDefined();

    // 2. 发起外发审批
    const approvalResp = await request.post('/distribution/approvals', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: {
        doc_id,
        external_recipient: {
          name: 'Partner A',
          email: 'partner@example.com',
          org: 'External Co.',
        },
        reason: 'Q3 review with partner, need to share report',
        valid_hours: 48,
        max_open_count: 3,
        allow_forward: false,
        allow_print: true,
      },
    });
    expect(approvalResp.status()).toBe(201);
    const approval = await approvalResp.json();
    expect(approval.status).toBe('pending');

    // 3. 审批人查询待办
    const listResp = await request.get('/distribution/approvals?status=pending', {
      headers: { Authorization: `Bearer ${approverToken}` },
    });
    expect(listResp.status()).toBe(200);
    const list = await listResp.json();
    expect(list.items.some((a: any) => a.id === approval.id)).toBeTruthy();

    // 4. 审批人批准
    const decideResp = await request.post(`/distribution/approvals/${approval.id}/decide`, {
      headers: { Authorization: `Bearer ${approverToken}` },
      data: { decision: 'approve', comment: 'OK, approved' },
    });
    expect(decideResp.status()).toBe(200);

    // 5. 验证状态变更
    const detailResp = await request.get(`/distribution/approvals/${approval.id}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const detail = await detailResp.json();
    expect(detail.status).toBe('approved');
  });

  test('审批驳回', async ({ request }) => {
    // 创建审批
    const uploadResp = await request.post('/documents', {
      headers: { Authorization: `Bearer ${accessToken}` },
      multipart: {
        file: {
          name: 'test.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('test'),
        },
        classification_lv: 'L4',
      },
    });
    const { doc_id } = await uploadResp.json();

    const approvalResp = await request.post('/distribution/approvals', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: {
        doc_id,
        external_recipient: { name: 'X', email: 'x@example.com' },
        reason: 'Test rejection flow',
        valid_hours: 24,
      },
    });
    const approval = await approvalResp.json();

    // 驳回
    const rejectResp = await request.post(`/distribution/approvals/${approval.id}/decide`, {
      headers: { Authorization: `Bearer ${approverToken}` },
      data: { decision: 'reject', comment: 'L4 too sensitive' },
    });
    expect(rejectResp.status()).toBe(200);

    const detail = await (await request.get(`/distribution/approvals/${approval.id}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })).json();
    expect(detail.status).toBe('rejected');
  });

  test('已批准外发紧急撤销', async ({ request }) => {
    // 创建并批准
    const uploadResp = await request.post('/documents', {
      headers: { Authorization: `Bearer ${accessToken}` },
      multipart: {
        file: { name: 'doc.pdf', mimeType: 'application/pdf', buffer: Buffer.from('x') },
        classification_lv: 'L3',
      },
    });
    const { doc_id } = await uploadResp.json();

    const approvalResp = await request.post('/distribution/approvals', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: {
        doc_id,
        external_recipient: { name: 'Y', email: 'y@example.com' },
        reason: 'For revocation test',
        valid_hours: 24,
      },
    });
    const approval = await approvalResp.json();

    await request.post(`/distribution/approvals/${approval.id}/decide`, {
      headers: { Authorization: `Bearer ${approverToken}` },
      data: { decision: 'approve' },
    });

    // 紧急撤销
    const revokeResp = await request.post(`/distribution/approvals/${approval.id}/revoke`, {
      headers: { Authorization: `Bearer ${approverToken}` },
    });
    expect(revokeResp.status()).toBe(204);

    const detail = await (await request.get(`/distribution/approvals/${approval.id}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })).json();
    expect(detail.status).toBe('revoked');
  });
});