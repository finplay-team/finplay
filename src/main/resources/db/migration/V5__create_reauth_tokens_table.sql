-- OAuth 재인증으로 발급한 일회용 토큰의 해시·만료·소비 여부를 저장한다 (원문 미저장, PRD AUTH-003).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE reauth_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    consumed_at DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reauth_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_reauth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);
