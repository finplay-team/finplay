# Run Log: 013-candle-interval

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer(①) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 1·3·7·9, spec.md "공통 계약" |
| - | implementer(②) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 4, spec.md "주식 — stock_candles 집계" |
| - | implementer(③) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 5·6, spec.md "공개 상한(reveal bound)" |
| - | implementer(④) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 8·9, spec.md "코인 — 빗썸 위임" |
| - | implementer(⑤ 통합 테스트만) | `./gradlew test --tests "com.finplay.api.market.*"` | plan.md "테스트 계획 — 통합", tasks.md ⑤, 기존 `CandleQueryServiceIntegrationTest` 패턴 재사용 |

## 모니터링 (사람용 요약)
- ① interval 4값 배관 완료, 컴파일 통과. 기존 `CandleIntervalTest.fromThrowsValidationErrorWhenValueHasDifferentCase`가 "1M"을 이제 유효값(ONE_MONTH)으로 처리해 회귀 실패 — tester가 spec 반영해 갱신 필요.
- ② `StockCandleAggregator`(market/service) 신규 작성, Spring 의존 없는 순수 클래스, 컴파일 통과. 단위 테스트는 미작성(tester 담당).
- ③ `StockCandleRepository`에 거래일 범위 파생 쿼리 1개 추가, `StockReplayService.getRevealedAggregatedCandles` 신규(2회 조회 분리+`StockCandleAggregator`+200 캡), `KisHistoricalReplayPriceProvider`가 집계 interval을 위임하도록 연결. `getRevealedCandles`·기존 리포지토리 메서드 6개 미변경. 컴파일 통과, 단위·`@DataJpaTest`는 tester 담당.
- ④ `BithumbRestCandleProvider`에 interval별 엔드포인트(`days`·`weeks`·`months`, 1m은 `minutes/1` 유지)·`count` 단위별 산출(주·월은 월요일·1일 정렬 후 계산) 추가, `BithumbCandleItem` 재사용, `to` UTC 변환·정렬 반전·필드 매핑·502 경로 공유. `FakeCryptoCandleProvider`는 내부 맵을 `interval→symbol→목록`으로 바꾸고 `setCandles(symbol, interval, candles)` 오버로드 추가, 기존 `setCandles(symbol, candles)`는 1m 시드로 위임 유지. 컴파일 통과, 단위 테스트는 tester 담당.
- ⑤(통합 테스트만) 기존 `CandleQueryServiceIntegrationTest`에 테스트 2개 추가(문서 동기화·외부 스모크는 별도 담당). ①같은 주(3/2·3/4)·다른 주(3/11)·다른 달(재생거래일 4/1)의 실 MySQL 분봉으로 `1d`·`1w`·`1M` 개수·`sourceTime`·OHLCV 검증 + 같은 테스트에서 `interval=1m` 회귀 확인. ②`FakeCryptoCandleProvider`에 코인 인스턴스 시드해 `1d`·`1w`·`1M`·`1m`(빈 배열) 확인. `market.*` 전체(42개 스위트)·`order`·`portfolio`·`account` 통합 테스트 재실행해 날짜·인스턴스 충돌 없음 확인, 전부 통과. `spotlessCheck` 통과.
