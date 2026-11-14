package com.example.edam.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.edam.dto.PaginationResponses;
import com.example.edam.exception.GlobalExceptionHandler;
import com.example.edam.model.DistributionApproval;
import com.example.edam.model.DistributionApprovalDecision;
import com.example.edam.model.DocResource;
import com.example.edam.repository.DistributionApprovalDecisionRepository;
import com.example.edam.repository.DistributionApprovalRepository;
import com.example.edam.security.CurrentUserArgumentResolver;
import com.example.edam.service.DocPermissionService;
import com.example.edam.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 外发审批的回归测试（2026-11-07 审计）。
 *
 * <p>覆盖三类以前会出事的地方：
 * <ol>
 *   <li><b>入参没校验</b> —— 缺 external_recipient / reason / valid_hours
 *       一律 NPE 或撞 NOT NULL ⇒ 500。应当是 400。</li>
 *   <li><b>审批意见不留痕</b> —— decide() 收了 comment 却不保存，
 *       决策流水表零引用。</li>
 *   <li><b>没有状态机 / 可以自审自批</b>。</li>
 * </ol>
 *
 * <p>方案书 5.4 细粒度权限与外发管控。
 */
class DistributionControllerTest {

    private static final Long DOC_ID = 7L;
    private static final Long APPROVAL_ID = 42L;
    private static final Long APPLICANT = 100L;
    private static final Long APPROVER = 200L;

    private DistributionApprovalRepository approvalRepository;
    private DistributionApprovalDecisionRepository decisionRepository;
    private DocumentService documentService;
    private DocPermissionService docPermissionService;
    private MockMvc mockMvc;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
 void setUp() {
        // LambdaUpdateWrapper 解析方法引用时需要实体的 TableInfo ——
        // 生产环境由 MyBatis 初始化 mapper 时建好，单测里 mapper 是 mock 的，
        // 得手动初始化一次，否则会抛 "can not find lambda cache for this entity"。
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            DistributionApproval.class);

        approvalRepository = mock(DistributionApprovalRepository.class);
        decisionRepository = mock(DistributionApprovalDecisionRepository.class);
        documentService = mock(DocumentService.class);
        docPermissionService = mock(DocPermissionService.class);

        DistributionController controller = new DistributionController(
            approvalRepository, decisionRepository, documentService, docPermissionService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        // 申请人：有 distribution:read，没有 approve
        authenticate(APPLICANT, "distribution:read");
    }

