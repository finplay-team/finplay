# Plan: 종목과 시세

## 관련 문서
- Spec: `./spec.md`
- 관련 ADR: ADR-0002 (도메인 패키지), ADR-0003 (테스트 전략), ADR-0004 (Flyway)
- PRD: §1 C-006(데이터 이용 정책), §5 종목·시세 API, §6 데이터 모델·Redis 키 책임
- 선행: `001-foundation` (Redis·Clock), `002-auth-account` (인증 — 조회 API·SSE는 인증 필요)

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/instruments?market= | 쿼리 market(선택) | `InstrumentResponse[]` | 종목 목록 |
| GET | /api/instruments/{instrumentId} | - | `InstrumentResponse` | 종목 단건 |
| GET | /api/instruments/{instrumentId}/price | - | `PriceResponse` (price, sourceTime, status, sourceTradingDate) | 최신 가격. 없으면 409 PRICE_UNAVAILABLE |
| GET | /api/instruments/{instrumentId}/candles?interval=1m&from=&to= | 쿼리 | `CandleResponse[]` | 주식 1분봉 (`stock_candles` 기반, 공개된 분봉까지만) |
| GET | /api/stocks/stream | Header: `Authorization: Bearer <accessToken>` | SSE | 주식 전용 스트림 |
| GET | /api/cryptos/stream | Header: `Authorization: Bearer <accessToken>` | SSE | 코인 전용 스트림 |

- 주식·코인 스트림을 분리한 이유: 프론트 화면이 시장별 탭으로 나뉘어 있어 각자 필요한 채널만 구독하면 되고, 재생 주기(1분)와 코인 갱신 주기(초 단위)가 달라 하나로 합치면 페이로드 구분 로직이 오히려 늘어난다.

### SSE 계약 (MVP 확정 — 변경하려면 문서와 프론트·백엔드를 함께 수정)

- **인증**: 브라우저 기본 `EventSource`는 커스텀 헤더를 지원하지 않으므로, 프론트는 `fetch()`로 스트림을 요청하며 `Authorization: Bearer <accessToken>` 헤더를 그대로 전달하고 응답 `ReadableStream`을 직접 파싱한다. Access Token을 URL 쿼리 파라미터에 넣지 않는다. 인증 실패는 401.
- **Content-Type**: `text/event-stream`.
- **이벤트 이름** (3종으로 확정):
  - `snapshot` — 구독 시작 직후 1회. 해당 MVP 시장의 **전체 종목**(주식 16종·코인 12종)을 배열 한 건으로 전송한다 (종목별로 여러 건 보내지 않는다). 가격이 없는 종목도 배열에서 빼지 않고 `price`·`sourceTime`을 `null`, `status`를 `UNAVAILABLE`로 포함한다.
  - `price` — 종목 하나의 가격이 변경될 때 전송 (주식: 매분 새로 공개된 가격, 코인: 업비트 수신 틱마다).
  - `status` — 시장 개장·마감, 코인 stale·연결 끊김/복구, 주식 데이터 준비 실패 등 상태 변화.
- **시간 필드 분리**:
  - `sourceTime` — 원본 데이터의 실제 시각. 주식은 과거 원본 분봉의 실제 시각(재생 중인 과거 거래일 기준), 코인은 업비트 틱의 실제 시각.
  - `emittedAt` — 우리 서버가 **지금** 이 SSE 이벤트를 전송한 실제 벽시계 시각(오늘 날짜).
  - `sourceTradingDate` — 주식에만 포함, 현재 재생 중인 실제 과거 거래일. 코인 이벤트에는 포함하지 않는다.
- **id**: `price` 이벤트에만 부여한다 — `{market}:{symbol}:{sourceTime 기반 식별자}` (예: `STOCK:005930:202607220901`). `snapshot`·`status`는 id를 넣지 않는다(`Last-Event-ID` 기반 누락 이벤트 재전송을 MVP에서 지원하지 않고, `snapshot`은 여러 종목을 포함해 단일 symbol이 없기 때문).
- **retry**: 연결 시작 시 `retry: 3000`(3초)을 1회 전송한다. 이 값은 서버가 보내는 힌트일 뿐 자동으로 처리되지 않는다 — `EventSource`와 달리 fetch 기반 클라이언트는 이 값을 자동으로 읽어 재연결하지 않으므로, **프론트 fetch 클라이언트가 이 값을 참고해 연결 실패 후 직접 재접속한다** (기본 3초 뒤).
- **heartbeat**: 20초마다 SSE 주석(`: heartbeat\n\n`)을 전송해 프록시·브라우저 타임아웃으로 인한 연결 끊김을 방지한다.
- **연결 종료 처리**: `SseEmitter`의 `onCompletion`·`onTimeout`·`onError` 콜백에서 구독자 목록(심볼별 emitter 집합)에서 제거한다.
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
| interval | 필수(candles) | 1차는 `1m`만. 그 외 400 VALIDATION_ERROR |
| from·to | 선택 | ISO-8601. from > to면 400 VALIDATION_ERROR |

