# Plan: 코인 체결 집계와 1분봉 Redis 캐싱

## 관련 문서

- Spec: `./spec.md`
- PRD: §4 MKT-010(이 작업의 근거), MKT-003·MKT-004(코인 실시간 시세·시세 장애), MKT-008(코인 캔들 중계), §6 Redis 키 책임, §10 Decision Gate(빗썸 레이트리밋)
- 선행 spec: `../003-market-data/plan.md`(빗썸 피드·REST 캔들 설계), `../013-candle-interval/plan.md`(interval 4종)
- 관련 ADR: ADR-0002(레이어 구조), ADR-0003(테스트 전략), ADR-0004(Flyway — **이번엔 마이그레이션 없음**)

## API 설계

**신규·변경 엔드포인트가 없다.** 기존 `GET /api/instruments/{instrumentId}/candles`의 응답 계약이 그대로 유지되고 데이터 출처만 바뀐다. 그래서 `docs/api-routes.md`는 갱신 대상이 아니고, `docs/api-contracts.md`는 "저장·캐시하지 않는다"는 서술 정정만 필요하다.

| Method | URL | 변경 |
|---|---|---|
| GET | /api/instruments/{instrumentId}/candles | 계약 무변경. `interval=1m` + 코인일 때만 내부 조회 경로가 Redis 우선으로 바뀐다 |

## 구성요소 설계

| 구성요소 | 책임 | 신규/변경 |
|---|---|---|
| `BithumbTransactionMessageParser` | `transaction` 메시지 → 체결 목록(`CryptoTrade` record) 변환. 인스턴스 상태 없는 순수 함수. **파싱은 이 클래스에만 둔다**(`BithumbTickerMessageParser`와 같은 격리 원칙) | 신규 |
| `CryptoCandleStore` | 1분봉의 Redis 저장·조회. **키 문자열 조립을 이 클래스에서만 한다**(`PriceStore`와 같은 원칙). Lua 스크립트 보유 | 신규 |
| `CachedCryptoCandleProvider` | `CryptoCandleProvider` 구현이자 **데코레이터**. Redis를 먼저 보고 없는 구간만 위임 대상(`BithumbRestCandleProvider`)에 넘긴다 | 신규 |
| `BithumbWebSocketFeedClient` | `transaction` 구독 추가. 체결을 `CryptoCandleStore`와 `PriceStore` 양쪽에 전달 | 변경 |
| `CandleQueryService` | **무변경.** `CryptoCandleProvider` 인터페이스만 알기 때문에 데코레이터로 갈아끼워도 모른다 | 무변경 |
| `BithumbRestCandleProvider` | **무변경.** 데코레이터의 위임 대상이 된다 | 무변경 |

### 빈 배선 (프로필)

현재 `CryptoCandleProvider` 구현은 프로필로 정확히 하나만 뜬다 — `BithumbRestCandleProvider`(`@Profile({"prod","crypto-real"})`), `FakeCryptoCandleProvider`(`@Profile("!prod & !crypto-real")`). 이 상호배타를 깨지 않는다.

- `CachedCryptoCandleProvider`는 `@Profile({"prod","crypto-real"})` + `@Primary`로 등록하고, 생성자로 `BithumbRestCandleProvider`를 주입받아 감싼다.
- `BithumbRestCandleProvider`는 더 이상 `@Primary`가 아닌 일반 빈이 되므로 `@Component`를 유지하되 주입 시 타입이 아니라 **구체 클래스로 명시**해 순환·모호성을 피한다.
- Fake 프로필(기본 로컬·테스트)에서는 캐싱 계층이 뜨지 않는다 — 기존 동작 그대로다. 캐싱 계층 테스트는 명시적으로 빈을 조립해 수행한다.

## Redis 설계

### 키 구조

| 키 | 타입 | 값 | TTL |
|---|---|---|---|
| `candle:crypto:{symbol}:1m:{epochMinute}` | Hash | `open`·`high`·`low`·`close`(원본 문자열), `volumeScaled`(정수 문자열) | 4시간 |
| `candle:crypto:{symbol}:1m:since` | String | 이 심볼의 집계가 연속적으로 유효한 **시작 분**(epochMinute) | 4시간 |

