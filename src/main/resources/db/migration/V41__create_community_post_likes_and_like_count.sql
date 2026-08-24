-- 게시물 좋아요(회원×게시물 유일)를 저장하고, 인기순 정렬을 위해
-- 게시물에 좋아요 수 비정규화 컬럼을 추가한다.

CREATE TABLE community_post_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_community_post_likes_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_community_post_likes_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_community_post_likes_post_user UNIQUE (post_id, user_id)
);

ALTER TABLE community_posts
    ADD COLUMN like_count BIGINT NOT NULL DEFAULT 0 AFTER content,
    ADD INDEX idx_community_posts_like_count_created (like_count, created_at, id);
