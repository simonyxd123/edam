// ============================================================================
// Playwright E2E 测试配置
// 配合 Prism Mock Server (dev/mock/prism.sh) 进行端到端测试
// ============================================================================

import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

export default defineConfig({
  // 测试文件实际在 e2e/playwright/tests/ —— 原值 './tests' 指向不存在的目录，
  // playwright 直接报 "No tests found"，这套 e2e 从来没被真正执行过
  testDir: './playwright/tests',
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
  //
  // 本地和 CI 都用 Python 轻量级 mock（dev/mock/server.py，零第三方依赖）。
  //
  // 原来 CI 分支走 `docker run stoplight/prism:5` —— CI 的 job 镜像里
  // 没有 docker（也没法 docker-in-docker），那条路径从来没跑通。
  //
  // 路径修正：原来用 process.cwd() 拼 `dev/mock/server.py`，但 playwright
  // 是在 e2e/ 目录下跑的 → 拼出 e2e/dev/mock/server.py（不存在）。
  // 正确路径是相对仓库根的 ../dev/mock/server.py。
  webServer: {
    // Windows 上 `python3` 是 Microsoft Store 的别名（会报 exit 9009），
    // Linux/CI 镜像里才是真的 python3
    command: `${process.platform === 'win32' ? 'python' : 'python3'} ${path.resolve(__dirname, '../dev/mock/server.py')} 4010`,
    url: 'http://localhost:4010/health/live',
    timeout: 30 * 1000,
    reuseExistingServer: !process.env.CI,
  },
});