- **기존 키와 별개다** — `price:crypto:{symbol}`(최신가 Hash)·`price:crypto:{symbol}:snapshots`(분당 샘플 ZSET, FEED-005·006)와 목적·구조가 다르다. 셋 다 유지한다.
- `epochMinute` = `epochSecond / 60`. 시각 변환은 `PriceStore`와 같이 `clock.getZone()`(Asia/Seoul) 기준으로 일관되게 한다.
- **ZSET 인덱스를 두지 않는다.** 조회 요청은 항상 `[from, to]` 범위가 정해져 있고 상한이 200봉이므로, 필요한 분 키 목록을 계산할 수 있다. 인덱스를 따로 유지하면 본체와 어긋날 여지만 생긴다.

### 왜 TTL이 4시간인가 (임의 숫자가 아니다)

응답 상한이 **200봉 = 200분(3시간 20분)**이다. 그보다 오래된 구간은 어차피 응답에 담기지 않으므로 보관할 이유가 없다. 여기에 경계 여유를 더해 **4시간**으로 둔다 — 상한에서 유도한 값이다. PRD §10의 "관측 전에 임의의 TTL 숫자를 넣지 않는다"는 게이트를 지키기 위해, **응답 계약에서 역산한 값만 쓰고 성능 추측으로 정하지 않는다.**

메모리 사용량도 확인해두면 — 12종목 × 240분 = 최대 2,880개 Hash. 무시할 수준이다.

### 거래량은 정수로 누적한다 (정밀도)

**문제**: 거래량은 봉 안에서 계속 **더해야** 하는 값이다. Redis의 `HINCRBYFLOAT`나 Lua의 `tonumber()`는 부동소수라 반복 덧셈에서 오차가 쌓인다. 이 프로젝트는 코인 수량을 `BigDecimal`로 다루고 응답에서 소수를 잘라내지 않기로 명시했다(`CandleResponse.volume`을 `long`→`BigDecimal`로 넓힌 이유, 013).

**결정**: 코인 수량의 소수 자릿수는 **8자리**로 이미 프로젝트 전반에 고정돼 있다(`OrderExecutionService`·`LimitOrderCreationService`·`LimitOrderModifyService` 세 곳에서 `quantity.stripTrailingZeros().scale() > 8`이면 거부). 이 관례를 그대로 따라 **`contQty × 10^8`을 `long`으로 변환해 `HINCRBY`(정수 연산)로 누적**하고, 조회 시 `10^8`로 나눠 `BigDecimal`로 되돌린다. 정수 덧셈이라 오차가 없고 원자적이다.

- `contQty`의 소수 자릿수가 8을 넘으면 그 체결은 **집계하지 않고 로그를 남긴다.** 조용히 반올림해 거래량을 왜곡하지 않는다. (관례상 넘지 않아야 하지만, 외부 데이터이므로 방어한다.)
- `long` 넘침 검토: `Long.MAX_VALUE / 10^8 ≈ 9.2 × 10^10`. 1분 안에 한 심볼의 거래량이 920억 개를 넘을 일은 없다.

**가격(open·high·low·close)은 스케일링하지 않는다.** 가격은 더하지 않고 비교·치환만 하므로 오차가 누적되지 않는다. 원본 문자열을 그대로 저장하고, Lua 안에서 고저 비교할 때만 `tonumber()`를 쓴다. 실제 시세 자릿수는 double(약 15~16 유효자리)로 비교해도 대소가 뒤집히지 않는다.

### 원자적 갱신 (Lua)

체결 하나를 반영하려면 `high`는 더 크면 갱신, `low`는 더 작으면 갱신, `close`는 항상 갱신, `volumeScaled`는 더하기 — **읽고-비교하고-쓰는 사이에 다른 갱신이 끼어들면 값이 유실된다.** Redis에서 Lua 스크립트는 통째로 원자 실행되므로 이걸로 묶는다.

```
KEYS[1] = candle:crypto:{symbol}:1m:{epochMinute}
ARGV[1] = price (문자열)   ARGV[2] = qtyScaled (정수 문자열)   ARGV[3] = ttlSeconds

if redis.call('EXISTS', KEYS[1]) == 0 then
    open=high=low=close=price, volumeScaled=qtyScaled 로 HSET
else
    high = max(high, price), low = min(low, price)   -- tonumber 비교, 저장은 원본 문자열
    close = price
    HINCRBY volumeScaled qtyScaled
end
EXPIRE KEYS[1] ttlSeconds
```

