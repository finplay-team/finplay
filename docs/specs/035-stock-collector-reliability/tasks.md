# Tasks: 주식 분봉 수집 배치 안정성 보강

- [x] 1. 샌드박스 종목 수집 제외 + 수집 실패 가시화 (COLLECT-STAB-002, COLLECT-STAB-004)
  - `InstrumentRepository`에 `findByMarketAndTutorialSampleFalseOrderByIdAsc(Market market)` 추가 +
    `@DataJpaTest`.
  - `KisHistoricalCandleCollector.collect()`가 이 메서드로 종목을 조회하도록 변경.
  - `KisHistoricalCandleImportWriter.persist()`가 `SUCCESS`가 아닌 결과에 `log.warn`(`PARTIAL_SUCCESS`)/
    `log.error`(`FAILED`)를 남기도록 추가.
  - 기존 `KisHistoricalCandleCollectorTest` 확장(샌드박스 종목이 섞인 mock 목록에서도 실제 종목만 처리됨을
    검증) + 로그 상호작용 단위 테스트.

- [x] 2. Redis 기반 수집 락 도입 (COLLECT-STAB-001)
  - `com.finplay.api.market.config.MarketStockProperties`/`MarketStockConfig` 신설
    (`collect-lock-ttl-seconds` 기본값 600) + `application.yml` 미러링 + 드리프트 테스트(있다면 기존 패턴 재사용).
  - `com.finplay.api.market.service.StockCollectionLock` 신설 — `feedback.service.RedisLock` 재사용,
    `CryptoWatchLock`과 같은 구조.
  - `KisHistoricalCandleCollector.collect()`에 락 획득/해제(`try/finally`) 배선. 락 획득 실패 시 조용히
    반환(KIS 호출·`market_data_imports` 기록 없음).
  - 신규 `StockCollectionLockConcurrencyIntegrationTest`(Testcontainers) — [방어 켠 상태] 동시 2실행 시
    `market_data_imports` 1건만 남고 `stock_candles` 유니크 위반 없음 / [재현] 락 우회 mock으로 위반 재현
    대조.

- [ ] 3. 당일 재시도 스케줄러 (COLLECT-STAB-003)
  - `MarketStockProperties`에 `retry-cron` 추가(기본값 `0 15,30,45 8-10 * * MON-FRI`).
  - `KisHistoricalCandleCollector.retryPendingInstruments()` 신설(`@Scheduled` + `collect()` 위임 호출).
  - `StockCollectionLockConcurrencyIntegrationTest`에 [재시도 멱등성]·[재시도 무대상] 시나리오 추가(태스크
    2에서 만든 클래스에 이어서 작성 — 같은 픽스처·같은 락 컴포넌트를 공유하므로 별도 클래스로 분리하지
    않는다).

- [ ] 4. 운영 KIS 호출 간격 설정 보강 (COLLECT-STAB-005)
  - `application-prod.yml`에 `kis.request-interval-ms: 600` 추가.
  - `KisProperties.java`의 `requestIntervalMs` 주석 중 "실전투자 도메인 기준" 근거 문장을 현재 배포 구성
    (운영도 모의투자 도메인)에 맞게 정정.

- [ ] 5. 전체 회귀 확인 + 문서 동기화
  - 기존 `KisHistoricalCandleCollectorTest`·`StockReplaySessionSchedulerTest`가 태스크 1~4 변경 후에도
    통과함을 확인.
  - `./gradlew build` 통과 확인.
  - `docs/prd.md` §3 "구현 현황"에 새 행 추가 — 기능: "주식 분봉 수집 배치 안정성 — 다중 인스턴스 중복 방지·
    샌드박스 제외·당일 재시도", 요구사항 ID: `COLLECT-STAB-001~005`, 상태·근거(이 spec 경로 + 구현 PR 번호)
    (CLAUDE.md 규칙 10).
  - 이 spec의 §배경 조사 ④(9시간 불일치)가 이 태스크 완료 시점까지도 미확인이면, `docs/prd.md` §3 행 또는
    별도 GitHub 이슈로 "미확인·후속 조사 필요"를 남긴다 — 추측으로 원인을 채우지 않는다.
