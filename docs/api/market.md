# API 계약 — market

`docs/api-contracts.md`의 market 도메인 절을 옮겨 정리한 문서다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

---

### 종목 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments?market= | Access Bearer 필수 | `market`(선택, `STOCK`·`CRYPTO`만 허용, 생략·빈 값 시 전체) | 200 `[{"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","tickSize":100,"minOrderAmount":70000,"tradable":true,"isTutorialSample":false}, ...]` | `market`이 `STOCK`·`CRYPTO` 외 값이면 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-001, Issue #14, 031 SANDBOX-001 |

`market` 생략·빈 값 시 시드된 주식 16종·코인 12종에 더해 031의 튜토리얼 전용 샘플 종목(`SANDBOX_STK_1~3`·`SANDBOX_COIN_1~3`, `isTutorialSample=true`, 시장당 1번째만 `tradable=true`)을 `id` 오름차순으로 반환한다. `isTutorialSample`은 목록·단건 응답 모두에 존재하는 추가 전용 필드다(기존 소비자에게 breaking change 없음).

### 종목 현재가 조회

| Method | URL | 인증 | 경로 변수 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/price | Access Bearer 필수 | `instrumentId` | 200 `{"price":71200,"sourceTime":"2026-07-22T09:00:00","status":"AVAILABLE","sourceTradingDate":"2026-07-22"}` (`PriceResponse`) — 코인은 `sourceTradingDate`가 `null`. **코인은 연결 유지 + 수신 이력이 있으면 마지막 관측 시각이 얼마나 오래됐든(수 분·수 시간이 지나도) 경과 시간과 무관하게 항상 `status: "AVAILABLE"`이다**(036 — 032가 도입했던 `PriceStatus.STALE`은 되돌려져 더 이상 나오지 않는다) — 예: `{"price":95000000,"sourceTime":"2026-07-28T10:30:00","status":"AVAILABLE","sourceTradingDate":null}`. **주식도 `marketStatus=CLOSED`이면 마지막으로 재생됐던 거래일의 값을 `AVAILABLE`로 유지한다**(038, QUOTE-HOLD-001, 아래 문단 참조) | 존재하지 않는 `instrumentId`는 404 `NOT_FOUND`. 유효한 최신 가격이 없으면(주식: `OPEN` 중 그 종목만 미공개 분봉이거나, `CLOSED`인데 폴백 후보 재생세션도 없음; 코인: 연결 끊김이거나 그 심볼의 시세를 한 번도 받은 적이 없음) 409 `PRICE_UNAVAILABLE` — **코인은 이 두 경우 외에는 관측 시각이 얼마나 오래됐어도 409가 아니다**(036, 032 PRICE-STALE-001을 대체). 숫자가 아닌 `instrumentId`는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-002/MKT-003/MKT-004, 032, 034, 036, 038, Issue #16, Issue #355, Issue #369, Issue #384 |

이 API는 회원 소유 리소스가 아니라 공개 종목 정보 조회이므로 별도 소유권 검사는 없다(로그인만 하면 누구나 조회 가능). `PriceQueryService`가 `instrument.getMarket()`에 따라 주식은 주입된 `StockPriceProvider`(현재 구현체가 무엇인지 이 API·서비스는 알지 못한다), 코인은 `PriceStore`(Redis)에 위임하며, 어느 경로든 유효성 판정 실패는 컨트롤러가 아니라 서비스가 `BusinessException`으로 던지고 `GlobalExceptionHandler`가 응답 코드로 변환한다(컨트롤러에 try-catch 없음). **코인 체결 거부(409)는 연결 끊김·수신 이력 없음 두 경우뿐이며, 그 외에는(연결 유지 + 수신 이력 있음) 경과 시간과 무관하게 항상 마지막 가격으로 시장가·지정가 주문이 체결된다**(`PriceQueryService.getOrderExecutionPrice`가 `getCryptoDisplayPriceQuote`에 위임하는 구조 그대로, 036 — 032 PRICE-STALE-003·034 PRICE-REST-004가 다루던 "stale이어도 체결 허용"이라는 구분 자체가 무의미해졌다). 034가 함께 도입한 REST 폴링 백업(3초 주기 빗썸 ticker 전체 심볼 조회)은 이 spec과 무관하게 그대로 유지되며, `CryptoPriceSnapshotService`의 변동 카드 신뢰도 게이트에 계속 쓰인다(PRICE-REST-001·002).