`RedisTemplate.execute(DefaultRedisScript, keys, args)`로 호출한다.

**오늘 시점의 실제 경합 정도**: 배포는 `compose.deploy.yaml` 기준 `app` 컨테이너 **1개**이고, 체결은 WebSocket 세션 하나의 핸들러에서 순차 처리되므로 쓰기끼리의 경합은 사실상 없다. 그럼에도 Lua로 묶는 이유는 둘이다 — ① **읽기와 쓰기 사이의 경합은 지금도 있다**(조회 스레드가 `HGETALL` 하는 중에 `high`만 갱신되고 `close`는 아직인 중간 상태를 볼 수 있다), ② 서버를 2대 이상으로 늘리는 순간 쓰기 경합이 실제로 생기는데 그때 구조를 다시 짜지 않기 위해서다. **"지금은 문제가 안 생긴다"와 "구조적으로 안전하다"는 다르다.**

## 체결 수신 설계

### 구독

`BithumbWebSocketFeedClient.subscribe()`가 **같은 세션에 구독 메시지를 두 번** 보낸다(연결 추가 없음 — 실측 확인).

```json
{"type":"ticker","symbols":["BTC_KRW",...],"tickTypes":["30M"]}
{"type":"transaction","symbols":["BTC_KRW",...]}
```

기존과 같이 구독 전송이 실패하면 소켓을 정리하고 `DISCONNECTED` 전환 후 재연결을 예약한다. **두 구독 중 하나라도 실패하면 같은 실패 경로를 탄다** — 한쪽만 성공한 반쪽 상태로 두지 않는다.

### 수신 처리

`handleTextMessage`에서 `type`으로 분기한다.

- `type=ticker` → 기존 그대로 `PriceStore.saveTick`
- `type=transaction` → `content.list`의 **모든** 원소에 대해:
  1. `CryptoCandleStore.recordTrade(symbol, contDtm, contPrice, contQty)`
  2. `PriceStore.saveTick(symbol, contPrice, contDtm)` — **현재가도 갱신한다**

### 왜 transaction도 현재가를 갱신하는가

`contPrice`는 실제 체결가이므로 유효한 현재가다. `saveTick`은 이미 "수신시각이 저장값보다 이전이거나 같으면 무시"하므로(MKT-003) 두 경로가 함께 써도 과거 값이 최신을 덮지 않는다.

이건 부수효과가 아니라 **의도한 개선**이다. 30초 실측에서 ticker 최대 공백이 6.0초(단독)·7.0초(transaction 동시)였는데 `PriceStore`의 stale 기준은 10초다 — 여유가 3~4초뿐이다. 두 경로가 함께 갱신하면 공백이 줄어 `PRICE_UNAVAILABLE` 발생 위험이 낮아진다.

**주문 경로는 변경하지 않는다.** `saveTick`이 발행하는 `CryptoPriceUpdatedEvent`(LMT-002 지정가 체결 트리거)는 기존 로직 그대로 동작하며, 트리거 빈도가 늘 뿐 판정 규칙은 바뀌지 않는다.

### 늦게 온 체결

`contDtm`이 속한 분이 **현재 분보다 이전**이면 그 체결을 버리고 로그만 남긴다. 이미 응답으로 나간 봉과 어긋나지 않게 하기 위함이며, `PriceStore.saveTick`의 "과거 틱은 최신을 덮지 못한다"와 같은 방향이다. 버린 건수를 관측할 수 있게 남긴다.

## 조회 설계 — 캐시와 빗썸 이어붙이기

### 핵심 문제: "봉이 없다"의 두 가지 의미

캐시에 어떤 분의 봉이 없을 때 원인이 둘이다.

1. **그 분에 체결이 정말 없었다** → 봉이 없는 게 정답이다(빗썸도 안 준다)
2. **그때 우리 서버가 안 떠 있었다** → 빗썸에 물어봐야 한다

키만 봐서는 구분할 수 없다. 그래서 **`since` 워터마크**를 둔다.

### `since` 워터마크

