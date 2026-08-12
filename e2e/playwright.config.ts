// ============================================================================
// Playwright E2E 测试配置
// 配合 Prism Mock Server (dev/mock/prism.sh) 进行端到端测试
// ============================================================================

import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  // 串行执行（Prism mock 不支持并发）
  fullyParallel: false,
  workers: 1,

  // 超时配置
  timeout: 30 * 1000,
  expect: { timeout: 5 * 1000 },

  // 重试
  retries: process.env.CI ? 2 : 0,

  // 报告
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['junit', { outputFile: 'results/junit.xml' }],
  ],

  use: {
    // Prism mock 地址
    baseURL: process.env.BASE_URL || 'http://localhost:4010',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
    {
      name: 'mobile-chrome',
      use: { ...devices['Pixel 5'] },
    },
  ],

  // 启动 Mock Server 作为前置服务
  // 本地使用 Python 轻量级 mock（无需 Docker）
  // CI 中可使用 stoplight/prism:5
  webServer: {
    command: process.env.CI
      ? 'docker run --rm -p 4010:4010 -v $PWD/doc/openapi.yaml:/tmp/openapi.yaml:ro --name edam-prism stoplight/prism:5 mock -p 4010 -h 0.0.0.0 /tmp/openapi.yaml'
      : `python ${process.cwd().replace(/\\/g, '/')}/dev/mock/server.py 4010`,
    url: 'http://localhost:4010/health/live',
    timeout: 30 * 1000,
    reuseExistingServer: true,
  },
});