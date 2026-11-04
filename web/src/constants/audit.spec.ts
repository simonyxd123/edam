/**
 * 审计操作类型清单的**前后端一致性**测试。
 *
 * <p>为什么必须有这个用例：2026-11-03 之前前端 Audit.vue 自己硬编码了一份
 * `['login','logout','view','download','upload','delete','permission_change','config_change']`，
 * 后端实际写进库里的是 `create / update / detail / preview / grant_permission …` ——
 * <b>两套名字几乎对不上</b>，审计页上的"操作类型"筛选框形同虚设：
 * 选了 `upload` 查不到任何东西。
 *
 * <p>这个用例直接读后端的 {@code AuditOpType.java} 源码，
 * 把里面的规范名抠出来跟前端常量逐个比对 ——
 * 以后后端加了新操作类型而前端忘了同步，这里立刻红。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  AUDIT_OP_TYPES,
  AUDIT_OP_LABELS,
  AUDIT_OP_LEGACY_ALIAS,
  auditOpLabel,
  auditResourceLabel,
  auditResultLabel,
} from './audit';

/** 后端 AuditOpType.java 的路径（web/ 的上一级就是仓库根） */
const BACKEND_OP_TYPE_FILE = resolve(
  __dirname,
  '../../../backend/src/main/java/com/example/edam/audit/AuditOpType.java',
);

/**
 * 从后端源码里抠出规范操作类型清单。
 *
 * <p>两步：先把 `public static final String LOGIN = "login";` 这类声明解析成
 * "常量名 → 字符串值" 的映射，再把 `ALL = List.of(LOGIN, LOGOUT, …)` 里的
 * 常量名逐个换成字符串值 —— 得到的才是真正写进数据库的 operation_type。
 *
 * <p>用正则而不是真的编译 Java —— 单测环境里跑不起 maven，
 * 而这个格式在 AuditOpType 里是稳定的（代码生成式的常量声明）。
 */
function backendCanonicalOpTypes(): string[] {
  const src = readFileSync(BACKEND_OP_TYPE_FILE, 'utf-8');

  const values = new Map<string, string>();
  for (const m of src.matchAll(
    /public static final String ([A-Z0-9_]+)\s*=\s*"([^"]+)"/g,
  )) {
    values.set(m[1], m[2]);
  }

  const allMatch = src.match(
    /public static final List<String> ALL\s*=\s*List\.of\(([\s\S]*?)\);/,
  );
  expect(allMatch, '后端 AuditOpType 里必须还有 ALL 清单').not.toBeNull();
  const body = allMatch![1];

  return Array.from(body.matchAll(/([A-Z][A-Z0-9_]*)/g)).map((m) => {
    const v = values.get(m[1]);
    expect(v, `后端常量 ${m[1]} 没有对应的字符串值`).toBeTruthy();
    return v!;
  });
}

describe('审计操作类型清单', () => {
  it('前端常量与后端 AuditOpType.ALL 完全一致（不多不少）', () => {
    const backend = backendCanonicalOpTypes();
    expect(AUDIT_OP_TYPES.length).toBe(backend.length);
    expect([...AUDIT_OP_TYPES].sort()).toEqual([...backend].sort());
  });

  it('每个规范名都有中文标签（审计员看不懂 grant_permission）', () => {
    for (const op of AUDIT_OP_TYPES) {
      expect(AUDIT_OP_LABELS[op], `缺少 ${op} 的中文标签`).toBeTruthy();
    }
  });

  it('历史别名的目标必须是一个存在的规范名', () => {
    for (const [legacy, canonical] of Object.entries(AUDIT_OP_LEGACY_ALIAS)) {
      expect(AUDIT_OP_TYPES, `${legacy} 指向了不存在的规范名 ${canonical}`).toContain(canonical);
    }
  });

  it('历史别名不能与规范名重复（同一个名字只能有一种含义）', () => {
    for (const legacy of Object.keys(AUDIT_OP_LEGACY_ALIAS)) {
      expect(AUDIT_OP_TYPES as readonly string[]).not.toContain(legacy);
    }
  });
});

describe('审计标签翻译', () => {
  it('历史别名先归并再查标签', () => {
    expect(auditOpLabel('upload')).toBe(AUDIT_OP_LABELS.create);
    expect(auditOpLabel('view')).toBe(AUDIT_OP_LABELS.detail);
    expect(auditOpLabel('create_role')).toBe(AUDIT_OP_LABELS.create);
  });

  it('规范名直接翻译', () => {
    expect(auditOpLabel('play')).toBe('播放');
    expect(auditOpLabel('preview')).toBe('预览');
    expect(auditOpLabel('download')).toBe('下载');
  });

  it('未知类型回落到原值（不显示成空白）', () => {
    expect(auditOpLabel('brand_new_op')).toBe('brand_new_op');
    expect(auditOpLabel('')).toBe('-');
    expect(auditOpLabel(null)).toBe('-');
  });

  it('资源类型与结果也有中文', () => {
    expect(auditResourceLabel('video')).toBe('视频');
    expect(auditResourceLabel('document')).toBe('文档');
    expect(auditResourceLabel('audit_log')).toBe('审计日志');
    expect(auditResourceLabel('unknown_thing')).toBe('unknown_thing');
    expect(auditResultLabel('denied')).toBe('拒绝');
    expect(auditResultLabel('failure')).toBe('失败');
  });
});
