-- 커뮤니티 게시물에 대한 "배웠어요" 반응(회원당 게시물 1회)을 저장한다.

CREATE TABLE community_post_learned_reactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_learned_reactions_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_learned_reactions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_learned_reactions_post_user UNIQUE (post_id, user_id)
);
