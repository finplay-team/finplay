# Plan: 종목과 시세

## 관련 문서
- Spec: `./spec.md`
- 관련 ADR: ADR-0002 (도메인 패키지), ADR-0003 (테스트 전략), ADR-0004 (Flyway)
- PRD: §1 C-006(데이터 이용 정책)·C-007(시세 공급자와 공개 표출 정책), §4 MKT-007·MKT-008(코인 차트), §5 종목·시세 API, §6 데이터 모델·Redis 키 책임, §10 Decision Gate(빗썸 레이트리밋)
- 선행: `001-foundation` (Redis·Clock), `002-auth-account` (인증 — 조회 API·SSE는 인증 필요)

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/instruments?market= | 쿼리 market(선택) | `InstrumentResponse[]` | 종목 목록 |
| GET | /api/instruments/{instrumentId} | - | `InstrumentResponse` | 종목 단건 |
| GET | /api/instruments/{instrumentId}/price | - | `PriceResponse` (price, sourceTime, status, sourceTradingDate) | 최신 가격. 없으면 409 PRICE_UNAVAILABLE — 이슈 #16 |
| GET | /api/instruments/{instrumentId}/candles?interval=1m&from=&to= | 쿼리 | `CandleResponse[]` | 주식 1분봉 (`stock_candles` 기반, 공개된 분봉까지만) — 이슈 #17 / 코인 1분봉 (빗썸 공개 캔들 REST 실시간 조회, 진행 중 분봉 포함) — 이슈 #20 |
| GET | /api/stocks/stream | Header: `Authorization: Bearer <accessToken>` | SSE | 주식 전용 스트림 — 이슈 #19 |