- `candle:crypto:{symbol}:1m:since` = 이 심볼의 집계가 **연속적으로 유효한 시작 분**.
- WebSocket **연결이 성립될 때마다**(`afterConnectionEstablished`) 현재 분으로 갱신한다. 재연결·재시작 직후에는 그 시점부터가 신뢰 구간이라는 뜻이다.
- 판정: `분 >= since` 구간은 **캐시가 정본**이다(그 안의 구멍은 진짜로 체결이 없던 분이다). `분 < since` 구간은 **빗썸에 위임**한다.

**트레이드오프(정직하게 기록)**: 재시작 직후에는 `since`가 현재 분이라 요청 구간 대부분이 빗썸으로 간다. 시간이 지날수록 캐시가 덮는 비율이 올라가고, 200분이 지나면 응답 전체를 캐시가 덮는다. 즉 **캐시 효과가 기동 직후 0%에서 시작해 200분에 걸쳐 100%로 오른다.** 재시작 전 Redis에 남아 있던 봉을 재활용하지 않는 건 손해지만, 다운타임 구간의 구멍을 정확히 알 수 없어 **틀린 봉을 주느니 다시 물어보는 쪽**을 택했다.

### 조회 절차 (`CachedCryptoCandleProvider.getCandles`)

1. `interval != 1m` 이면 **즉시 위임**한다(일·주·월봉은 범위 밖).
2. 요청 `[from, to]`를 분 단위로 정규화하고 상한 200봉으로 자른다(기존 규칙 그대로).
3. `since`를 읽는다. 읽기 실패·부재면 캐시를 아예 쓰지 않고 **전량 위임**한다.
4. 구간을 둘로 나눈다 — `[from, since)`는 **위임 구간**, `[max(from,since), to]`는 **캐시 구간**.
5. 캐시 구간: 필요한 분 키들을 **파이프라인 1회**(`executePipelined`)로 일괄 `HGETALL` 한다. 존재하는 것만 봉으로 만든다(없는 분 = 체결 없음 = 봉 없음).
6. 위임 구간이 비어 있지 않으면 `BithumbRestCandleProvider.getCandles(symbol, 1m, from, since-1분)`을 **1회** 호출한다.
7. 두 결과를 시각 오름차순으로 합친다. `sourceTime`이 겹치면 **캐시 쪽을 채택**한다(우리 데이터가 진행 중 봉을 담고 있어 더 최신이다).

### 실패 처리

| 상황 | 동작 |
|---|---|
| Redis 조회 실패(연결 끊김 등) | 예외를 삼키고 **전량 위임**한다. 코인 차트가 Redis 단일 장애점이 되지 않는다. 로그를 남긴다 |
| 빗썸 REST 실패 + 위임 구간 있음 | 기존대로 502 `MARKET_DATA_PROVIDER_ERROR`. **빈 배열 200으로 위장하지 않는다**(MKT-008) |
| 빗썸 REST 실패 + 위임 구간 없음(전부 캐시 커버) | 빗썸을 아예 호출하지 않으므로 **정상 200**. 이게 이 작업의 가용성 이득이다 |
| Redis·빗썸 둘 다 실패 | 502 |

## 데이터 모델

**신규 테이블 없음. Flyway 마이그레이션 없음.** (MKT-008의 "코인 캔들 전용 테이블을 만들지 않는다" 유지 — ADR-0004 대상 아님)

신규 내부 record 2개만 추가한다.

| record | 필드 | 위치 |
|---|---|---|
| `CryptoTrade` | `symbol`·`tradedAt`(LocalDateTime)·`price`(BigDecimal)·`quantity`(BigDecimal) | `market/feed` (파서 반환형) |
| (기존 `CryptoCandleDto` 재사용) | — | 조회 결과는 기존 DTO 그대로 |

## 측정 계획 (PRD §10 Decision Gate 충족)

**구현 전에 먼저 측정한다.** 게이트를 폐기하지 않고 충족시킨다.

| 항목 | 방법 | 시점 |
|---|---|---|
| 빗썸 REST 캔들 호출 수 | `BithumbRestCandleProvider.fetchCandles` 진입 카운터(로그 또는 단순 카운터) | before / after |
| 캔들 응답 시간 | 같은 요청에 대한 응답 지연 | before / after |
| 캐시 적중률 | 캐시 구간 봉 수 / 전체 반환 봉 수 | after |

