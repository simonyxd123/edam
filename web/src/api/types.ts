/**
 * 后端分页响应的共享类型。
 *
 * <p>此前这个结构在 `api/video.ts` / `api/document.ts` / `api/distribution.ts`
 * 里各定义了一份（外加 `views/Audit.vue` 里还有第 4 份内联的）。
 * 改字段名要同步改 4 处，漏一处就是某个页面拿不到 items。
 *
 * <p>后端统一由 `dto/PaginationResponses.java` 生成，结构见该类。
 */
export interface PageResult<T> {
  items: T[];
  pagination: {
    page: number;
    page_size: number;
    total: number;
    total_pages: number;
  };
}