## 구성 요소 설계

### market 패키지 (`com.finplay.api.market`)

| 구성 요소 | 역할 |
|---|---|
| `Instrument` 엔티티 + Repository | 종목 정본 (MySQL) |
| `StockCandle` 엔티티 + Repository | 주식 1분봉 정본 (MySQL). `UNIQUE(instrument_id, trading_date, candle_time)` |
| `StockReplaySession` 엔티티 + Repository | "오늘 어떤 원본 거래일을 재생 중인가"의 정본. `service_date`(오늘, UNIQUE)·`source_trading_date`·`preparation_status`(PREPARING·READY·FAILED)·`resolved_at`(결과가 결정된 시각 — READY·FAILED 공통, "성공 시각"이라는 오해를 피하기 위해 `prepared_at` 대신 이 이름을 쓴다)·`failure_reason`. `OPEN`·`CLOSED`는 이 엔티티에 저장하지 않는다. 상태별 필드 nullable 규칙은 아래 "재생세션 nullable 규칙" 참조 |
| `InstrumentController` / `InstrumentService` | 목록·단건·가격·캔들 조회 |
| `KrxFileImporter` | KRX 파일을 읽어 명백한 오류만 검증 → 허용 16종 추출 → 정규화 → `StockCandle` 저장 + `MarketDataImport` 이력 기록. **`StockReplaySession`을 직접 생성·수정하지 않는다.** 전체 파일 오류면 `StockCandle`을 부분 저장하지 않고 `MarketDataImport`에 FAILED 기록. 특정 종목만 명백한 구조 오류면 그 종목의 행만 거래일 단위로 저장하지 않고 나머지는 저장, `MarketDataImport`에 PARTIAL_SUCCESS와 문제 종목·원인 기록. **수집 전 `market_data_imports`에서 해당 `source_trading_date`의 기존 SUCCESS·PARTIAL_SUCCESS 이력과 file_hash를 확인해 재수집 정책(아래 "동일 거래일 재수집 정책" 참조)을 적용한다.** production 파일 포맷 파싱은 KRX 답변 대기 상태 — 샘플 데이터 기반 골격만 우선 구현 |
| `MarketDataImport` 엔티티 + Repository | 수집 시도 이력(SUCCESS·PARTIAL_SUCCESS·FAILED·SKIPPED_DUPLICATE, 실패사유, 수집시각, file_hash). 저장에 실패해 `StockCandle` 행이 없는 경우도 기록 |
| `StockReplaySessionScheduler` | `MarketDataImport`·`StockCandle`의 수집 결과를 확인해 오늘의 `StockReplaySession`을 생성하고 `source_trading_date`를 고정한다. `preparation_status`를 `PREPARING`→`READY` 또는 `PREPARING`→`FAILED`로만 전환한다. **`StockCandle`을 직접 저장하지 않는다.** 정확한 production 실행시각(장 시작 전 언제 실행할지)은 KRX 데이터 제공시각 확인 후 확정하는 Decision Gate — 테스트에서는 Clock으로 실행시점을 제어한다 |
| `StockCandleCleanupJob` | 20영업일 초과 분봉 삭제 배치. 재생 중인 거래일(`StockReplaySession.source_trading_date`)은 제외 |
| `StockReplayService` | `StockReplaySession`을 읽어 오늘의 원본 거래일·준비상태를 확인하고, Clock과 조합해 `OPEN`·`CLOSED`를 계산한다 (`preparation_status != READY`면 이용 불가, `READY`면 Clock 기준 09:00~15:30 KST·영업일 여부로 OPEN·CLOSED 계산). 09:00~09:00:59는 첫 분봉의 시가, 09:01부터는 마감된 마지막 분봉의 종가를 현재가로 제공한다. 매분 스케줄(`@Scheduled`)로 새로 공개된 가격을 SSE로 push한다. **파일 수집이나 재생 대상 날짜 선택을 하지 않고, `StockReplaySession`의 DB 상태를 변경하지 않는다** (읽기 전용) |
| `UpbitFeedClient` | 업비트 WebSocket 수신 → `PriceStore` 저장. 재연결 처리. 인터페이스로 추상화해 테스트는 Fake 구현 사용 |
| `PriceStore` | Redis 읽기/쓰기 단일 창구 (코인 전용). 과거 틱 무시(수신 timestamp 비교 후 최신만 저장) |
| `PriceQueryService` | 주문 도메인과 SSE가 공통으로 소비하는 "유효한 최신 가격" 계약 — 주식은 `StockReplayService`가 계산한 현재가(첫 분봉 구간은 시가, 이후는 마감된 종가), 코인은 stale 검사(10초) 통과한 Redis 가격. 유효하지 않으면 PRICE_UNAVAILABLE·MARKET_CLOSED 판정 근거 반환 |
| `StockPriceSseController` / `CryptoPriceSseController` | `/stocks/stream`, `/cryptos/stream` 구독 엔드포인트. snapshot(배열 1건)·price·status 이벤트, heartbeat, emitter 정리 담당 |