- 측정 조건(동시 사용자 수·차트 재조회 주기·측정 시간)을 **before/after 동일하게** 맞추고 기록한다.
- 실측 없이 "호출이 줄었다"고 주장하지 않는다(C-005).

## 테스트 계획

ADR-0003 기준. **자동 테스트는 실제 빗썸을 호출하지 않는다**(C-005).

### 단위

- `BithumbTransactionMessageParser` — 정상 파싱(단건), **`list` 다건 전부 반환**, `type`이 transaction이 아닌 메시지 무시, 필드 누락·잘못된 JSON에서 예외 미전파, `contDtm` 밀리초 파싱, 심볼 `_KRW` 제거.
- 집계 로직 — 같은 분 체결들로 첫=open·최대=high·최소=low·마지막=close·합=volume 산출, 분이 바뀌면 새 봉, 체결 없는 분에 봉 없음, 늦게 온 체결 무시.
- 수량 스케일링 — `contQty` 소수 8자리가 정확히 왕복(`×10^8` → `long` → `÷10^8` → 원래 `BigDecimal`), 9자리 이상이면 집계에서 제외.
- `CachedCryptoCandleProvider` 구간 분할 — `since` 기준으로 위임/캐시 구간이 갈리는지, `interval != 1m`이면 즉시 위임하는지, 겹치는 `sourceTime`에서 캐시가 채택되는지, Redis 예외 시 전량 위임하는지(위임 대상 mock으로 호출 여부 검증).

### 통합 (Testcontainers 실제 Redis)

**이 작업의 핵심 검증이며 mock으로 대체하지 않는다.**

- **동시성** — 스레드 여러 개가 같은 심볼·같은 분에 체결을 동시에 넣었을 때 `volumeScaled` 합계가 정확하고 **단 하나도 유실되지 않는다.** high/low도 전체 체결의 최대·최소와 일치한다. (Lua 원자성 검증 — 이게 깨지면 이 설계의 존재 이유가 없다.)
- **읽기-쓰기 경합** — 갱신이 진행되는 동안 조회해도 `open`만 있고 `close`가 없는 식의 중간 상태가 보이지 않는다.
- TTL이 실제로 걸리는지(짧은 TTL로 주입해 만료 확인).
- 캐시 구간 + 위임 구간 이어붙이기 — `sourceTime` 중복·누락 없음.
- 빗썸(Fake) 실패 시 — 위임 구간이 있으면 502, 없으면 200.

### 슬라이스

- `@WebMvcTest` — 캔들 API 계약 회귀. `interval` 4종의 200/400/404/401/502가 기존과 동일한지. (조회 경로가 바뀌어도 **계약은 그대로**라는 것을 고정한다.)

### 회귀

- 주식 캔들 전체(`1m`·`1d`·`1w`·`1M`)와 코인 `1d`·`1w`·`1M`이 영향받지 않는다.
- 코인 현재가 조회·시장가/지정가 주문 경로가 영향받지 않는다 — 특히 `CryptoPriceUpdatedEvent` 발행 규칙(과거 틱 무시)이 두 채널 동시 유입에서도 유지되는지.

### 외부 스모크 (자동 테스트와 구분 보고, C-005)

- 실제 빗썸 `transaction` 채널 연결·수신, `contQty` 실제 소수 자릿수 확인.
- 한 연결에서 `ticker`·`transaction` 동시 구독이 운영 환경에서도 유지되는지.
- before/after 호출량 실측.

## 남은 위험

- **체결이 드문 분이 많으면 캐시 효과가 준다.** 30초 실측에서 BTC의 transaction 메시지가 3건이었다 — 체결이 없는 분에는 봉이 안 생기고, 그 구간이 `since` 이후라면 봉 없이 응답한다(정답이지만 빗썸 응답과 봉 개수가 달라 보일 수 있다). 거래가 한산한 종목일수록 두드러진다. **착수 전 종목별로 "1분 안에 체결이 있는 비율"을 실측해 기록한다.**
- **우리 봉과 빗썸 봉의 값이 미세하게 다를 수 있다.** 빗썸이 어떤 체결까지 그 분에 넣는지 정확히 알 수 없다. 서버에서 보정하지 않는다(MKT-008 원칙).
- **재시작 직후 캐시 효과가 0에서 시작한다.** 위 `since` 트레이드오프 참조.
