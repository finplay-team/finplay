-- 커뮤니티 게시물에 첨부하는 이미지(게시물당 최대 1장)를 저장한다.
-- post_id는 업로드 시점에는 NULL이고, 게시물 생성 시 연결되며 이후 변경하지 않는다(선업로드-후참조 방식).

CREATE TABLE community_post_images (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    uploader_id BIGINT NOT NULL,
    post_id BIGINT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_community_post_images_uploader
        FOREIGN KEY (uploader_id) REFERENCES users (id),
    CONSTRAINT fk_community_post_images_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_community_post_images_post UNIQUE (post_id)
) ENGINE = InnoDB;
