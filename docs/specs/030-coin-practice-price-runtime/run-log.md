# Run Log: 030-coin-practice-price-runtime

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `education.priceruntime` 패키지에 엔티티·Repository·v1 생성기·서비스·컨트롤러·V29 migration 작성 | plan.md 도메인·패키지 경계, 데이터 모델, 생성기·가격 anchor 절, Issue #318 |
| - | implementer | `.\gradlew.bat compileJava`, `spotlessApply` | conventions.md 포맷 규칙 |
| - | implementer | `ErrorCode` 2건 추가, `PracticePriceSession.advance()`, `findByIdAndUserIdForUpdate` 비관 잠금, `PracticePriceTickAdvanceRequest`, 신설 `PracticePriceTickService.advanceTick`, 컨트롤러에 `POST /{sessionId}/ticks` 추가 | 이슈 #319 코멘트(tick 전용 서비스 분리), plan.md 트랜잭션·잠금 절, Order.cancel() 패턴 |
| - | implementer | `./gradlew compileJava` | 컴파일 통과 확인 |
| - | tester | 단위(`PracticePriceTickServiceTest`)·`@DataJpaTest`(잠금 조회)·`@WebMvcTest`(tick 엔드포인트)·Testcontainers 동시성(`PracticePriceTickConcurrencyIntegrationTest`) 작성, `ErrorCodeTest` 화이트리스트 갱신 | ADR-0003, conventions.md 테스트 규칙, `LimitOrderConcurrencyIntegrationTest` 동시성 패턴 |
| - | 메인 세션 | 전체 `./gradlew build`에서 `InstrumentRepositoryTest` 3건 회귀 발견(신설 concurrency 테스트가 커밋한 crypto Instrument 2건 정리 누락) → `PracticePriceTickConcurrencyIntegrationTest`에 `@AfterEach` raw JDBC 정리 추가, 재검증 | `PracticeHoldingReflectionConcurrencyIntegrationTest` 관례, `docs/agent-mistakes.md` 2026-08-10(두 번째) 행 |

## 모니터링 (사람용 요약)
- Issue #318 범위(세션 생성·조회 API, v1 생성기, migration V29)는 완료. 이번 작업으로 next-tick(`POST /{sessionId}/ticks`)까지 구현. 교육 지정가 주문 체결 연결(tick 트랜잭션에 주문 체결·취소 추가)은 여전히 후속 이슈.
- `PracticePriceSessionService`는 수정하지 않고 새 `PracticePriceTickService`로 tick 전진만 분리(이슈 #319 코멘트 2안).
- `./gradlew build` 전체 통과(Spotless·SpotBugs·JaCoCo 40%·전체 테스트 포함). api-routes.md·api-contracts.md·prd.md §3·`030/tasks.md` 동기화 완료.
