// ============================================================================
// Cypress E2E: 完整登录流程
// 模拟用户从登录到访问受保护资源的端到端流程
// ============================================================================

describe('鉴权 E2E 流程', () => {
  it('完整登录 → 获取用户信息 → 登出', () => {
    // 1. 登录
    cy.request({
      method: 'POST',
      url: '/auth/login',
      body: {
        employee_no: 'SA0001',
        password: 'admin123',
      },
    }).then((loginResp) => {
      expect(loginResp.status).to.eq(200);
      const { access_token, refresh_token } = loginResp.body;
      expect(access_token).to.exist;
      expect(refresh_token).to.exist;

      // 2. 使用 token 获取当前用户
      cy.request({
        url: '/auth/me',
        headers: { Authorization: `Bearer ${access_token}` },
      }).then((meResp) => {
        expect(meResp.status).to.eq(200);
        expect(meResp.body.employee_no).to.eq('SA0001');
      });

      // 3. 刷新 token
      cy.request({
        method: 'POST',
        url: '/auth/refresh',
        body: { refresh_token },
      }).then((refreshResp) => {
        expect(refreshResp.status).to.eq(200);
        expect(refreshResp.body.access_token).to.exist;
      });

      // 4. 登出
      cy.request({
        method: 'POST',
        url: '/auth/logout',
        headers: { Authorization: `Bearer ${access_token}` },
      }).then((logoutResp) => {
        expect(logoutResp.status).to.eq(204);
      });
    });
  });

  it('健康检查端点', () => {
    cy.request('/health/live').its('status').should('eq', 200);
    cy.request('/health/ready').its('status').should('be.oneOf', [200, 503]);
  });
});

describe('业务功能 E2E', () => {
  beforeEach(() => {
    cy.login();
  });

  it('视频列表分页', () => {
    cy.authRequest({ url: '/videos?page=1&page_size=20' }).then((resp) => {
      expect(resp.status).to.eq(200);
      expect(resp.body.items).to.be.an('array');
    });
  });

  it('按密级过滤 L3 视频', () => {
    cy.authRequest({ url: '/videos?classification_lv=L3' }).then((resp) => {
      expect(resp.status).to.eq(200);
      resp.body.items.forEach((video) => {
        expect(video.classification_lv).to.eq('L3');
      });
    });
  });

  it('外发审批完整流程', () => {
    // 1. 创建一个测试文档
    cy.authRequest({
      method: 'POST',
      url: '/documents',
      multipart: true,
      body: {
        file: {
          name: 'test.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('test'),
        },
        classification_lv: 'L2',
        title: 'Test Doc',
      },
    }).then((docResp) => {
      expect(docResp.status).to.be.oneOf([200, 202]);
      const docId = docResp.body.doc_id;

      // 2. 发起外发审批
      cy.authRequest({
        method: 'POST',
        url: '/distribution/approvals',
        body: {
          doc_id: docId,
          external_recipient: {
            name: 'External User',
            email: 'external@example.com',
          },
          reason: 'Test distribution for e2e',
          valid_hours: 24,
        },
      }).then((approvalResp) => {
        expect(approvalResp.status).to.eq(201);
        expect(approvalResp.body.status).to.eq('pending');
      });
    });
  });
});