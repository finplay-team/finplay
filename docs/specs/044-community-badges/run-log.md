# Run Log: 044-community-badges

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `db/migration` 최신 버전 확인(`V38`) 후 `V39__create_community_post_learned_reactions.sql`·`V40__create_member_badges.sql`을 plan.md 데이터 모델 SQL 그대로 작성, `badge.domain`(`BadgeType`·`BadgeTier`·`MemberBadge`)·`badge.repository.MemberBadgeRepository` 신설 후 `.\gradlew.bat compileJava`·`spotlessApply` | tasks.md 항목 1, plan.md "데이터 모델"·"패키지·클래스 설계", ADR-0002, ADR-0004 |

## 모니터링 (사람용 요약)
- badge 도메인 기반(tasks.md 항목 1) 구현 — `MemberBadge` 엔티티(하락 없음 `raiseTier` 메서드 포함)·`BadgeType`/`BadgeTier` enum·`MemberBadgeRepository`(`findByUserId`/`findByUserIdAndBadgeType`/`findAllByUserIdIn`)·`V39`/`V40` 마이그레이션. 테스트는 tester 담당이라 미작성. 컴파일 통과.