    private void authenticate(Long userId, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), null, AuthorityUtils.createAuthorityList(authorities)));
    }

    private DistributionApproval approval(int status) {
        DistributionApproval a = new DistributionApproval();
        a.setId(APPROVAL_ID);
        a.setDocId(DOC_ID);
        a.setApplicantId(APPLICANT);
        a.setExternalRecipientName("张三");
        a.setExternalRecipientEmail("zhangsan@partner.com");
        a.setExternalRecipientOrg("合作方");
        a.setReason("对外交付验收材料");
        a.setValidHours(24);
        a.setMaxOpenCount(5);
        a.setAllowForward(false);
        a.setAllowPrint(false);
        a.setStatus(status);
        a.setCurrentOpenCount(0);
        return a;
    }

    // ==================================================================
    // 入参校验：非法请求必须是 400，不是 500
    // ==================================================================

    @Test
    @DisplayName("create 缺 external_recipient ⇒ 400（以前是 NPE → 500）")
    void createWithoutRecipientIs400() throws Exception {
        mockMvc.perform(post("/distribution/approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "reason", "对外交付"))))
            .andExpect(status().isBadRequest());

        verify(approvalRepository, never()).insert(any(DistributionApproval.class));
    }

    @Test
    @DisplayName("create 缺 reason ⇒ 400（库里 reason 是 NOT NULL）")
    void createWithoutReasonIs400() throws Exception {
        mockMvc.perform(post("/distribution/approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "external_recipient", Map.of("name", "张三", "email", "a@b.com")))))
            .andExpect(status().isBadRequest());

        verify(approvalRepository, never()).insert(any(DistributionApproval.class));
    }

    @Test
    @DisplayName("create 缺 external_recipient.email ⇒ 400")
    void createWithoutRecipientEmailIs400() throws Exception {
        mockMvc.perform(post("/distribution/approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "reason", "对外交付",
                    "external_recipient", Map.of("name", "张三")))))
            .andExpect(status().isBadRequest());

        verify(approvalRepository, never()).insert(any(DistributionApproval.class));
    }

    @Test
    @DisplayName("create valid_hours 越界 ⇒ 400")
    void createWithOutOfRangeValidHoursIs400() throws Exception {
        mockMvc.perform(post("/distribution/approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "reason", "对外交付",
                    "valid_hours", 99999,
                    "external_recipient", Map.of("name", "张三", "email", "a@b.com")))))
            .andExpect(status().isBadRequest());

        verify(approvalRepository, never()).insert(any(DistributionApproval.class));
    }

    @Test
    @DisplayName("create 参数齐备 ⇒ 201，且落库的是补齐默认值后的字段")
    void createWithValidBodyPersists() throws Exception {
        DocResource doc = new DocResource();
        doc.setId(DOC_ID);
        doc.setClassificationLv(2);
        when(documentService.getById(DOC_ID)).thenReturn(doc);
        when(docPermissionService.canView(doc, APPLICANT)).thenReturn(true);
        doAnswer(inv -> {
            DistributionApproval a = inv.getArgument(0);
            a.setId(APPROVAL_ID);
            return 1;
        }).when(approvalRepository).insert(any(DistributionApproval.class));

        mockMvc.perform(post("/distribution/approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "reason", "对外交付验收材料",
                    "external_recipient",
                        Map.of("name", "张三", "email", "zhangsan@partner.com", "org", "合作方")))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.valid_hours").value(24))
            .andExpect(jsonPath("$.max_open_count").value(5));

        ArgumentCaptor<DistributionApproval> captor =
            ArgumentCaptor.forClass(DistributionApproval.class);
        verify(approvalRepository).insert(captor.capture());
        assertEquals("zhangsan@partner.com", captor.getValue().getExternalRecipientEmail());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("create 看得到的文档才允许发起 ⇒ 看不到 ⇒ 403")
    void createForInvisibleDocIs403() throws Exception {
        DocResource doc = new DocResource();
        doc.setId(DOC_ID);
        doc.setClassificationLv(4);
        when(documentService.getById(DOC_ID)).thenReturn(doc);
        when(docPermissionService.canView(doc, APPLICANT)).thenReturn(false);

        mockMvc.perform(post("/distribution/approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "doc_id", DOC_ID,
                    "reason", "对外交付",
                    "external_recipient", Map.of("name", "张三", "email", "a@b.com")))))
            .andExpect(status().isForbidden());

        verify(approvalRepository, never()).insert(any(DistributionApproval.class));
    }

    // ==================================================================
    // 审批决策：留痕 + 状态机 + 不可自审自批
    // ==================================================================

    @Test
    @DisplayName("decide 写决策流水（含审批意见）")
    void decideWritesDecisionRecord() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(0));
        // 条件更新命中（影响 1 行）
        when(approvalRepository.update(any(), any())).thenReturn(1);

        mockMvc.perform(post("/distribution/approvals/{id}/decide", APPROVAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "decision", "approve",
                    "comment", "合同已签署，同意外发"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("approved"));

        ArgumentCaptor<DistributionApprovalDecision> captor =
            ArgumentCaptor.forClass(DistributionApprovalDecision.class);
        verify(decisionRepository).insert(captor.capture());
        assertEquals(APPROVAL_ID, captor.getValue().getApprovalId());
        assertEquals(APPROVER, captor.getValue().getApproverId());
        assertEquals(1, captor.getValue().getDecision());
        assertEquals("合同已签署，同意外发", captor.getValue().getComment());
        assertNotNull(captor.getValue().getDecidedAt());
    }

    @Test
    @DisplayName("decide 驳回时必须写理由 ⇒ 没写 ⇒ 400")
    void rejectWithoutCommentIs400() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(0));

        mockMvc.perform(post("/distribution/approvals/{id}/decide", APPROVAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "reject"))))
            .andExpect(status().isBadRequest());

        verify(decisionRepository, never()).insert(any(DistributionApprovalDecision.class));
    }

    @Test
    @DisplayName("decide 状态机：已处理过的单子再批 ⇒ 409")
    void decideOnAlreadyDecidedIs409() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(2));  // rejected

        mockMvc.perform(post("/distribution/approvals/{id}/decide", APPROVAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "approve"))))
            .andExpect(status().isConflict());

        verify(decisionRepository, never()).insert(any(DistributionApprovalDecision.class));
    }

    @Test
    @DisplayName("decide 不能自审自批 ⇒ 403")
    void selfApprovalIs403() throws Exception {
        authenticate(APPLICANT, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(0));

        mockMvc.perform(post("/distribution/approvals/{id}/decide", APPROVAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "approve"))))
            .andExpect(status().isForbidden());

        verify(decisionRepository, never()).insert(any(DistributionApprovalDecision.class));
        verify(approvalRepository, never()).updateById(any(DistributionApproval.class));
    }

    @Test
    @DisplayName("decide 非法 decision ⇒ 400")
    void decideWithInvalidDecisionIs400() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(0));

        mockMvc.perform(post("/distribution/approvals/{id}/decide", APPROVAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "APPROVE"))))
            .andExpect(status().isBadRequest());

        verify(decisionRepository, never()).insert(any(DistributionApprovalDecision.class));
    }

    @Test
    @DisplayName("decide 并发：条件更新影响 0 行（已被别人处理）⇒ 409，且不写决策流水")
    void decideConflictWithConcurrentApprovalIs409() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(0));
        // 读到的还是 pending，但落库时 WHERE 条件已经不成立（被别人抢先改了）
        when(approvalRepository.update(any(), any())).thenReturn(0);

        mockMvc.perform(post("/distribution/approvals/{id}/decide", APPROVAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "approve"))))
            .andExpect(status().isConflict());

        // 关键：不能留下第二条互相矛盾的决策流水
        verify(decisionRepository, never()).insert(any(DistributionApprovalDecision.class));
    }

    // ==================================================================
    // 撤销
    // ==================================================================

    @Test
    @DisplayName("revoke 已撤销的单子 ⇒ 409")
    void revokeAlreadyRevokedIs409() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(4));  // revoked

        mockMvc.perform(post("/distribution/approvals/{id}/revoke", APPROVAL_ID))
            .andExpect(status().isConflict());

        verify(approvalRepository, never()).updateById(any(DistributionApproval.class));
    }

    @Test
    @DisplayName("revoke 并发：条件更新影响 0 行（状态已被改）⇒ 409")
    void revokeConflictWithConcurrentDecisionIs409() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(1));
        when(approvalRepository.update(any(), any())).thenReturn(0);

        mockMvc.perform(post("/distribution/approvals/{id}/revoke", APPROVAL_ID))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("revoke 已批准的单子 ⇒ 204，并把原因写进去")
    void revokeApprovedIs204() throws Exception {
        authenticate(APPROVER, "distribution:read", "distribution:approve");
        // 条件更新命中（影响 1 行）
        when(approvalRepository.update(any(), any())).thenReturn(1);
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(1));  // approved

        mockMvc.perform(post("/distribution/approvals/{id}/revoke", APPROVAL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reason", "接收方信息有误"))))
            .andExpect(status().isNoContent());

        // 现在是**条件更新**（UPDATE ... WHERE id=? AND status IN (pending, approved)），
        // 不再是 updateById —— 所以断言的对象是 wrapper 本身。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DistributionApproval>>
            captor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(approvalRepository).update(isNull(), captor.capture());
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DistributionApproval> w =
            captor.getValue();
        java.util.Map<String, Object> params = w.getParamNameValuePairs();
        assertTrue(params.containsValue(4), "撤销后状态应为 4（revoked），实际参数：" + params);
        assertTrue(params.containsValue(APPROVER), "应记录撤销人，实际参数：" + params);
        assertTrue(params.containsValue("接收方信息有误"), "应记录撤销原因，实际参数：" + params);
        // WHERE 里必须带上"当前仍是 pending/approved"这个守卫
        assertTrue(w.getSqlSegment().contains("status"),
            "条件更新的 WHERE 必须带状态守卫，实际：" + w.getSqlSegment());
    }

    // ==================================================================
    // 决策流水读接口
    // ==================================================================

    @Test
    @DisplayName("GET 决策流水：申请人能看自己的单子")
    void applicantCanReadDecisionHistory() throws Exception {
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(1));
        DistributionApprovalDecision d = new DistributionApprovalDecision();
        d.setId(1L);
        d.setApprovalId(APPROVAL_ID);
        d.setApproverId(APPROVER);
        d.setDecision(1);
        d.setComment("合同已签署，同意外发");
        when(decisionRepository.findByApprovalId(APPROVAL_ID)).thenReturn(List.of(d));

        mockMvc.perform(get("/distribution/approvals/{id}/decisions", APPROVAL_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approval_id").value(APPROVAL_ID))
            .andExpect(jsonPath("$.items[0].decision").value("approve"))
            .andExpect(jsonPath("$.items[0].comment").value("合同已签署，同意外发"));
    }

    @Test
    @DisplayName("GET 决策流水：别人（且无审批权）的单子 ⇒ 403")
    void otherUsersCannotReadDecisionHistory() throws Exception {
        authenticate(300L, "distribution:read");
        when(approvalRepository.selectById(APPROVAL_ID)).thenReturn(approval(1));

        mockMvc.perform(get("/distribution/approvals/{id}/decisions", APPROVAL_ID))
            .andExpect(status().isForbidden());

        verify(decisionRepository, never()).findByApprovalId(anyLong());
    }
}
