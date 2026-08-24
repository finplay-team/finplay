-- Issue #277: 부모 댓글 삭제가 ON DELETE CASCADE로 자식 대댓글까지 함께 지우던 것을
-- tombstone(표시 변경) 방식으로 전환한다. 삭제 표시 시각을 저장할 컬럼을 추가하고,
-- 부모 댓글은 이제 실제로 DELETE되지 않으므로(UPDATE만 발생) CASCADE가 발동할
-- 상황 자체가 없어야 정상이다 — 그럼에도 향후 실수로 부모를 하드 삭제하는 코드가
-- 생겨 자식이 조용히 함께 사라지는 회귀를 막기 위해 FK 규칙을 RESTRICT로 명시한다.

ALTER TABLE post_comments
    ADD COLUMN deleted_at DATETIME NULL AFTER content;

ALTER TABLE post_comments
    DROP FOREIGN KEY fk_post_comments_parent;

ALTER TABLE post_comments
    ADD CONSTRAINT fk_post_comments_parent
        FOREIGN KEY (parent_comment_id) REFERENCES post_comments (id)
        ON DELETE RESTRICT;
