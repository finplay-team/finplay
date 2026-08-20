# Plan: 튜토리얼 전용 샘플 종목·항시 시세·매도 단계·5분 제한

## 관련 문서

- Spec: `./spec.md`
- 상속 정본(변경하지 않음): `docs/specs/026-market-order-practice-tutorial`(2단계 chain 해석, 참조 가격선,
  evidence A/B 판정, 불변 완료 원칙 — 이 spec은 이 문서의 계약을 상속하고 4단계로만 확장한다),
  `docs/specs/030-coin-practice-price-runtime`(코인 가상 가격 세션, 병행 — 건드리지 않음)
- 참고: `docs/specs/019-exit-price-policy`(PRICE/PERCENT 계산, 변경 없음), `docs/adr/0012-tutorial-state-in-memory.md`
- 관련 ADR: ADR-0002(레이어드), ADR-0003(테스트 전략), ADR-0004(migration 정책), ADR-0012(즐겨찾기·의도 인메모리)
- 기존 API(변경 없이 재사용): `GET /api/instruments`, `POST /api/orders`(BUY·SELL 시장가), `POST /api/education/practice/intentions`

**새 ADR은 만들지 않는다.** 이 spec이 건드리는 것은 (1) 특정 종목 부분집합에 한정된 가격 계산 분기
(`PriceQueryService` 내부 조건 분기, 기존 아키텍처 경계 안에서 완결됨, ADR-0002 위반 없음), (2) 기존
DTO·엔드포인트의 필드·단계 수 확장(스키마·계약 변경이지 아키텍처 결정이 아님)이다. "실거래와 다른 시세
규칙을 허용하는 것이 아키텍처 결정인지" 검토했으나, 대상이 `is_tutorial_sample=true`인 신설 종목으로
완전히 격리되고 실거래 종목·경로는 단 한 줄도 분기를 타지 않으므로 ADR-0002가 이미 규정한 "레이어드
경계 안에서의 조건 분기"에 해당한다. 새 번호 ADR로 대체할 기존 결정도 없다.

## 도메인 경계

- 샘플 종목 플래그·시드 데이터·가격 계산 분기는 `market` 도메인이 소유한다(기존 `Instrument`,
  `PriceQueryService`가 있는 도메인과 동일 — 새 도메인을 만들지 않는다).
- 4단계(매도·복기) evidence 판정은 `education.marketpractice` 하위(`026`이 이미 쓰는 패키지)에 그대로
  둔다. `education`은 매도 체결 조회를 위해 `order` 도메인의 기존 조회 전용 서비스(`TradeService`류)만
  거치고 `OrderRepository`를 직접 주입하지 않는다(`026`과 동일 원칙).
- `order` 도메인은 이 spec으로 인해 어떤 코드도 바뀌지 않는다 — `POST /api/orders`(SELL)는 그대로다.
  샘플 종목이 "항상 매매 가능"해지는 것은 `order`가 호출하는 `PriceQueryService.getOrderExecutionPrice`가
  샘플 종목에서 이미 항상 AVAILABLE·OPEN을 반환하기 때문이며, `OrderService`·`OrderExecutionService`는
  이 종목이 샘플인지 전혀 알 필요가 없다.

## 1. 샘플 종목 데이터 모델 — 결정과 근거

**결정: 기존 `instruments` 테이블에 `is_tutorial_sample BOOLEAN NOT NULL DEFAULT FALSE` 컬럼을 추가한다
(별도 테이블을 만들지 않는다).**

근거:
- `Instrument`는 이미 `tradable`이라는 불변 성격의 boolean 플래그로 "같은 테이블 안에서 행마다 다르게
  동작하는 성질"을 표현한다(`InstrumentService.getTradableInstrumentEntity`가 이미 이 플래그로 분기).
  `is_tutorial_sample`도 같은 성격의 플래그이므로 기존 관례를 그대로 따르는 것이 자연스럽다.
- `GET /api/instruments`, `GET /api/instruments/{id}`, `GET /api/instruments/{id}/price`는 모두 `Instrument`
  엔티티 하나를 조회해 응답을 만든다(`InstrumentController`). 별도 테이블(`tutorial_sample_instruments`
  등)을 두면 이 3개 엔드포인트 모두가 항상 LEFT JOIN 또는 2차 조회를 해야 하고, `InstrumentRepository`의
  기존 메서드(`findByMarketOrderByIdAsc`, `findByMarketAndTradableTrueOrderByIdAsc`)를 전부 새로 만들어야
  한다. 컬럼 하나 추가로 기존 메서드·서비스가 전혀 바뀌지 않고 그대로 동작한다.
- `symbol`·`name`·`market`처럼 샘플 종목도 결국 "종목"의 모든 속성(호가단위, 최소주문금액)이 필요하다.
  별도 테이블은 이 컬럼들을 다시 정의하거나 `instruments`와 조인해야 하므로 중복이다.
- 대안으로 검토한 "market·symbol 명명 규칙만으로(`SANDBOX_` 접두사) 판별"은 문자열 파싱에 의존해
  실수로 실제 종목 심볼이 그 접두사와 충돌하거나, 나중에 실제 종목 명명 규칙이 바뀌면 조용히
  깨질 수 있어 채택하지 않는다. 명시적 컬럼이 유일한 안전한 판별 근거다.

