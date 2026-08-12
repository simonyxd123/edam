// Cypress 全局支持
// 包含：自定义命令、Hooks、全局配置

// 登录命令：缓存 access_token 到 cy.session
Cypress.Commands.add('login', (employeeNo = 'SA0001', password = 'admin123') => {
  cy.session([employeeNo, password], () => {
    cy.request({
      method: 'POST',
      url: '/auth/login',
      body: { employee_no: employeeNo, password },
    }).then((response) => {
      window.localStorage.setItem('access_token', response.body.access_token);
      window.localStorage.setItem('refresh_token', response.body.refresh_token);
    });
  });
});

// 携带 token 的请求
Cypress.Commands.add('authRequest', (options) => {
  const token = window.localStorage.getItem('access_token');
  return cy.request({
    ...options,
    headers: {
      ...options.headers,
      Authorization: `Bearer ${token}`,
    },
  });
});

// 健康检查前置
beforeEach(() => {
  cy.request({ url: '/health/live' }).its('status').should('eq', 200);
});