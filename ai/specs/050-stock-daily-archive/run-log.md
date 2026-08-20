# Run Log: 050-stock-daily-archive

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-08-20 | implementer | curl로 KIS `/oauth2/tokenP` 토큰 발급 후 `inquire-daily-itemchartprice`(005930) 실호출, `FID_ORG_ADJ_PRC=0/1` 대조(2018 액면분할 구간) | spec.md §Decision Gate, plan.md 설계 요지 |
| 2026-08-20 | implementer | `git ls-tree origin/dev src/main/resources/db/migration/`로 V53 번호 재확인 후 `./gradlew compileJava` | plan.md 데이터 모델·Decision Gate 해소 절, ADR-0004 |
| 2026-08-20 | implementer | `./gradlew compileJava` | plan.md Decision Gate 해소 절(필드 매핑·수정주가), 구성요소 표, 입력 명세 표. 1분봉 `KisHistoricalCandleClientImpl` 패턴 재사용 |
| 2026-08-20 | implementer | `grep -rn "@Scheduled"`로 기존 배치 시각 전수 확인 후 `./gradlew compileJava` | plan.md 결정 1(빈 구간 계산), 구성요소 표. 1분봉 `KisHistoricalCandleCollector`·`KisHistoricalCandleImportWriter`·`StockCollectionLock` 패턴 재사용 |

## 모니터링 (사람용 요약)
- 2026-08-20 — 외부 스모크로 `output2` 필드명(`stck_bsop_date`·`stck_oprc`·`stck_hgpr`·`stck_lwpr`·`stck_clpr`·`acml_vol`)과 `FID_ORG_ADJ_PRC=0`(수정주가) 채택을 확정, plan.md에 근거와 함께 반영. 이후 항목(엔티티·클라이언트)은 이 매핑을 그대로 쓰면 된다.
- 2026-08-20 — `StockDailyCandle`·`StockDailyCandleRepository`·`V53__create_stock_daily_candles.sql` 추가, `docs/erd.md` market 절 동기화. compileJava 통과. `@DataJpaTest`는 tester 담당이라 이번 항목에서 작성하지 않음.
- 2026-08-20 — `KisDailyCandleClient`/`KisDailyCandleClientImpl`/`FakeKisDailyCandleClient`/`RawDailyCandleDto` 추가. 날짜 커서 역방향 페이징·레이트리밋 재시도는 1분봉 클라이언트와 같은 패턴(별도 토큰 캐시), 응답 행 검증은 위반 행만 폐기(부분성공 근거). compileJava 통과, 단위 테스트는 tester 담당이라 작성하지 않음.
- 2026-08-20 — `StockDailyCandleCollector`/`StockDailyCandleImportWriter`/`DailyInstrumentOutcome` 추가. 배치 스케줄 08:25 KST로 확정(기존 스케줄 전수 확인, plan.md 반영). `StockCollectionLock` 재사용 시 1분봉 배치와 락 키가 겹칠 수 있다는 트레이드오프를 plan.md에 명시. compileJava 통과, 단위 테스트는 tester 담당이라 작성하지 않음. tester가 남긴 관찰(KisDailyCandleClientImpl의 커서 진행 검증 도달 불가 방어 코드)은 이번 항목 범위 밖이라 손대지 않음.