### 시드 데이터

`V32__add_tutorial_sample_instruments.sql`(정확한 번호는 착수 시 `origin/dev` 최신 `V{N}` 재확인 필수 —
이 문서 작성 시점 최신은 `V31__change_post_comments_parent_fk_to_restrict.sql`)에서:

1. `instruments` 테이블에 `is_tutorial_sample BOOLEAN NOT NULL DEFAULT FALSE` 컬럼을 추가한다(기존 16종
  주식·12종 코인은 전부 `FALSE`로 남는다 — 백필 불필요, `DEFAULT FALSE`가 처리).
2. 다음 6행을 삽입한다(기존 `V7` 시드 데이터 형식을 그대로 따른다):

```sql
ALTER TABLE instruments ADD COLUMN is_tutorial_sample BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO instruments (market, symbol, name, tick_size, min_order_amount, tradable, is_tutorial_sample, created_at) VALUES
('STOCK', 'SANDBOX_STK_1', '연습용 주식 A', 100, 10000, TRUE,  TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', 'SANDBOX_STK_2', '연습용 주식 B', 100, 10000, FALSE, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', 'SANDBOX_STK_3', '연습용 주식 C', 100, 10000, FALSE, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'SANDBOX_COIN_1', '연습용 코인 A', 1, 5000, TRUE,  TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'SANDBOX_COIN_2', '연습용 코인 B', 1, 5000, FALSE, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'SANDBOX_COIN_3', '연습용 코인 C', 1, 5000, FALSE, TRUE, CURRENT_TIMESTAMP(6));
```

- **이름 정정(V33, 이슈 #339 후속)**: 위 V32의 `연습용 주식 A/B/C`·`연습용 코인 A/B/C`는 프론트가 이름
  옆에 이미 "연습용" 배지를 붙이므로 이름 자체에 반복돼 어색했다. `V33__rename_tutorial_sample_instruments.sql`
  이 `알파전자`·`베타바이오`·`감마에너지`(주식), `알파코인`·`베타코인`·`감마코인`(코인)으로 UPDATE한다.
  머지된 V32는 수정하지 않는다(ADR-0004). `symbol`(SANDBOX_*)은 변경하지 않는다.
- 심볼은 `uk_instruments_symbol` 유니크 제약과 충돌하지 않도록 실제 심볼(종목코드·티커)과 겹치지 않는
  접두사(`SANDBOX_`)를 쓴다. `tradable=false`인 2개는 상세 시세·틱사이즈가 실제로 쓰이지 않으므로
  `tradable=true` 종목과 같은 값을 그대로 둬도 무방하다(spec의 "내부 시세 등 상세는 채우지 않아도 됨").
- `InstrumentResponse`(`GET /api/instruments` 응답 DTO)에 `isTutorialSample` 필드를 추가한다 — 프론트엔드가
  실제 종목과 샘플 종목을 구분해 배지·안내를 표시할 근거가 필요하기 때문이다(추가 전용 필드,
  기존 소비자에게 breaking change 없음).

## 2. 항시 시세·장시간 우회 — 구현 위치와 근거

**결정(리뷰 차단사항 반영, 정정): `PriceQueryService`의 `getPriceQuote(Instrument)`·
`getOrderExecutionPrice(Instrument)`뿐 아니라 배치 조회 `getPriceQuotes(List<Instrument>)`에도 각각
`instrument.isTutorialSample()` 분기를 추가하고, 새 클래스 `TutorialSampleInstrumentPriceService`(패키지
`com.finplay.api.market.service`)에 위임한다. `stockPriceProvider`·`priceStore`는 호출하지 않는다.**

- **리뷰에서 확인된 문제(차단 사항)**: `getPriceQuotes(List<Instrument>)`(69~82행)는 `getPriceQuote(Instrument)`를
  재사용하지 않고 자체 private 메서드(`getStockPriceQuotes`/`getCryptoPriceQuotes`)로 바로 위임한다.
  `getPriceQuote(Instrument)` 하나만 패치하면 이 배치 경로가 그대로 남아, `HoldingValuationService.
  evaluateHoldings`(보유 종목 목록·계좌 요약 화면)가 샘플 종목 holding에 대해 여전히
  `PriceStatus.UNAVAILABLE`을 받는다 — SANDBOX-002를 위반한다.
- **수정**: `getPriceQuotes`도 진입 시 `instruments`를 `isTutorialSample()` 여부로 분할한다. 샘플 종목은
  `TutorialSampleInstrumentPriceService`로 개별 처리하고, 나머지(실제 종목)만 기존
  `getStockPriceQuotes`/`getCryptoPriceQuotes`에 넘긴 뒤 두 결과를 **원래 `instruments` 순서대로 병합**해
  반환한다(69행 주석이 "반환 순서는 instruments 순서와 일치"를 이미 계약으로 명시하고 있으므로 병합 시
  순서 보존이 필수). market 혼재 방어 검사(77~80행)는 실제 종목 부분집합에만 적용한다(샘플 종목은
  market이 STOCK/CRYPTO 어느 쪽이든 이 분기에서 먼저 빠지므로 그 검사와 무관하다).

### 왜 여기인가

- `PriceQueryService`는 "주문·화면·평가손익이 공통으로 소비하는 유효 가격" 조회의 진입점이다(파일 헤더
  주석, `getPrice`/`getOrderExecutionPrice`/`getPriceQuote`/`getPriceQuotes` 전부 이 클래스에 있다).
  다만 위에서 확인했듯 이 네 메서드가 서로를 재사용하는 관계가 아니라 각자 자체 구현을 갖고 있어,
  "한 곳만 고치면 전부 적용된다"는 최초 판단은 틀렸다 — **네 개 메서드 각각**에 분기가 필요하다.
  `OrderService`·SSE·평가손익·`026`·`030`이 이 네 메서드 중 하나를 호출하므로, 넷 모두 패치해야 모든
  소비 경로에 적용된다. `getPrice`는 내부적으로 `getPriceQuote(Long)` → `getPriceQuote(Instrument)`를
  거치므로 별도 분기가 필요 없다(체인 확인 완료).
- `getOrderExecutionPrice`의 STOCK 분기는 `stockPriceProvider.getCurrentPrice()`를 호출해 `StockMarketStatus`,
  재생세션(`StockReplaySession`) 존재를 함께 확인한다(현재 코드 39~44행). 이 경로를 그대로 타면 샘플
  종목도 실제 `StockReplaySession`·`StockCandle` 데이터가 있어야 하므로(`StockReplayService`가 이 없이는
  항상 `CLOSED`/빈 결과를 반환) 결국 샘플 종목용 재생세션·분봉을 실제처럼 시딩해야 하는 문제로
  돌아간다. 샘플 종목은 애초에 "실제 시세 인프라가 전혀 필요 없어야 한다"는 요구사항(SANDBOX-002·003)
  이므로, `stockPriceProvider`를 호출하기 **전에** 분기해 그 인프라 자체를 우회한다.
- 코인도 마찬가지로 `priceStore`(Redis, 빗썸 피드)를 호출하지 않아야 "피드가 비어 있어도" 항상 성립한다.
- `LocalForcedOpenStockPriceProvider`(기존 local 프로필 전용 데코레이터)가 보여주는 패턴과 목적은
  다르다 — 그 클래스는 "실제 데이터가 있는데 장시간만 강제로 열기"이고, 이 spec은 "실제 데이터 자체가
  없어도 되는 별도 종목"이다. 그 클래스를 확장하거나 재사용하지 않는다(local 프로필에만 존재하고,
  이 spec은 모든 프로필에서 항상 동작해야 한다).

