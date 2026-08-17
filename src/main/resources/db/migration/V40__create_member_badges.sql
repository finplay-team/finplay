-- 회원별 배지 카테고리마다 현재 달성한 최고 등급 하나만 저장한다(하락 없음, 이력 없음).

CREATE TABLE member_badges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    badge_type VARCHAR(30) NOT NULL,
    tier VARCHAR(20) NOT NULL,
    achieved_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_member_badges_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_member_badges_user_type UNIQUE (user_id, badge_type)
);
