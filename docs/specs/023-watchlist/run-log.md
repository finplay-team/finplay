# Run Log: 023-watchlist

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 15:40 | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md 데이터 모델·repository 설계, ADR-0002/0004 |
| 16:20 | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md Service·오류 코드 설계, conventions.md DTO/레이어 규칙 |
| 16:50 | implementer | `gradlew.bat compileJava` + `spotlessApply` | plan.md Controller 설계, conventions.md API/DTO 규칙, favorite 패키지 기존 패턴 |
| 11:34 | reviewer(리뷰) | `git diff dev...HEAD` (PR #253) | conventions.md, .claude/agents/reviewer.md N+1 점검 항목, HoldingRepository JOIN FETCH 선례 |
| 20:42 | implementer(PR #253 리뷰 차단·권장 반영) | `WatchlistItemRepository` 두 조회 메서드를 `@Query`+`JOIN FETCH`로 전환(N+1 제거), `existsByUserIdAndInstrumentId`를 `createWatchlistItem`의 선검사로 배선(plan.md:58 원래 의도대로), 단위 테스트 추가, `.\gradlew.bat test --tests "com.finplay.api.watchlist.*"` + `spotlessApply` | 11:34 리뷰 차단 1건·권장 1건 |

## 모니터링 (사람용 요약)
- 15:40 — V23 마이그레이션·WatchlistItem 엔티티·WatchlistItemRepository·@DataJpaTest 추가, 컴파일 통과.
- 16:20 — ErrorCode.WATCHLIST_ITEM_NOT_FOUND·WatchlistService(등록/조회/해제)·응답 DTO 2종·단위 테스트 추가, 컴파일 통과.
- 16:50 — WatchlistController·WatchlistItemCreateRequest 추가, api-routes.md·api-contracts.md에 새 라우트 3건 반영, 컴파일 통과.
- 11:34 — PR #253 리뷰 완료: 튜토리얼 즐겨찾기와의 분리, 동시 등록 경합 테스트, V23 마이그레이션 충돌 없음 확인. 차단 1건(목록 조회 두 쿼리 모두 N+1 — 리포지토리 조회가 LAZY `instrument`를 fetch하지 않는데 응답 매핑이 필드 4개를 전부 읽음, `HoldingRepository`의 JOIN FETCH 선례 지목), 권장 1건(`existsByUserIdAndInstrumentId`가 테스트만 있고 서비스에서 호출되지 않는 죽은 코드 — `api-contracts.md`의 "최종 방어선" 문구는 1차 검사 존재를 전제).
- 20:42 — 11:34 리뷰 반영: 두 조회 메서드를 `JOIN FETCH`로 전환해 N+1 제거. `existsByUserIdAndInstrumentId`는 삭제 대신 `createWatchlistItem`의 선검사로 배선했다 — `plan.md:58`이 애초에 "필요 시 선검사(최종 방어선은 DB unique)"로 설계해뒀던 의도라 삭제보다 배선이 맞다. 선검사 자체는 TOCTOU 창을 없애지 못하므로 `saveAndFlush`의 `DataIntegrityViolationException` catch(진짜 동시 경합용)는 그대로 유지. 신규 단위 테스트로 선검사 경로(저장 호출 없이 409) 확인, 리포지토리 테스트는 fetch join으로도 그대로 통과.
