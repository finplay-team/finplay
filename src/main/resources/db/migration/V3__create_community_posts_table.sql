-- 커뮤니티 게시글과 작성자 관계 및 최신순 조회 인덱스를 생성한다.

CREATE TABLE community_posts (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    author_id  BIGINT        NOT NULL,
    title      VARCHAR(100)  NOT NULL,
    content    VARCHAR(5000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_community_posts_author
        FOREIGN KEY (author_id) REFERENCES users (id),
    INDEX idx_community_posts_created_at_id (created_at, id)
);
