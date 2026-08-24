-- 회원의 이메일 변경용 인증번호 해시·만료·발송 이력을 저장한다 (원문 미저장, PRD AUTH-005).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE email_change_verifications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    new_email     VARCHAR(255) NOT NULL,
    code_hash     VARCHAR(255) NOT NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    expires_at    DATETIME(6)  NOT NULL,
    last_sent_at  DATETIME(6)  NOT NULL,
    consumed_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_email_change_verifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_email_change_verifications_user_created_at (user_id, created_at)
);
