-- 사용자별 관심목록 항목과 중복 등록 방지 제약을 생성한다.

CREATE TABLE watchlist_items (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    instrument_id BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_watchlist_items_user_instrument UNIQUE (user_id, instrument_id),
    CONSTRAINT fk_watchlist_items_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_watchlist_items_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    INDEX idx_watchlist_items_user_created_id (user_id, created_at, id)
);
