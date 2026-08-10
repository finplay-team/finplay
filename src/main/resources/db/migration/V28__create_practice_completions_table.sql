-- 시장가/지정가 매매 기반 실습 튜토리얼의 완료 판정 1건(튜토리얼당 1회)을 영속하는 테이블을 생성한다.

CREATE TABLE practice_completions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    tutorial_key   VARCHAR(50)  NOT NULL,
    reflection_id  BIGINT       NOT NULL,
    completed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_practice_completions_user_tutorial UNIQUE (user_id, tutorial_key),
    CONSTRAINT fk_practice_completions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_practice_completions_reflection FOREIGN KEY (reflection_id) REFERENCES practice_market_reflections (id)
);
