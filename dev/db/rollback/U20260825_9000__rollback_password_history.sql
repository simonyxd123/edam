-- 回滚 V20260825_1000
DROP TABLE IF EXISTS sys_password_history;

ALTER TABLE sys_user DROP INDEX idx_pwd_changed_at;
ALTER TABLE sys_user DROP COLUMN pwd_history;
ALTER TABLE sys_user DROP COLUMN must_change_password;
ALTER TABLE sys_user DROP COLUMN password_changed_at;