package com.example.edam.audit;

import com.example.edam.security.CurrentUser;
import com.example.edam.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计切面：把 {@link AuditOp} 标注的接口调用<b>一律</b>写进 operation_log。
 *
 * <p><b>覆盖三种结果</b>（这是本切面存在的理由）：
 * <ul>
 *   <li>{@code success} —— 正常返回</li>
 *   <li>{@code denied}  —— 未认证（401）或权限不足（403）</li>
 *   <li>{@code failure} —— 其它异常（参数非法、资源不存在、基础设施故障…）</li>
 * </ul>
 *
 * <p>手写 {@code auditService.log(...)} 只能记到"走到那行为止"：
 * 一旦方法在权限校验处抛异常，操作就完全没进日志。
 * 而"越权访问未授权视频"恰恰是防泄密系统里最该被记录的一类事件。
 *
 * <p><b>为什么要 {@code @Order(HIGHEST_PRECEDENCE)}</b>：
 * {@code @PreAuthorize} 是由 Spring Security 的方法级拦截器（一个 Advisor）执行的，
 * 它的顺序比普通 Advisor 高。切面若排在它<b>里面</b>，
 * 权限不足抛出的 {@code AccessDeniedException} 就发生在切面之外 ——
 * 最该记的"越权尝试"反而一条不落地漏掉。
 * 把自己排成最外层，才能把授权拦截也包进来。
 *
 * <p><b>注意</b>：排成最外层意味着本切面<b>先于</b> Spring 的
 * {@code ExposeInvocationInterceptor}（order = {@code HIGHEST_PRECEDENCE + 1}）执行。
 * 这正是 {@code @Around("@annotation(auditOp)")} 这种"把注解绑成 advice 形参"的写法会炸的原因
 * —— 注解绑不上 ⇒ 每个被审计的接口 500。
 * 本切面<b>不</b>依赖那条链路：{@link AuditOp} 注解是自己从方法签名上取的
 * （见 {@link #resolveAuditOp}），所以 order 想放多靠外就放多靠外。
 *
 * <p>过滤器层（JWT 校验失败）的 401 仍然进不到切面，
 * 那一类由 {@code AuditAccessFilter} 兜底。
 *
 * <p>对应方案书 <b>§7.1</b>（operation_log）与 <b>§14.3 STRIDE / Repudiation</b>
 * （"员工否认操作行为 → 操作日志全审计"）。
 */
@Slf4j
@Aspect
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class AuditAspect {

    /**
     * 标记"这个请求已经被切面记过审计"的请求属性名。
     *
     * <p>{@link AuditAccessFilter} 靠它避免重复记账：
     * 过滤器记的是"没进到控制器就被拒"的请求，切面记的是"进了控制器"的请求。
     */
    public static final String AUDITED_REQUEST_ATTR = "edam.audit.recorded";

    /**
     * 允许控制器**手工指定**操作者的请求属性名。
     *
     * <p>用途：{@code /playback/*} 那三个端点是 {@code permitAll} 的
     * （Hls.js 带不了自定义头，只能把 token 放 query string），
     * 所以 Spring Security 的 SecurityContext 里没有身份。
     * 但播放 token 里有 —— 控制器校验完 token 后把 user_id 塞进这个属性，
     * 审计记录就能记成"谁在拉流"，而不是一个没有主人的 access_denied。
     */
    public static final String USER_ID_REQUEST_ATTR = "edam.audit.user_id";

    private final AuditService auditService;
    /** 真实 IP / UA 的**唯一**取法（反代后面 request.getRemoteAddr() 拿到的是代理 IP） */
    private final com.example.edam.util.AuditHelper auditHelper;
    private final ExpressionParser parser = new SpelExpressionParser();

    public AuditAspect(AuditService auditService,
                       com.example.edam.util.AuditHelper auditHelper) {
        this.auditService = auditService;
        this.auditHelper = auditHelper;
    }

    /** 参数摘要最多记多少个（防 detail 膨胀） */
    private static final int MAX_PARAM_ENTRIES = 8;
    /** 单个参数值的最大长度 */
    private static final int MAX_PARAM_VALUE_LENGTH = 120;

    @Around("@annotation(com.example.edam.audit.AuditOp)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long startedNanos = System.nanoTime();
        HttpServletRequest request = currentRequest();
        String endpoint = routeTemplate(request);
        String httpMethod = request != null ? request.getMethod() : null;

        // 注解自己从方法签名上取，不靠 Spring 绑参数（原因见 resolveAuditOp 的注释）
        AuditOp auditOp = resolveAuditOp(pjp);
        if (auditOp == null) {
            // 理论上到不了这里：切点只匹配带 @AuditOp 的方法。
            // 真到了也不能把请求打挂 —— 放行，但留一条日志让人知道切面失灵了。
            log.warn("audit_annotation_unresolved, method={}",
                pjp.getSignature() == null ? "?" : pjp.getSignature().toLongString());
            return pjp.proceed();
        }

        // 先进来就标记：这个请求归切面管了。
        // 放在最前面（而不是写库成功之后）是为了保证"切面跑过了"这件事
        // 一定被标记 —— 否则切面自己出异常时，过滤器会把它当成
        // "没人记过"再记一遍，或者反过来漏记。
        if (request != null) {
            request.setAttribute(AUDITED_REQUEST_ATTR, Boolean.TRUE);
        }

        Object result = null;
        Throwable failure = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            try {
                write(pjp, auditOp, request, endpoint, httpMethod,
                    result, failure, elapsedMs(startedNanos));
            } catch (Exception e) {
                // 审计本身失败**绝不能**影响业务响应。
                // AuditService 内部也会兜底，这里再兜一层：表达式求值失败也不该把请求打挂。
                log.warn("audit_aspect_write_failed, op={}, endpoint={}", auditOp.op(), endpoint, e);
            }
        }
    }

    /**
     * 从连接点里取出 {@link AuditOp} 注解实例。
     *
     * <p><b>为什么不在切点里把注解当参数绑进来</b>
     * （也就是 {@code @Around("@annotation(auditOp)")} + {@code AuditOp auditOp}）：
     * <p>那种写法要靠 Spring 把注解通过 {@code JoinPointMatch} 绑给 advice 方法，
     * 而 {@code JoinPointMatch} 只有在 {@code ExposeInvocationInterceptor} 跑过之后才拿得到 ——
     * 它的 order 是 {@code HIGHEST_PRECEDENCE + 1}，
     * <b>任何 order 比它更靠外的 advice 都会抢在它前面执行</b>。
     * 本切面为了包住 Spring Security 的方法级授权拦截（{@code @PreAuthorize}），
     * 用的正是 {@code HIGHEST_PRECEDENCE} ⇒ 注解绑不上 ⇒ Spring 抛
     * {@code IllegalStateException: Required to bind 2 arguments, but only bound 1}
     * ⇒ <b>每个被审计的接口都 500</b>。
     * <p>Spring 自己在 {@code ExposeInvocationInterceptor} 的异常信息里写了这条坑：
     * "advices with order HIGHEST_PRECEDENCE will execute before ExposeInvocationInterceptor!"。
     *
     * <p>自己从方法签名上取，就没有这层依赖，
     * advice 的 order 也自由了（想包住授权拦截就包住，不用再和拦截器链较劲）。
     *
     * <p>注意切点必须写成<b>类型形式</b> {@code @annotation(全限定类名)}：
     * 变量形式 {@code @annotation(auditOp)} 里的 {@code auditOp} 是"切点参数"，
     * 它由 advice 方法的<b>形参</b>提供 —— advice 不再有这个形参，切点就解不出这个变量，
     * AspectJ 解析失败 ⇒ <b>切面静默不生效</b>（比抛异常更难发现）。
     */
    private AuditOp resolveAuditOp(ProceedingJoinPoint pjp) {
        if (!(pjp.getSignature() instanceof MethodSignature sig)) {
            return null;
        }
        Method method = sig.getMethod();
        Object target = pjp.getTarget();
        if (target != null) {
            // CGLIB 代理上拿到的可能是代理类的方法，注解不在那儿 —— 换成目标类的真实方法
            Method specific = AopUtils.getMostSpecificMethod(method, target.getClass());
            if (specific != null) {
                method = specific;
            }
        }
        AuditOp op = AnnotatedElementUtils.findMergedAnnotation(method, AuditOp.class);
        if (op == null) {
            // 兜底：万一拿到的是接口方法，注解在实现类上
            op = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), AuditOp.class);
        }
        return op;
    }

    // ==================================================================
    // 落库
    // ==================================================================

    private void write(ProceedingJoinPoint pjp, AuditOp auditOp,
                       HttpServletRequest request, String endpoint, String httpMethod,
                       Object result, Throwable failure, long durationMs) {

        int statusCode = statusCode(failure, result);
        String outcome = classify(failure, statusCode);

        Long userId = CurrentUser.optionalUserId();
        String sessionId = currentSessionId();

        EvaluationContext ctx = buildContext(pjp, result);

        // 显式指定的操作者优先（登录接口：此时还没有 JWT，身份在响应体里）
        Long explicitUserId = evalLong(auditOp.userId(), ctx);
        if (explicitUserId != null) {
            userId = explicitUserId;
        }
        // 再退一步：控制器手工塞进来的（permitAll 的播放端点，身份在播放 token 里）
        if (userId == null && request != null) {
            Object attr = request.getAttribute(USER_ID_REQUEST_ATTR);
            if (attr instanceof Number n) {
                userId = n.longValue();
            }
        }

        Long resourceId = evalLong(auditOp.resourceId(), ctx);
        String resourceType = auditOp.resourceType().isBlank()
            ? "system" : auditOp.resourceType();

        String detail = buildDetail(auditOp, request, ctx, failure);

        String ip = clientIp(request);
        String userAgent = userAgent(request);

        // 高频接口（分片拉取）：只记失败与拒绝，成功是噪声
        if (auditOp.auditSuccess() || !"success".equals(outcome)) {
            auditService.log(userId, auditOp.op(), resourceType, resourceId,
                outcome, detail, ip, userAgent,
                httpMethod, endpoint, statusCode, durationMs, sessionId);
        }

        // 告诉过滤器"这个请求已经记过了" —— 过滤器只补它自己那一类（没进控制器的 401/403）
        if (request != null) {
            request.setAttribute(AUDITED_REQUEST_ATTR, Boolean.TRUE);
        }
    }

    // ==================================================================
    // 结果与状态码
    // ==================================================================

    /**
     * 把这次调用翻译成审计结果。
     *
     * <p><b>不只看异常，也看状态码</b>：本项目有相当一部分接口是
     * {@code return ResponseEntity.status(FORBIDDEN).body(...)}
     * 而不是抛异常 —— 比如文档详情的可见性校验、分享权限校验。
     * 只看异常的话，这些"被拒绝的访问"会被记成 success，
     * 审计页上显示"成功"，而用户实际看到的是 403。
     */
    private String classify(Throwable failure, int statusCode) {
        if (failure == null) {
            if (statusCode == 401 || statusCode == 403) {
                return "denied";
            }
            if (statusCode >= 400) {
                return "failure";
            }
            return "success";
        }
        if (failure instanceof AccessDeniedException) {
            return "denied";
        }
        Integer status = httpStatusOf(failure);
        if (status != null && (status == 401 || status == 403)) {
            return "denied";
        }
        return "failure";
    }

    /**
     * 这次调用的 HTTP 状态码（异常时从异常里取，成功时从 ResponseEntity 取）。
     */
    private int statusCode(Throwable failure, Object result) {
        if (failure != null) {
            Integer status = httpStatusOf(failure);
            return status != null ? status : 500;
        }
        if (result instanceof ResponseEntity<?> entity) {
            return entity.getStatusCode().value();
        }
        // void 返回值 + 直接操作 HttpServletResponse 的接口
        // （如文档预览流式输出）：从当前响应里读实际状态码
        Integer status = currentResponseStatus();
        return status != null ? status : 200;
    }

    private Integer currentResponseStatus() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            var response = sra.getResponse();
            if (response != null) {
                return response.getStatus();
            }
        }
        return null;
    }

    /**
     * 从异常里提取 HTTP 状态码；取不到返回 null。
     *
     * <p><b>为什么要在这里复刻一遍状态码映射</b>：{@code GlobalExceptionHandler}
     * 是在异常<b>离开控制器方法之后</b>才把它翻译成状态码的，
     * 而切面恰好包在控制器方法这一层 —— 它看到的是原始异常。
     * 不做这层映射，审计里的 status_code 就全是 500，
     * 而实际返回给客户端的可能是 404 / 400 / 429，
     * 审计记录和真实响应对不上，排障时会被带偏。
     *
     * <p>映射表与 {@code GlobalExceptionHandler} 保持一致。
     */
    private Integer httpStatusOf(Throwable t) {
        if (t instanceof org.springframework.web.server.ResponseStatusException rse) {
            return rse.getStatusCode().value();
        }
        if (t instanceof org.springframework.security.access.AccessDeniedException) {
            return 403;
        }
        if (t instanceof org.springframework.security.core.AuthenticationException) {
            return 401;
        }
        if (t instanceof com.example.edam.exception.ResourceNotFoundException) {
            return 404;
        }
        if (t instanceof com.example.edam.exception.RateLimitExceededException) {
            return 429;
        }
        if (t instanceof com.example.edam.exception.AccountLockedException) {
            return 423;
        }
        if (t instanceof org.springframework.web.multipart.MaxUploadSizeExceededException) {
            return 413;
        }
        if (t instanceof org.springframework.web.multipart.MultipartException) {
            return 400;
        }
        if (t instanceof org.springframework.web.bind.MethodArgumentNotValidException) {
            return 400;
        }
        if (t instanceof IllegalArgumentException) {
            return 400;
        }
        // 业务异常上带 @ResponseStatus 的（若以后新增）
        org.springframework.web.bind.annotation.ResponseStatus rs =
            org.springframework.core.annotation.AnnotationUtils.findAnnotation(t.getClass(),
                org.springframework.web.bind.annotation.ResponseStatus.class);
        if (rs != null) {
            return rs.code().value();
        }
        return null;
    }

    // ==================================================================
    // SpEL 求值
    // ==================================================================

    /**
     * 构造求值上下文：方法入参（按参数名 + 位置别名 {@code #p0}）与返回值 {@code #result}。
     */
    private EvaluationContext buildContext(ProceedingJoinPoint pjp, Object result) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        Object[] args = pjp.getArgs();
        String[] names = null;
        if (pjp.getSignature() instanceof MethodSignature sig) {
            names = sig.getParameterNames();
        }
        for (int i = 0; i < args.length; i++) {
            ctx.setVariable("p" + i, args[i]);
            if (names != null && i < names.length && names[i] != null) {
                ctx.setVariable(names[i], args[i]);
            }
        }
        ctx.setVariable("result", result);
        return ctx;
    }

    private Long evalLong(String expression, EvaluationContext ctx) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Object v = parser.parseExpression(expression).getValue(ctx);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s && !s.isBlank()) {
                return Long.valueOf(s.trim());
            }
        } catch (Exception e) {
            // 资源 ID 取不到不该让审计记录丢失
            log.debug("audit_resource_id_eval_failed, expr={}", expression, e);
        }
        return null;
    }

    /**
     * 求值一个**单独的** SpEL 表达式（{@code resourceId} / {@code userId} 用）。
     *
     * <p>与 {@link #evalDetailTemplate} 的区别：那两个属性就是**一个**表达式
     * （{@code #videoId}、{@code #result?.body?.get('user_id')}），
     * 而 {@code detail} 是「{@code k=v, k2=v2}」模板 —— 整串丢给 SpEL 会被
     * 当成赋值而失败。
     */
    private String evalString(String expression, EvaluationContext ctx) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Object v = parser.parseExpression(expression).getValue(ctx);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            log.debug("audit_detail_eval_failed, expr={}", expression, e);
            return null;
        }
    }

    // ==================================================================
    // detail 拼装
    // ==================================================================

    /**
     * detail = 注解里写的补充说明 + 请求参数摘要 + 失败原因。
     */
    private String buildDetail(AuditOp auditOp, HttpServletRequest request,
                               EvaluationContext ctx, Throwable failure) {
        List<String> parts = new ArrayList<>();

        String explicit = evalDetailTemplate(auditOp.detail(), ctx);
        if (explicit != null && !explicit.isBlank()) {
            parts.add(AuditDetail.scrub(explicit));
        }

        if (!auditOp.hideParams()) {
            String params = summarizeParams(request);
            if (!params.isBlank()) {
                parts.add(params);
            }
        }

        if (failure != null) {
            String reason = failure.getMessage();
            parts.add("reason=" + AuditDetail.scrub(
                reason != null ? reason : failure.getClass().getSimpleName()));
        }

        return String.join("; ", parts);
    }

    /**
     * 求值 detail 模板。
     *
     * <p><b>detail 不是 SpEL 表达式，而是「{@code k=v, k2=v2}」模板</b> ——
     * 每个 {@code v} 单独求值。
     *
     * <p><b>为什么不能整串丢给 SpEL</b>：SpEL 把 {@code =} 解析成**赋值运算符**。
     * 于是 {@code detail = "employee_no=#request.employeeNo"} 会被当成
     * 「把 {@code #request.employeeNo} 赋给变量 {@code employee_no}」，
     * 而 {@code employee_no} 不是可写属性 ⇒
     * {@code EL1009E: Property or field 'employee_no' cannot be set on null}。
     * 带逗号的更糟：{@code "title=#title, classification_lv=#classificationLv"}
     * 直接报 {@code EL1041E: After parsing a valid expression, there is still more data}。
     *
     * <p>也就是说<b>全仓 40 多处 {@code @AuditOp(detail=...)} 全部求值失败</b>，
     * 审计详情里的补充说明（操作了哪个对象、什么密级）一直是空的 ——
     * 而这恰恰是审计日志最该有的部分。
     *
     * <p><b>怎么求值</b>：按 {@code ,} 切成段，每段再按第一个 {@code =} 切成
     * 键和值；<b>只有值</b>拿去当 SpEL 求值（键是字面量，不参与求值）。
     * 值求不出来（引用了不存在的变量、或压根不是表达式）时，
     * <b>保留原文</b>而不是整段丢弃 —— 宁可记下「{@code employee_no=#request.employeeNo}」
     * 让人知道这里本该有值，也好过留下空白。
     */
    private String evalDetailTemplate(String template, EvaluationContext ctx) {
        if (template == null || template.isBlank()) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : template.split(",")) {
            String s = segment.trim();
            if (s.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq < 0) {
                // 没有 '=' 的整段是字面量（如 "refresh_token cleared"）
                segments.add(s);
                continue;
            }
            String key = s.substring(0, eq).trim();
            String rawValue = s.substring(eq + 1).trim();
            String value = evalOrKeep(rawValue, ctx);
            segments.add(key + "=" + value);
        }
        return segments.isEmpty() ? null : String.join(", ", segments);
    }

    /**
     * 求值一个 detail 的值；求不出来就原样返回。
     */
    private String evalOrKeep(String rawValue, EvaluationContext ctx) {
        if (rawValue.isEmpty()) {
            return rawValue;
        }
        try {
            Object v = parser.parseExpression(rawValue).getValue(ctx);
            return v == null ? rawValue : String.valueOf(v);
        } catch (Exception e) {
            // 表达式本身合法、但引用的变量不存在（如 #req 在该方法里没有这个参数）
            // —— 保留原文，好过留空。
            log.debug("audit_detail_value_eval_failed, expr={}", rawValue, e);
            return rawValue;
        }
    }

    /**
     * 请求参数摘要：{@code keyword=abc, page=1}。
     *
     * <p>敏感键名的值一律替换成 {@code ***}。
     */
    private String summarizeParams(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        Map<String, String[]> params;
        try {
            params = request.getParameterMap();
        } catch (Exception e) {
            return "";
        }
        if (params == null || params.isEmpty()) {
            return "";
        }
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : params.entrySet()) {
            if (flat.size() >= MAX_PARAM_ENTRIES) {
                break;
            }
            String[] values = e.getValue();
            String v = (values != null && values.length > 0) ? values[0] : "";
            if (v.length() > MAX_PARAM_VALUE_LENGTH) {
                v = v.substring(0, MAX_PARAM_VALUE_LENGTH) + "…";
            }
            flat.put(e.getKey(), AuditDetail.isSensitive(e.getKey()) ? AuditDetail.MASK : v);
        }
        List<String> parts = new ArrayList<>();
        flat.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(", ", parts);
    }

    // ==================================================================
    // 请求上下文
    // ==================================================================

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    /**
     * 命中的路由模板（路径变量已被替换成 {@code {name}}），例如 {@code /videos/{video_id}}。
     *
     * <p>比记录真实路径好：真实路径里的 ID 是资源标识，
     * 模板里的路径变量名才是"这次操作打到了哪个接口"的稳定信息。
     */
    private String routeTemplate(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? String.valueOf(pattern) : request.getRequestURI();
    }

    /**
     * 真实客户端 IP：优先 X-Forwarded-For 第一个，其次 X-Real-IP，最后 RemoteAddr。
     *
     * <p>取法统一走 {@link com.example.edam.util.AuditHelper} ——
     * 别处再写一遍，迟早两边不一致（反代部署形态一变就露馅）。
     */
    private String clientIp(HttpServletRequest request) {
        return auditHelper.extractClientIp(request);
    }

    private String userAgent(HttpServletRequest request) {
        return auditHelper.extractUserAgent(request);
    }

    /**
     * 当前会话 ID（JWT claims 里的 session_id）。
     */
    private String currentSessionId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object details = auth.getDetails();
        if (details instanceof Map<?, ?> map) {
            Object sid = map.get("sessionId");
            if (sid instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
