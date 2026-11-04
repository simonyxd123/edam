package com.example.edam.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditOpType} 的单测。
 *
 * <p><b>为什么这个用例必须存在</b>：库里存着一批历史别名
 * （{@code create_role}、{@code upload}、{@code view}…），
 * 而新代码写的是规范名（{@code create}、{@code detail}…）。
 * 如果筛选时不做归并，审计页上按 {@code create} 查，
 * 老记录（存成 {@code upload} / {@code create_role}）就<b>一条都查不到</b> ——
 * 审计员会误以为那些操作没发生过。
 */
class AuditOpTypeTest {

    @Test
    @DisplayName("按规范名筛选 ⇒ 能查到等价的历史别名")
    void storedValuesIncludesLegacyAliases() {
        Set<String> create = AuditOpType.storedValuesFor(AuditOpType.CREATE);
        assertTrue(create.contains("create"));
        assertTrue(create.contains("upload"), "历史上传写的是 upload，必须能被 create 查到");
        assertTrue(create.contains("create_role"), "历史建角色写的是 create_role，必须能被 create 查到");
    }

    @Test
    @DisplayName("按 grant_permission 筛选 ⇒ 能查到 assign_roles / assign_permissions / permission_change")
    void permissionAliasesAreMerged() {
        Set<String> values = AuditOpType.storedValuesFor(AuditOpType.GRANT_PERMISSION);
        assertTrue(values.contains("assign_roles"));
        assertTrue(values.contains("assign_permissions"));
        assertTrue(values.contains("permission_change"));
    }

    @Test
    @DisplayName("update 能查到 reset_password / revoke_keys / update_role")
    void updateAliasesAreMerged() {
        Set<String> values = AuditOpType.storedValuesFor(AuditOpType.UPDATE);
        assertTrue(values.contains("reset_password"));
        assertTrue(values.contains("revoke_keys"));
        assertTrue(values.contains("update_role"));
    }

    @Test
    @DisplayName("没有历史别名的规范名 ⇒ 只匹配自己")
    void canonicalWithoutAliasMatchesOnlyItself() {
        Set<String> values = AuditOpType.storedValuesFor(AuditOpType.PLAY);
        assertEquals(Set.of("play"), values);
    }

    @Test
    @DisplayName("null / 空白 ⇒ 空集合（不能变成 match-all）")
    void blankReturnsEmpty() {
        assertTrue(AuditOpType.storedValuesFor(null).isEmpty());
        assertTrue(AuditOpType.storedValuesFor("  ").isEmpty());
    }

    @Test
    @DisplayName("归并只影响展示，不改写库里存的原值")
    void normalizeDoesNotMutateStoredValue() {
        assertEquals(AuditOpType.CREATE, AuditOpType.normalize("upload"));
        assertEquals(AuditOpType.DETAIL, AuditOpType.normalize("view"));
        // 本来就是规范名的原样返回
        assertEquals(AuditOpType.PLAY, AuditOpType.normalize("play"));
        assertEquals(null, AuditOpType.normalize(null));
    }

    @Test
    @DisplayName("规范名清单里不能有重复项")
    void allHasNoDuplicates() {
        assertEquals(AuditOpType.ALL.size(), Set.copyOf(AuditOpType.ALL).size());
    }

    @Test
    @DisplayName("每个规范名都能在 legacyAlias 表里找到自己（表是完整的）")
    void legacyAliasCoversAllCanonicalNames() {
        for (String canonical : AuditOpType.ALL) {
            assertTrue(AuditOpType.storedValuesFor(canonical).contains(canonical),
                canonical + " 必须能匹配到自己");
        }
    }
}
