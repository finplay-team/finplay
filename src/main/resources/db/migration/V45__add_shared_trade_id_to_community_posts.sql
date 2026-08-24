-- 커뮤니티 게시물에 공유된 매도 체결(네이티브 매매 카드)을 가리키는 shared_trade_id를 추가한다.

ALTER TABLE community_posts
    ADD COLUMN shared_trade_id BIGINT NULL AFTER instrument_id,
    ADD CONSTRAINT fk_community_posts_shared_trade
        FOREIGN KEY (shared_trade_id) REFERENCES trades (id),
    ADD INDEX idx_community_posts_shared_trade_id (shared_trade_id);
