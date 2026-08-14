# Run Log: 034-crypto-price-rest-backup

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 10:31 | implementer | `./gradlew compileJava compileTestJava` | tasks.md 항목 1, plan.md B절, spec.md PRICE-REST-004 |
| 11:20 | implementer | `./gradlew spotlessApply test --tests PriceStoreTest,PriceQueryServiceTest,CryptoPriceSnapshotServiceTest,CryptoCandleAndPriceIndependenceTest` | tasks.md 항목 2, plan.md "핵심 설계" A절·컴포넌트 설계 PriceStore, spec.md PRICE-REST-001 |
| 12:05 | implementer | `./gradlew test --tests PriceStoreTest,LimitOrderTriggerListenerTest,CryptoPriceStreamServiceTest` | tasks.md 항목 3, plan.md "컴포넌트 설계 — PriceStore" §이벤트 발행 |
| 12:40 | implementer | `./gradlew test --tests PriceStoreTest,LimitOrderTriggerListenerTest,CryptoPriceStreamServiceTest,LimitOrderFillIntegrationTest,CryptoPriceStreamIntegrationTest` | tester 지적 — SSE id가 receivedAt 기준이라 REST전용 연속 가격 변경 시 id 충돌, `docs/api-contracts.md` price 이벤트 절 |
| 15:03 | implementer | `./gradlew compileJava compileTestJava test --tests BithumbRestTickerPollerTest,BithumbRestTickerPollerConditionalTest,CryptoRealProfileContextIntegrationTest` | tasks.md 항목 4, plan.md "컴포넌트 설계 — BithumbRestTickerPoller", spec.md PRICE-REST-002·PRD C-005 |
| 15:40 | implementer | `./gradlew compileJava test --tests NewsCollectionIntegrationTest` | tasks.md 항목 5, plan.md "스케줄러 풀" §PRICE-REST-006 |

## 모니터링 (사람용 요약)
- 10:31 — 항목 1(체결 경로 STALE 허용) 구현, `getCryptoExecutionPriceQuote` 제거하고 코인 체결이 표시 판정을 재사용하도록 변경, 컴파일 통과.
- 11:20 — 항목 2(observedAt 분리) 구현: saveTick이 observedAt도 기록, recordObservation 신규(호출자 없음, 이벤트 미발행), CryptoPriceDto에 observedAt 추가(3-인자 호환 생성자로 기존 호출부 무변경), PriceStoreTest에 PRICE-REST-003 포함 신규 케이스 추가, 관련 회귀 테스트 통과.
- 12:05 — 항목 3(recordObservation 이벤트 발행 조건) 구현: 가격이 실제로 바뀔 때만 publish, receivedAt은 기존 값(없으면 observedAt)을 그대로 실음. PriceStoreTest에 발행/미발행 2케이스 추가, LimitOrderTriggerListenerTest·CryptoPriceStreamServiceTest 무변경으로 회귀 없이 통과.
- 12:40 — tester 발견 결함 수정: `CryptoPriceUpdatedEvent`에 `observedAt` 필드 추가, `CryptoPriceStreamService`의 SSE id를 receivedAt 대신 observedAt 기준으로 변경(payload sourceTime은 그대로 receivedAt). CryptoPriceStreamServiceTest에 id 충돌 회귀 테스트 추가, CryptoPriceStreamIntegrationTest id 기대값 갱신, LimitOrderTriggerListenerTest 생성자 호출부 갱신. `docs/api-contracts.md` price 이벤트 id 설명 갱신. 통합 테스트 포함 전부 통과.
- 15:03 — 항목 4(BithumbRestTickerPoller 운영 확장) 구현: `@Profile`을 `prod | crypto-real`로 확장, 의존성을 `FakeBithumbFeedClient`에서 `PriceStore`로 교체하고 `emitTicks`가 `recordObservation(symbol, price, observedAt)`을 호출하도록 변경(로컬 변수명도 receivedAt→observedAt으로 정정). `BithumbRestTickerPollerTest`를 PriceStore mock 검증으로 전환(기존 MockRestServiceServer·실패 스킵 단정 유지), `BithumbRestTickerPollerConditionalTest`에 prod 단독·prod+crypto-real 케이스를 "생성됨"으로 반전하고 prod의 enabled=false 격리 케이스를 추가. `application.yml`의 폴러 관련 주석 1곳(라인 155)을 새 프로필 범위에 맞게 정정. `CryptoRealProfileContextIntegrationTest` 재실행으로 회귀 없음 확인.
- 15:40 — 항목 5(스케줄러 풀 상향) 구현: `application.yml`의 `spring.task.scheduling.pool.size`를 16→17로 상향. 코드베이스 전체 `@Scheduled` 메서드가 17개(신규 `BithumbRestTickerPoller.pollTickers` 포함)임을 grep으로 재확인하고, 프로필별 실제 활성 개수(기본 16·crypto-real 16·prod 16, 이슈 #369 이전 prod는 15)를 주석에 명시 — 세 프로필 모두 16이 필요하므로 여유 1개(17)를 유지. `application.yml`·`application-crypto-real.yml` 두 곳의 "지금은 13/16이다" 주석을 실제 값과 계산 근거로 정정(PRICE-REST-006). `NewsCollectionIntegrationTest.schedulingPoolIsLargeEnoughForEveryScheduledTask` 통과 확인.