**전체 흐름 요약**:

```
KrxFileImporter → StockCandle 저장, MarketDataImport 저장
StockReplaySessionScheduler → 수집 결과 확인 → StockReplaySession 생성 → PREPARING/READY/FAILED
StockReplayService → StockReplaySession + Clock 조회 → 현재가격·OPEN/CLOSED 계산 → PriceQueryService·SSE에 제공
```

### 재생세션 nullable 규칙

`stock_replay_sessions`의 상태별 필드 nullable 규칙 (강제는 `StockReplaySessionScheduler`의 애플리케이션 검증 + 단위 테스트로만 한다 — 이 테이블은 단일 writer만 있고 기존 스키마에 CHECK 제약 선례가 없어, DB CHECK 제약은 도입하지 않는다):

| preparation_status | source_trading_date | resolved_at | failure_reason |
|---|---|---|---|
| PREPARING | NULL 가능 | NULL | NULL |
| READY | 필수 | 필수 | NULL |
| FAILED | NULL 가능 | 필수 | 필수 |

- 후보 거래일을 고르기 전 `PREPARING`이면 `source_trading_date`는 NULL, 특정 거래일을 검증하는 중이면 그 값을 가질 수 있다.
- `FAILED`인데 데이터 자체를 찾지 못했다면 `source_trading_date`는 NULL, 특정 거래일을 준비하다 실패했다면 그 값을 가질 수 있다 — 둘 다 허용.

### 동일 거래일 재수집 정책

한 번 받아들인(SUCCESS·PARTIAL_SUCCESS) 거래일 데이터는 불변으로 취급한다. `market_data_imports.status`에서 해당 `source_trading_date`에 SUCCESS 또는 PARTIAL_SUCCESS가 있으면 "이미 받아들인 기준 데이터가 있다"로 보고, FAILED만 있으면 "아직 없다"로 본다.

| 상황 | 처리 |
|---|---|
| 기존 SUCCESS·PARTIAL_SUCCESS 없음 (최초 수집) | 정상 전체 저장 시 SUCCESS, 일부 종목만 구조 오류 시 PARTIAL_SUCCESS, 전체 파일 오류 시 FAILED |
| 같은 거래일 + 같은 file_hash 재수집 | `StockCandle`·`StockReplaySession` 변경 없음. `MarketDataImport`에 `SKIPPED_DUPLICATE` 이력만 추가(배치 실행 자체의 감사 추적용) |
| 같은 거래일 + 다른 file_hash | `StockCandle`·`StockReplaySession`(기존 READY 세션 포함) 변경 없이 수집 거부. `MarketDataImport`에 FAILED 기록, 실패사유 `SOURCE_DATE_HASH_CONFLICT` |
| 기존 이력이 FAILED만 있음 | 받아들인 데이터가 없으므로 새 파일 수집 시도를 허용, 결과에 따라 SUCCESS·PARTIAL_SUCCESS·FAILED 기록 |

- 검사는 `KrxFileImporter`의 애플리케이션 로직으로 수행한다 — `market_data_imports`에서 해당 `source_trading_date`의 기존 SUCCESS·PARTIAL_SUCCESS와 file_hash를 조회해 비교한다. DB UNIQUE 제약만으로 이 조건부 규칙(같은 해시는 허용, 다른 해시는 거부)을 표현하지 않는다 — `(source_trading_date, file_hash)` 인덱스는 조회 보조용으로만 쓴다.
- MVP 제외: 이미 받아들인 거래일 데이터의 자동 교체, 거래일 전체 원자적 삭제·재삽입, 데이터 버전 관리, 관리자 강제 교체 기능, 장중 데이터 변경.

