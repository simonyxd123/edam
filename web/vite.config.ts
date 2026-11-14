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
        // xfwd: 代理时补上 X-Forwarded-For（http-proxy 的 x-forwarded-for）。
        //
        // 不开这个，dev server 对后端来说就是"客户端"（peer = 127.0.0.1），
        // 后端拿不到浏览器的地址 ⇒ **审计日志里所有 IP 都是 127.0.0.1**，
        // 登录限流也会把所有人算成同一个 IP。
        //
        // http-proxy 是**追加**到已有的 XFF 末尾（客户端伪造的值在前、真实值在后），
        // 与 nginx 的 $proxy_add_x_forwarded_for 行为一致 ⇒ 后端统一从右往左取。
        xfwd: true,
      },
      '/ws': {
        target: process.env.VITE_WS_BASE_URL || 'ws://localhost:8092',
        ws: true,
        xfwd: true,
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
    server: {
      deps: {
        // element-plus 的 ESM 入口会 import 自己的 CSS（theme-chalk/base.css）。
        // vitest 默认把 node_modules 里的依赖**外置**给 Node 原生加载，
        // 而 Node 不认 ".css" 扩展名 ⇒ 收集/导航阶段直接抛
        // ERR_UNKNOWN_FILE_EXTENSION。
        //
        // 路由守卫的用例会真的 push 一次路由，而 vue-router 4 在**导航过程中**
        // 就会 await 懒加载组件 ⇒ 组件里自动引入的 element-plus 组件被加载
        // ⇒ 正好踩到这个坑。把 element-plus 标成 inline，让 vite 走
        // 常规的 CSS 处理（测试里 CSS 会被丢弃）。
        inline: ['element-plus'],
      },
    },
    // 没有测试文件时不要让 CI 挂掉（但仓库里已有真实用例，见 src/utils/*.spec.ts）
    passWithNoTests: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      exclude: ['**/node_modules/**', '**/*.d.ts', 'src/auto-imports.d.ts', 'src/components.d.ts'],
    },
  },
});