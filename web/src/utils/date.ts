/**
 * 日期格式化工具（与后端 JacksonConfig 保持一致）
 *
 * 后端 Java Spring 默认输出：yyyy-MM-dd HH:mm:ss.SS (Asia/Shanghai)
 * 前端这里兼容三种输入：
 *   1) 已是格式化字符串（'2026-08-25 10:14:32.29'）→ 原样返回
 *   2) ISO 8601 字符串（'2026-08-25T02:14:32.291Z'）→ 转 CST 后格式化
 *   3) epoch 毫秒（number）→ 转 CST 后格式化
 *   4) null / 空 / 解析失败 → '-'
 *
 * 模板中调用示例：{{ formatDateTime(row.upload_time) }}
 */
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import customParseFormat from 'dayjs/plugin/customParseFormat';

dayjs.extend(utc);
dayjs.extend(customParseFormat);

/** 完整日期时间：yyyy-MM-dd HH:mm:ss.SS */
export const FMT_DATETIME = 'YYYY-MM-DD HH:mm:ss.SS';
/** 仅日期：yyyy-MM-dd */
export const FMT_DATE = 'YYYY-MM-DD';
/** 仅时间：HH:mm:ss */
export const FMT_TIME = 'HH:mm:ss';

/**
 * 解析任意输入（已格式化 / ISO / epoch 毫秒）为 dayjs 实例（CST）。
 * 解析失败返回 null（调用方降级显示 '-'）。
 */
function toCst(input: string | number | Date | null | undefined): dayjs.Dayjs | null {
  if (input === null || input === undefined || input === '') return null;

  // 1) 后端已格式化的字符串（不含 T 也不含 Z）—— 原样视为本地时间
  if (typeof input === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(input)) {
    const d = dayjs(input, ['YYYY-MM-DD HH:mm:ss.SS', 'YYYY-MM-DD HH:mm:ss']);
    return d.isValid() ? d : null;
  }

  // 2) ISO 8601（含 T 或 Z）—— dayjs 自动解析，按 UTC 处理
  // 3) epoch 毫秒 / Date —— dayjs 自动识别
  const d = dayjs(input);
  return d.isValid() ? d : null;
}

/**
 * 通用格式化入口（默认 datetime）
 */
export function formatDateTime(
  input: string | number | Date | null | undefined,
  fmt: string = FMT_DATETIME,
): string {
  const d = toCst(input);
  if (!d) return '-';
  // 字符串输入是已格式化时不要再次转换时区；utc().local() 不会改变无时区时间
  if (typeof input === 'string' && !/[T.Z]/.test(input)) {
    return d.format(fmt);
  }
  return d.format(fmt);
}

export function formatDate(input: string | number | Date | null | undefined): string {
  return formatDateTime(input, FMT_DATE);
}

export function formatTime(input: string | number | Date | null | undefined): string {
  return formatDateTime(input, FMT_TIME);
}

/** Vue 模板里直接用：{{ formatDateTime(row.upload_time) }} */
export default {
  formatDateTime,
  formatDate,
  formatTime,
};