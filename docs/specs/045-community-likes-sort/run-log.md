# Run Log: 045-community-likes-sort

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `ls db/migration` (V38 최신 확인 후 V41 사용) | plan.md "데이터 모델" 절, ADR-0004 |
| - | implementer | `.\gradlew.bat compileJava` | plan.md 엔티티·리포지토리 설계 |
| - | implementer | `.\gradlew.bat spotlessApply` | docs/conventions.md 포맷 규칙 |

## 모니터링 (사람용 요약)
- 데이터 모델 항목(V41 마이그레이션, CommunityPostLike 엔티티, likeCount 필드, 원자적 증감·배치 조회 리포지토리) 구현, compileJava 통과.
