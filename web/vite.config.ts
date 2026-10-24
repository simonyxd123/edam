// Vite 配置
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue', 'vue-router', 'pinia'],
      dts: 'src/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        // EDAM 全链路统一端口 = 8092
        //   （与 backend/src/main/resources/application.yml 的 server.port=8092
        //    及 helm 的 backend.service.port 一致）
        // 后端跑在另一台机器/端口时，通过环境变量覆盖：
        //   VITE_API_BASE_URL=http://your.host:port  npm run dev
        target: process.env.VITE_API_BASE_URL || 'http://localhost:8092',
        changeOrigin: true,
      },
      '/ws': {
        target: process.env.VITE_WS_BASE_URL || 'ws://localhost:8092',
        ws: true,
      },
    },
  },
  build: {
    target: 'es2020',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'video-player': ['hls.js', 'dplayer', 'video.js'],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // 没有测试文件时不要让 CI 挂掉（但仓库里已有真实用例，见 src/utils/*.spec.ts）
    passWithNoTests: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      exclude: ['**/node_modules/**', '**/*.d.ts', 'src/auto-imports.d.ts', 'src/components.d.ts'],
    },
  },
});