/**
 * 日期格式化工具单测
 *
 * 覆盖后端实际返回的几种格式：
 * - ISO 8601 带时区（JacksonConfig 现在用的是 ISO_OFFSET_DATE_TIME）
 * - 旧格式 'yyyy-MM-dd HH:mm:ss.SS'
 * - epoch 毫秒
 * - 空 / 非法输入
 */
import { describe, it, expect } from 'vitest';
import { formatDateTime, formatDate, formatTime } from './date';

describe('formatDateTime', () => {
  it('空值返回 "-"', () => {
    expect(formatDateTime(null)).toBe('-');
    expect(formatDateTime(undefined)).toBe('-');
    expect(formatDateTime('')).toBe('-');
  });

  it('非法字符串返回 "-"', () => {
    expect(formatDateTime('not-a-date')).toBe('-');
  });

  it('已格式化的字符串原样保留', () => {
    // 输入 2 位小数（Java 的 .SS）→ 补零成 3 位输出
    expect(formatDateTime('2026-08-25 10:14:32.29')).toBe('2026-08-25 10:14:32.290');
  });

  it('ISO 8601 带时区可解析（不抛错、输出合法）', () => {
    const out = formatDateTime('2026-08-25T10:14:32.291+08:00');
    expect(out).not.toBe('-');
    expect(out).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$/);
  });

  it('ISO 8601 带 Z 可解析', () => {
    const out = formatDateTime('2026-08-25T02:14:32.291Z');
    expect(out).not.toBe('-');
  });

  it('epoch 毫秒可解析', () => {
    const out = formatDateTime(1756112072000);
    expect(out).not.toBe('-');
  });

  it('Date 对象可解析', () => {
    const out = formatDateTime(new Date('2026-08-25T10:14:32Z'));
    expect(out).not.toBe('-');
  });
});

describe('formatDate / formatTime', () => {
  it('formatDate 只输出日期部分', () => {
    expect(formatDate('2026-08-25 10:14:32.29')).toBe('2026-08-25');
  });

  it('formatTime 只输出时间部分', () => {
    expect(formatTime('2026-08-25 10:14:32.29')).toBe('10:14:32');
  });
});
