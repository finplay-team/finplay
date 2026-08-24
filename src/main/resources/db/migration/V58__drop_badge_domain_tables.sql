-- 배지 기능 철회(이슈 #481) 2차 배포 — 앱 코드 제거(PR #484)가 운영에 반영된 뒤 테이블을 삭제한다
-- (ADR-0021 §결정 7의 파괴적 스키마 변경 2단계 배포). 머지된 V43·V44는 수정하지 않는다(ADR-0004).
-- 두 테이블 모두 서로를 참조하지 않고 다른 테이블도 이들을 참조하지 않아 순서 제약이 없다.

DROP TABLE member_badges;
DROP TABLE community_post_learned_reactions;
