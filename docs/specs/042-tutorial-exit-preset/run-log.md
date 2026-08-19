# Run Log: 042-tutorial-exit-preset

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-08-19 19:45 | 메인 세션(직접 구현) | `./gradlew compileJava`, `./gradlew test --tests "*ExitPreset*" --tests "*ReferencePriceCalculator*" --tests "*PracticeAttempt*" --tests "*ExitPlan*"` | 042 tasks 1·2번, 041 tasks §교차 순서 4번 |
| 2026-08-19 19:53 | reviewer(리뷰) | `git diff origin/dev...HEAD` | 042 spec·plan·tasks, 041 plan §프리셋 도달 조건 검증, conventions.md, ADR-0002·0003·0004·0021 |
| 2026-08-19 20:10 | reviewer ×3(리뷰) | `git diff origin/dev...HEAD` — 반영 재검증 / 결함 사냥 / 다음 세션 관점 | 042·041 spec·plan·tasks, conventions.md, ADR-0002·0003·0004·0021 |
| 2026-08-19 20:40 | 메인 세션 | `./gradlew build` | PR 전 전체 검증 (git-conventions §머지 조건) |

## 모니터링 (사람용 요약)
- 19:45 — 042 1·2번 구현(이슈 #470). 프리셋 상수·계산 / V51 마이그레이션·엔티티 두 커밋.
- 19:53 — 리뷰: 차단 0건, 권장 3건, 참고 7건. 머지 가능.
- 20:05 — 권장 3건 + 손댈 수 있는 참고 3건 반영(왕복 테스트 `@EnumSource`, FK 자동 인덱스 부재 단언, 체결가 scale 8 선정규화, 현행 상수 직접 참조, 테스트 이름 정정, 탐색 루프 범위 방어).
- 20:10 — 2차 리뷰 3종: 차단 0건. V51 주석의 "컬럼을 읽지도 쓰지도 않는다"와 orders 인덱스 근거가 틀린 것을 잡아 실측으로 정정했고, 042 5번이 밟을 정규화 비대칭 함정을 tasks 5번·javadoc에 박았다.
- 20:15 — 문서와 다르게 간 판단(부등식 판정 위치, CHECK·인덱스 추가, 표시 이름 미구현)은 `tasks.md` 각 항목의 인용구와 V51 주석에 적었다 — 이 파일은 한 줄 요약만 둔다(`specs/README.md`).
- 20:40 — `./gradlew build` **BUILD SUCCESSFUL** (4,609건, 실패·오류 0건). 검증 SHA `73bdc9d9`. PR #471.