- `KrxApiClient`는 이번 스펙 범위에 없다. KRX API 상품이 확인되면 `KrxFileImporter`와 같은 저장 결과(검증·정규화된 `StockCandle`, `MarketDataImport` 이력)를 만드는 별도 구현을 추가하고, `StockReplaySessionScheduler`·`StockReplayService`·`PriceQueryService`·SSE 계층은 수정하지 않는다.
- 종목별 "거래불가"를 표현하는 날짜별 전용 상태 테이블이나 `instruments`의 날짜 한정 컬럼은 만들지 않는다. 특정 종목·거래일에 유효한 `StockCandle`이 없으면 `PriceQueryService`가 자연히 `PRICE_UNAVAILABLE`을 반환한다 — 이 자체가 "거래불가"의 표현이다.
- 공휴일 판정은 리소스 파일의 공휴일 목록(당해 연도)으로 단순 관리한다. 외부 캘린더 API 미사용.
- 코인 stale 기준은 **10초로 확정** (업비트는 정상 연결 시 통상 수 초 이내로 틱이 계속 수신되므로, 일시적 네트워크 지연으로 오탐하지 않으면서 실제 장애를 빠르게 감지하는 균형점).
- 종목별 분봉 누락 임계치(예: 기대 390개 대비 몇 %, 350개 이상 등)는 **확정하지 않는다** — 임시 숫자도 production 설정에 넣지 않는다. KRX 상품의 데이터 형식과 timestamp 의미(거래 없는 분을 생략하는지 여부)가 확인된 후 결정한다 (Decision Gate, spec.md 참조). 샘플 데이터 테스트는 샘플 자체의 기대 결과만 검증한다.

### Redis 키 설계 (PRD §6 책임 준수, 코인 전용)

| 키 | 값 | 용도 |
|---|---|---|
| `price:crypto:{symbol}` | price, receivedAt | 코인 최신 시세 |
| `feed:crypto:status` | CONNECTED·DISCONNECTED | 업비트 연결상태 |

## 데이터 모델

마이그레이션 `V{next}__create_instruments.sql` (종목 시드) + `V{next+1}__create_stock_candles.sql` + `V{next+2}__create_stock_replay_sessions.sql` + `V{next+3}__create_market_data_imports.sql`.

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| instruments | id PK, market(STOCK·CRYPTO), symbol, name, tick_size DECIMAL(18,8), min_order_amount BIGINT, tradable BOOLEAN, created_at | UNIQUE(symbol) |
| stock_candles | id PK, instrument_id FK, trading_date DATE, candle_time TIME, open/high/low/close DECIMAL(18,4), volume BIGINT, data_source VARCHAR, collected_at | UNIQUE(instrument_id, trading_date, candle_time) |
| stock_replay_sessions | id PK, service_date DATE, source_trading_date DATE(nullable), preparation_status(PREPARING·READY·FAILED), resolved_at(nullable), failure_reason(nullable), created_at | UNIQUE(service_date) |
| market_data_imports | id PK, source VARCHAR, source_trading_date DATE, collected_at, status(SUCCESS·PARTIAL_SUCCESS·FAILED·SKIPPED_DUPLICATE), failure_reason, file_hash | 인덱스(source_trading_date, file_hash) — 재수집 시 기존 성공 이력·해시 조회용 |

- `stock_candles`에 `validation_status` 컬럼을 두지 않는다 — 검증을 통과한 분봉만 저장되므로 저장된 모든 행이 같은 성공값을 반복하는 죽은 컬럼이 된다. 성공·부분성공·실패와 실패사유는 `market_data_imports`에만 기록한다.
- 종목 시드는 마이그레이션에 포함한다 (기준 데이터 — 코드·환경 간 동일 보장, ADR-0004).
- `stock_candles`·`stock_replay_sessions`·`market_data_imports`는 각각 `KrxFileImporter`·`StockReplaySessionScheduler`가 채운다 — 마이그레이션에 데이터를 포함하지 않는다 (거래일마다 갱신되는 운영 데이터이므로 ADR-0004의 "기준 데이터"와 다름).
- 20영업일 초과 데이터 삭제는 `StockCandleCleanupJob`(스케줄 배치)이 담당하며, `stock_replay_sessions.source_trading_date`(재생 중인 거래일)는 삭제 대상에서 제외한다. `stock_replay_sessions`·`market_data_imports` 자체의 보관 기간은 별도 정책이 없다 (운영 이력이라 분봉과 달리 삭제 압박이 크지 않음 — 필요해지면 후속 결정).
- 20영업일 규모(16종 × 390개 × 20일 ≈ 12.5만 행)는 MySQL 기본 설정으로 충분해 파티셔닝 등 별도 저장 전략은 도입하지 않는다.

