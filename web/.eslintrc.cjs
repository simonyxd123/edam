// ESLint 配置（eslint 8.x 传统格式）
//
// 此前 web/ 目录下**没有任何 eslint 配置**，CI 的 `npm run lint`
// 直接报 "ESLint couldn't find a configuration file" 退出 ——
// 前端 lint 环节从来没真正跑过。
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true,
  },
  parser: 'vue-eslint-parser',
  parserOptions: {
    parser: '@typescript-eslint/parser',
    ecmaVersion: 'latest',
    sourceType: 'module',
    extraFileExtensions: ['.vue'],
  },
  plugins: ['@typescript-eslint'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
  ],
  rules: {
    // 未使用变量：警告（不阻断 CI，避免存量代码卡住流水线）
    '@typescript-eslint/no-unused-vars': [
      'warn',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
    ],
    // 任何类型都收（前端大量使用 any 做后端契约占位）
    '@typescript-eslint/no-explicit-any': 'off',
    // Vue 多词组件名
    'vue/multi-word-component-names': 'off',
    // 空 catch 块：允许（前端很多 catch 是"错误已处理"）
    'no-empty': ['error', { allowEmptyCatch: true }],
  },
  overrides: [
    {
      files: ['**/*.vue'],
      rules: {
        // 模板里的未知组件不报错（Element Plus 走 unplugin 自动注册）
        'vue/no-undef-components': 'off',
      },
    },
  ],
  ignorePatterns: [
    'dist/',
    'node_modules/',
    'coverage/',
    'src/auto-imports.d.ts',
    'src/components.d.ts',
  ],
};
