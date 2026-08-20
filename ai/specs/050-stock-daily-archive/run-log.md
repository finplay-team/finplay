# Run Log: 050-stock-daily-archive

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-08-20 | implementer | curl로 KIS `/oauth2/tokenP` 토큰 발급 후 `inquire-daily-itemchartprice`(005930) 실호출, `FID_ORG_ADJ_PRC=0/1` 대조(2018 액면분할 구간) | spec.md §Decision Gate, plan.md 설계 요지 |
| 2026-08-20 | implementer | `git ls-tree origin/dev src/main/resources/db/migration/`로 V53 번호 재확인 후 `./gradlew compileJava` | plan.md 데이터 모델·Decision Gate 해소 절, ADR-0004 |

## 모니터링 (사람용 요약)
- 2026-08-20 — 외부 스모크로 `output2` 필드명(`stck_bsop_date`·`stck_oprc`·`stck_hgpr`·`stck_lwpr`·`stck_clpr`·`acml_vol`)과 `FID_ORG_ADJ_PRC=0`(수정주가) 채택을 확정, plan.md에 근거와 함께 반영. 이후 항목(엔티티·클라이언트)은 이 매핑을 그대로 쓰면 된다.
- 2026-08-20 — `StockDailyCandle`·`StockDailyCandleRepository`·`V53__create_stock_daily_candles.sql` 추가, `docs/erd.md` market 절 동기화. compileJava 통과. `@DataJpaTest`는 tester 담당이라 이번 항목에서 작성하지 않음.
