-- 커뮤니티 게시물에 종목 태그(선택, 단일)를 추가한다.

ALTER TABLE community_posts
    ADD COLUMN instrument_id BIGINT NULL AFTER author_id,
    ADD CONSTRAINT fk_community_posts_instrument
        FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    ADD INDEX idx_community_posts_instrument_created (instrument_id, created_at, id);
