package com.example.edam.controller;

import com.example.edam.model.SysPermission;
import com.example.edam.model.SysRole;
import com.example.edam.repository.SysPermissionRepository;
import com.example.edam.repository.SysRolePermissionRepository;
import com.example.edam.repository.SysRoleRepository;
import com.example.edam.repository.SysUserRoleRepository;
import com.example.edam.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RBAC 管理接口的回归测试（2026-11-08 审计）。
 *
 * <p>核心回归点：<b>系统预置角色不可改权限</b>。
 * 原先只有 {@code updateRole} / {@code deleteRole} 挡了系统角色，
 * {@code POST /roles/{id}/permissions} 没挡 ——
 * 于是"编辑角色"页面点不动 {@code employee}，但"分配权限"接口能把它的
 * 权限集整个换掉，把普通员工的只读白名单改成有上传权。
 *
 * <p>方案书 5.4 细粒度权限与外发管控。
 */
class RbacControllerTest {

    private SysPermissionRepository permissionRepository;
    private SysRoleRepository roleRepository;
    private SysRolePermissionRepository rolePermRepository;
    private RbacController controller;

    @BeforeEach
    void setUp() {
        permissionRepository = mock(SysPermissionRepository.class);
        roleRepository = mock(SysRoleRepository.class);
        rolePermRepository = mock(SysRolePermissionRepository.class);
        controller = new RbacController(
            permissionRepository,
            roleRepository,
            rolePermRepository,
            mock(SysUserRoleRepository.class),
            mock(PermissionService.class));
    }

    private SysRole role(boolean isSystem) {
        SysRole r = new SysRole();
        r.setId(1L);
        r.setCode(isSystem ? "employee" : "custom_role");
        r.setName(isSystem ? "普通员工" : "自定义角色");
        r.setIsSystem(isSystem);
        return r;
    }

    @Test
    @DisplayName("系统预置角色：分配权限 ⇒ 拒绝，且不写库")
    void systemRolePermissionsCannotBeReassigned() {
        when(roleRepository.selectById(1L)).thenReturn(role(true));

        RbacController.AssignPermissionsRequest req = new RbacController.AssignPermissionsRequest();
        req.setPermission_ids(List.of(1L, 2L));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> controller.assignPermissions(1L, req, 99L));
        assertEquals("系统预置角色不可修改权限", ex.getMessage());

        // 关键：一条权限关联都不能写
        verify(rolePermRepository, never()).deleteByRoleId(anyLong());
        verify(rolePermRepository, never()).insert(any(com.example.edam.model.SysRolePermission.class));
    }

    @Test
    @DisplayName("自定义角色：分配权限 ⇒ 正常写入")
    void customRolePermissionsCanBeAssigned() {
        when(roleRepository.selectById(1L)).thenReturn(role(false));
        SysPermission p = new SysPermission();
        p.setId(1L);
        p.setCode("video:read");
        when(permissionRepository.selectBatchIds(List.of(1L))).thenReturn(List.of(p));

        RbacController.AssignPermissionsRequest req = new RbacController.AssignPermissionsRequest();
        req.setPermission_ids(List.of(1L));

        var resp = controller.assignPermissions(1L, req, 99L);

        assertEquals(1, resp.get("assigned_count"));
        verify(rolePermRepository).deleteByRoleId(1L);
        verify(rolePermRepository).insert(any(com.example.edam.model.SysRolePermission.class));
    }

    @Test
    @DisplayName("权限 id 不存在 ⇒ 400，而不是撞外键 500")
    void unknownPermissionIdIsRejected() {
        when(roleRepository.selectById(1L)).thenReturn(role(false));
        when(permissionRepository.selectBatchIds(List.of(404L))).thenReturn(List.of());

        RbacController.AssignPermissionsRequest req = new RbacController.AssignPermissionsRequest();
        req.setPermission_ids(List.of(404L));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> controller.assignPermissions(1L, req, 99L));
        assertEquals("权限 id 不存在: [404]", ex.getMessage());
        verify(rolePermRepository, never()).insert(any(com.example.edam.model.SysRolePermission.class));
    }
}
