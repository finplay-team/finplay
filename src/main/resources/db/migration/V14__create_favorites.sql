-- 사용자별 종목 즐겨찾기와 중복 등록 방지 제약을 생성한다.

CREATE TABLE favorites (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    instrument_id BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_favorites_user_instrument UNIQUE (user_id, instrument_id),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_favorites_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    INDEX idx_favorites_user_created_id (user_id, created_at, id)
);
