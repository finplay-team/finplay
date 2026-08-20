# Tasks: 주식 일봉 3년치 아카이브 수집

spec 하나당 3~7개 권장(`ai/specs/README.md`) 기준으로 5개로 나눈다. 각 항목이 커밋 1개다.

- [x] **1. 외부 스모크로 Decision Gate 해소** — 실제 KIS `inquire-daily-itemchartprice` 1회 호출로 `output2` 일봉 필드명과 수정주가 옵션(`FID_ORG_ADJ_PRC`) 의미를 확인하고, 확정 내용을 `plan.md`에 반영한다. **이 항목이 끝나기 전에는 2번의 응답 매핑을 확정하지 않는다** (spec §Decision Gate).

- [x] **2. 엔티티 + Repository + 마이그레이션** — `StockDailyCandle`, `StockDailyCandleRepository`, `V54__create_stock_daily_candles.sql`(작성 직전 `git ls-tree origin/dev`로 번호 재확인, ADR-0004). `@DataJpaTest`로 `UNIQUE(instrument_id, trading_date)` 위반·최신 거래일 조회·기간 조회 정렬 검증.

- [x] **3. KIS 일봉 클라이언트** — `KisDailyCandleClient` / `KisDailyCandleClientImpl`(날짜 커서 역방향 페이징, 레이트리밋 재시도, 페이지 상한) / `FakeKisDailyCandleClient`. 단위 테스트로 페이징 종료 조건·응답 검증 규칙·상한 도달 시 중단 검증.

- [x] **4. 수집기 + Writer** — `StockDailyCandleCollector`(빈 구간 계산, 락, 종목 단위 실패 격리, 이력 기록), `StockDailyCandleImportWriter`(저장 트랜잭션 경계), 배치 스케줄 등록. 단위 테스트로 빈 구간 계산 4케이스(최초·정상·재실행·부분 보유).

- [x] **5. 통합 테스트 + 개발용 트리거 + 문서 갱신** — Testcontainers로 최초 전량 → 증분 → 재실행 행 수 검증, 종목 1개 실패 시 나머지 저장 검증, `stock_candles` 무변경 회귀. `POST /api/dev/stock-daily-imports`(local 프로필 전용) 추가. `ai/api-routes.md`에 그 라우트 행 추가(CLAUDE.md 규칙 7), `ai/prd.md` §3 구현 현황의 MKT-011 행을 완료로 갱신(규칙 10).

## 마무리 확인

- [x] `./gradlew build` 통과 (CLAUDE.md 규칙 4).
- [x] 새 소스 파일 첫 줄에 한 줄 한국어 주석 (규칙 6).
- [x] `docs/erd.md`의 `stock_daily_candles` 항목이 실제 스키마와 일치하는지 대조 (task 2에서 반영, V53 스키마와 변경 없음).
- [x] 외부 스모크 결과를 자동 테스트 통과와 **구분해서** 보고 (C-005) — task 1에서 실제 KIS 호출 결과를 plan.md에 명시, 이후 tasks는 그 결과를 근거로 Fake 기반 자동 테스트만 수행.
