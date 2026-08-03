# Run Log: 013-candle-interval

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer(①) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 1·3·7·9, spec.md "공통 계약" |
| - | implementer(②) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 4, spec.md "주식 — stock_candles 집계" |
| - | implementer(③) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 5·6, spec.md "공개 상한(reveal bound)" |
| - | implementer(④) | `./gradlew compileJava compileTestJava` | plan.md 구성요소 8·9, spec.md "코인 — 빗썸 위임" |
| - | implementer(⑤ 통합 테스트만) | `./gradlew test --tests "com.finplay.api.market.*"` | plan.md "테스트 계획 — 통합", tasks.md ⑤, 기존 `CandleQueryServiceIntegrationTest` 패턴 재사용 |
| - | reviewer(리뷰) | `git diff origin/dev...HEAD` (013 관련 커밋만, 인증 리팩터링 머지분 제외) | docs/conventions.md, docs/adr/0002·0003·0004, docs/api-routes.md·api-contracts.md, docs/specs/013-candle-interval/{spec,plan,tasks}.md |
| - | implementer(PR #151 외부 리뷰 차단 대응) | `./gradlew compileJava compileTestJava` + `./gradlew test --tests StockReplayServiceTest --tests StockCandleAggregatorTest` | docs/api-contracts.md의 "버킷 시작일이 [from,to] 안에 있으면 포함" 계약, PR #151 리뷰(namdongyeob) |

## 모니터링 (사람용 요약)
- ① interval 4값 배관 완료, 컴파일 통과. 기존 `CandleIntervalTest.fromThrowsValidationErrorWhenValueHasDifferentCase`가 "1M"을 이제 유효값(ONE_MONTH)으로 처리해 회귀 실패 — tester가 spec 반영해 갱신 필요.
- ② `StockCandleAggregator`(market/service) 신규 작성, Spring 의존 없는 순수 클래스, 컴파일 통과. 단위 테스트는 미작성(tester 담당).
- ③ `StockCandleRepository`에 거래일 범위 파생 쿼리 1개 추가, `StockReplayService.getRevealedAggregatedCandles` 신규(2회 조회 분리+`StockCandleAggregator`+200 캡), `KisHistoricalReplayPriceProvider`가 집계 interval을 위임하도록 연결. `getRevealedCandles`·기존 리포지토리 메서드 6개 미변경. 컴파일 통과, 단위·`@DataJpaTest`는 tester 담당.
- ④ `BithumbRestCandleProvider`에 interval별 엔드포인트(`days`·`weeks`·`months`, 1m은 `minutes/1` 유지)·`count` 단위별 산출(주·월은 월요일·1일 정렬 후 계산) 추가, `BithumbCandleItem` 재사용, `to` UTC 변환·정렬 반전·필드 매핑·502 경로 공유. `FakeCryptoCandleProvider`는 내부 맵을 `interval→symbol→목록`으로 바꾸고 `setCandles(symbol, interval, candles)` 오버로드 추가, 기존 `setCandles(symbol, candles)`는 1m 시드로 위임 유지. 컴파일 통과, 단위 테스트는 tester 담당.
- ⑤(통합 테스트만) 기존 `CandleQueryServiceIntegrationTest`에 테스트 2개 추가(문서 동기화·외부 스모크는 별도 담당). ①같은 주(3/2·3/4)·다른 주(3/11)·다른 달(재생거래일 4/1)의 실 MySQL 분봉으로 `1d`·`1w`·`1M` 개수·`sourceTime`·OHLCV 검증 + 같은 테스트에서 `interval=1m` 회귀 확인. ②`FakeCryptoCandleProvider`에 코인 인스턴스 시드해 `1d`·`1w`·`1M`·`1m`(빈 배열) 확인. `market.*` 전체(42개 스위트)·`order`·`portfolio`·`account` 통합 테스트 재실행해 날짜·인스턴스 충돌 없음 확인, 전부 통과. `spotlessCheck` 통과.

## 외부 스모크 (이슈 #143) — 자동 테스트와 구분 (C-005)

자동 테스트는 외부 네트워크를 호출하지 않는다. 아래는 빗썸 공개 캔들 REST를 직접 호출해 확인한 결과이며 CI에서 재현되지 않는다. 인증이 필요 없는 공개 시세 엔드포인트라 앱을 기동하지 않고 직접 호출했다.

- 실행 조건: 2026-08-03 KST, `market=KRW-BTC`, `count=2`.
- `GET https://api.bithumb.com/v1/candles/days` → 최신 봉 `candle_date_time_kst: "2026-08-03T00:00:00"` — **KST 자정, 그날 날짜**.
- `GET https://api.bithumb.com/v1/candles/weeks` → 최신 봉 `candle_date_time_kst: "2026-08-03T00:00:00"`, `first_day_of_period: "2026-08-03"`. **2026-08-03은 실제로 월요일**(검산: 2026-01-01=목요일 기준 215번째 날 → 월요일) — **빗썸의 주 경계도 월요일 시작**이다.
- `GET https://api.bithumb.com/v1/candles/months` → 최신 봉 `candle_date_time_kst: "2026-08-01T00:00:00"`, `first_day_of_period: "2026-08-01"` — **월의 1일, KST 자정**.
- 비교용 `GET https://api.bithumb.com/v1/candles/minutes/1` → `candle_date_time_utc: "2026-08-03T07:19:00"` vs `candle_date_time_kst: "2026-08-03T16:19:00"` — UTC+9 변환이 정확함을 재확인(기존 1분봉 경로가 이미 이 변환을 쓰고 있어 새로운 사실은 아니지만 교차 확인).

**결론(spec.md 미결 사항 해소)**: spec.md는 "주식과 코인의 버킷 경계 정의가 다를 수 있다"고 방어적으로 열어뒀으나(서버가 강제로 맞추지 않는다는 원칙 자체는 유지), **실측 결과 빗썸의 일/주/월봉 경계는 KST 자정·월요일 시작·매월 1일 시작으로 이번 스펙의 주식 집계 규칙과 사실상 동일하다.** 우연의 일치이며 빗썸이 규칙을 바꾸면 다시 벌어질 수 있으므로, 서버가 이를 전제로 로직을 짜지는 않았다(코인 경로는 여전히 빗썸 값을 그대로 통과시킬 뿐 이 일치를 가정하지 않음) — 다만 **현재 시점 기준으로는 프론트 차트에서 두 시장을 나란히 봐도 어색하지 않다**는 것을 확인했다.
- **미실행**: 인증이 필요한 우리 API(`GET /api/instruments/{id}/candles`)를 통한 end-to-end 스모크(`SPRING_PROFILES_ACTIVE=local,crypto-real ./gradlew bootRun`)는 수동으로 실행하지 않았다 — 빗썸 응답 자체의 경계값 확인이 목적이었고, 우리 서버가 그 값을 무보정으로 전달한다는 것은 ④의 단위 테스트(`getCandlesParsesDayCandleIgnoringPeriodOnlyFieldsWithoutExposingThem` 등)로 이미 고정되어 있다.
- 리뷰(013 관련 커밋만) — 차단 0건, 권장 2건(기존 파일 첫줄 주석이 "1분봉"으로 남아 다중 interval 확장을 반영하지 못함), 참고 1건. 공개 상한 유출 방지·대소문자 구분·버킷 경계·빗썸 count 계산·1m 회귀 모두 코드·테스트로 확인, 외부 스모크 기록도 실제 빗썸 호출로 재검증해 일치 확인. 머지 가능.

## 외부 스모크 추가 — `to` 파라미터의 경계 포함 여부 (PR #151 리뷰 논의 3건 대응, 2026-08-03 KST)

PR #151 리뷰(namdongyeob)에서 "일/주/월봉에서도 `to`의 경계 포함 여부를 확인했나요"라는 논의가 있어, 실제로 `to`를 지정해 빗썸 공개 API를 재호출해 관측했다.

- `GET .../candles/days?market=KRW-BTC&count=3&to=2026-08-03 00:00:00`(오늘 일봉의 시작 시각과 정확히 같은 값) → 응답의 최신 봉은 `2026-08-02T00:00:00`. **오늘(8/3) 봉이 응답에서 빠졌다.**
- `GET .../candles/weeks?...&to=2026-08-03 00:00:00`(이번 주 월요일=이번 주 봉의 시작 시각) → 최신 봉이 `2026-07-27T00:00:00`(지난주). **이번 주 봉이 빠졌다.**
- `GET .../candles/months?...&to=2026-08-01 00:00:00`(이번 달 1일=이번 달 봉의 시작 시각) → 최신 봉이 `2026-07-01T00:00:00`(지난달). **이번 달 봉이 빠졌다.**

**결론**: 빗썸의 `to`는 그 정확한 경계 시각을 **배제(exclusive)**한다 — `to`와 정확히 같은 시각에 시작하는 봉은 응답에 포함되지 않는다. 그리고 `BithumbRestCandleProvider.resolveToParam`은 사용자가 보낸 `to`를 KST→UTC 변환만 하고 그대로 전달하므로(`interval`별 보정 없음), **프론트가 "오늘 날짜"를 `to`로 넘기면(자정 KST로 파싱됨) 오늘 일봉이 응답에서 빠지는 실제 문제가 재현된다.** 리뷰어가 예측한 "오늘 봉이 안 보인다" 시나리오가 실측으로 확인됐다.

**이번 PR에서 고치지 않은 이유**: 이 배제 동작은 `interval=1m`에서도 원래 있던 것으로 보이며(빗썸 API 자체의 사양), `to`를 프론트가 상대적으로 자주 안 쓰는 `1m` 경로에서는 최대 1분 손실이라 체감이 적었을 가능성이 높다. 이번에 새로 추가된 `1d`·`1w`·`1M`에서는 최대 하루~한 달치 데이터가 통째로 빠지므로 체감 임팩트가 크다. 다만 이 배제 동작을 보정해서 "우리 API의 `to`는 항상 포함(inclusive)"으로 만들려면 `1m` 경로까지 일관되게 손봐야 하는 더 큰 설계 문제라, 이번 PR(작은 루프) 범위를 넘는다고 판단해 이번엔 **정확한 관측 사실만 문서에 기록**하고 코드는 고치지 않았다. 후속 이슈로 분리하는 것을 제안한다.

## PR #151 외부 리뷰(namdongyeob) 차단 대응 — `getRevealedAggregatedCandles`

- **차단 1건(해결)**: `rangeStart`가 버킷 경계(월요일/1일)와 어긋나면(예: `interval=1w&from=수요일`) `StockCandleAggregator`가 그 이전 요일 분봉이 없는데도 "완전한 봉인 것처럼" 반쪽 버킷을 그대로 반환하던 문제. `docs/api-contracts.md`의 "버킷 시작일이 [from,to] 안에 있으면 포함" 계약대로, 집계 결과에서 `tradingDate() < rangeStart`인 선두 버킷을 200개 캡 적용 **이전에** 필터링하도록 `StockReplayService.getRevealedAggregatedCandles`를 수정. `fromDate`가 생략돼 `rangeStart`가 `lookbackFloor`인 경우에도 동일 필터를 적용(계약상 문제는 없고, 실질적으로는 200 캡이 흡수하던 경계라 큰 영향 없음).
- **권장 1 (해결)**: `rangeEnd` 클램프 주석을 "방어 규칙"에서 PRD MKT-005 08:40 폴백으로 실제 도달 가능한 경로임을 밝히도록 정정.
- **권장 2 (해결)**: `StockCandleAggregator`(정렬 전제가 깨지면 조용히 틀린 OHLC를 만드는 순수 클래스)를 `public`에서 package-private으로 좁힘 — 호출부가 `market.service` 패키지 안(`StockReplayService`, 테스트)뿐이라 가능. 순서 역행 가드 대신 이 방식을 택함(코드베이스에 이미 package-private 유틸 클래스 관례가 있고 더 간단함).
- 컴파일 통과, 기존 `StockReplayServiceTest`·`StockCandleAggregatorTest` 재실행 통과(회귀 없음). `interval=1w`·`1M`에서 `from`이 버킷 중간에 걸치는 실제 케이스 테스트는 tester가 추가 예정.

## 후속 이슈 분리 (2026-08-03)

PR #151 리뷰에서 나온 논의 2건은 이번 PR(작은 루프) 범위를 넘는다고 판단해 큰 루프(새 이슈)로 분리했다.

- [#155](https://github.com/finplay-team/finplay/issues/155) — 캔들 집계 조회에 읽기 상한이 없어 데이터가 쌓일수록 조회 비용 증가
- [#157](https://github.com/finplay-team/finplay/issues/157) — 빗썸 캔들 `to` 파라미터가 경계 시각을 배제해 오늘 봉이 안 보일 수 있음
