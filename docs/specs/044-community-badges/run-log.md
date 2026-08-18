# Run Log: 044-community-badges

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `db/migration` 최신 버전 확인(`V38`) 후 `V43__create_community_post_learned_reactions.sql`·`V44__create_member_badges.sql`(작성 당시 `V39`·`V40`)을 plan.md 데이터 모델 SQL 그대로 작성, `badge.domain`(`BadgeType`·`BadgeTier`·`MemberBadge`)·`badge.repository.MemberBadgeRepository` 신설 후 `.\gradlew.bat compileJava`·`spotlessApply` | tasks.md 항목 1, plan.md "데이터 모델"·"패키지·클래스 설계", ADR-0002, ADR-0004 |

## 모니터링 (사람용 요약)
- badge 도메인 기반(tasks.md 항목 1) 구현 — `MemberBadge` 엔티티(하락 없음 `raiseTier` 메서드 포함)·`BadgeType`/`BadgeTier` enum·`MemberBadgeRepository`(`findByUserId`/`findByUserIdAndBadgeType`/`findAllByUserIdIn`)·`V43`/`V44` 마이그레이션. 테스트는 tester 담당이라 미작성. 컴파일 통과.
- **각주(리뷰 권장 반영, 2026-08-18)**: `V43__create_community_post_learned_reactions.sql`은 스키마만 이번 항목에서 선반영했다 — 그 테이블에 대응하는 엔티티·서비스·API는 tasks.md 항목 2("배웠어요" 반응) 담당이며 이번 PR에는 없다. 이번 항목이 실제로 구현한 도메인 코드는 `MemberBadge`(`V44`)뿐이다. 다음 작업자는 이 사실을 참고해 항목 2를 처음부터 시작하되(테이블은 이미 있음), 항목 1이 "배웠어요"까지 구현했다고 오해하지 않아야 한다.
