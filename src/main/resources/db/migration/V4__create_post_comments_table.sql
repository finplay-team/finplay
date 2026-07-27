-- 커뮤니티 게시글의 평면 댓글과 작성자 관계 및 후속 목록 조회 인덱스를 생성한다.

CREATE TABLE post_comments (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    post_id    BIGINT        NOT NULL,
    author_id  BIGINT        NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_comments_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id),
    CONSTRAINT fk_post_comments_author
        FOREIGN KEY (author_id) REFERENCES users (id),
    INDEX idx_post_comments_post_created_at_id (post_id, created_at, id)
);
