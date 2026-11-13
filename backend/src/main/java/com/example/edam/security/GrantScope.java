package com.example.edam.security;

import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;

/**
 * 转授（再分享）时的权限上限校验。
 *
 * <p><b>为什么需要它</b>（2026-11-11 代码审计第三轮 §1，定级 critical）：
 *
 * <p>授权接口 {@code POST /documents/batch-permission} 与
 * {@code POST /videos/batch-permission} 此前只校验两件事：
 * <ul>
 *   <li>{@code actions != 0}</li>
 *   <li>{@code actions} 里没有未知位（{@code actions & ~ACTION_ALL == 0}）</li>
 * </ul>
 *
 * <p><b>完全没有校验"授权人自己有没有这些位"</b>。
 * 于是拿着 {@code distribute} 位（16）的人可以授出 {@code ACTION_ALL}（63）——
 * 权限被放大了好几倍，而授权人从头到尾只给过"看 + 转发"。
 *
 * <p><b>规则</b>：要授出的权限不得超过授权人自己持有的权限。
 * 两个维度都要卡：
 * <ol>
 *   <li><b>位掩码</b> —— 要授出的位必须是授权人自己那份额权的子集</li>
 *   <li><b>有效期</b> —— 要授出的有效期不得晚于授权人自己那份的到期时间</li>
 * </ol>
 *
 * <p>第 2 条容易被忽略，但它是同一个漏洞的另一个面：
 * 授权人自己那份 30 天后到期，他却能授出"永不过期"的权限
 * ⇒ 30 天后他失去了访问权，被授权的人却永久保留。
 *
 * <p><b>为什么规则放在这一处而不是各服务各写一遍</b>：
 * 文档侧与视频侧都调它，避免"两处各写一遍、以后改一处忘了另一处"
 * （第四轮 §6.4 记的结构性隐患）。
 *
 * <p><b>为什么规则放在 service 层而不是 controller</b>：
 * {@code grant()} 是权限写入的<b>唯一入口</b>，
 * 放在这里能保证将来多一个调用方也不会漏掉这道闸。
 */
public final class GrantScope {

    private GrantScope() {
    }

    /**
     * 算出"要授出的位"里超出授权人自身持有的部分。
     *
     * @param ownBits       授权人自己实际持有的位掩码
     * @param requestedBits 本次要授出的位掩码
     * @return 越界的位（0 表示没有越界）
     */
    public static int excessBits(int ownBits, int requestedBits) {
        return requestedBits & ~ownBits;
    }

    /**
     * 断言：授权人的权限足以覆盖要授出的权限。
     *
     * @param ownBits        授权人自己实际持有的位掩码
     * @param ownExpireAt    授权人自己那份的到期时间；null = 永不过期
     * @param requestedBits  本次要授出的位掩码
     * @param requestedExpireAt 本次要授出的到期时间；null = 永不过期
     * @param bitNames       各位的名字（按下标对应，例：{@code {"view","download",...}}），
     *                       用于拼出用户看得懂的错误信息
     * @param resourceDesc   资源描述（例："文档 42"），用于错误信息
     * @throws AccessDeniedException 越权时抛出（→ HTTP 403）
     */
    public static void assertCanGrant(int ownBits, OffsetDateTime ownExpireAt,
                                      int requestedBits, OffsetDateTime requestedExpireAt,
                                      String[] bitNames, String resourceDesc) {
        int excess = excessBits(ownBits, requestedBits);
        if (excess != 0) {
            throw new AccessDeniedException(
                "不能授出自己没有的权限：" + describeBits(excess, bitNames)
                    + "（只能转授自己持有的权限）");
        }
        // 有效期：授权人自己那份 30 天后到期，他却能授出"永不过期"
        // ⇒ 30 天后他没权限了，被授权的人却永久保留 ⇒ 同一个漏洞。
        if (ownExpireAt != null) {
            if (requestedExpireAt == null || requestedExpireAt.isAfter(ownExpireAt)) {
                throw new AccessDeniedException(
                    "不能授出比自己那份更长期限的授权（自己那份于 "
                        + ownExpireAt.toLocalDate() + " 到期）");
            }
        }
    }

    /**
     * 把位掩码翻译成"下载、编辑"这样的可读名字。
     */
    public static String describeBits(int bits, String[] bitNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bitNames.length; i++) {
            if ((bits & (1 << i)) == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(bitNames[i]);
        }
        return sb.length() == 0 ? String.valueOf(bits) : sb.toString();
    }
}
