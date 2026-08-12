// ============================================================================
// Cypress E2E 测试配置
// 配合 Prism Mock Server (dev/mock/prism.sh) 进行端到端测试
// ============================================================================

const { defineConfig } = require('cypress');

module.exports = defineConfig({
  e2e: {
    baseUrl: process.env.BASE_URL || 'http://localhost:4010',
    specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}',
    supportFile: 'cypress/support/e2e.js',
    fixturesFolder: 'cypress/fixtures',
    screenshotsFolder: 'cypress/screenshots',
    videosFolder: 'cypress/videos',
    downloadsFolder: 'cypress/downloads',
    viewportWidth: 1280,
    viewportHeight: 720,
    video: false,
    screenshotOnRunFailure: true,
    defaultCommandTimeout: 10000,
    requestTimeout: 10000,
    responseTimeout: 10000,
    setupNodeEvents(on, config) {
      // 启动 Prism Mock（如未启动）
      const { exec } = require('child_process');
      on('before:run', () => {
        exec('docker ps --filter "name=edam-prism" --format "{{.Names}}"', (err, stdout) => {
          if (!stdout.includes('edam-prism')) {
            exec('docker run --rm -d -p 4010:4010 -v $PWD/doc/openapi.yaml:/tmp/openapi.yaml:ro --name edam-prism stoplight/prism:5 mock -p 4010 -h 0.0.0.0 /tmp/openapi.yaml');
          }
        });
      });
      return config;
    },
  },
});