## 테스트 계획

- 단위:
  - `KrxFileImporter` (정상 파일 저장, 전체 파일 오류 시 미저장·FAILED 기록, 특정 종목만 구조 오류 시 그 종목만 미저장·나머지 저장·PARTIAL_SUCCESS 기록, 재실행 멱등, `StockReplaySession`을 직접 쓰지 않음)
  - `KrxFileImporter` 재수집 정책 (최초 정상→SUCCESS, 최초 일부 오류→PARTIAL_SUCCESS, 최초 전체 오류→FAILED, 동일 날짜·동일 해시→SKIPPED_DUPLICATE·Candle 변화 없음, 동일 날짜·다른 해시→FAILED/SOURCE_DATE_HASH_CONFLICT·Candle·세션 변화 없음, FAILED 이력만 있는 거래일은 재시도 허용, 서로 다른 파일의 Candle이 섞이지 않음)
  - `StockReplaySessionScheduler` (`PREPARING`→`READY`, `PREPARING`→`FAILED`, `StockCandle`을 직접 쓰지 않음, Clock으로 실행시점 제어, 상태별 nullable 규칙 검증 — `PREPARING`+`resolved_at` 존재·`PREPARING`+`failure_reason` 존재·`READY`+`source_trading_date` 없음·`READY`+`resolved_at` 없음·`READY`+`failure_reason` 존재는 거부, `FAILED`+`resolved_at` 없음·`FAILED`+`failure_reason` 없음은 거부, `FAILED`+`source_trading_date` NULL과 값 존재는 둘 다 허용)
  - `StockReplayService` (`READY` 상태에서 Clock에 따른 OPEN·CLOSED 계산, `preparation_status != READY`면 이용 불가, 첫 분봉 시가·이후 마감 종가, 재생세션 DB를 쓰지 않음)
  - `StockCandleCleanupJob` (20영업일 경계·재생 중 거래일 보존)
  - `PriceStore` 과거 틱 무시, `PriceQueryService` 유효성 판정 (stale 10초·끊김·장외)
  - SSE 컨트롤러: 토큰 없음/잘못된 토큰 시 401, price 이벤트에만 id 존재(snapshot·status는 id 없음), snapshot에 주식 16종·코인 12종 전체 포함(가격 없는 종목도 포함), 가격 없는 종목은 price·sourceTime이 null이고 status는 UNAVAILABLE, 가격 변경 시 price 이벤트, 시장·연결상태 변경 시 status 이벤트, `sourceTime`과 `emittedAt` 구분, 주식은 `sourceTradingDate` 포함·코인은 미포함, 장 마감 후 marketStatus=CLOSED이면서 마지막 유효가격 유지(장 마감과 가격 없음 구분), 코인 stale 시 marketStatus는 OPEN 유지·종목 status만 UNAVAILABLE, `retry: 3000` 전달, heartbeat 전송, 연결 종료 시 emitter 제거, 재접속 시 snapshot 재전송, 누락 이벤트 전체 재전송 안 함
- 슬라이스: `@DataJpaTest` — 종목 시드·UNIQUE(symbol), `stock_candles`/`stock_replay_sessions` UNIQUE 제약. `@WebMvcTest` — 목록·가격·캔들 계약, 404·400·409 매핑.
- 통합 (Testcontainers MySQL+Redis): 샘플 KRX 파일 수집→`StockReplaySessionScheduler`가 세션 READY로 전환→재생→가격 조회(첫 분봉 시가, 이후 종가), 서버 재시작 시나리오에서 같은 원본 거래일 유지, DB에 OPEN·CLOSED가 저장되지 않음을 확인, 동일 거래일에 다른 file_hash 재수집을 거부해도 기존 READY 세션·StockCandle이 그대로인 시나리오, Fake Feed 정상 수신→가격 조회, 끊김→PRICE_UNAVAILABLE, 재연결 새 틱→복귀 시나리오.