### 가격 알고리즘 (결정적, 저장 상태 없음)

```
basePrice = 50,000.00000000 (STOCK 샘플)  |  10,000.00000000 (CRYPTO 샘플)
amplitude = 0.03  (±3%)
periodSeconds = 180  (3분 주기)
phase = (instrument.getId() % 7) * (π / 7)

epochSeconds = Instant.now(clock).getEpochSecond()
rate = amplitude * sin(2π * epochSeconds / periodSeconds + phase)
price = (basePrice * (1 + rate)) 을 scale 8 HALF_UP으로 반올림
```

- `Clock` 빈(기존 `StockReplayService`·`PracticePriceSessionService`가 이미 주입받는 것과 동일 빈)을
  그대로 주입한다 — 새 시계 추상화를 만들지 않는다.
- 완전히 고정된 가격(변동 없음) 대신 이 공식을 쓰는 이유: `026`의 3단계 evidence A(경계 접근, 매수
  체결가 대비 손절/익절선 방향으로 가격이 가까워졌는지)는 가격이 실제로 움직여야만 성립한다. 고정
  가격이면 evidence A가 샘플 종목에서 영원히 도달 불가능해지고 evidence B(시간 분산 관찰 3회, `026`과
  동일)만 남는다 — MKT-PRACTICE-006이 "A 또는 B"를 요구하므로 기능적으로는 여전히 완료 가능하지만,
  두 경로 중 하나가 구조적으로 막히는 것은 불필요한 제약이다. 이 공식은 DB 저장이나 세션 상태 없이
  순수하게 `(instrument, now)`만으로 계산되므로 재기동·다중 인스턴스에도 항상 같은 값을 재현한다.
- `030`의 `PracticePriceGeneratorV1`(seed·tick 기반 결정적 생성기)을 재사용하지 않는다 — 그건 "세션이
  진행시키는 이산적 tick"이 전제이고, 이 spec은 "언제 조회해도 즉시 답이 나오는 연속 함수"가 필요하다.
  서로 다른 문제라 알고리즘을 공유하면 오히려 결합이 생긴다.
- `getPriceQuote`는 `PriceStatus.AVAILABLE`을 항상 반환(절대 `UNAVAILABLE` 없음, SANDBOX-002).
  `getOrderExecutionPrice`는 STOCK이든 CRYPTO든 이 가격을 그대로 쓰고 `StockMarketStatus`·재생세션
  검사를 완전히 건너뛰므로 `MARKET_CLOSED`가 나지 않는다(SANDBOX-003). `replaySession` 필드는 STOCK
  주문 체결 시 `trades.stock_replay_session_id`에 쓰이는데, 샘플 종목은 재생세션이 없으므로 `null`을
  반환한다 — `OrderExecutionPriceDto(price, null)` 형태이며, `026`의 상태 헤더가 이미 "코인은 null"이라고
  기록한 nullable FK 규칙과 같은 모양이다(주식도 이 한 가지 경우에서는 null이 된다. 기존 실제 주식
  체결의 "STOCK ⇒ non-null" 불변식은 **실제 종목에만** 성립하도록 범위를 명확히 한다 — 이 nullable
  컬럼은 이미 nullable로 정의돼 있으므로 스키마 변경은 필요 없다).
