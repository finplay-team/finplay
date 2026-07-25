-- 인증·계좌 도메인(002-auth-account)의 5개 테이블을 일괄 생성한다. 금액은 원 단위 BIGINT (PRD C-003).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NULL,
    nickname      VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_nickname UNIQUE (nickname)
);

CREATE TABLE social_accounts (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(20)  NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_social_accounts_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_social_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE refresh_tokens (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME(6)  NOT NULL,
    revoked_at DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_refresh_tokens_token_hash (token_hash)
);

CREATE TABLE email_verifications (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(255) NOT NULL,
    code_hash         VARCHAR(255) NOT NULL,
    attempt_count     INT          NOT NULL DEFAULT 0,
    expires_at        DATETIME(6)  NOT NULL,
    last_sent_at      DATETIME(6)  NOT NULL,
    verified_at       DATETIME(6)  NULL,
    token_hash        VARCHAR(255) NULL,
    token_expires_at  DATETIME(6)  NULL,
    consumed_at       DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_email_verifications_token_hash UNIQUE (token_hash),
    INDEX idx_email_verifications_email_created_at (email, created_at)
);

CREATE TABLE accounts (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    market       VARCHAR(20) NOT NULL,
    cash_balance BIGINT      NOT NULL,
    seed_money   BIGINT      NOT NULL,
    realized_pnl BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_accounts_user_market UNIQUE (user_id, market),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
);
