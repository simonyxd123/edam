package com.example.edam.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明"这个接口是一次需要留痕的操作"。
 *
 * <p>由 {@link AuditAspect} 统一处理：成功 / 失败 / 拒绝三种结果都会落库，
 * 并自动带上操作者、真实 IP、User-Agent、HTTP 方法、命中的路由模板、状态码、耗时。
 *
 * <p><b>为什么用切面而不是在每个方法体里手写 auditService.log</b>：
 * 手写只能记到"走到那行为止"，一旦方法在权限校验、参数校验、状态机判断处
 * 抛出异常，<b>这条操作就完全没进日志</b>。而在防泄密系统里，
 * "越权尝试"恰恰是最该被记下来的一类 —— 它恰恰是那些会在中途抛异常的请求。
 * 切面包住整个方法调用，三种结果一个不落。
 *
 * <p><b>用法</b>：
 * <pre>{@code
 * @GetMapping("/{video_id}")
 * @AuditOp(op = AuditOpType.DETAIL, resourceType = "video",
 *          resourceId = "#videoId", detail = "title=#video.title")
 * public ResponseEntity<?> getById(@PathVariable("video_id") Long videoId, ...) { ... }
 * }</pre>
 *
 * <p><b>三个属性里只有 {@code resourceId} / {@code userId} 是单个 SpEL 表达式</b>
 * （{@code #videoId}、{@code #result?.body?.get('user_id')}）。
 *
 * <p><b>{@code detail} 不是 SpEL 表达式，而是「{@code k=v, k2=v2}」模板</b>：
 * 每个 {@code v} 单独求值，键是字面量。
 * <b>不能</b>把整串丢给 SpEL —— SpEL 会把 {@code =} 当成**赋值运算符**，
 * 于是 {@code "employee_no=#request.employeeNo"} 报
 * {@code EL1009E: cannot be set on null}，带逗号的报 {@code EL1041E}。
 *
 * <p><b>SpEL 可用的变量</b>：方法的所有入参（按参数名，如 {@code #videoId}），
 * 以及位置别名 {@code #p0}、{@code #p1}…（不依赖编译参数名，最保险）。
 *
 * <p>对应方案书 <b>§7.1</b>（operation_log 表）与 <b>§14.3 STRIDE / Repudiation</b>。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditOp {

    /**
     * 操作类型，取 {@link AuditOpType} 里的常量。
     */
    String op();

    /**
     * 资源类型，如 {@code video} / {@code document} / {@code user} / {@code role} / {@code audit_log}。
     *
     * <p>留空则记成 {@code system}。
     */
    String resourceType() default "";

    /**
     * 资源 ID 的 SpEL 表达式，如 {@code #videoId}。求值失败不影响业务，只是不记 ID。
     */
    String resourceId() default "";

    /**
     * 操作者的 SpEL 表达式，**留空则用当前登录用户**（从 JWT 取）。
     *
     * <p>什么时候需要显式写：登录接口。此时还没有 JWT，
     * SecurityContext 里没有身份，但登录成功的响应体里有 {@code user_id}，
     * 写成 {@code #result?.body?.get('user_id')} 就能把它填进审计记录 ——
     * 否则登录记录的操作者永远是 null，审计页上查不出是谁登的。
     *
     * <p>取值失败时退回到"当前登录用户"，再失败就是 null（登录失败场景）。
     */
    String userId() default "";

    /**
     * 补充说明，格式为 <b>{@code k=v, k2=v2}</b> 模板。
     *
     * <p><b>每个 {@code v} 单独按 SpEL 求值</b>（键是字面量，不参与求值）。
     * 整串**不是**一个 SpEL 表达式 —— SpEL 会把 {@code =} 解析成赋值运算符，
     * 那样求值必然失败。
     *
     * <p>求不出值的项保留原文（{@code #noSuchVar}），不整段丢弃。
     *
     * <p>建议只放**不敏感**的字段（标题、大小、密级、条数）。
     * 凭据类字段一律不许出现 —— 用 {@link AuditDetail} 拼装时它会自动脱敏，
     * 但直接写字符串字面量则绕过了脱敏。
     */
    String detail() default "";

    /**
     * 是否屏蔽"请求参数摘要"。
     *
     * <p>登录、改密码这类接口的参数里天然带凭据，整条摘要都不该落库 ⇒ 置 true。
     */
    boolean hideParams() default false;

    /**
     * 成功的调用是否也要记。
     *
     * <p><b>什么时候置 false</b>：调用量极大、而单次成功没有信息量的接口 ——
     * 比如 HLS 分片拉取（一个 10 分钟视频就是几十个分片）。
     * 全部记下来，审计表会被正常流量淹没，
     * 真正该看的"拉分片失败"反而找不到。
     *
     * <p>置 false 时<b>只有失败与拒绝才会留痕</b> ——
     * 对这类接口来说，"失败"才是信号，"成功"是噪声。
     */
    boolean auditSuccess() default true;
}