- **버그 발견·수정(이슈 #339, tasks.md 6번 통합 테스트 작성 중 발견)**: 위 정정이 `PriceQueryService`에는
  반영됐지만 `Trade.validateStockReplaySession`(엔티티 불변식 검사)에는 반영되지 않아, STOCK 샘플 종목
  매수/매도가 전부 `IllegalArgumentException("주식 체결에는 재생세션이 필수입니다.")`으로 실패했다.
  `instrument.isTutorialSample()`이면 STOCK이어도 `stockReplaySession == null`을 허용하도록 조건을 좁혀
  수정했다 — 실제 종목(`isTutorialSample() == false`)의 기존 동작(`026`)은 그대로 유지된다.

## 3. 매도 단계 API 설계

**결정: 신규 매도 엔드포인트를 만들지 않고 기존 `POST /api/orders`(`side=SELL`, `orderType=MARKET`)를
그대로 재사용한다.**

- 위 2번 결정 덕분에 샘플 종목은 이미 항상 `PriceQueryService.getOrderExecutionPrice`에서 AVAILABLE·OPEN을
  반환하므로, `OrderService`·`OrderExecutionService`는 어떤 코드 변경도 없이 샘플 종목의 매도를 정상
  체결한다. `026`이 매수에 대해 이미 확립한 "기존 거래 API를 그대로 쓰고 튜토리얼은 그 결과만 읽는다"
  원칙을 매도에도 그대로 연장한다.
- 서버가 "이 chain의 매도"를 식별하는 방법은 매수를 식별하던 방법(`026` plan.md "2단계 chain 해석")과
  대칭이다: `MarketPracticeChainResolutionService`에 매도 조회를 추가한다 — 같은 사용자·같은
  `instrumentId`의 `FILLED` **SELL** `trade` 중 `executedAt > buyTrade.executedAt`인 것을
  `executedAt ASC, tradeId ASC`로 하나 선택한다. `TradeService`에 기존
  `findEarliestFilledBuyTradeMatching`과 대칭되는 `findEarliestFilledSellTradeAfter(userId, instrumentId,
  after)` 조회 메서드를 추가한다(수량 일치는 요구하지 않는다 — holding 전량이 아니라 일부만 팔아도
  실습상 "매도를 실행해봤다"는 사실은 성립한다).
- `ResolvedPracticeChainDto`(현재 `favoriteId`~`holdingId` 10필드)에 `sellTradeId`, `sellTradeExecutedAt`
  2필드를 추가한다.

### `GET /api/education/practice` 4단계 응답 (샘플 종목 chain 한정)

**오케스트레이터 결정(사용자 확인, 최초 계획 대비 정정): 4단계 확장은 해석된 chain의 종목이
샘플 종목(`is_tutorial_sample=true`)일 때만 적용된다.** 실제 종목 chain은 `026`의 3단계 응답을
그대로 유지한다 — 이미 진행 중이거나 매수까지 마친 실제 사용자에게 새로 매도 단계를 강제하지 않는다.

`InvestmentPracticeQueryService`가 chain 해석 후 `resolvedInstrument.isTutorialSample()`을 확인해
`PracticeStepResponse` 배열을 3개(실제 종목) 또는 4개(샘플 종목)로 만든다. 프론트엔드는 배열 길이로
분기해야 한다(이 트레이드오프는 "샘플 종목 전용" 요구사항을 만족시키기 위해 받아들인다 — 최초 계획이
우려했던 가변 배열 길이 문제가 실제로 발생하지만, 기존 사용자의 완료 경로를 바꾸지 않는 것이 더 중요한
제약이다).

| step | 이름 | 적용 대상 | evidence 필요 조건 | 완료 조건 |
|---|---|---|---|---|
| 1 | 즐겨찾기 | 공통 | favorite 존재 | `026`과 동일 |
| 2 | 의도 기록·매수 | 공통 | intention + buyTrade + holding, 수량 2자 비교 | `026`과 동일 |
| 3 | 견디기/관찰 | 공통 | evidence A(경계 접근) 또는 B(시간 분산 3회) | `026`과 동일 |
| 4 | 매도·복기(신규) | **샘플 종목 chain만** | (a) `buyTrade.executedAt` + 5분 이내 SELL FILLED trade, (b) 자유 복기 저장 | (a)·(b) 모두 있어야 COMPLETED. (a)만 있으면 IN_PROGRESS(복기 대기). 5분 초과 시 EXPIRED |

실제 종목 chain은 여전히 3단계에서 완료되며(evidence A/B + 자유 복기, `026`과 완전히 동일), 매도·5분
제한과 무관하다.

- 새 step 상태 값 `EXPIRED`를 `InvestmentPracticeQueryService`의 상태 상수에 추가한다(샘플 종목 chain
  에서만 등장, 기존 `COMPLETED`/`IN_PROGRESS`/`NOT_STARTED`에 추가, DTO의 `status`는 여전히 `String`이라
  스키마 변경 없음).
- `PracticeEvidenceResponse`(현재 14필드)에 `Long sellTradeId`, `LocalDateTime sellTradeExecutedAt`,
  `LocalDateTime saleDeadlineAt`(= `buyTradeExecutedAt + 5분`, 3필드를 추가한다. **실제 종목 chain의
  step 3(마지막 단계)까지는 이 필드들이 항상 `null`이다** — 4단계 자체가 없으므로. 샘플 종목 chain의
  4단계에서만 `buyTrade`가 있으면 `saleDeadlineAt`이 채워진다(프론트엔드 카운트다운용). `empty()`·
  `favoriteOnly()` 정적 팩토리는 새 필드를 모두 `null`로 채운다.

### `POST /api/education/practice/holding-reflections` 전제조건 변경 (샘플 종목 chain 한정)

**샘플 종목 chain에 대해서만** 기존 전제(A 또는 B 관찰 존재)에 추가로 **유효한 매도 체결**을 요구한다.
실제 종목 chain은 `026`의 기존 전제조건(evidence A/B만, 매도 무관)을 그대로 유지한다.

| 조건 | 응답 |
|---|---|
| evidence A/B 없음 | 409 `PRACTICE_EVIDENCE_MISSING`(변경 없음) |
| A/B는 있으나 매도 체결 없음, 5분 이내 | 409 `PRACTICE_EVIDENCE_MISSING`(신규 원인, 같은 코드 재사용) |
| A/B는 있으나 매도 체결 없음, 5분 초과 | 409 `PRACTICE_SANDBOX_TIME_EXPIRED`(신규 코드) |
| 매도 체결이 있으나 `executedAt`이 `buyTrade.executedAt + 5분` 초과 | 409 `PRACTICE_SANDBOX_TIME_EXPIRED` |
| 위 전부 충족 | 201(변경 없음, 응답에 `sellTradeId` 등 신규 필드는 없음 — `PracticeHoldingReflectionResponse`는
  reflection 자체의 필드만 가지므로 변경 불필요, 매도 정보는 `GET /api/education/practice`에서만 노출) |

새 `ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED(HttpStatus.CONFLICT, "실습 매수 후 5분이 지나 이 시도는
만료됐습니다. 다시 매수해 주세요.")`를 추가한다.

### 재도전을 위한 buyTrade 선택 정정 (이슈 #339 tasks.md 6번 진행 중 발견)

`MarketPracticeChainResolutionService.resolveForFavorite`가 항상 `TradeService.
findEarliestFilledBuyTradeMatching`(가장 이른 매수)으로 chain의 buyTrade를 고정하면, 샘플 종목 chain이 한 번
5분 만료된 뒤 같은 종목을 다시 매수해도 anchor가 최초의 만료된 매수에 고정돼 재도전이 불가능하다(SANDBOX-007
위반). **샘플 종목 chain에 한정해서만** 신규 `TradeService.findLatestFilledBuyTradeMatching`(같은 조회·정렬,
마지막 매칭 항목 선택)을 쓰도록 수정한다. `MarketPracticeChainResolutionService`가 `InstrumentService`를
주입받아 `favorite.instrumentId()`의 `isTutorialSample()`로 분기한다. 실제 종목 chain은 `026`의 anti-gaming
근거(가장 이른 체결 고정, `TradeServiceTest.
findEarliestFilledBuyTradeMatchingPicksFirstQuantityMatchInRepositoryOrder`가 고정한 계약)를 그대로 유지한다.

## 4. 5분 타이머 — 서버 강제 여부와 anchor

**결정: 서버가 강제하지만, 강제 지점은 "매도 주문 접수"가 아니라 "그 매도를 evidence로 인정하는
판정"(`MarketPracticeChainResolutionService`의 매도 조회, `PracticeHoldingReflectionService`의 전제조건
검사)이다.**

- **anchor는 `buyTrade.executedAt`이다.** `026`이 이미 3단계 참조 가격선의 기준점으로 `buyTrade`를
  선택한 것과 같은 논리(불변 원장에 이미 있는 사실만 사용, 별도 snapshot 불필요)를 그대로 연장한다.
  `intention.createdAt`은 매수 전 시점이라 "매수~매도 실행 시간"을 재는 이 요구사항의 anchor로 맞지
  않는다.
- **매도 주문 자체(`POST /api/orders`)는 거부하지 않는다.** 이 주문은 사용자의 실제 계좌(현금)·holding
  (수량)을 실제로 움직이는 거래다. 5분이 지났다는 이유로 실제 매도를 막으면 사용자가 원하는 시점에
  자산을 처분할 수 없게 되는데, 이는 튜토리얼 판정 로직의 책임 범위를 벗어난다 — `order` 도메인이
  "이 매도가 어떤 튜토리얼의 몇 번째 chain에 속하는지" 알 필요가 없다는 `026`의 도메인 경계 원칙과도
  일치한다. 대신 서버는 "그 매도가 4단계 evidence로 인정되는지"만 판정한다.
- **트레이드오프**: 클라이언트 안내만으로 충분한 대안(서버가 전혀 강제하지 않고 프론트엔드가 카운트다운
  UI만 보여줌)도 검토했으나, 서버가 유일한 신뢰 가능한 완료 판정자여야 한다는 `026` MKT-PRACTICE-009
  ("완료 판정은 클라이언트 완료 주장을 받지 않고 서버가 실제 도메인 증거를 연결해 계산")를 그대로
  따르려면 5분 제한도 서버가 검증해야 한다. 클라이언트 타이머만 믿으면 사용자가 6분 뒤 매도해도
  클라이언트를 조작해 복기·완료를 그냥 요청할 수 있다.
- 만료 판정은 매 요청 시점에 재계산한다(snapshot 저장 없음, `026`의 참조 가격선과 같은 원칙) — 별도
  스케줄러·배치로 만료를 미리 확정해두지 않는다. `practice_completions` 행이 이미 있으면(완료 후)
  만료 여부를 다시 검사하지 않는다(`026`의 재시작 유실·완료 불변 원칙과 동일선상).

## 5. 026·030과의 관계

- **026을 대체하지 않고 확장한다.** `026`의 spec.md·plan.md는 수정하지 않는다(과거 완료 기록 보존).
  구현 착수 시 `026/spec.md` 상단에 "상태" 헤더로 "이 spec의 4단계 확장은 `031`이 정본"이라는 1줄
  참조만 추가한다(이 문서 자체가 이미 그런 상호 참조 관례를 따르고 있다 — `030`이 `026`을 참조하는
  방식과 동일).
  - **왜 대체가 아니라 확장인가**: `026`이 이미 만든 2단계 chain 해석·참조 가격선·evidence A/B 판정·
    완료 불변 원칙은 이 spec의 요구사항과 전혀 충돌하지 않는다. 바뀌는 것은 응답 DTO의 단계 개수와
    4단계의 evidence 조건뿐이며, 이는 "새 기능을 얹는다"보다는 "기존 계약을 한 단계 더 정밀하게
    만든다"에 가깝다.
  - **왜 4단계 확장을 샘플 종목 chain에만 좁히는가(오케스트레이터가 사용자와 확인 후 정정)**: 최초
    안은 "이슈 #339가 조건을 달지 않았다"는 근거로 4단계를 모든 chain에 동일 적용했으나, 사용자가
    직접 확인한 결과 실제 종목으로 이미 진행 중이거나 매수까지 마친 사용자에게 새로 매도를 강제하는
    것은 의도가 아니었다. 그래서 4단계 확장·매도 evidence·5분 만료는 **샘플 종목 chain에만** 적용하고,
    실제 종목 chain은 `026`의 3단계 계약을 그대로 유지한다. 이 결정은 프론트엔드에 배열 길이 분기
    부담을 지우지만(위 최초 우려가 실제로 발생), 기존 사용자의 완료 경로를 바꾸지 않는 제약이 우선한다.
  - **ADR 필요 여부**: 위 "관련 문서" 절에서 판단했듯 새 ADR은 필요 없다. 이것은 레이어드 경계
    안에서의 계약(DTO 필드·단계 수) 변경이며 ADR-0002가 규율하는 아키텍처 수준의 결정이 아니다.
- **030과는 병행이다.** `030`의 코인 가상 가격 세션(`practice_price_sessions`, 지정가 BUY,
  next-tick)은 이 spec이 손대지 않는다. 코인 샘플 종목(`SANDBOX_COIN_1`)에 대해 사용자가 `030`의
  지정가 경로를 쓰면, 그 세션의 `PracticePriceSessionService.resolveStartPrice`가
  `priceQueryService.getPriceQuote(instrument)`를 호출해 이 spec의 항시 가용 가격을 anchor로 그대로
  받는다(코드 변경 없이 자연히 성립 — `030`은 이미 `PriceQueryService`를 거치므로 이 spec의 분기를
  투명하게 상속한다). 즉 `030`의 세션은 실제 시세가 없어도 이제는 `10000` 고정 fallback이 아니라
  이 spec의 변동 가격을 anchor로 받게 되며, 이는 `030`의 계약(COIN-PRICE-RUNTIME-003 "없으면 10000을
  사용한다")과 충돌하지 않는다 — 그 규칙은 "실제 유효 현재가가 없을 때"의 fallback이고, 샘플 종목은
  이제 항상 유효 현재가가 있으므로 fallback 분기 자체를 타지 않을 뿐이다.
  세션이 끝난 뒤의 holding 관찰(`030`의 buyTrade→order 세션 역추적)도 변경 없다 — 세션이 있으면 세션
  가격, 없으면 `PriceQueryService` 경로라는 기존 분기가 그대로 유지된다.
- **매수 evidence의 chain 해석(`026`)은 종목이 샘플인지 실제인지 구분하지 않는다.** `intention.instrumentId`
  가 샘플 종목이든 실제 종목이든 같은 코드 경로로 처리된다 — 이것도 이 spec이 "샘플 종목이라는 새
  종류의 종목"만 추가하고 evidence 판정 로직 자체는 건드리지 않는다는 원칙을 보여준다.

## 6. 프론트엔드 영향 요약 (finplay-frontend, 별도 레포)

- **동시 진행 경합 완화(권장, 서버 강제 아님)**: 같은 시장에서 이미 진행 중인 chain(step ≥ 2)이 있으면
  다른 종목(실제·샘플 불문) 즐겨찾기 등록 UI를 숨기거나 경고를 보여준다 — `steps` 배열 길이가 3↔4로
  흔들리는 edge case를 사용자가 애초에 만들지 않도록 UI에서 유도한다(위 "리뷰 권장사항 반영" 참고).
- **종목 선택 UI**: `GET /api/instruments?market=`이 이제 시장별로 실제 종목 목록 + 샘플 종목 3개를
  함께 반환한다. `isTutorialSample=true`인 종목 중 `tradable=false`인 2개는 선택 불가 상태로 표시하되
  숨기지 않는다(spec 요구사항). `tradable=true` 샘플 종목은 "연습용" 배지와 함께 정상 선택 가능하게
  노출한다.
- **4단계 UI (샘플 종목 chain 한정)**: `steps` 배열 길이가 3(실제 종목) 또는 4(샘플 종목)로 달라지므로
  프론트엔드는 길이로 분기해야 한다. 샘플 종목 chain일 때만 기존 3단계 진행 표시를 4단계(즐겨찾기·
  의도매수·견디기관찰·매도복기)로 나눈다. 4단계 카드는 `saleDeadlineAt`을 받아 카운트다운을 표시하고,
  만료(`EXPIRED` 상태 또는 409 `PRACTICE_SANDBOX_TIME_EXPIRED`) 시 "다시 매수해서 재도전하세요" 안내로
  전환한다. 실제 종목 chain은 기존 3단계 UI를 그대로 쓴다(변경 없음).
- **매도 액션**: 4단계 화면에서 기존 매도 폼(이미 일반 매매에 존재하는 `POST /api/orders` SELL 흐름)을
  재사용하도록 안내하면 된다 — 튜토리얼 전용 매도 API가 없으므로 프론트엔드도 별도 API 연동을 새로
  만들 필요가 없다.
- **finplay-frontend#8과의 관계**: #8은 `030`의 코인 가상 가격 세션 매수 UI(지정가, tick 진행)를
  다룬다. 이 spec은 `030`을 바꾸지 않으므로 #8이 이미 구현한 지정가 매수 흐름은 그대로 유효하다.
  다만 #8이 종목 선택을 "코인 지정가 튜토리얼 전용 종목"으로 하드코딩했다면, 이제는 샘플 종목
  목록(`isTutorialSample && tradable`)에서 동적으로 가져오도록 조정이 필요할 수 있다 — 이 spec
  착수 시 #8의 실제 구현 상태를 확인해 별도 이슈로 반영 여부를 정한다(이 문서는 그 조정 자체를
  설계하지 않는다, 확인 필요 항목으로만 남긴다).
- 이 spec 착수 전, 프론트엔드 팀과 `isTutorialSample` 필드명·4단계 응답 스키마·`PRACTICE_SANDBOX_TIME_EXPIRED`
  오류 코드를 확정 공유해야 한다(백엔드가 먼저 계약을 확정한 뒤 #8을 포함한 프론트엔드 작업이
  재작업된다는 이슈 #339의 전제와 일치).

## 데이터 모델 요약

- `instruments.is_tutorial_sample BOOLEAN NOT NULL DEFAULT FALSE` (신규 컬럼, 위 1번).
- 신규 테이블 없음. `026`의 `practice_market_observations`·`practice_market_reflections`·
  `practice_completions`, `030`의 `practice_price_sessions`는 스키마 변경 없이 그대로 재사용한다.
- `ResolvedPracticeChainDto`(Java record, DB 테이블 아님)에 `sellTradeId`·`sellTradeExecutedAt` 2필드
  추가.
- 신규 migration 번호는 착수 시점에 `origin/dev` 최신 `V{N}`을 재확인해 정한다(ADR-0004,
  `docs/specs/README.md` 번호 규칙과 같은 종류의 선점 위험).

## 입력 명세

| 요청 | 필드 | 검증 |
|---|---|---|
| `GET /api/instruments?market=` | 없음(기존과 동일) | 변경 없음. 응답 `InstrumentResponse`에 `isTutorialSample` 필드만 추가 |
| `POST /api/orders`(SELL, 샘플 종목 대상) | 기존과 동일(`instrumentId`, `quantity`, `orderType=MARKET`, `side=SELL`) | 변경 없음. 종목이 `is_tutorial_sample=true`이면 `PriceQueryService` 내부 분기로 가격·장시간 검사만 달라진다 |
| `GET /api/education/practice?market=` | `market` 필수(`026`과 동일) | 변경 없음. 응답 `steps`가 4개, `PracticeEvidenceResponse`에 3필드 추가 |
| `POST /api/education/practice/holding-reflections` | `holdingId`, `answer`(`026`과 동일) | 기존 검증 그대로 + 새 전제조건(매도 evidence, 5분 이내) 검사 추가. 위반 시 409 `PRACTICE_EVIDENCE_MISSING`/`PRACTICE_SANDBOX_TIME_EXPIRED` |

## 테스트 계획

- 단위: 가격 알고리즘의 결정성·범위(±3% 내, scale 8), `is_tutorial_sample` 분기가 `stockPriceProvider`/
  `priceStore`를 호출하지 않는지(Mockito `verifyNoInteractions`), 매도 chain 조회(`executedAt` 순서,
  수량 무관), 5분 만료 판정 경계값(정확히 5분 시점 포함/제외 확정 필요 — 구현 시 `!isAfter` 등으로
  경계를 명시).
- 슬라이스: `GET /api/instruments`가 샘플 종목을 포함해 반환하고 `isTutorialSample` 필드가 정확한지
  (`@WebMvcTest`), `GET /api/education/practice`가 4단계를 반환하는지, `holding-reflections`의 신규
  409 매핑.
- 통합: 샘플 종목으로 즐겨찾기→의도→매수→관찰(A 또는 B)→매도→복기 전체 흐름이 실제 시세 피드가
  전혀 없는 상태(빗썸 poller 미기동, 주식 재생세션 미시딩)에서도 끝까지 성공하는지 — 이것이 이슈
  #339가 재현한 문제의 직접적 회귀 테스트다. 5분 초과 후 매도 시나리오가 409
  `PRACTICE_SANDBOX_TIME_EXPIRED`를 반환하고, 이후 재매수로 새 chain을 만들면 4단계를 다시 시도할 수
  있는지.
- 회귀: 실제 종목(`is_tutorial_sample=false`)의 `PriceQueryService` 동작(`PRICE_UNAVAILABLE`,
  `MARKET_CLOSED` 포함)이 이 spec 이전과 완전히 동일한지. `026`의 기존 통합 테스트(이슈 #313)가
  이 변경 이후에도 그대로 통과하는지(3→4단계 응답 변경에 맞춰 테스트 기대값만 갱신, 판정 로직
  자체는 손대지 않았으므로 실패하면 회귀).

## 후속 확인 필요 (이 spec이 결정하지 않음)

- 샘플 종목의 캔들·SSE 지원 여부와 시점(범위 제외 절 참고) — 프론트엔드가 실습 화면에 차트를
  보여줘야 한다면 후속 이슈로 별도 spec 필요.
- finplay-frontend#8의 실제 구현이 종목을 어떻게 선택하는지 확인 후 조정 범위를 이슈로 분리.
- 가격 알고리즘의 `amplitude`·`periodSeconds` 구체값은 실제 실습 중 evidence A 도달 빈도를 QA하며
  조정할 수 있다 — 이 문서의 값은 시작점이며 불변 계약은 아니다(공식의 구조·결정성만 계약).

## 리뷰 권장사항 반영 (PR #340)

- **동시 진행 시 `steps` 배열 길이 흔들림 위험 문서화**: `GET /api/education/practice?market=`의 chain
  선택은 `026/plan.md`의 우선순위 규칙(qualifying observation 있는 chain 우선, 없으면
  `favorite.createdAt ASC`)을 그대로 쓴다. 한 사용자가 같은 시장에서 실제 종목 favorite과 샘플 종목
  favorite을 동시에 갖고 있으면, 요청마다 어느 chain이 우선순위로 뽑히는지에 따라 `steps` 배열이 3개
  또는 4개로 흔들릴 수 있다 — 이 spec은 이 경합을 새로 막지 않는다(026의 기존 다중 favorite 처리
  방식을 그대로 상속하고, 별도 배제 로직을 추가하지 않는다). 이 흔들림은 **드문 edge case로 받아들이고
  프론트엔드에 명시적으로 알린다**: 튜토리얼 진입 화면에서 이미 하나의 chain이 진행 중(step ≥ 2)이면
  같은 시장의 다른 종목 즐겨찾기 등록 UI를 숨기거나 경고를 보여주는 것을 프론트엔드 권장사항으로
  `plan.md` "6. 프론트엔드 영향 요약"에 추가한다(서버 강제는 하지 않는다 — 026이 이미 다중 favorite
  자체를 금지하지 않으므로 이 spec만 새로 강제할 근거가 약하다).
- **샘플 종목이 다른 `Instrument` 전체 순회 기능에 미치는 영향 점검**: 랭킹(`GET /api/rankings`)은
  거래 이력(실현손익) 기준이라 `Instrument` 카탈로그를 순회하지 않으므로 영향 없음(코드 확인 필요 —
  착수 시 `RankingRebuildService`가 `Instrument`를 직접 조회하는지 재확인). 커뮤니티(COM-004 종목 태그,
  `CommunityPostService.createPost` → `InstrumentService.getTradableInstrumentEntity`)는 `tradable=true`
  종목이면 태그 대상으로 허용하므로, 샘플 종목 중 `tradable=true`인 1번째가 실제 커뮤니티 게시물에
  태그될 수 있다 — 이는 의도치 않은 노출이므로, `getTradableInstrumentEntity`(또는 그 호출부)에
  `!instrument.isTutorialSample()` 조건을 추가해 샘플 종목은 `tradable` 값과 무관하게 커뮤니티 태그
  대상에서 제외하기로 결정한다. 이 결정은 착수 시 실제 코드 재확인 후 구현에 반영한다(이 문서는 결정만
  기록, 구현은 후속).
- **시장별 최초 완료 보상(이슈 #343)**: 샘플 종목 4단계 완료도 `PracticeHoldingReflectionService
  .createReflection`을 그대로 거치므로, 026이 지급하는 시장별 최초 완료 보상 500만원이 이 4단계 완료
  경로에도 동일하게 적용된다. 상세 지급 로직·`practice_completions` unique와의 결합은
  `026-market-order-practice-tutorial/plan.md`가 정본이다.
