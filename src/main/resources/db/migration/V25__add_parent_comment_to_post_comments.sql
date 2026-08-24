-- 커뮤니티 댓글에 1단계 대댓글을 위한 self-referencing 부모 댓글 참조(선택)를 추가한다.

ALTER TABLE post_comments
    ADD COLUMN parent_comment_id BIGINT NULL AFTER post_id,
    ADD CONSTRAINT fk_post_comments_parent
        FOREIGN KEY (parent_comment_id) REFERENCES post_comments (id)
        ON DELETE CASCADE,
    ADD INDEX idx_post_comments_parent (parent_comment_id);
