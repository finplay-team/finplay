-- 비밀번호 재설정 인증번호의 해시·만료·발송 이력과 거부된 요청 행을 저장한다 (원문 미저장, PRD AUTH-006).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).
-- user_id FK를 두지 않는다 — 미가입 이메일 요청도 발송 제한 집계를 위해 행을 남기기 때문이다 (issue-115-plan.md D6).
-- code_hash·expires_at·last_sent_at이 모두 NULL인 행 = 요청은 있었으나 발송하지 않음(404·409 거부).

CREATE TABLE password_reset_verifications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    code_hash     VARCHAR(255) NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    expires_at    DATETIME(6)  NULL,
    last_sent_at  DATETIME(6)  NULL,
    consumed_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_password_reset_verifications_email_created_at (email, created_at)
);