- **코인은 전용 스트림을 두지 않는다 (2026-07-30 방향 변경).** 코인의 실시간 표출은 캔들 API(위 행, 이슈 #20)의 REST 조회로 충분하다 — 진행 중 분봉을 포함해 반환하므로 프론트가 짧은 주기로 다시 호출하는 것만으로 화면이 갱신된다. `/api/cryptos/stream` 엔드포인트는 만들지 않는다.
- **이슈 분할**: 이슈 #18은 `/stocks/stream`이 쓸 공통 골격(`SseEmitterRegistry`·SSE 이벤트 DTO 3종·`PriceQueryService`의 예외 없는 조회 경로·`@EnableScheduling`/`request-timeout` 설정)만 다루며 컨트롤러 엔드포인트를 포함하지 않는다 — `./gradlew build`는 컨트롤러 없이도 통과해야 한다. `/api/stocks/stream`은 이슈 #19에서 이 골격 위에 구현한다. `SseEmitterRegistry`·이벤트 DTO는 market을 매개변수로 받는 범용 설계라 코인용 컨트롤러를 만들지 않아도 코드를 되돌릴 필요는 없다.

### 코인 캔들 설계 (MKT-008, 이슈 #20)

코인 1분봉은 **빗썸 공개 캔들 REST API**를 요청 시점에 호출해 중계한다. 저장하지 않으므로 새 테이블·새 Redis 키·새 마이그레이션이 없다.

**이것은 실시간 차트다 — 과거 데이터 재생이 아니다.** 주식의 `KIS_HISTORICAL`은 옛 거래일을 오늘 다시 트는 방식이지만, 빗썸 REST 캔들은 지금 이 순간까지의 실제 시장을 돌려준다(실측: 11:43:06에 조회 → `11:43`·`11:42`·`11:41` 봉). WebSocket 틱과 REST 분봉은 **같은 지금의 시장을 다른 해상도로 본 것**이며, REST를 쓰는 이유는 WebSocket이 직전 봉들을 주지 않아 기동 직후 차트가 비기 때문이다.

- **외부 엔드포인트**: `GET https://api.bithumb.com/v1/candles/minutes/{unit}?market={market}&to={to}&count={count}` — 인증·API Key 불필요(공개), `count` 최대 200, 응답은 **최신→과거 내림차순**.
- **응답 필드 매핑** (빗썸 → 우리 `CandleResponse`):

  | 빗썸 필드 | 우리 필드 | 비고 |
  |---|---|---|
  | `candle_date_time_kst` | `sourceTime` | KST `LocalDateTime`. 주식과 같은 타입이라 프론트가 시장별로 파싱을 나누지 않는다 |
  | `opening_price` | `open` | |
  | `high_price` | `high` | |
  | `low_price` | `low` | |
  | `trade_price` | `close` | 빗썸은 종가를 `trade_price`로 부른다 — 이름에 속아 현재가로 해석하지 않는다 |
  | `candle_acc_trade_volume` | `volume` | **코인 수량**(소수). `candle_acc_trade_price`(거래대금)와 혼동하지 않는다 |

- **`volume` 타입 확대**: 코인 거래량은 `0.26725783`처럼 소수라 현재 `CandleResponse.volume`의 `long`으로는 0으로 잘린다. `BigDecimal`로 넓힌다 — 주식 값의 표현은 바뀌지 않으며, 주식 캔들 응답 회귀 테스트로 확인한다.
- **심볼 변환**: `Instrument.symbol`(`BTC`) → 빗썸 마켓 코드(`KRW-BTC`). MVP 코인 12종은 모두 KRW 마켓이므로 `"KRW-" + symbol` 규칙으로 충분하다. 별도 매핑 테이블을 만들지 않는다.
- **정렬 반전**: 빗썸 내림차순 응답을 시각 오름차순으로 뒤집어 반환한다 (주식 캔들과 동일한 계약).
- **진행 중 분봉 포함**: 빗썸이 돌려주는 가장 최신 봉은 아직 마감하지 않은 분봉이며, **그것을 그대로 포함해 반환한다** — 그게 지금의 실시간 시세다. 다시 조회하면 그 봉의 고가·저가·종가·거래량이 자란다(정상 동작이며, 캐시하지 않는 이유 중 하나).
  - **주식(MKT-002)은 미마감 봉을 제외하는데 코인은 포함한다 — 의도된 차이다.** 주식의 제외 규칙은 과거 거래일 재생 구조에서 아직 공개되지 않아야 할 분봉이 새는 것을 막고 체결가 계약과 어긋나지 않게 하기 위한 것이다. 코인은 재생이 아니라 실시간이고 주문도 최신 틱으로 체결되므로 가릴 대상이 없다. 잘라내면 차트 오른쪽 끝이 최대 59초 늦게 움직여 실시간성을 해친다. 구현 시 `StockReplayService`의 공개 컷오프 로직을 코인 경로에 재사용하지 않는다.
  - 분 이하 해상도의 실시간 갱신은 프론트가 이미 구독하는 SSE 틱(#20)으로 마지막 봉을 갱신해 얻는다. **서버는 틱을 분봉으로 집계하지 않고 진행 중 분봉 상태를 메모리에 들고 있지도 않는다** — 코인에는 `KisTickAggregator`에 해당하는 컴포넌트를 만들지 않는다(재시작 시 유실·메모리 관리 부담을 지지 않기 위함).
- **`from`·`to` → `to`+`count` 변환**: 빗썸은 `from`을 받지 않으므로 우리 쪽에서 변환한다.

  | 요청 | 빗썸 호출 |
  |---|---|
  | `from`·`to` 모두 생략 | `to` 생략(최신 기준) + `count=200` |
  | `to`만 | `to` 그대로 + `count=200` |
  | `from`만 | `to` 생략 + `count=min(200, now~from 분 수)` |
  | 둘 다 | `to` 그대로 + `count=min(200, from~to 분 수)` |

  범위가 200분을 넘으면 `to` 기준 최신 200개만 반환한다 — 이 상한은 계약에 명시하며 조용히 잘라내지 않는다. 여러 번 호출해 긴 구간을 이어붙이는 페이징은 범위 제외다.
- **장애 처리**: 타임아웃·비정상 상태코드·파싱 불가는 새 `ErrorCode.MARKET_DATA_PROVIDER_ERROR`(502)로 반환한다 — 기존 `OAUTH_PROVIDER_ERROR`(502)와 같은 "외부 공급자 오류" 패턴이다. **빈 배열 200으로 성공을 위장하거나 이전 값으로 대체하지 않는다.** 주식의 "재생세션 미준비 → 200 `[]`"와는 성격이 다르다 — 주식의 빈 배열은 "아직 공개할 분봉이 없다"는 정상 상태이고, 코인의 502는 "외부 조회가 실패했다"는 장애다.
- **타임아웃**: 연결·읽기 타임아웃을 설정으로 두고 짧게 잡는다(구체값은 구현 시 `application.yml`에 명시). 무한 대기로 SSE·요청 스레드를 붙잡지 않는다.
- **캐시 없음**: 요청마다 호출하고 응답을 캐시하지 않는다. 레이트리밋 차단이 실제로 관측되면 그때 짧은 TTL 캐시를 판단한다 (Decision Gate — PRD §10). 관측 전에 임의의 TTL 숫자를 넣지 않는다.
- **현재가와의 관계**: 코인 캔들은 `PriceQueryService`·`PriceStore`(Redis)를 거치지 않는다 — 두 경로는 완전히 독립이다. 따라서 빗썸 WebSocket이 끊겨 현재가가 `UNAVAILABLE`이어도 캔들 조회는 성공할 수 있고, 반대로 캔들 REST가 죽어도 현재가·주문은 정상이다. 이 독립성은 의도된 설계이며 테스트로 고정한다.

### SSE 계약 (MVP 확정 — 변경하려면 문서와 프론트·백엔드를 함께 수정)

아래 계약은 `/stocks/stream`(#19)에 적용된다. `retry`·heartbeat·emitter 정리는 이슈 #18에서 만드는 `SseEmitterRegistry`가 제공한다. **코인은 전용 SSE 엔드포인트가 없으므로 이 계약 대상이 아니다** — 코인 이벤트 필드 설명(`sourceTradingDate` 미포함 등)은 `SseEmitterRegistry`·이벤트 DTO가 market 매개변수를 받는 범용 설계임을 보여주는 참고용으로만 남겨둔다.

- **인증**: 브라우저 기본 `EventSource`는 커스텀 헤더를 지원하지 않으므로, 프론트는 `fetch()`로 스트림을 요청하며 `Authorization: Bearer <accessToken>` 헤더를 그대로 전달하고 응답 `ReadableStream`을 직접 파싱한다. Access Token을 URL 쿼리 파라미터에 넣지 않는다. 인증 실패는 401.
- **Content-Type**: `text/event-stream`.
- **이벤트 이름** (3종으로 확정):
  - `snapshot` — 구독 시작 직후 1회. 해당 MVP 시장의 **전체 종목**(주식 16종·코인 12종)을 배열 한 건으로 전송한다 (종목별로 여러 건 보내지 않는다). 가격이 없는 종목도 배열에서 빼지 않고 `price`·`sourceTime`을 `null`, `status`를 `UNAVAILABLE`로 포함한다.
  - `price` — 종목 하나의 가격이 변경될 때 전송 (주식: 매분 새로 공개된 가격, 코인: 빗썸 수신 틱마다).
  - `status` — 시장 개장·마감, 코인 stale·연결 끊김/복구, 주식 데이터 준비 실패 등 상태 변화.
- **시간 필드 분리**:
  - `sourceTime` — 원본 데이터의 실제 시각. 주식은 과거 원본 분봉의 실제 시각(재생 중인 과거 거래일 기준), 코인은 빗썸 틱의 실제 시각.
  - `emittedAt` — 우리 서버가 **지금** 이 SSE 이벤트를 전송한 실제 벽시계 시각(오늘 날짜).
  - `sourceTradingDate` — 주식에만 포함, 현재 재생 중인 실제 과거 거래일. 코인 이벤트에는 포함하지 않는다.
- **id**: `price` 이벤트에만 부여한다 — `{market}:{symbol}:{sourceTime 기반 식별자}` (예: `STOCK:005930:202607220901`). `snapshot`·`status`는 id를 넣지 않는다(`Last-Event-ID` 기반 누락 이벤트 재전송을 MVP에서 지원하지 않고, `snapshot`은 여러 종목을 포함해 단일 symbol이 없기 때문).
- **retry**: 연결 시작 시 `retry: 3000`(3초)을 1회 전송한다. 이 값은 서버가 보내는 힌트일 뿐 자동으로 처리되지 않는다 — `EventSource`와 달리 fetch 기반 클라이언트는 이 값을 자동으로 읽어 재연결하지 않으므로, **프론트 fetch 클라이언트가 이 값을 참고해 연결 실패 후 직접 재접속한다** (기본 3초 뒤). `SseEmitterRegistry.register(market)`가 emitter 생성 시점에 전송한다 (이슈 #18).
- **heartbeat**: 20초마다 SSE 주석(`:heartbeat\n\n`, Spring `SseEmitter.comment()`의 실제 출력은 콜론 뒤 공백 없음)을 전송해 프록시·브라우저 타임아웃으로 인한 연결 끊김을 방지한다. `SseEmitterRegistry`의 `@Scheduled` 작업이 등록된 모든 emitter에 공통으로 전송한다 (이슈 #18).
- **스케줄러 pool size 주의(PR #90 리뷰)**: heartbeat는 Boot 기본 pool size 1인 스케줄러에서 돈다. `SseEmitter.send()`는 블로킹 write라 멈춘 클라이언트 하나가 스레드를 붙잡으면 나머지 커넥션의 heartbeat가 밀린다. #19가 매분 price push를 같은 스케줄에 얹을 예정이므로, 착수 전에 `spring.task.scheduling.pool.size` 상향 또는 SSE 전용 `TaskScheduler` 분리 여부를 결정해야 한다.
- **연결 종료 처리**: `SseEmitter`의 `onCompletion`·`onTimeout`·`onError` 콜백에서 구독자 목록(market별 emitter 집합)에서 제거한다. `SseEmitterRegistry`가 등록 시점에 콜백을 배선한다 (이슈 #18).
- **재접속 정책**: 재접속하면 `snapshot` 1건을 먼저 받고 이후 새 이벤트만 받는다. 연결이 끊긴 동안 놓친 모든 이벤트를 서버가 다시 전송하는 기능은 MVP에서 제외한다. `Last-Event-ID` 기반 과거 이벤트 재생도 MVP 제외.
- **여러 탭 정책**: 탭마다 독립적인 SSE 연결을 허용한다. 별도의 탭 제한·중복 연결 차단 로직은 만들지 않는다.
- **원본 데이터·API Key 노출 금지**: SSE 페이로드에 원본 파일 내용이나 외부 API Key를 실어보내지 않는다 (PRD C-006).
- **`marketStatus`(시장 전체)와 종목별 `status`(개별 종목)는 의미가 다르다**:
  - `marketStatus`는 "이 시장에서 지금 주문이 가능한 시간대인가"를 뜻한다 — 주식은 `OPEN`·`CLOSED`, 코인은 24시간이므로 항상 `OPEN`.
  - 종목별 `status`는 "이 종목의 유효한 가격이 있는가"를 뜻한다 — `AVAILABLE`·`UNAVAILABLE` 두 가지만 사용한다.
  - 주식 장 마감(`marketStatus=CLOSED`) 후에도 마지막으로 공개된 유효가격이 있던 종목은 `status=AVAILABLE`을 유지하고 `price`·`sourceTime`은 마지막 값 그대로 둔다 — 다만 그 시간대의 주문은 `MARKET_CLOSED`로 거부된다(장 상태 문제이지 가격 문제가 아니다).
  - 코인은 최신 틱이 10초 이내이고 연결이 정상이면 `status=AVAILABLE`, stale이거나 연결이 끊기면 `status=UNAVAILABLE`이다 — 이때도 코인 시장 자체는 24시간이므로 `marketStatus`는 계속 `OPEN`으로 유지한다(코인 장애를 시장 폐장으로 표현하지 않는다). 이 경우 주문은 `PRICE_UNAVAILABLE`로 거부된다.
  - 상세 장애 사유(예: `STALE_PRICE`)는 종목별 `status`에 필드를 추가하지 않고 `status` 이벤트의 `reason`으로만 전달한다.
- **`snapshot` 이벤트 JSON 예시** (주식, 장중 — 일부만 발췌):

```
event: snapshot
data:
{
  "market": "STOCK",
  "sourceTradingDate": "2026-07-22",
  "marketStatus": "OPEN",
  "emittedAt": "2026-07-25T09:01:00+09:00",
  "prices": [
    {
      "symbol": "005930",
      "price": "71200",
      "sourceTime": "2026-07-22T09:00:00+09:00",
      "status": "AVAILABLE"
    }
  ]
}
```

- **`snapshot` 이벤트 JSON 예시** (주식, 장 마감 후 — 마지막 유효가격 유지 + 데이터 없는 종목 포함):

```
event: snapshot
data:
{
  "market": "STOCK",
  "sourceTradingDate": "2026-07-22",
  "marketStatus": "CLOSED",
  "emittedAt": "2026-07-25T16:00:00+09:00",
  "prices": [
    {
      "symbol": "005930",
      "price": "71200",
      "sourceTime": "2026-07-22T15:30:00+09:00",
      "status": "AVAILABLE"
    },
    {
      "symbol": "000660",
      "price": null,
      "sourceTime": null,
      "status": "UNAVAILABLE"
    }
  ]
}
```

- **`price` 이벤트 JSON 예시** (주식):

```
event: price
id: STOCK:005930:202607220901
data:
{
  "market": "STOCK",
  "symbol": "005930",
  "price": "71200",
  "sourceTime": "2026-07-22T09:00:00+09:00",
  "emittedAt": "2026-07-25T09:01:00+09:00",
  "sourceTradingDate": "2026-07-22",
  "marketStatus": "OPEN"
}
```

- **`price` 이벤트 JSON 예시** (코인, `sourceTradingDate` 없음):

```
event: price
id: CRYPTO:BTC:1753145460000
data:
{
  "market": "CRYPTO",
  "symbol": "BTC",
  "price": "142300000",
  "sourceTime": "2026-07-25T09:31:00+09:00",
  "emittedAt": "2026-07-25T09:31:00+09:00",
  "marketStatus": "OPEN"
}
```

- **`status` 이벤트 JSON 예시** (코인 장애):

```
event: status
data:
{
  "market": "CRYPTO",
  "symbol": "BTC",
  "marketStatus": "OPEN",
  "status": "UNAVAILABLE",
  "reason": "STALE_PRICE",
  "emittedAt": "2026-07-25T09:31:11+09:00"
}
```

- 위 이벤트 이름·필드는 MVP 계약으로 확정한다. 변경하려면 이 문서와 프론트·백엔드 구현을 함께 수정한다.

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| market (쿼리) | 선택 | STOCK·CRYPTO만. 그 외 400 VALIDATION_ERROR |
| instrumentId | 필수 | 미존재 시 404 NOT_FOUND |
| interval | 필수(candles) | 1차는 `1m`만. 그 외 400 VALIDATION_ERROR (주식·코인 공통 — 빗썸이 3·5·10분봉을 지원하더라도 계약을 넓히지 않는다) |
| from·to | 선택 | ISO-8601. from > to면 400 VALIDATION_ERROR. 코인은 범위가 200분을 넘으면 `to` 기준 최신 200개로 제한 |

## 구성 요소 설계

### market 패키지 (`com.finplay.api.market`)

| 구성 요소 | 역할 |
|---|---|
| `Instrument` 엔티티 + Repository | 종목 정본 (MySQL) |
| `StockCandle` 엔티티 + Repository | 주식 1분봉 정본 (MySQL). `UNIQUE(instrument_id, trading_date, candle_time)` |
| `StockReplaySession` 엔티티 + Repository | "오늘 어떤 원본 거래일을 재생 중인가"의 정본. `service_date`(오늘, UNIQUE)·`source_trading_date`·`preparation_status`(PREPARING·READY·FAILED)·`resolved_at`(결과가 결정된 시각 — READY·FAILED 공통, "성공 시각"이라는 오해를 피하기 위해 `prepared_at` 대신 이 이름을 쓴다)·`failure_reason`. `OPEN`·`CLOSED`는 이 엔티티에 저장하지 않는다. 상태별 필드 nullable 규칙은 아래 "재생세션 nullable 규칙" 참조 |
| `InstrumentController` / `InstrumentService` | 목록·단건·가격·캔들 조회 |
| `KisHistoricalCandleCollector` | KIS Open API로 과거 1분봉을 조회해 명백한 오류만 검증 → 허용 16종 추출 → 정규화 → `StockCandle` 저장 + `MarketDataImport` 이력 기록 (이슈 #17 범위 아님 — 별도 후속 작업, 아직 GitHub 이슈 번호 미배정). **`StockReplaySession`을 직접 생성·수정하지 않는다.** 전체 응답 오류면 `StockCandle`을 부분 저장하지 않고 `MarketDataImport`에 FAILED 기록. 특정 종목만 명백한 구조 오류면 그 종목의 행만 거래일 단위로 저장하지 않고 나머지는 저장, `MarketDataImport`에 PARTIAL_SUCCESS와 문제 종목·원인 기록. `UNIQUE(instrument_id, trading_date, candle_time)` 기반 기본 재실행 멱등성만 이 항목의 범위이며, 동일 거래일 재수집 판정(아래 "동일 거래일 재수집 정책" 참조)은 이슈 #83에서 진행한다. production 응답 포맷 파싱은 KIS 상품 세부사항 확인 대기 상태 — 샘플 데이터 기반 골격만 우선 구현 |
| `MarketDataImport` 엔티티 + Repository | 수집 시도 이력(SUCCESS·PARTIAL_SUCCESS·FAILED·SKIPPED_DUPLICATE, 실패사유, 수집시각). 저장에 실패해 `StockCandle` 행이 없는 경우도 기록 (이슈 #17) |
| `StockReplaySessionScheduler` | `MarketDataImport`·`StockCandle`의 수집 결과를 확인해 오늘의 `StockReplaySession`을 생성하고 `source_trading_date`를 고정한다. `preparation_status`를 `PREPARING`→`READY` 또는 `PREPARING`→`FAILED`로만 전환한다. **`StockCandle`을 직접 저장하지 않는다.** 정확한 production 실행시각(장 시작 전 언제 실행할지)은 KIS 데이터 제공시각 확인 후 확정하는 Decision Gate — 테스트에서는 Clock으로 실행시점을 제어한다 (이슈 #17 범위 아님 — 별도 후속 작업, 아직 GitHub 이슈 번호 미배정) |
| `StockCandleCleanupJob` | 20영업일 초과 분봉 삭제 배치. 재생 중인 거래일(`StockReplaySession.source_trading_date`)은 제외 (**이슈 #83 — 장기운영 방어 로직, MVP 범위 아님**) |
| `StockReplayService` | `StockReplaySession`을 읽어 오늘의 원본 거래일·준비상태를 확인하고, Clock과 조합해 `OPEN`·`CLOSED`를 계산한다 (`preparation_status != READY`면 이용 불가, `READY`면 Clock 기준 09:00~15:30 KST·영업일 여부로 OPEN·CLOSED 계산). 09:00~09:00:59는 첫 분봉의 시가, 09:01부터는 마감된 마지막 분봉의 종가를 현재가로 제공한다. 매분 스케줄(`@Scheduled`)로 새로 공개된 가격을 SSE로 push한다. **수집이나 재생 대상 날짜 선택을 하지 않고, `StockReplaySession`의 DB 상태를 변경하지 않는다** (읽기 전용) |
| `BithumbFeedClient` | 빗썸 WebSocket 수신 → `PriceStore` 저장. 재연결 처리. 인터페이스로 추상화해 테스트는 Fake 구현 사용 |
| `PriceStore` | Redis 읽기/쓰기 단일 창구 (코인 전용). 과거 틱 무시(수신 timestamp 비교 후 최신만 저장) |
| `CryptoCandleProvider` (인터페이스) | 코인 1분봉 조회 공통 계약. 심볼·간격·시각 범위를 받아 시각 오름차순 분봉 목록을 반환한다. 구현체가 무엇인지 `CandleQueryService`에 노출하지 않는다 (이슈 #20) |
| `BithumbRestCandleProvider` | `CryptoCandleProvider` 구현 — 빗썸 공개 캔들 REST 호출, 심볼→`KRW-{symbol}` 변환, `from`·`to`→`to`+`count` 변환, 내림차순→오름차순 반전, **진행 중 분봉 포함**(주식과 반대 — 위 "코인 캔들 설계" 절 참조), 실패 시 `MARKET_DATA_PROVIDER_ERROR`(502). 응답을 저장·캐시하지 않는다 (이슈 #20) |
| `FakeCryptoCandleProvider` | 자동 테스트용 `CryptoCandleProvider` 구현. 실제 빗썸 REST 연결은 외부 스모크로 구분 보고한다 (C-005) — Fake 통과를 실제 연동 성공으로 보고하지 않는다 (이슈 #20) |
| `CandleQueryService` | 캔들 조회의 시장 분기점. `Instrument.market`이 `STOCK`이면 `StockPriceProvider`, `CRYPTO`면 `CryptoCandleProvider`에 위임하고 같은 `CandleResponse[]` 계약으로 반환한다. **기존의 "코인이면 400 `VALIDATION_ERROR`" 거부를 제거한다** (이슈 #17에서 추가 → 이슈 #20에서 제거) |
| `StockPriceProvider` (인터페이스) | 주식 시세 공급자 공통 계약. 현재가(가격·`sourceTime`·유효성)와 1분봉 조회, 시장 상태를 반환한다. 구현체가 무엇인지는 아래 소비 계층에 노출하지 않는다 |
| `KisHistoricalReplayPriceProvider` | `StockPriceProvider` 구현 — 내부적으로 `StockReplayService`를 사용한다. **공개 배포(`PUBLIC`)의 기본 구현** (이슈 #17) |
| `KisRealtimePriceProvider` | `StockPriceProvider` 구현 — KIS Open API 국내주식 WebSocket 체결 틱 수신, 재연결 처리, 연결상태 기록. **개인 개발·본인 전용 검증(`PRIVATE`)용.** 인터페이스 뒤에 두어 테스트는 `FakeKisRealtimePriceProvider` 사용 (**이슈 #82 — MVP 범위 아님**) |
| `KisTickAggregator` | KIS 체결 틱을 **서버에서 1분 OHLCV로 집계**한다. 결과는 `KisHistoricalReplayPriceProvider`가 제공하는 것과 같은 분봉 모델이어야 캔들 API·차트가 Provider를 구분하지 않는다. 집계 결과의 영구 보관은 이번 범위 밖 (spec 범위 제외) (**이슈 #82 — MVP 범위 아님**) |
| `StockFeedConfig` | `STOCK_FEED_PROVIDER`·`SERVICE_EXPOSURE`·`KIS_PUBLIC_DISPLAY_APPROVED`를 읽어 `StockPriceProvider` 빈 하나를 결정한다. 아래 "실행 환경 조합"의 금지 조합이면 **애플리케이션 시작 단계에서 예외를 던져 기동을 실패**시킨다 (fail-fast) |
| `PriceQueryService` | 주문 도메인과 SSE가 공통으로 소비하는 "유효한 최신 가격" 계약 — 주식은 주입된 `StockPriceProvider`가 제공하는 현재가, 코인은 stale 검사(10초) 통과한 Redis 가격. **두 조회 경로를 제공한다**: 기존 `getPrice`(Long·Instrument)는 유효하지 않으면 `BusinessException(PRICE_UNAVAILABLE)`을 던져 가격 API의 409 계약을 유지하고(이슈 #16 확정, 동작 변경 없음), 새 `getPriceQuote`류 경로는 예외를 던지지 않고 `PriceQuoteDto`의 `status`를 `AVAILABLE`·`UNAVAILABLE`로 채워 반환한다 — SSE(#19·#20)의 snapshot·price 판정처럼 "가격이 없는 상태 자체를 정상 응답으로 표현해야 하는" 소비자를 위한 경로다. `getPrice`는 이 조회 경로를 감싸 `UNAVAILABLE`일 때만 예외로 변환하는 방식으로 구현해 두 경로가 판정 로직을 중복하지 않는다. **어느 주식 Provider가 동작 중인지 알지 못한다** (이슈 #18: 예외를 던지지 않는 조회 경로 추가) |
| `SseEmitterRegistry` | `STOCK`·`CRYPTO` market별 `SseEmitter` 집합을 관리하는 공통 컴포넌트(코인은 실제로 쓰이지 않지만 market 매개변수를 일반화해 두 시장을 함께 지원할 수 있게 만들어진 기존 구현이다). `register(market)` 호출 시 `SseEmitter`를 생성해 집합에 추가하고 그 자리에서 `retry: 3000`을 1회 전송한다. `onCompletion`·`onTimeout`·`onError` 콜백에서 해당 emitter를 집합에서 제거한다. `@Scheduled`(`@EnableScheduling` 필요)로 20초마다 등록된 모든 emitter에 heartbeat 주석(`:heartbeat\n\n`)을 전송한다. snapshot·price·status 이벤트의 payload 구성이나 실제 전송 트리거는 담당하지 않는다 — 그건 `StockPriceSseController`(#19)의 책임이다 (이슈 #18) |
| SSE 이벤트 DTO (`snapshot`/`price`/`status`) | 3종 이벤트의 직렬화 모델. `sourceTime`(원본 데이터의 실제 시각)·`emittedAt`(서버가 지금 전송한 벽시계 시각)·`sourceTradingDate`(주식에만 포함, 코인 이벤트는 필드 자체를 생략)를 구분해서 담는다. 필드 상세는 아래 "SSE 계약"의 JSON 예시를 따른다 (이슈 #18 — DTO·직렬화만, 실제 컨트롤러 배선은 #19·#20) |
| `StockPriceSseController` | `/stocks/stream`(이슈 #19) 구독 엔드포인트. `SseEmitterRegistry.register(STOCK)`로 emitter를 얻고, 구독 직후 `snapshot`(배열 1건)을 전송한 뒤 `price`·`status` 이벤트를 push한다. retry 전송·heartbeat·emitter 정리는 `SseEmitterRegistry`(#18)에 위임한다. **코인은 이에 대응하는 컨트롤러가 없다** — 실시간 표출은 캔들 API(이슈 #20)의 REST 조회로 대신한다 |

**전체 흐름 요약**:

```
[KIS_HISTORICAL 경로 — 공개 배포 기본, 이슈 #17]
KisHistoricalCandleCollector → StockCandle 저장, MarketDataImport 저장
StockReplaySessionScheduler → 수집 결과 확인 → StockReplaySession 생성 → PREPARING/READY/FAILED
StockReplayService → StockReplaySession + Clock 조회 → 현재가격·OPEN/CLOSED 계산
   → KisHistoricalReplayPriceProvider

[KIS_REALTIME 경로 — 개인 개발·본인 전용 검증, 이슈 #82]
KIS WebSocket 체결 틱 → KisRealtimePriceProvider → KisTickAggregator(1분 OHLCV)

[공통 — 아래 계층은 Provider 종류를 모른다]
StockFeedConfig가 둘 중 하나를 선택 → StockPriceProvider
   → PriceQueryService → 가격·캔들 API · SSE · 모의 주문 체결 · 보유자산 평가손익

[코인 — 두 경로가 서로 독립이다. 코인은 전용 SSE 엔드포인트가 없다]
빗썸 WebSocket 틱 → BithumbFeedClient → PriceStore(Redis) → PriceQueryService
   → 현재가 API(GET .../price) · 모의 주문 체결·주문 가능 판정(MKT-004)   (이슈 #16, 이미 병합)
빗썸 캔들 REST  → BithumbRestCandleProvider → CandleQueryService
   → 캔들 API(차트, 화면 실시간 표출은 이 경로의 REST 재조회로 충분하다). Redis·MySQL을 거치지 않고 저장도 하지 않는다  (이슈 #20)
```

### 실행 환경 조합 (PRD MKT-007·C-007)

| 설정 | 값 | 기본값 |
|---|---|---|
| `STOCK_FEED_PROVIDER` | `KIS_REALTIME` · `KIS_HISTORICAL` | `KIS_HISTORICAL` |
| `SERVICE_EXPOSURE` | `PRIVATE`(개발자 본인만 접근하는 로컬·접근 통제 환경) · `PUBLIC`(본인 외 접근 — 팀 시연 포함) | `PRIVATE` |
| `KIS_PUBLIC_DISPLAY_APPROVED` | `true` · `false` | `false` |

| SERVICE_EXPOSURE | STOCK_FEED_PROVIDER | 결과 |
|---|---|---|
| PRIVATE | KIS_REALTIME | 허용 — 개인 개발·본인 전용 검증 (개발자 본인만 접근) |
| PRIVATE | KIS_HISTORICAL | 허용 — 로컬 재생 테스트 |
| PUBLIC | KIS_HISTORICAL | 허용 — **현재 공개 배포 기본값. 팀원·튜터·심사위원 시연도 이 조합** |
| PUBLIC | KIS_REALTIME + `KIS_PUBLIC_DISPLAY_APPROVED=true` | 허용 — 단 한국투자 서면 허가·계약 근거가 실재할 때만 |
| PUBLIC | KIS_REALTIME + `KIS_PUBLIC_DISPLAY_APPROVED=false` | **기동 실패 (fail-fast)** |

- `PRIVATE`는 로그인 여부가 아니라 **접근 주체**로 판정한다 — KIS 개인 계정 소유자인 개발자 본인만 접근 가능한 로컬 또는 접근 통제 환경이어야 한다. 이 환경을 공개 URL이나 다중 사용자 서버로 운영하지 않는다. 팀원·튜터·심사위원이 접근하는 순간 그 환경은 `PUBLIC`이며, 실시간 표출 서면 답변 전까지 `KIS_HISTORICAL`을 써야 한다.
- fail-fast는 요청 처리 시점이 아니라 **애플리케이션 시작 시점**에 판정한다. 경고 로그만 남기고 뜨는 동작은 금지한다 — 잘못된 조합으로 공개 서비스가 떠 있는 시간을 0으로 만들기 위함이다.
- `KIS_PUBLIC_DISPLAY_APPROVED`는 **서면 허가·계약 확인 결과를 시스템에 반영하는 수단일 뿐이다.** 이 값을 `true`로 바꾸는 것 자체가 허가를 만들지 않는다. 정본은 한국투자증권의 서면 답변이며, 아직 받은 적이 없다 (Decision Gate — PRD §10, 실시간에 한정. 과거 데이터는 공공데이터로 이미 확인됨).
- KIS 실시간 키(`KIS_APP_KEY`·`KIS_APP_SECRET`·`KIS_ACCOUNT_NO`·`KIS_ACCOUNT_PRODUCT_CODE`)는 `KIS_REALTIME`일 때만 필요하다. `KIS_HISTORICAL`에서는 이 값들을 필수로 바인딩하지 않으며, 없어도 기동과 `./gradlew build`가 성공해야 한다.
- 키 실제 값은 서버 환경변수 또는 AWS Secret에만 둔다. 브라우저·코드·문서·GitHub에 넣지 않는다 (`conventions.md` 시크릿 규칙). SSE·API 응답에도 실어보내지 않는다.

### 재생세션 nullable 규칙

`stock_replay_sessions`의 상태별 필드 nullable 규칙 (강제는 `StockReplaySessionScheduler`의 애플리케이션 검증 + 단위 테스트로만 한다 — 이 테이블은 단일 writer만 있고 기존 스키마에 CHECK 제약 선례가 없어, DB CHECK 제약은 도입하지 않는다):

| preparation_status | source_trading_date | resolved_at | failure_reason |
|---|---|---|---|
| PREPARING | NULL 가능 | NULL | NULL |
| READY | 필수 | 필수 | NULL |
| FAILED | NULL 가능 | 필수 | 필수 |

- 후보 거래일을 고르기 전 `PREPARING`이면 `source_trading_date`는 NULL, 특정 거래일을 검증하는 중이면 그 값을 가질 수 있다.
- `FAILED`인데 데이터 자체를 찾지 못했다면 `source_trading_date`는 NULL, 특정 거래일을 준비하다 실패했다면 그 값을 가질 수 있다 — 둘 다 허용.

### 동일 거래일 재수집 정책 (이슈 #83 — 장기운영 방어 로직, MVP 범위 아님)

한 번 받아들인(SUCCESS·PARTIAL_SUCCESS) 거래일 데이터는 불변으로 취급한다. `market_data_imports.status`에서 해당 `source_trading_date`에 SUCCESS 또는 PARTIAL_SUCCESS가 있으면 "이미 받아들인 기준 데이터가 있다"로 보고, FAILED만 있으면 "아직 없다"로 본다.

| 상황 | 처리 |
|---|---|
| 기존 SUCCESS·PARTIAL_SUCCESS 없음 (최초 수집) | 정상 전체 저장 시 SUCCESS, 일부 종목만 구조 오류 시 PARTIAL_SUCCESS, 전체 응답 오류 시 FAILED |
| 같은 거래일 + 동일한 수집 결과 재수집 | `StockCandle`·`StockReplaySession` 변경 없음. `MarketDataImport`에 `SKIPPED_DUPLICATE` 이력만 추가(배치 실행 자체의 감사 추적용) |
| 같은 거래일 + 상충하는 수집 결과 | `StockCandle`·`StockReplaySession`(기존 READY 세션 포함) 변경 없이 수집 거부. `MarketDataImport`에 FAILED 기록 |
| 기존 이력이 FAILED만 있음 | 받아들인 데이터가 없으므로 새 수집 시도를 허용, 결과에 따라 SUCCESS·PARTIAL_SUCCESS·FAILED 기록 |

- 검사는 수집 컴포넌트의 애플리케이션 로직으로 수행한다 — `market_data_imports`에서 해당 `source_trading_date`의 기존 SUCCESS·PARTIAL_SUCCESS와 수집 결과 식별값을 조회해 비교한다. DB UNIQUE 제약만으로 이 조건부 규칙(동일 결과는 허용, 상충 결과는 거부)을 표현하지 않는다.
- MVP 제외: 이미 받아들인 거래일 데이터의 자동 교체, 거래일 전체 원자적 삭제·재삽입, 데이터 버전 관리, 관리자 강제 교체 기능, 장중 데이터 변경.

- 종목별 "거래불가"를 표현하는 날짜별 전용 상태 테이블이나 `instruments`의 날짜 한정 컬럼은 만들지 않는다. 특정 종목·거래일에 유효한 `StockCandle`이 없으면 `PriceQueryService`가 자연히 `PRICE_UNAVAILABLE`을 반환한다 — 이 자체가 "거래불가"의 표현이다.
- 공휴일 판정은 리소스 파일의 공휴일 목록(당해 연도)으로 단순 관리한다. 외부 캘린더 API 미사용.
- 코인 stale 기준은 **10초로 확정** (빗썸은 정상 연결 시 통상 수 초 이내로 틱이 계속 수신되므로, 일시적 네트워크 지연으로 오탐하지 않으면서 실제 장애를 빠르게 감지하는 균형점).
- 종목별 분봉 누락 임계치(예: 기대 390개 대비 몇 %, 350개 이상 등)는 **확정하지 않는다** — 임시 숫자도 production 설정에 넣지 않는다. KIS 상품의 데이터 형식과 timestamp 의미(거래 없는 분을 생략하는지 여부)가 확인된 후 결정한다 (Decision Gate, spec.md 참조). 샘플 데이터 테스트는 샘플 자체의 기대 결과만 검증한다.

### Redis 키 설계 (PRD §6 책임 준수, 코인 전용)

| 키 | 값 | 용도 |
|---|---|---|
| `price:crypto:{symbol}` | price, receivedAt | 코인 최신 시세 |
| `feed:crypto:status` | CONNECTED·DISCONNECTED | 빗썸 연결상태 |

- 위 두 키가 전부다. **코인 1분봉(MKT-008)용 캐시 키를 추가하지 않는다** — 캔들은 요청마다 빗썸에서 조회해 중계하고 보관하지 않는다. spec.md 완료 조건의 "Redis에 최신 가격·수신시각·연결상태 외 데이터가 저장되지 않음" 검증이 이 규칙을 지킨다.

## 데이터 모델

마이그레이션 `V{next}__create_instruments.sql` (종목 시드) + `V{next+1}__create_stock_candles.sql` + `V{next+2}__create_stock_replay_sessions.sql` + `V{next+3}__create_market_data_imports.sql`.

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| instruments | id PK, market(STOCK·CRYPTO), symbol, name, tick_size DECIMAL(18,8), min_order_amount BIGINT, tradable BOOLEAN, created_at | UNIQUE(symbol) |
| stock_candles | id PK, instrument_id FK, trading_date DATE, candle_time TIME, open/high/low/close DECIMAL(18,4), volume BIGINT, data_source VARCHAR, collected_at | UNIQUE(instrument_id, trading_date, candle_time) |
| stock_replay_sessions | id PK, service_date DATE, source_trading_date DATE(nullable), preparation_status(PREPARING·READY·FAILED), resolved_at(nullable), failure_reason(nullable), created_at | UNIQUE(service_date) |
| market_data_imports | id PK, source VARCHAR, source_trading_date DATE, collected_at, status(SUCCESS·PARTIAL_SUCCESS·FAILED·SKIPPED_DUPLICATE), failure_reason | 인덱스(source_trading_date) — 재수집 시 기존 성공 이력 조회용. 수집 결과 식별 컬럼(구 `file_hash`)의 정확한 형태는 이슈 #83에서 API 응답 기준으로 재설계한다 |

- **코인 차트(MKT-008)는 마이그레이션이 없다** — 코인 분봉 테이블을 만들지 않고 빗썸 조회 결과를 그대로 중계한다. `stock_candles`는 이름 그대로 주식 전용으로 남는다.
- `stock_candles`에 `validation_status` 컬럼을 두지 않는다 — 검증을 통과한 분봉만 저장되므로 저장된 모든 행이 같은 성공값을 반복하는 죽은 컬럼이 된다. 성공·부분성공·실패와 실패사유는 `market_data_imports`에만 기록한다.
- 종목 시드는 마이그레이션에 포함한다 (기준 데이터 — 코드·환경 간 동일 보장, ADR-0004).
- `stock_candles`·`stock_replay_sessions`·`market_data_imports`는 각각 `KisHistoricalCandleCollector`·`StockReplaySessionScheduler`가 채운다 — 마이그레이션에 데이터를 포함하지 않는다 (거래일마다 갱신되는 운영 데이터이므로 ADR-0004의 "기준 데이터"와 다름).
- 20영업일 초과 데이터 삭제는 `StockCandleCleanupJob`(스케줄 배치)이 담당하며, `stock_replay_sessions.source_trading_date`(재생 중인 거래일)는 삭제 대상에서 제외한다. `stock_replay_sessions`·`market_data_imports` 자체의 보관 기간은 별도 정책이 없다 (운영 이력이라 분봉과 달리 삭제 압박이 크지 않음 — 필요해지면 후속 결정).
- 20영업일 규모(16종 × 390개 × 20일 ≈ 12.5만 행)는 MySQL 기본 설정으로 충분해 파티셔닝 등 별도 저장 전략은 도입하지 않는다.

## 테스트 계획

- 단위:
  - `KisHistoricalCandleCollector` (정상 응답 저장, 전체 응답 오류 시 미저장·FAILED 기록, 특정 종목만 구조 오류 시 그 종목만 미저장·나머지 저장·PARTIAL_SUCCESS 기록, `UNIQUE` 제약 기반 재실행 멱등, `StockReplaySession`을 직접 쓰지 않음) — 이슈 #17
  - **(이슈 #83)** 동일 거래일 재수집 정책 (최초 정상→SUCCESS, 최초 일부 오류→PARTIAL_SUCCESS, 최초 전체 오류→FAILED, 동일 날짜·동일 수집 결과→SKIPPED_DUPLICATE·Candle 변화 없음, 동일 날짜·상충 결과→FAILED·Candle·세션 변화 없음, FAILED 이력만 있는 거래일은 재시도 허용, 서로 다른 수집 결과의 Candle이 섞이지 않음)
  - `StockReplaySessionScheduler` (`PREPARING`→`READY`, `PREPARING`→`FAILED`, `StockCandle`을 직접 쓰지 않음, Clock으로 실행시점 제어, 상태별 nullable 규칙 검증 — `PREPARING`+`resolved_at` 존재·`PREPARING`+`failure_reason` 존재·`READY`+`source_trading_date` 없음·`READY`+`resolved_at` 없음·`READY`+`failure_reason` 존재는 거부, `FAILED`+`resolved_at` 없음·`FAILED`+`failure_reason` 없음은 거부, `FAILED`+`source_trading_date` NULL과 값 존재는 둘 다 허용) — 이슈 #17
  - **(이슈 #83)** `StockCandleCleanupJob` (20영업일 경계·재생 중 거래일 보존)
  - `PriceStore` 과거 틱 무시, `PriceQueryService` 유효성 판정 (stale 10초·끊김·장외)
  - `StockFeedConfig` Provider 선택: `PRIVATE`+`KIS_REALTIME`→`KisRealtimePriceProvider`, `PUBLIC`+`KIS_HISTORICAL`→`KisHistoricalReplayPriceProvider`, `PRIVATE`+`KIS_HISTORICAL`→`KisHistoricalReplayPriceProvider`
  - `StockFeedConfig` fail-fast: `PUBLIC`+`KIS_REALTIME`+`KIS_PUBLIC_DISPLAY_APPROVED=false`면 컨텍스트 기동 실패 (`@SpringBootTest` 컨텍스트 로드 실패를 기대값으로 검증)
  - KIS 실시간 키 환경변수가 전혀 없는 상태에서 `KIS_HISTORICAL` 기동·빌드 성공
  - `StockPriceProvider` 계약 동등성: 같은 시나리오를 두 구현으로 각각 실행해 `PriceQueryService` 응답 계약이 동일함을 검증
  - **(이슈 #82)** `FakeKisRealtimePriceProvider` 연결 끊김→가격 무효, 재연결→새 체결 수신 후 복귀
  - **(이슈 #82)** `KisTickAggregator` 1분 OHLCV 집계 — 같은 분의 첫 체결이 open, 최고가 high, 최저가 low, 마지막 체결이 close, 수량 합이 volume. 분 경계에서 새 봉으로 넘어감. 결과 모델이 캔들 API 응답 모델과 동일
  - 화면 가격과 체결가격의 공급자 일치: SSE·가격 API가 반환한 값과 모의 주문 체결가가 같은 `StockPriceProvider` 인스턴스에서 나온다
  - **(이슈 #18)** `SseEmitterRegistry`: `register(market)` 호출 시 해당 market의 emitter 집합에 추가되고 `retry: 3000`이 전송됨, `onCompletion`·`onTimeout`·`onError` 콜백 발생 시 집합에서 제거됨, `STOCK`·`CRYPTO` 집합이 서로 섞이지 않음. SSE 이벤트 DTO 3종 직렬화: `sourceTime`·`emittedAt`·`sourceTradingDate` 필드가 의도한 대로 구분되어 나오는지(코인은 `sourceTradingDate` 생략), snapshot의 `prices` 배열·price/status 이벤트의 단일 종목 필드 구조. `PriceQueryService`의 새 예외 없는 조회 경로: 가격 있음→AVAILABLE 반환, 가격 없음→예외 없이 UNAVAILABLE 반환, 기존 `getPrice`는 여전히 PRICE_UNAVAILABLE에서 예외를 던져 409 계약 유지(회귀 테스트)
  - **(이슈 #19·#20)** SSE 컨트롤러: 토큰 없음/잘못된 토큰 시 401, price 이벤트에만 id 존재(snapshot·status는 id 없음), snapshot에 주식 16종·코인 12종 전체 포함(가격 없는 종목도 포함), 가격 없는 종목은 price·sourceTime이 null이고 status는 UNAVAILABLE, 가격 변경 시 price 이벤트, 시장·연결상태 변경 시 status 이벤트, `sourceTime`과 `emittedAt` 구분, 주식은 `sourceTradingDate` 포함·코인은 미포함, 장 마감 후 marketStatus=CLOSED이면서 마지막 유효가격 유지(장 마감과 가격 없음 구분), 코인 stale 시 marketStatus는 OPEN 유지·종목 status만 UNAVAILABLE, 재접속 시 snapshot 재전송, 누락 이벤트 전체 재전송 안 함 (retry·heartbeat·emitter 정리 자체는 #18의 `SseEmitterRegistry` 단위 테스트로 이미 커버 — 컨트롤러 테스트에서 재검증하지 않는다)
  - **(이슈 #20)** `BithumbRestCandleProvider`: 빗썸 내림차순 응답이 시각 오름차순으로 반전됨, **진행 중인 분봉이 응답에 포함됨**(주식과 반대라는 것을 명시적으로 고정), `candle_acc_trade_volume`(수량)이 `volume`에 매핑되고 `candle_acc_trade_price`(거래대금)와 섞이지 않음, `trade_price`가 `close`에 매핑됨, 심볼 `BTC`→`KRW-BTC` 변환, `from`·`to` 조합별 `to`+`count` 산출(둘 다 생략→count 200, `to`만, `from`만, 둘 다, 200분 초과 시 200으로 캡), 소수 `volume`이 잘리지 않음, 타임아웃·비정상 상태코드·파싱 불가에서 `MARKET_DATA_PROVIDER_ERROR`를 던지고 빈 목록을 반환하지 않음, 응답을 Redis·MySQL에 쓰지 않음
  - **(이슈 #20)** `CandleQueryService` 시장 분기: `STOCK`이면 `StockPriceProvider`, `CRYPTO`면 `CryptoCandleProvider`에 위임하고 코인 요청을 더 이상 400으로 거부하지 않음. 코인 응답에 `sourceTradingDate`가 없음
  - **(이슈 #20)** 코인 캔들 경로와 현재가 경로의 독립성: `PriceStore`가 비어 있거나 연결이 끊긴 상태에서도 캔들 조회 성공, 캔들 Provider가 실패해도 현재가 API·주문은 정상
  - **(이슈 #20)** 주식 캔들 회귀: `volume`을 `BigDecimal`로 넓힌 뒤에도 기존 주식 캔들의 값·정렬·공개 컷오프·재생세션 미준비 시 200 `[]` 계약이 유지됨
- 슬라이스: `@DataJpaTest` — 종목 시드·UNIQUE(symbol), `stock_candles`/`stock_replay_sessions` UNIQUE 제약. `@WebMvcTest` — 목록·가격·캔들 계약, 404·400·409 매핑, SSE 컨트롤러 계약(#19·#20), 코인 캔들 계약(#20: 401, 코인 200, `interval` 오류 400, 빗썸 실패 502).
- 통합 (Testcontainers MySQL+Redis, 이슈 #17): 샘플 KIS 응답 데이터 수집→`StockReplaySessionScheduler`가 세션 READY로 전환→재생→가격 조회(첫 분봉 시가, 이후 종가), 서버 재시작 시나리오에서 같은 원본 거래일 유지, DB에 OPEN·CLOSED가 저장되지 않음을 확인, Fake Feed 정상 수신→가격 조회, 끊김→PRICE_UNAVAILABLE, 재연결 새 틱→복귀 시나리오.
- **(이슈 #83)** 통합: 동일 거래일에 상충하는 수집 결과 재수집을 거부해도 기존 READY 세션·StockCandle이 그대로인 시나리오.
- 외부 스모크(자동 테스트와 구분 보고): 실제 빗썸 WebSocket 연결, **실제 빗썸 캔들 REST 조회(이슈 #20 — 12종 전체가 200 응답하는지, 필드명이 문서와 일치하는지)**, 실제 KIS Open API 과거 데이터 수집, **실제 KIS Open API WebSocket 연결과 국내주식 체결 틱 수신**. Fake 통과를 실제 연동 성공으로 보고하지 않는다 (PRD C-005).
