/**
 * 全局模板属性类型声明（main.ts 注册的全局方法）
 */
declare module '@vue/runtime-core' {
  interface ComponentCustomProperties {
    $fmt: (input: string | number | Date | null | undefined, fmt?: string) => string;
    $fmtDate: (input: string | number | Date | null | undefined) => string;
    $fmtTime: (input: string | number | Date | null | undefined) => string;
  }
}

export {};