**주식의 장외 시세 유지(038, QUOTE-HOLD-001~006, 이슈 #384)**: 예전에는 `marketStatus=CLOSED`(평일 09:01 이전·주말·공휴일)면 오늘 재생세션 기준으로 공개할 값이 없어 이 API가 곧바로 409 `PRICE_UNAVAILABLE`을 반환했다. 이제는 `CLOSED`이고 오늘 세션 기준 결과가 없을 때, **서비스 날짜가 오늘보다 이전인 마지막 `READY` 재생세션**의 마지막 공개 분봉 종가를 `status: "AVAILABLE"`로 반환한다 — `sourceTime`은 그 분봉의 시각, `sourceTradingDate`는 그 원본 거래일이다. 값을 새로 계산하지 않고 이미 공개됐던 값을 그대로 유지할 뿐이다(보간·추정 없음). 폴백 세션 탐색에는 날짜 상한이 없어 연휴가 길어도 값이 비지 않는다(QUOTE-HOLD-006). **오늘 세션은 폴백 후보가 아니다** — 08:40~09:00처럼 오늘 세션이 `READY`여도 아직 그날 오후가 재생되지 않았으므로, 이때도 직전 서비스 날짜의 값을 보여준다(QUOTE-HOLD-003). **장중(`OPEN`)에는 이 폴백이 전혀 동작하지 않는다** — 특정 종목만 분봉이 없는 것은 수집 장애(#370)이며 옛 값으로 가리지 않고 그대로 409다(QUOTE-HOLD-004). 폴백으로 채운 시세는 `sessionReady=false`·`replaySession=null`을 유지해 체결가 경로에 도달하지 않는다(QUOTE-HOLD-005) — 이 상태에서 주식 주문은 여전히 409 `MARKET_CLOSED`다(`docs/api/order.md` 참조). 폴백 후보 세션의 원본 거래일에 분봉이 하나도 없으면(보관 정리 등) 폴백은 성립하지 않고 지금처럼 409 `PRICE_UNAVAILABLE`이다.

### 캔들 조회 (주식·코인 — 1m·1d·1w·1M)

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/candles | Access Bearer 필수 | `interval`(필수, `1m`\|`1d`\|`1w`\|`1M` 중 하나, **대소문자 구분**), `from`·`to`(선택, ISO-8601 `LocalDateTime`, 예: `2026-07-27T09:00:00`) | 200 `[{"sourceTime":"2026-07-22T09:00:00","open":71000,"high":71500,"low":70900,"close":71200,"volume":12345}, ...]` (`CandleResponse[]`, 시각 오름차순, 모든 `interval`·모든 시장 공통 최대 200개, 초과 시 `to` 기준 최신 200개). 주식은 어떤 `interval`이든 아직 공개된 분봉이 없거나 재생세션이 준비되지 않은 경우 예외 없이 200 `[]` — **단 `marketStatus=CLOSED`이면 빈 배열 대신 마지막 재생 거래일의 하루치 봉을 반환한다**(038, 아래 "주식 캔들 전용 규칙" 참조) | `interval`이 `1m`·`1d`·`1w`·`1M`이 아니면(대소문자 변형 `1D`·`1W`·`1MO`·`1min` 포함) 400 `VALIDATION_ERROR`. `from > to`면 400 `VALIDATION_ERROR` — **코인은 `interval`과 무관하게 항상 시각까지 포함해 비교**하지만, 주식 `1d`·`1w`·`1M`은 날짜 성분만 비교한다(아래 요약 참조). 그래서 같은 `from`·`to`라도 주식 집계에서는 통과하는 값이 코인에서는 400이 될 수 있다. 존재하지 않는 `instrumentId`는 404 `NOT_FOUND`. 코인에서 빗썸 조회가 실패하면 502 `MARKET_DATA_PROVIDER_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. **오류 계약은 `1m`만 지원하던 때와 동일하며 013에서 바뀌지 않았다** — 4값으로 확장된 것은 `interval` 허용값 자체뿐이다 | 003 MKT-002·MKT-008, 013 MKT-009, 027 MKT-010(이슈 #242, `1m` 코인만 캐싱 경로 추가), 038, Issue #17, Issue #20, Issue #143, Issue #384 |

- **`interval` 4값**: `1m`(1분봉)·`1d`(일봉)·`1w`(주봉)·`1M`(월봉). **대소문자를 구분한다** — `1M`은 월봉, `1m`은 분봉이며 서버는 정규화하지 않는다. `interval` 검증은 `instrumentId` 존재 조회보다 **먼저** 수행한다 — 잘못된 `interval`은 존재하지 않는 종목이어도 400이다.
- **주식과 코인의 출처가 다르다** — 응답 형식은 같지만 주식은 MySQL `stock_candles`의 **과거 거래일 재생** 1분봉(`1d`·`1w`·`1M`은 그 1분봉을 서버가 직접 집계), 코인은 빗썸 공개 캔들 REST의 **지금 이 순간까지의 실시간** 봉(모든 `interval`에서 위임, 서버 집계 없음)이다. 소비자는 `market`에 따라 파싱을 나누지 않지만, **미마감 분봉/미완성 버킷 규칙은 시장별로 다르다**(바로 아래 두 절 참조).
- 검증 순서는 `CandleQueryService.getCandles`가 `interval` → `instrumentId` 존재(404) → 시장 종류 → `from`/`to`(시장·`interval`별 판정 기준 상이 — 코인은 날짜 포함 전체 시각, 주식 `1m`은 시각만, 주식 `1d`·`1w`·`1M`은 날짜만) 순으로 판정한 뒤 주식은 `StockPriceProvider.getCandles`, 코인은 `CryptoCandleProvider.getCandles`에 위임한다(컨트롤러에 try-catch 없음).
- **코인 `instrumentId`는 더 이상 400이 아니다** (Issue #20). Issue #17에서 "주식 종목만 캔들 조회를 지원합니다" 400 `VALIDATION_ERROR`로 거부했던 계약이 제거됐다 — 프론트에서 이 400을 분기 처리하던 코드가 있으면 함께 정리한다.
- `volume`은 코인의 소수 수량(예: `0.26725783`)을 표현하기 위해 `BigDecimal`이다. 주식 값의 표현(`12345`, `1d`·`1w`·`1M` 집계도 정수 합계)은 바뀌지 않는다.
- `from`·`to`를 모두 생략하면 공개 상한까지의 최신 200개를 반환한다 — 주식 `1m`은 재생 중인 거래일 전체(그 중 이미 공개된 분봉), 주식 `1d`·`1w`·`1M`은 공개 상한까지의 최신 200개 버킷, 코인은 진행 중인 분봉/봉을 포함해 최신부터 과거로 200개를 반환한다.
- **주식 집계(`1d`·`1w`·`1M`) 규칙 요약** — 원천은 `stock_candles` 1분봉뿐이고 집계 결과는 저장하지 않는다. 버킷 경계는 `1d`=거래일(`trading_date`) 하나, `1w`=그 거래일이 속한 주(**월요일 시작**, ISO-8601·KST), `1M`=그 거래일이 속한 달력월(**1일 시작**, KST). `sourceTime`은 버킷 시작일 `T00:00:00`(월요일·1일이 실제 거래일이 아니어도 라벨로만 쓴다). OHLCV는 버킷 안 분봉 중 최초 open·최대 high·최소 low·최종 close·합계 volume이다. **공개 상한(reveal bound)**: 오늘 `READY` 재생세션이 없으면 어떤 `interval`이든 200 `[]`(1분봉과 동일 계약), 있으면 그 세션의 재생거래일보다 이전 거래일은 전량 집계, 재생거래일 당일은 1분봉과 같은 공개 컷오프(09:01 이전 0개, 09:01부터 컷오프까지)까지만 집계, 재생거래일 이후 거래일은 방어적으로 제외한다. **미완성 버킷은 응답에 포함된다**(진행 중인 거래일·주·월도 이미 공개된 분봉만으로 계산해 노출, 공개된 분봉이 0개인 버킷만 제외). **거래일이 없는 주·월은 응답에 없다**(빈 봉으로 채우지 않음, 반환된 봉 사이가 달력상 연속임을 보장하지 않음). **`from`·`to` 해석이 `1m`과 다르다** — `1d`·`1w`·`1M`은 `from`·`to`의 **날짜 성분만** 쓰고(시각 성분 무시) 버킷 시작일이 `[from의 날짜, to의 날짜]`(양끝 포함) 안에 있으면 포함한다. `1m`은 기존대로 날짜 성분을 무시하고 시각 성분만 쓴다 — 두 규칙은 의도적으로 다르다.
- **코인 위임(`1d`·`1w`·`1M`) 규칙 요약** — `interval`에 따라 빗썸 엔드포인트가 `/v1/candles/days`·`/v1/candles/weeks`·`/v1/candles/months`(`1m`은 기존 `/v1/candles/minutes/1`)로 분기될 뿐, 저장·캐시(MySQL·Redis 어느 쪽에도)는 하지 않는다는 `1m` 원칙이 그대로 적용된다. 필드 매핑도 동일하다 — `trade_price`→`close`(이름과 달리 현재가가 아니다), `candle_acc_trade_volume`→`volume`(거래대금 `candle_acc_trade_price`와 다르다). **버킷 경계는 빗썸이 준 값을 그대로 쓴다** — 서버는 빗썸 봉을 다시 자르거나 묶지 않고 `candle_date_time_kst`를 `sourceTime`으로 그대로 매핑한다. 일·주·월봉 전용 필드(`prev_closing_price`·`change_price`·`change_rate`·`first_day_of_period` 등)는 무시하고 원본 응답을 그대로 내려주지 않는다.
- **경계 일치 관측(외부 스모크, 2026-08-03 KST, `docs/specs/013-candle-interval/run-log.md` "외부 스모크" 참조)** — 빗썸 공개 캔들 REST를 직접 호출해 확인한 결과, 일봉 `candle_date_time_kst`는 KST 자정·그날 날짜, 주봉은 KST 자정·**월요일**(2026-08-03 실측 기준 검증), 월봉은 KST 자정·**매월 1일**이었다. 즉 **관측 시점 기준으로 주식(우리가 KST 거래일로 자르는 경계)과 코인(빗썸이 준 경계)의 버킷 경계가 사실상 일치**한다(KST 자정·월요일 시작·1일 시작). 이것은 빗썸 쪽 사양을 관측한 결과일 뿐이며, 서버 코드는 이 일치를 전제로 코인 경로를 보정하지 않는다 — 빗썸이 경계를 바꾸면 주식·코인 버킷 경계는 다시 벌어질 수 있다.

#### 코인 캔들 전용 규칙 (MKT-008, 013)

> 아래 규칙은 원래 `1m` 계약이며 `1d`·`1w`·`1M`(013)에도 그대로 적용됐다 — 코인은 4개 `interval` 모두 저장·집계 없이 빗썸에 위임하는 같은 구조였다. **(2026-08-06 MKT-010, `027-crypto-tick-candle-cache`) `1m`만 이 구조에서 갈라졌다** — 서버가 빗썸 체결 스트림으로 직접 만든 최근 진행 중 분봉을 Redis에 캐싱하고, 캐시에 없는 구간만 아래 REST 위임으로 보충한다. `1d`·`1w`·`1M`은 이 절 그대로이며 위 "코인 위임(`1d`·`1w`·`1M`) 규칙 요약"을 참조한다.

- `1d`·`1w`·`1M`의 데이터 출처는 빗썸 공개 캔들 REST API다 — 인증·API Key가 필요 없고, 응답을 MySQL·Redis에 저장하지 않는다. **`1m`은 다르다** — 서버가 만든 진행 중 분봉을 Redis에 캐싱하고, 캐시에 없는 구간만 이 REST를 호출한다(MKT-010). 코인 캔들 전용 **MySQL** 테이블은 `1m`도 여전히 만들지 않는다 — 캐싱은 Redis에만 한다.
- **실시간 차트다 — 과거 데이터 재생이 아니다.** 주식의 재생 분봉은 옛 거래일을 오늘 다시 트는 것이지만, 코인 분봉은 지금 이 순간까지의 실제 시장이다(11:43:06에 조회하면 `11:43`·`11:42`·`11:41` 봉이 온다). 현재가 API의 WebSocket 틱과 **같은 지금의 시장을 다른 해상도로 본 것**이다.
- **한 요청의 최대 개수는 200개**(빗썸 API 상한)다. `from`·`to` 범위가 200분을 넘으면 `to` 기준 최신 200개만 반환한다. 200개를 넘는 구간을 이어붙이는 페이징은 1차 범위가 아니다.
- **진행 중인(아직 마감하지 않은) 분봉을 포함한다** — 그게 지금의 실시간 시세다. **주식은 미마감 봉을 제외하는데 코인은 포함하며, 이 차이는 의도된 것이다** (주식만 과거 거래일 재생이기 때문). 같은 요청을 다시 보내면 그 마지막 봉의 고가·저가·종가·거래량이 자란 값으로 온다 — 정상 동작이다.
- **코인은 전용 스트림이 없다.** 분 이하 해상도로 차트를 부드럽게 갱신하려면 프론트가 이 API를 짧은 주기로 다시 호출한다 — 진행 중 분봉이 매 호출마다 최신 값으로 갱신되므로 그것으로 충분하다.
- 코인 응답의 `sourceTradingDate` 개념은 없다 — 과거 데이터 재생이 아니라 실제 현재 시각의 분봉이다.
- **빗썸 조회 실패는 502 `MARKET_DATA_PROVIDER_ERROR`다.** 빈 배열 200으로 성공을 위장하거나 이전 값으로 대체하지 않는다. 주식의 200 `[]`("아직 공개할 분봉이 없다"는 정상 상태)와 성격이 다르다. **(MKT-010, `1m`만)** 요청 구간이 Redis 캐시로 전부 커버되면 빗썸을 호출하지 않으므로, 이 시점의 빗썸 장애는 그 요청에 영향을 주지 않고 200이 그대로 나간다 — 캐시 밖 구간이 필요할 때만 이 502 경로를 탄다. Redis 장애 시에는 반대로 기존처럼 빗썸으로 전량 위임한다(단일 장애점 방지).
- 코인 캔들 조회는 `PriceStore`(Redis)·`PriceQueryService`를 거치지 않는다 — **현재가 경로와 완전히 독립이다.** 빗썸 WebSocket이 끊겨 현재가가 409 `PRICE_UNAVAILABLE`인 상태에서도 캔들 조회는 200일 수 있고, 반대로 캔들이 502인 상태에서도 현재가·주문은 정상이다.
- 차트 분봉과 현재가(최신 틱)는 서로 다른 엔드포인트에서 오므로 값이 밀리초 단위로 정확히 일치하지 않을 수 있다. 서버가 둘을 보정하지 않는다.
- **`from`·`to`의 날짜 성분이 실제로 쓰인다** — 아래 주식 규칙의 "날짜 성분 무시"는 코인에 적용되지 않는다. 주식은 재생 중인 단일 거래일 안에서만 조회되지만, 코인은 실제 달력 시각의 분봉이므로 날짜까지 그대로 빗썸 `to`로 전달한다.
- **`to`는 항상 포함(inclusive)이다** — `to`와 정확히 같은 시각에 시작하는 봉도 응답에 포함된다(`1m`·`1d`·`1w`·`1M` 공통, 이슈 #157). 클라이언트는 경계 보정 없이 "오늘 날짜"·"이번 주 월요일" 등을 그대로 `to`로 보내면 된다.
  - **내부 구현 메모**: 빗썸 공개 캔들 REST의 `to`는 **UTC가 아니라 `candle_date_time_kst`와 그대로 비교되는 KST 값**이며(PR #164 리뷰에서 실제 빗썸 API로 재현 확인 — 문서상 UTC로 오인해 KST→UTC 변환을 하면 9시간 밀린 엉뚱한 구간이 반환된다), 그 정확한 경계 시각을 배제(exclusive)한다(2026-08-03 외부 스모크로 실측 확인, `docs/specs/013-candle-interval/run-log.md` "`to` 파라미터의 경계 포함 여부" 참조 — `to`로 오늘 날짜(자정 KST)를 넘기면 그날의 봉이 빠지는 문제가 실측됐다). `BithumbRestCandleProvider.resolveToParam`은 변환 없이 원본 KST 값에 **1초만 더해** 빗썸에 전달함으로써 이 배제 동작을 흡수한다. 빗썸의 최소 봉 간격(1분)보다 훨씬 작은 보정값이라 다음 봉을 끌어오지 않으며, `interval`별 분기 없이 네 값 모두 동일하게 동작한다(2026-08-03 외부 스모크로 1m·1d·1w·1M 모두 재검증, `run-log.md` 참조).

#### 주식 캔들 전용 규칙 (MKT-002, 013)

> 아래 규칙은 `interval=1m`(원분봉) 전용이며 013에서 값·정렬·미마감 규칙·`from`/`to` 해석·빈 배열 조건 모두 회귀 없이 그대로 유지된다. `1d`·`1w`·`1M`(거래일·주·월 집계)의 버킷 경계·공개 상한·미완성 버킷·`from`/`to` 해석은 위 "주식 집계(`1d`·`1w`·`1M`) 규칙 요약"을 참조한다 — 그 규칙은 여기의 `1m` 공개 컷오프를 재사용하지만 날짜 단위로 묶은 결과라는 점에서 다르다.

- 아직 마감하지 않은 분봉은 어떤 경우에도 응답에서 제외된다(리뷰 확정, PR #87) — 09:00~09:00:59(첫 분봉 구간)에는 그 첫 분봉조차 아직 마감 전이므로 **빈 배열**을 반환한다. 09:01부터는 마감이 완료된 마지막 분봉까지, 15:30 마감 후에는 그날 공개된 분봉 전체가 후보가 된다. 같은 첫 분봉 구간에서도 가격 API(`GET .../price`)는 그 첫 분봉의 시가를 예외적으로 현재가로 노출한다는 점에서 캔들 API와 계약이 다르다 — 혼동하지 않는다.
- 재생세션이 `READY`가 아니거나(`PREPARING`·`FAILED`) 그 서비스 날짜의 세션 자체가 없으면 예외를 던지지 않고 200을 반환한다 — 가격 API가 이 경우 409 `PRICE_UNAVAILABLE`을 반환하는 것과 다른 계약이다. **`marketStatus=OPEN`이면 이 200은 빈 배열이지만, `CLOSED`이면 빈 배열 대신 아래 장외 유지 규칙이 적용된다.**
- **장외 시세 유지 — `marketStatus=CLOSED`(038, QUOTE-HOLD-002~004·006, 이슈 #384)**: 오늘 재생세션 기준으로 반환할 분봉이 없을 때(오늘 세션이 없거나·`PREPARING`/`FAILED`이거나·있어도 공개 분봉이 아직 0건일 때), 예전에는 빈 배열이었지만 이제는 **서비스 날짜가 오늘보다 이전인 마지막 `READY` 재생세션**의 원본 거래일 **하루치 분봉 전체**를 반환한다(`1d`·`1w`·`1M` 집계 경로는 그 거래일까지 공개 컷오프 없이 전량 집계). 오늘 세션 자체는 폴백 대상이 아니다 — 08:40~09:00처럼 오늘 세션이 이미 `READY`여도 그날 오후는 아직 재생 전이므로, 하루치를 새어 나가게 하지 않고 직전 서비스 날짜의 하루치를 대신 보여준다(QUOTE-HOLD-003). 폴백 세션 탐색에는 날짜 상한이 없다(QUOTE-HOLD-006). **`marketStatus=OPEN`에서 특정 종목만 분봉이 없는 경우는 이 규칙의 대상이 아니다** — 그 상태는 수집 장애(#370)이며 여전히 빈 배열이다(QUOTE-HOLD-004, 회귀 없음). 폴백 대상 거래일에도 분봉이 하나도 없으면(보관 정리 등) 폴백은 성립하지 않고 지금처럼 빈 배열이다.
- `from`·`to`로 범위를 좁히면 그 범위와 공개 컷오프의 교집합만 반환한다(둘 중 더 이른 시각이 상한이 된다). 장외 유지로 반환되는 하루치도 요청이 준 `from`·`to` 시간 범위를 그대로 적용한다.
- **`from`·`to`의 날짜 성분은 무시되고 시각(시:분:초)만 사용된다**(리뷰 확정, PR #87 QA FAIL 옵션 c) — 결과는 항상 현재 재생 중인 단일 거래일(`source_trading_date`) 범위 안에서만 반환되므로, 요청에 다른 날짜를 넣어도 그 날짜는 조회에 반영되지 않는다. `from > to` 판정도 날짜가 아니라 시각만으로 비교한다 — 예를 들어 `from=2026-07-23T09:00:00`·`to=2026-07-22T09:01:00`처럼 날짜만 보면 역전돼 보여도 시각(09:00 ≤ 09:01)이 유효하면 통과하고, 반대로 `from=2026-07-22T09:01:00`·`to=2026-07-23T09:00:00`처럼 날짜는 정상 순서여도 시각(09:01 > 09:00)이 역전되어 있으면 400 `VALIDATION_ERROR`다.

### 주식 SSE 스트림

| Method | URL | 인증 | 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/stocks/stream | Access Bearer 필수(fetch + `Authorization: Bearer <accessToken>` 헤더, 브라우저 기본 `EventSource` 미사용) | `Content-Type: text/event-stream`. `retry: 3000` 1회 → `snapshot`(주식 16종 전체, id 없음) → 이후 `price`(변경된 종목만, id 있음)·`status`(개장·마감 전환 1회, id 없음) | Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식(응답 본문, 스트림 시작 전) | 003 MKT-002, 038, Issue #19, Issue #384 |

- `StockPriceSseController.stream()`이 `SseEmitterRegistry.register(Market.STOCK)`로 emitter를 얻은 뒤(그 자리에서 `retry: 3000` 1회 전송, 이슈 #18) `StockPriceStreamService.sendSnapshot(emitter)`로 그 emitter에만 snapshot을 전송하고 반환한다 — 이후 push는 `StockPriceStreamService`가 매분(정각, `Asia/Seoul`) 스케줄로 담당한다.
- **snapshot**: `{"market":"STOCK","sourceTradingDate":"2026-07-22","marketStatus":"OPEN","emittedAt":"2026-07-25T09:01:00","prices":[{"symbol":"005930","price":71200,"sourceTime":"2026-07-22T09:00:00","status":"AVAILABLE"}, ...]}` — 주식 16종 전체를 배열 1건에 담는다. 가격이 없는 종목도 배열에서 빠지지 않고 `price`·`sourceTime`은 `null`, `status`는 `UNAVAILABLE`이다. id 없음.
- **price**: `id: STOCK:005930:202607220900` + `{"market":"STOCK","symbol":"005930","price":71200,"sourceTime":"2026-07-22T09:00:00","emittedAt":"2026-07-25T09:01:00","sourceTradingDate":"2026-07-22","marketStatus":"OPEN"}` — 매분 스케줄에서 해당 종목의 `sourceTime`이 직전 값과 달라진(새로 공개된) 경우에만 전송한다. id는 `STOCK:{symbol}:{sourceTime을 yyyyMMddHHmm으로 포맷}`(`sourceTime`이 09:00:00이므로 id는 emittedAt의 분(09:01)이 아니라 0900으로 끝난다).
- **status**: `{"market":"STOCK","marketStatus":"CLOSED","emittedAt":"2026-07-25T15:30:00"}` — `symbol`·`status`·`reason`은 시장 전체 상태 변화라 없음(생략). 직전 스케줄 실행의 `marketStatus`와 달라진 경우에만 1회 전송한다(서버 기동 시점 값을 기준선으로 삼아 기동 직후 오탐 전송하지 않는다).
- 장 마감(`marketStatus=CLOSED`) 후에도 마지막으로 공개됐던 종목의 `price`·`sourceTime`은 그대로 유지되고 `status`는 `AVAILABLE`을 유지한다 — snapshot·이미 전송된 price 이벤트 모두 동일하게 마지막 값을 유지하며 별도로 `UNAVAILABLE`로 되돌리지 않는다. **이 유지는 같은 날 장 마감 이후로 한정되지 않는다 — 자정을 넘겨도, 주말·공휴일이 껴도 계속된다**(038, QUOTE-HOLD-001·007, 이슈 #384). 값이 `StockReplayService`의 장외 폴백(위 "종목 현재가 조회"·"주식 캔들 전용 규칙" 절 참조)으로 유지되는 동안은 매분 스케줄이 돌아도 그 종목의 `sourceTime`이 바뀌지 않으므로 `price` 이벤트가 추가로 전송되지 않는다 — 값이 멈춰 있으면 이벤트도 멈춘다(QUOTE-HOLD-007). 이 시점에 새로 구독한 클라이언트는 `snapshot`으로 그 멈춘 값을 그대로 받는다. **금요일 23:59 → 토요일 00:00처럼 세션이 전환되는 순간에도 값이 바뀌지 않는다** — 폴백이 찾는 "오늘 이전 마지막 `READY` 세션"이 전날과 같은 세션이라 가격·`sourceTime`·`sourceTradingDate`가 전부 동일하게 이어진다.
- 재접속하면 새 emitter로 `snapshot` 1건을 다시 받는다. 연결이 끊긴 동안 놓친 이벤트를 서버가 재전송하는 기능은 없다(MVP 제외).
- heartbeat(20초 간격 SSE 주석)·`retry` 힌트·`onCompletion`/`onTimeout`/`onError` 시 emitter 정리는 `SseEmitterRegistry`(이슈 #18)가 공통 처리하며 이 컨트롤러에서 재구현하지 않는다.

### 로컬 KIS 실수집 트리거 (local 프로필 전용)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/dev/stock-replay-imports | Access Bearer 필수 | 본문 없음 | 200 `{"serviceDate":"2026-07-30","tradingDate":"2026-07-29","collectedKisCandleCount":5630,"preparationStatus":"READY","failureReason":null,"marketStatus":"OPEN"}` (`StockReplayImportTriggerResponse`) | `local` 프로필이 아니면 404. Access 인증 실패는 401 `UNAUTHORIZED` | 003 MKT-005 (개발 도구) |

**왜 있나.** 실제 KIS 분봉은 평일 08:10 배치가 수집하고 08:40 배치가 재생세션을 확정한다. 그 시각을 기다리지 않고, 또는 앱이 그 시각에 꺼져 있어 건너뛴 날에 즉시 실데이터를 채우기 위한 트리거다. `KisHistoricalCandleCollector.collect()`와 `StockReplaySessionScheduler.resolveTodaySession()`을 순서대로 한 번 실행한다.

**무엇을 하나.** ① 실제 수집을 실행한다(종목·거래일 단위로 이미 분봉이 있으면 건너뛴다 — 멱등). ② 재생세션을 확정한다. ③ 결과(수집 건수·세션 상태·시장상태)를 돌려준다.

- **`collectedKisCandleCount`는 그 거래일에 저장된 KIS 분봉의 총량이다** — 이번 호출로 새로 넣은 건수가 아니다. 수집은 종목·거래일 단위로 멱등 스킵하므로, 이미 채워진 날에 다시 호출하면 아무것도 새로 넣지 않고도 같은 총량(예: 5,630)이 그대로 나온다. **정상이다.**
- **0이면 그날 재생할 원본이 없다.** `preparationStatus`가 `READY`여도 마찬가지다 — 세션은 이전 호출에서 이미 확정돼 있을 수 있고 세션 상태만으로는 분봉 유무를 알 수 없다. 즉 **`READY` + `0`은 정상이 아니라 "세션은 섰는데 재생할 데이터가 없는" 깨진 상태**이며, 이 조합이면 그날 시세·차트·주문이 전부 막힌다. 판정은 세션 상태가 아니라 **이 건수로 한다.**
- 0이 나왔을 때 원인은 서버 로그의 `KIS 과거 분봉 조회가 종목 단위로 실패했습니다` WARN에 붙은 KIS 오류 코드로 판별한다. 실측한 원인 세 가지 — `EGW02004`(앱키 환경과 `kis.base-url` 도메인 불일치), `EGW00133`(토큰 발급 1분당 1회 제한), `EGW00201`(초당 거래건수 초과).
- **트랜잭션을 열지 않는다.** `collect()`가 16종목 순차 HTTP 호출 동안 DB 커넥션을 점유하지 않도록 설계된 것을 깨지 않기 위해, DB 작업만 `StockReplayImportTriggerWriter`의 별도 트랜잭션 메서드로 분리했다.
- 실측 소요는 **약 1분 20초**(16종목, `kis.request-interval-ms=600` + 재시도 포함). 08:10~08:40의 30분 여유 안에 충분히 들어간다.

**KIS 앱키 환경과 도메인은 반드시 짝이 맞아야 한다.** 모의투자 앱키로 실전 도메인을 호출하면 토큰 발급까지는 성공하지만 업무 API가 `EGW02004`로 거부한다 — **토큰 발급 성공을 앱키 종류의 근거로 삼으면 안 된다**(2026-07-30 실측).

| 앱키 종류 | `kis.base-url` |
|---|---|
| 모의투자 | `https://openapivts.koreainvestment.com:29443` |
| 실전투자 | `https://openapi.koreainvestment.com:9443` |

**2026-07-30 실측 결과.** 19:50 KST(장외)에 시드 → `marketStatus: OPEN`, 삼성전자 현재가 `70900.0000`(`sourceTime` `2026-07-29T15:30:00`), 캔들 391건(09:00~15:30), 16종목·6,256건 삽입. 이어서 10주 매수(`amount` 709,000 / `fee` 106) → 4주 매도(`realizedPnl` -84) → `cashBalance` 9,574,452로 정산까지 확인했다. 시드 전에는 현재가 409 `PRICE_UNAVAILABLE`·캔들 `200 []`·주문 409 `MARKET_CLOSED`였다.
