-- ============================================================================
-- V20260829_2000__seed_k6_users.sql
-- k6 压测测试用户 seed（100 个 TEST_NNN）
-- 生成时间: 2026-08-20 22:40:08
-- 用途: v3.4 V4-03 k6 压测（参见 perf/k6/README.md）
-- 关联 modify: 2026-08-29-V4-03-烟囱测试执行指南.md
-- 
-- 重新生成命令:
--   python3 generate_k6_seed.py > V20260829_2000__seed_k6_users.sql
-- 
-- 密码: TestP@ssw0rd!（仅 dev/staging 使用）
-- ============================================================================

SET NAMES utf8mb4;

-- 1. k6_tester 角色（id=5）
INSERT IGNORE INTO sys_role (id, code, name, description, is_system) VALUES
    (5, 'k6_tester', 'k6 压测专用', '用于 k6 压测场景的虚拟用户角色', 1);

-- 2. k6_tester 角色权限（覆盖 8 大类 read 类接口）
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission WHERE code IN (
    'video:read',
    'video:download',
    'video:distribute',
    'document:read',
    'document:download',
    'document:distribute',
    'system:audit_export'
);

-- 3. k6 测试用户（100 个，password_hash 共享）
INSERT IGNORE INTO sys_user (
    username, password_hash, employee_no, real_name, email,
    dept_id, status, mfa_enabled, version
) VALUES
    ('TEST_001', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_001', 'K6 Test 001', 'k6_001@perf.local', 5, 1, 0, 0),
    ('TEST_002', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_002', 'K6 Test 002', 'k6_002@perf.local', 5, 1, 0, 0),
    ('TEST_003', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_003', 'K6 Test 003', 'k6_003@perf.local', 5, 1, 0, 0),
    ('TEST_004', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_004', 'K6 Test 004', 'k6_004@perf.local', 5, 1, 0, 0),
    ('TEST_005', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_005', 'K6 Test 005', 'k6_005@perf.local', 5, 1, 0, 0),
    ('TEST_006', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_006', 'K6 Test 006', 'k6_006@perf.local', 5, 1, 0, 0),
    ('TEST_007', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_007', 'K6 Test 007', 'k6_007@perf.local', 5, 1, 0, 0),
    ('TEST_008', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_008', 'K6 Test 008', 'k6_008@perf.local', 5, 1, 0, 0),
    ('TEST_009', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_009', 'K6 Test 009', 'k6_009@perf.local', 5, 1, 0, 0),
    ('TEST_010', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_010', 'K6 Test 010', 'k6_010@perf.local', 5, 1, 0, 0),
    ('TEST_011', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_011', 'K6 Test 011', 'k6_011@perf.local', 5, 1, 0, 0),
    ('TEST_012', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_012', 'K6 Test 012', 'k6_012@perf.local', 5, 1, 0, 0),
    ('TEST_013', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_013', 'K6 Test 013', 'k6_013@perf.local', 5, 1, 0, 0),
    ('TEST_014', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_014', 'K6 Test 014', 'k6_014@perf.local', 5, 1, 0, 0),
    ('TEST_015', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_015', 'K6 Test 015', 'k6_015@perf.local', 5, 1, 0, 0),
    ('TEST_016', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_016', 'K6 Test 016', 'k6_016@perf.local', 5, 1, 0, 0),
    ('TEST_017', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_017', 'K6 Test 017', 'k6_017@perf.local', 5, 1, 0, 0),
    ('TEST_018', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_018', 'K6 Test 018', 'k6_018@perf.local', 5, 1, 0, 0),
    ('TEST_019', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_019', 'K6 Test 019', 'k6_019@perf.local', 5, 1, 0, 0),
    ('TEST_020', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_020', 'K6 Test 020', 'k6_020@perf.local', 5, 1, 0, 0),
    ('TEST_021', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_021', 'K6 Test 021', 'k6_021@perf.local', 5, 1, 0, 0),
    ('TEST_022', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_022', 'K6 Test 022', 'k6_022@perf.local', 5, 1, 0, 0),
    ('TEST_023', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_023', 'K6 Test 023', 'k6_023@perf.local', 5, 1, 0, 0),
    ('TEST_024', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_024', 'K6 Test 024', 'k6_024@perf.local', 5, 1, 0, 0),
    ('TEST_025', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_025', 'K6 Test 025', 'k6_025@perf.local', 5, 1, 0, 0),
    ('TEST_026', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_026', 'K6 Test 026', 'k6_026@perf.local', 5, 1, 0, 0),
    ('TEST_027', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_027', 'K6 Test 027', 'k6_027@perf.local', 5, 1, 0, 0),
    ('TEST_028', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_028', 'K6 Test 028', 'k6_028@perf.local', 5, 1, 0, 0),
    ('TEST_029', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_029', 'K6 Test 029', 'k6_029@perf.local', 5, 1, 0, 0),
    ('TEST_030', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_030', 'K6 Test 030', 'k6_030@perf.local', 5, 1, 0, 0),
    ('TEST_031', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_031', 'K6 Test 031', 'k6_031@perf.local', 5, 1, 0, 0),
    ('TEST_032', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_032', 'K6 Test 032', 'k6_032@perf.local', 5, 1, 0, 0),
    ('TEST_033', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_033', 'K6 Test 033', 'k6_033@perf.local', 5, 1, 0, 0),
    ('TEST_034', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_034', 'K6 Test 034', 'k6_034@perf.local', 5, 1, 0, 0),
    ('TEST_035', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_035', 'K6 Test 035', 'k6_035@perf.local', 5, 1, 0, 0),
    ('TEST_036', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_036', 'K6 Test 036', 'k6_036@perf.local', 5, 1, 0, 0),
    ('TEST_037', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_037', 'K6 Test 037', 'k6_037@perf.local', 5, 1, 0, 0),
    ('TEST_038', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_038', 'K6 Test 038', 'k6_038@perf.local', 5, 1, 0, 0),
    ('TEST_039', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_039', 'K6 Test 039', 'k6_039@perf.local', 5, 1, 0, 0),
    ('TEST_040', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_040', 'K6 Test 040', 'k6_040@perf.local', 5, 1, 0, 0),
    ('TEST_041', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_041', 'K6 Test 041', 'k6_041@perf.local', 5, 1, 0, 0),
    ('TEST_042', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_042', 'K6 Test 042', 'k6_042@perf.local', 5, 1, 0, 0),
    ('TEST_043', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_043', 'K6 Test 043', 'k6_043@perf.local', 5, 1, 0, 0),
    ('TEST_044', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_044', 'K6 Test 044', 'k6_044@perf.local', 5, 1, 0, 0),
    ('TEST_045', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_045', 'K6 Test 045', 'k6_045@perf.local', 5, 1, 0, 0),
    ('TEST_046', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_046', 'K6 Test 046', 'k6_046@perf.local', 5, 1, 0, 0),
    ('TEST_047', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_047', 'K6 Test 047', 'k6_047@perf.local', 5, 1, 0, 0),
    ('TEST_048', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_048', 'K6 Test 048', 'k6_048@perf.local', 5, 1, 0, 0),
    ('TEST_049', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_049', 'K6 Test 049', 'k6_049@perf.local', 5, 1, 0, 0),
    ('TEST_050', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_050', 'K6 Test 050', 'k6_050@perf.local', 5, 1, 0, 0),
    ('TEST_051', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_051', 'K6 Test 051', 'k6_051@perf.local', 5, 1, 0, 0),
    ('TEST_052', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_052', 'K6 Test 052', 'k6_052@perf.local', 5, 1, 0, 0),
    ('TEST_053', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_053', 'K6 Test 053', 'k6_053@perf.local', 5, 1, 0, 0),
    ('TEST_054', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_054', 'K6 Test 054', 'k6_054@perf.local', 5, 1, 0, 0),
    ('TEST_055', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_055', 'K6 Test 055', 'k6_055@perf.local', 5, 1, 0, 0),
    ('TEST_056', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_056', 'K6 Test 056', 'k6_056@perf.local', 5, 1, 0, 0),
    ('TEST_057', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_057', 'K6 Test 057', 'k6_057@perf.local', 5, 1, 0, 0),
    ('TEST_058', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_058', 'K6 Test 058', 'k6_058@perf.local', 5, 1, 0, 0),
    ('TEST_059', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_059', 'K6 Test 059', 'k6_059@perf.local', 5, 1, 0, 0),
    ('TEST_060', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_060', 'K6 Test 060', 'k6_060@perf.local', 5, 1, 0, 0),
    ('TEST_061', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_061', 'K6 Test 061', 'k6_061@perf.local', 5, 1, 0, 0),
    ('TEST_062', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_062', 'K6 Test 062', 'k6_062@perf.local', 5, 1, 0, 0),
    ('TEST_063', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_063', 'K6 Test 063', 'k6_063@perf.local', 5, 1, 0, 0),
    ('TEST_064', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_064', 'K6 Test 064', 'k6_064@perf.local', 5, 1, 0, 0),
    ('TEST_065', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_065', 'K6 Test 065', 'k6_065@perf.local', 5, 1, 0, 0),
    ('TEST_066', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_066', 'K6 Test 066', 'k6_066@perf.local', 5, 1, 0, 0),
    ('TEST_067', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_067', 'K6 Test 067', 'k6_067@perf.local', 5, 1, 0, 0),
    ('TEST_068', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_068', 'K6 Test 068', 'k6_068@perf.local', 5, 1, 0, 0),
    ('TEST_069', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_069', 'K6 Test 069', 'k6_069@perf.local', 5, 1, 0, 0),
    ('TEST_070', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_070', 'K6 Test 070', 'k6_070@perf.local', 5, 1, 0, 0),
    ('TEST_071', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_071', 'K6 Test 071', 'k6_071@perf.local', 5, 1, 0, 0),
    ('TEST_072', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_072', 'K6 Test 072', 'k6_072@perf.local', 5, 1, 0, 0),
    ('TEST_073', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_073', 'K6 Test 073', 'k6_073@perf.local', 5, 1, 0, 0),
    ('TEST_074', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_074', 'K6 Test 074', 'k6_074@perf.local', 5, 1, 0, 0),
    ('TEST_075', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_075', 'K6 Test 075', 'k6_075@perf.local', 5, 1, 0, 0),
    ('TEST_076', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_076', 'K6 Test 076', 'k6_076@perf.local', 5, 1, 0, 0),
    ('TEST_077', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_077', 'K6 Test 077', 'k6_077@perf.local', 5, 1, 0, 0),
    ('TEST_078', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_078', 'K6 Test 078', 'k6_078@perf.local', 5, 1, 0, 0),
    ('TEST_079', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_079', 'K6 Test 079', 'k6_079@perf.local', 5, 1, 0, 0),
    ('TEST_080', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_080', 'K6 Test 080', 'k6_080@perf.local', 5, 1, 0, 0),
    ('TEST_081', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_081', 'K6 Test 081', 'k6_081@perf.local', 5, 1, 0, 0),
    ('TEST_082', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_082', 'K6 Test 082', 'k6_082@perf.local', 5, 1, 0, 0),
    ('TEST_083', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_083', 'K6 Test 083', 'k6_083@perf.local', 5, 1, 0, 0),
    ('TEST_084', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_084', 'K6 Test 084', 'k6_084@perf.local', 5, 1, 0, 0),
    ('TEST_085', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_085', 'K6 Test 085', 'k6_085@perf.local', 5, 1, 0, 0),
    ('TEST_086', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_086', 'K6 Test 086', 'k6_086@perf.local', 5, 1, 0, 0),
    ('TEST_087', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_087', 'K6 Test 087', 'k6_087@perf.local', 5, 1, 0, 0),
    ('TEST_088', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_088', 'K6 Test 088', 'k6_088@perf.local', 5, 1, 0, 0),
    ('TEST_089', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_089', 'K6 Test 089', 'k6_089@perf.local', 5, 1, 0, 0),
    ('TEST_090', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_090', 'K6 Test 090', 'k6_090@perf.local', 5, 1, 0, 0),
    ('TEST_091', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_091', 'K6 Test 091', 'k6_091@perf.local', 5, 1, 0, 0),
    ('TEST_092', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_092', 'K6 Test 092', 'k6_092@perf.local', 5, 1, 0, 0),
    ('TEST_093', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_093', 'K6 Test 093', 'k6_093@perf.local', 5, 1, 0, 0),
    ('TEST_094', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_094', 'K6 Test 094', 'k6_094@perf.local', 5, 1, 0, 0),
    ('TEST_095', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_095', 'K6 Test 095', 'k6_095@perf.local', 5, 1, 0, 0),
    ('TEST_096', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_096', 'K6 Test 096', 'k6_096@perf.local', 5, 1, 0, 0),
    ('TEST_097', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_097', 'K6 Test 097', 'k6_097@perf.local', 5, 1, 0, 0),
    ('TEST_098', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_098', 'K6 Test 098', 'k6_098@perf.local', 5, 1, 0, 0),
    ('TEST_099', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_099', 'K6 Test 099', 'k6_099@perf.local', 5, 1, 0, 0),
    ('TEST_100', '$2b$10$DBSA9uh9tt9yw0aY3FFhKeGhyGfxWNLyEwDf4Svzul0j00jaosJ5G', 'TEST_100', 'K6 Test 100', 'k6_100@perf.local', 5, 1, 0, 0);

-- 4. k6 用户角色关联（每个用户关联 k6_tester 角色）
INSERT IGNORE INTO sys_user_role (user_id, role_id, granted_at)
SELECT u.id, 5, NOW(3)
FROM sys_user u
WHERE u.employee_no LIKE 'TEST_%'
  AND u.id NOT IN (SELECT user_id FROM sys_user_role WHERE role_id = 5);

-- 5. 验证视图（可选，用于 CI 快速核对 seed 成功）
CREATE OR REPLACE VIEW v_k6_seed_status AS
SELECT
    (SELECT COUNT(*) FROM sys_role WHERE id = 5) AS role_exists,
    (SELECT COUNT(*) FROM sys_role_permission WHERE role_id = 5) AS role_perms,
    (SELECT COUNT(*) FROM sys_user WHERE employee_no LIKE 'TEST_%') AS k6_users,
    (SELECT COUNT(*) FROM sys_user_role ur JOIN sys_user u ON ur.user_id = u.id
     WHERE u.employee_no LIKE 'TEST_%' AND ur.role_id = 5) AS k6_role_assigned;

-- 验证查询:
--   SELECT * FROM v_k6_seed_status;
--   -- 期望: role_exists=1, role_perms=7, k6_users=100, k6_role_assigned=100

-- ============================================================================
-- k6 seed 生成完成
-- 重要: 默认密码 TestP@ssw0rd! 仅用于 dev/staging 环境，prod 严禁使用
-- ============================================================================

