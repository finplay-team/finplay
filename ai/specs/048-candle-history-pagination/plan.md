# Plan: 캔들 차트 과거 구간 탐색 — 과거 방향 커서 페이지네이션

## 관련 문서

- Spec: `./spec.md` — **확정 계약은 전부 그쪽에 있다. 이 plan은 그것을 뒤집지 않는다.** 뒤집어야 한다고 판단한 것은 §14 "spec 재검토 필요"에 모아 두고 사용자 판단을 기다린다.
- PRD: `../../prd.md` — 선행 MKT-002·MKT-005·MKT-008·MKT-009·MKT-010, 정책 C-005. 이 spec은 `CANDLE-PAGE-001~029`를 자체 소유하고 PRD에는 §3 "구현 현황" 행만 추가한다(spec §요구사항 ID 소유).
- 선행 spec: `../003-market-data/`, `../013-candle-interval/`(200개 상한·집계 구조의 원 설계), `../027-crypto-tick-candle-cache/`(코인 캐시·`since` 위임 병합), `../038-stock-quote-last-known-hold/`(CLOSED 폴백), `../018-order-list-pagination/`(이 저장소의 커서 페이지네이션 선례 — 봉투 3필드·커서 파싱·검증 순서를 여기에 맞춘다).
- 관련 ADR
  - **ADR-0002(레이어드 아키텍처)** — 커서 파싱·`to` 덮어쓰기·봉투 조립·`hasNext` 판정은 전부 **service(`CandleQueryService`)** 책임이다. controller는 `cursor` 문자열을 그대로 넘기는 것 말고 아무 판단도 하지 않는다. provider는 "주어진 구간의 봉을 준다"만 유지한다.
  - **ADR-0003(테스트 전략)** — §12 참조. 집계·커서 판정은 단위, API 계약은 `@WebMvcTest`, 다중 페이지 이어받기는 Testcontainers 통합, 코인 실호출은 외부 스모크로 분리(C-005).
  - **ADR-0004(Flyway)** — **이번 작업은 스키마 변경이 없다.** 새 마이그레이션을 만들지 않고 기존 `V8__create_stock_candles.sql`도 손대지 않는다. 근거는 §11.
  - **ADR-0021(지속 배포)** — `dev` 머지가 곧 배포다. 봉투 전환이 breaking change이므로 §13의 배포 순서가 계약의 일부다.
  - **ADR-0022(프론트 S3 정적 호스팅)** — 프론트는 백엔드와 따로 배포된다. 그래서 "동시 배포로 흡수"가 불가능하고 순서를 지켜야 한다.
- 이슈: #473 / 브랜치: `feat/473-candle-history-pagination`

## 1. 설계 요약 — 한 문장

**커서는 `CandleQueryService` 한 곳에서 "배타 상한 → 기존 포함 상한(`to`)"으로 1회 정규화되고, 봉투·`nextCursor`·`hasNext`도 같은 곳에서 만들어진다.** 그 결과 `StockPriceProvider`·`CryptoCandleProvider` 인터페이스, `StockReplayService`, `BithumbRestCandleProvider`는 **시그니처도 로직도 바뀌지 않는다.**

예외는 `CachedCryptoCandleProvider` 하나다 — 커서 때문이 아니라 **캐시 구간이 200개를 채우지 못해 `hasNext`가 조기에 `false`가 되는 것을 막기 위해서**이며, 시그니처는 그대로다(§9-2 결정 D-2).

이 설계를 고른 이유는 spec의 두 요구가 동시에 걸리기 때문이다.

- CANDLE-PAGE-008(균질 계약): `hasNext` 판정이 시장별로 갈리면 안 된다 → **판정은 provider가 아니라 service에서 1회**.
- CANDLE-PAGE-002(순수 추가): 커서 없는 요청의 값·개수·정렬이 한 글자도 바뀌면 안 된다 → **provider 코드를 건드리지 않는 것이 가장 강한 회귀 방어**다.

정규화의 근거는 "모든 봉의 `sourceTime`이 분 단위로 정렬돼 있다"는 사실이다 — 코인 `1m`은 분 경계, 집계봉(`1d`·`1w`·`1M`)과 코인 일·주·월봉은 전부 `T00:00:00`이다. 따라서

```
배타 상한 cursor  ≡  포함 상한 to = cursor.minusMinutes(1)
```

가 **손실 없이 정확히 같다**. 그리고 집계 경로는 `KisHistoricalReplayPriceProvider`가 이미 `to.toLocalDate()`를 하는데, `cursor`가 `T00:00`이므로 `cursor.minusMinutes(1).toLocalDate() == cursor날짜.minusDays(1)` — 원하는 "커서 버킷 직전 날짜"가 그대로 나온다. 별도 날짜 변환 코드가 필요 없다.

> **분 미만 정밀도 커서**: 커서는 불투명 토큰이 아니라 소비자가 임의 값을 넣을 수 있다(spec 확정 계약). `10:00:30`처럼 분 경계가 아닌 값을 주면 위 변환이 `09:59:30`이 되어 `10:00` 봉이 **제외**된다("10:00:30보다 과거"의 엄밀한 해석과 30초 차이). 응답 `sourceTime`은 항상 분 경계이므로 정상 페이징에서는 발생하지 않는다. **"커서는 분 단위로 내림 해석된다"를 `docs/api-contracts.md`에 한 줄 적는다.**

## 2. API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/instruments/{instrumentId}/candles?interval=&from=&to=&cursor=` | 쿼리 | **`CandleListResponse`(신규 봉투)** | 기존 엔드포인트. **URL·봉 필드 스키마 변경 없음.** `cursor` 파라미터가 추가되고, 봉 배열이 `content`로 감싸진다 |

- 신규 엔드포인트를 만들지 않는다(`/candles/history` 금지 — 같은 데이터에 두 경로가 생긴다, spec 확정 계약).
- 응답 형태는 **커서 유무·`interval`·시장과 무관하게 항상 봉투 하나**다.

## 3. 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `instrumentId` (path) | 필수 | 존재하지 않으면 404 `NOT_FOUND`. **`interval` 검증 이후**에 조회한다(기존 순서 유지) |
| `interval` (query) | 필수 | `1m`·`1d`·`1w`·`1M`, **대소문자 구분**. 그 외·누락·빈 문자열은 400 `VALIDATION_ERROR`. **커서가 유효해도 여전히 400**(CANDLE-PAGE-010) |
| `from` (query) | 선택 | ISO-8601 `LocalDateTime`. 해석 규칙 기존 그대로(코인=전체 시각, 주식 `1m`=시각 성분만, 주식 집계=날짜 성분만). **커서가 있어도 하한으로 계속 유효** |
| `to` (query) | 선택 | 위와 동일. **`cursor`가 함께 오면 값 해석·검증 모두에서 무시된다**(400이 아니다, CANDLE-PAGE-012 + §5 결정 D-1) |
| `cursor` (query) | 선택 | ISO-8601 `LocalDateTime` **문자열**(예: `2026-08-19T09:00:00`). 직전 페이지 `content[0].sourceTime`을 그대로 넣는다. 파싱 실패는 400 `VALIDATION_ERROR`(CANDLE-PAGE-009). 의미는 **배타 상한** — 같은 `sourceTime`의 봉은 다음 페이지에 없다 |

**`cursor`는 `LocalDateTime`이 아니라 `String`으로 바인딩한다.** `@DateTimeFormat(iso = ISO.DATE_TIME)`을 붙이면 파싱 실패가 스프링 바인더 단계에서 터져 **`interval` 400·종목 404보다 먼저** 나가고, 오류 본문도 우리 `VALIDATION_ERROR` 포맷이 아니게 된다 — CANDLE-PAGE-009·010을 동시에 어긴다. `OrderCursor`·`TradeCursor`가 이미 `String` 파라미터 + service 파싱인 것과 같은 이유다.

## 4. 응답 계약 (`docs/api-contracts.md`에 그대로 옮길 수준)

### 4-1. 성공 200

```json
{
  "content": [
    {"sourceTime":"2026-07-22T09:00:00","open":71000,"high":71500,"low":70900,"close":71200,"volume":12345},
    {"sourceTime":"2026-07-22T09:01:00","open":71200,"high":71600,"low":71100,"close":71550,"volume":9821}
  ],
  "nextCursor": "2026-07-22T09:00:00",
  "hasNext": true
}
```

| 필드 | 타입 | 의미 |
|---|---|---|
| `content` | `CandleResponse[]` | 봉 배열. **시각 오름차순**, 한 응답 최대 **200개**. 봉 하나의 필드(`sourceTime`·`open`·`high`·`low`·`close`·`volume`)는 **013 계약 그대로 한 글자도 바뀌지 않는다** |
| `nextCursor` | `string \| null` | 다음 요청에 넣을 커서 = **`content`의 가장 오래된 봉(`content[0]`)의 `sourceTime`**. `hasNext=false`면 항상 `null` |
| `hasNext` | `boolean` | **"이번 페이지의 `content`가 200개로 꽉 찼는가"**. 유일한 예외는 주식 `1m`으로 200개여도 항상 `false`(CANDLE-PAGE-026) |

문서에 반드시 함께 적을 3가지.

1. **`to`와 `cursor`를 동시에 보내면 `to`는 무시된다** — 400이 아니다. `from`은 하한으로 계속 유효하다(CANDLE-PAGE-012).
2. **마지막 페이지가 정확히 200개면 빈 `content` 페이지가 한 번 더 나오고 거기서 `hasNext=false`가 된다** — 버그가 아니라 계약이다(CANDLE-PAGE-007). 소비자는 빈 페이지 1회를 정상 종료로 받아들인다.
3. **끝 판정은 오직 `hasNext`다** — 거래일이 없는 주·월, 체결이 없는 분 때문에 `content` 안의 봉 사이가 달력상 비는 것은 데이터 끝의 신호가 아니다(CANDLE-PAGE-023).
4. **코인은 `content`의 가장 오래된 봉이 요청한 `from`보다 과거일 수 있다** — 빗썸이 "구간"이 아니라 "`to` 기준 최신 N개"를 주기 때문이며(§9-1) 페이징 이전부터의 기존 동작이다. `from`은 코인에서 하한 보장이 아니라 **조회 폭의 힌트**다. `nextCursor`는 요청 창이 아니라 실제로 돌려준 가장 오래된 봉에서 나오므로 이어받기에는 영향이 없다.

`ai/api-routes.md`·`docs/api-contracts.md`에 남아 있는 **"200개를 넘는 구간을 이어붙이는 페이징은 1차 범위가 아니다"** 서술은 이 계약으로 교체한다(컨트롤러 변경과 같은 커밋, CLAUDE.md 규칙 7).

### 4-2. 오류

| 상태 | 코드 | 조건 | 비고 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | `interval`이 4값이 아님 | 커서가 유효해도 **먼저** 판정 |
| 400 | `VALIDATION_ERROR` | `cursor` ISO-8601 파싱 실패 | 조용히 무시하고 첫 페이지를 주지 않는다. **주식 `1m`도 마찬가지로 400**(무시되는 것은 "형식이 맞는 커서의 효과"이지 형식 검증이 아니다) |
| 400 | `VALIDATION_ERROR` | `from > to`, **`cursor`가 없을 때만** | §5 결정 D-1 |
| 401 | `UNAUTHORIZED` | Access 인증 실패 | 기존 그대로 |
| 404 | `NOT_FOUND` | 없는 `instrumentId` | `interval` 검증 **이후** |
| 502 | `MARKET_DATA_PROVIDER_ERROR` | 코인 빗썸 조회 실패(타임아웃·비정상 상태코드·파싱 불가) | **"그 구간에 봉이 없음"은 502가 아니다**(CANDLE-PAGE-016) — 200 + 빈 `content` + `hasNext=false` |

**새 오류 코드를 만들지 않는다**(CANDLE-PAGE-011).

## 5. 결정 D-1 — `cursor`가 있을 때 `from > to` 검증 (spec이 plan으로 넘긴 미결 1건)

**결정: `cursor`가 있으면 `from`/`to` 비교를 아예 하지 않는다. `to`는 값 해석뿐 아니라 검증에서도 무시하고, `from`과 `cursor`를 비교하는 새 400도 만들지 않는다.** `from`이 커서보다 뒤(미래)에 있으면 **빈 `content` + `hasNext=false` + `nextCursor=null`인 정상 200**이다.

근거 4가지.

1. **`cursor`가 없는 요청의 동작이 한 글자도 바뀌지 않는다.** `cursor`는 신규 파라미터이므로 "검증을 뺐다"의 영향을 받는 기존 요청이 존재하지 않는다 — CANDLE-PAGE-002(순수 추가)를 문자 그대로 만족한다. "기존 계약이 조용히 달라진다"는 우려는 커서를 보내는 **새 요청에만** 적용되고, 그 요청에는 아직 계약이 없다.
2. **무시되는 값으로 거부하면 소비자가 원인을 못 찾는다.** `to`가 무시된다고 문서에 적어 놓고 그 `to` 때문에 400을 내면, 프론트는 "무시된다더니 왜 400인가"를 겪는다. 무시는 일관돼야 한다.
3. **`from > cursor`는 오류가 아니라 페이징의 정상 종료다.** 하한 `from`을 고정한 채 과거로 페이징하면 커서는 언제고 `from` 밑으로 내려간다. 그 지점을 400으로 만들면 **정상 페이징 루프의 마지막 한 번이 예외로 끝난다** — 소비자는 종료 판정을 `hasNext`로 하라고 계약(CANDLE-PAGE-007)에 적어 놓고 정작 마지막에 오류를 던지는 셈이다.
4. **새 400 조건을 만들지 않는다**는 CANDLE-PAGE-011의 취지에 맞는다.

**구현상의 함정 — 이 결정 때문에 반드시 필요한 방어 1개.**
지금 `CachedCryptoCandleProvider` 45~48행은 `from > to`를 만나면 "이건 내 관심사가 아니다"라며 원래 인자로 위임한다. 그 주석의 전제는 **"service가 이미 400으로 걸렀다"**이다. D-1로 그 전제가 깨지므로, 걸러지지 않은 `from > to`가 빗썸까지 내려가면 `resolveCount`가 `max(1, ...)` = 1이 되어 **요청 구간 밖의 봉 1개**가 돌아온다. 따라서

> **`CandleQueryService`가 provider를 부르기 전에 짧게 끊는다** — `cursor`가 적용되는 요청에서 `from`이 정규화된 상한보다 뒤면 provider를 호출하지 않고 곧바로 빈 봉투를 반환한다.

이 방어는 시장·`interval`과 무관한 한 곳이라 균질 계약(CANDLE-PAGE-008)을 지키고, 외부 호출도 아낀다. 비교 기준은 **기존 `from > to` 판정과 완전히 같은 기준**을 쓴다(코인=전체 `LocalDateTime`, 주식 집계=날짜 성분). 같은 비교를 하되 **상한이 `to`에서 왔으면 400, `cursor`에서 왔으면 빈 페이지** — 이 한 문장이 D-1의 전부다.

## 6. 구성요소 변경

실측한 현재 코드 기준이다. **아래에 없는 클래스는 손대지 않는다.**

### 6-1. `CandleListResponse` (**신규**, `com.finplay.api.market.dto.response`)

```java
public record CandleListResponse(List<CandleResponse> content, String nextCursor, boolean hasNext) {
    public CandleListResponse { content = List.copyOf(content); }
    public static CandleListResponse of(List<CandleResponse> content, String nextCursor, boolean hasNext) { ... }
}
```

- `OrderListResponse`·`TradeListResponse`·`JournalListResponse`와 **필드 이름·순서·타입·컴팩트 생성자의 `List.copyOf`까지 동일**하게 맞춘다(`docs/conventions.md` DTO 규칙 — 목록 응답 접미사 `~ListResponse`).
- 항목 타입은 **기존 `CandleResponse`를 그대로 재사용**한다. 새 항목 DTO를 만들지 않는다(`~ListItemResponse` 신설 금지 — 봉 스키마가 바뀌면 안 되기 때문이다).
- `nextCursor`는 **`String`**이다. 형제 봉투와 타입을 맞추기 위해서이기도 하고, 값 자체가 `sourceTime`의 **표기**여야 하기 때문이다(CANDLE-PAGE-001).

### 6-2. `CandleCursor` (**신규**, `com.finplay.api.market.service`)

```java
public final class CandleCursor {
    public static LocalDateTime parse(String raw);        // 실패 시 BusinessException(VALIDATION_ERROR)
    public static String encode(LocalDateTime sourceTime);
}
```

- `OrderCursor`·`TradeCursor`와 같은 위치·같은 책임(형식 지식이 service 본문에 흩어지지 않게 한다). 다만 캔들 커서는 `{시각}_{id}`가 아니라 **시각 하나**다 — 캔들에 `id`가 없기 때문이며 spec에서 확정된 사항이다.
- `parse`는 `DateTimeFormatter.ISO_LOCAL_DATE_TIME`으로 파싱한다(초 생략형 `2026-08-19T09:00`도 받아들인다).
- **`encode`는 `LocalDateTime.toString()`을 쓰지 않는다.** `toString()`은 초가 0이면 `2026-07-22T09:00`으로 초를 떨어뜨리는데, 현재 응답의 `sourceTime`은 `2026-07-22T09:00:00`으로 직렬화된다(`InstrumentControllerTest` 570행이 그 형태를 고정하고 있다). 그대로 두면 `nextCursor`와 `sourceTime`의 표기가 갈려 CANDLE-PAGE-001("응답의 `sourceTime`과 같은 표기")을 어긴다. **초를 항상 찍는 패턴(`yyyy-MM-dd'T'HH:mm:ss`)을 명시**하고, `@WebMvcTest`에서 `nextCursor`가 `content[0].sourceTime`의 JSON 문자열과 **정확히 같은 문자열**임을 단언한다.

### 6-3. `InstrumentController.getCandles` (기존, 52~63행) — 얇게 유지

```java
public ResponseEntity<CandleListResponse> getCandles(
    @PathVariable Long instrumentId,
    @RequestParam String interval,
    @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime from,
    @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) LocalDateTime to,
    @RequestParam(required = false) String cursor)
```

- 변경은 **반환 타입과 파라미터 1개뿐**이다. 커서 파싱도, `to` 무시 판단도, `hasNext` 계산도 여기서 하지 않는다(ADR-0002 — controller는 비즈니스 판단을 하지 않는다).
- 같은 컨트롤러의 다른 3개 매핑은 손대지 않는다.

### 6-4. `CandleQueryService.getCandles` (기존, 25~63행) — **이번 작업의 전부가 여기 있다**

```java
public CandleListResponse getCandles(Long instrumentId, String interval,
                                     LocalDateTime from, LocalDateTime to, String cursor)
```

책임 5가지(순서는 §7).

1. `cursor` 파싱(400).
2. **커서 적용 여부 판정** — 주식 `1m`이면 적용하지 않는다(§10).
3. **`to` 덮어쓰기** — 적용되면 `to := cursor.minusMinutes(1)`, 기존 `to`는 버린다.
4. **하한 역전 시 조기 반환**(§5 D-1의 방어).
5. **봉투 조립** — `nextCursor`·`hasNext`를 여기서 한 번만 계산한다.

```
hasNext   = 주식1m ? false : (content.size() == 200)
nextCursor= hasNext ? CandleCursor.encode(content.get(0).sourceTime()) : null
```

`content`가 오름차순이므로 `content.get(0)`이 가장 오래된 봉이다. **200은 이미 provider들이 각자 지키고 있는 상한이므로 service는 자르지 않고 개수만 읽는다** — 자르면 상한 규칙이 두 곳에 생긴다.

### 6-5. `CandleQueryService.getCryptoCandles` (기존, 80~83행) — **건드리지 않는다**

매도 회고(spec 012 §FEED-012)가 쓰는 별도 경로다. 반환 타입이 화면용 DTO가 아니라 도메인 `CryptoCandleDto`라 **봉투 변경의 영향을 받지 않고**, 커서 파라미터도 받지 않는다. 시그니처·본문·주석 전부 그대로 둔다. 회고 경로의 "200봉 상한은 호출부 책임" 계약도 이 spec으로 바뀌지 않는다.

### 6-6. `CachedCryptoCandleProvider.getCandles` (기존, 36~90행) — 200개 미달 시 위임 보충 (§9-2 D-2)

**시그니처는 그대로다.** 커서·페이지·`hasNext`를 알지 못하는 것도 그대로다. 89행 `merge(delegated, cached)` 직후에 규칙 하나가 붙는다.

```
병합 결과가 200개 미만이고 요청 창이 200분 폭이면
    → delegate.getCandles(symbol, 1m, effectiveFrom, effectiveTo) 를 한 번 더 호출해 merge
병합 결과가 200개를 넘으면
    → 최신 200개만 남긴다
```

- 캐시가 200개를 채운 페이지(활발한 구간)에서는 **추가 호출이 없다** — 조건 자체가 거짓이다.
- 겹침 처리는 기존 `merge` 그대로이므로 **진행 중 분봉은 계속 우리 값이 이긴다**(027 우선순위 불변).
- 이 규칙은 커서 유무와 무관하게 적용된다. 커서 없는 첫 페이지가 D-2가 가장 자주 걸리는 자리이기 때문이다(§9-2).

### 6-7. 바뀌지 않는 것 (의도적으로 명시한다)

| 클래스 | 왜 안 바뀌는가 |
|---|---|
| `StockPriceProvider` / `CryptoCandleProvider` (인터페이스) | 커서가 service에서 이미 `to`로 정규화돼 내려오므로 **파라미터를 늘릴 이유가 없다**. 5개짜리 시그니처(`from`·`to`·`cursor` 3연속 `LocalDateTime`)의 위치 혼동 위험도 함께 사라진다 |
| `StockReplayService` (`getRevealedCandles`·`getRevealedAggregatedCandles`·`buildAggregatedCandles`·`narrowRangeStart`·`lookbackFloor`) | §8에서 보이듯 **`rangeEnd`가 움직이면 기준점 이동이 자동으로 성립**한다 |
| `BithumbRestCandleProvider` | `resolveToParam`이 이미 "우리 포함 상한 → 빗썸 배타 상한(`+1초`)" 변환을 하고 있고, `from`으로 응답을 거르지 않아 200개가 그대로 찬다(§9-1) |
| `LocalForcedOpenStockPriceProvider` · `FakeCryptoCandleProvider` · `KisHistoricalReplayPriceProvider` | provider 시그니처가 그대로이므로 한 줄도 바뀌지 않는다 |
| `CandleResponse`·`CandleInterval`·`StockCandleAggregator`·`StockCandleRepository` | 봉 스키마·집계 규칙·쿼리가 그대로다 |

> 위 표는 **리뷰의 체크리스트로 쓴다.** 이 중 하나라도 diff에 등장하면 "왜 필요했는가"를 PR에서 설명해야 한다 — 설계상 필요 없는 변경이기 때문이다.

## 7. 커서 처리 흐름 (검증 순서 — CANDLE-PAGE-010)

`CandleQueryService.getCandles` 본문 순서다.

```
① CandleInterval.from(interval)                    → 400 VALIDATION_ERROR
② instrumentRepository.findById(instrumentId)      → 404 NOT_FOUND
③ 시장 판정 (instrument.getMarket() == CRYPTO)
④ CandleCursor.parse(cursor)                       → 400 VALIDATION_ERROR   (cursor != null일 때만)
⑤ 커서 적용 여부:  cursorApplies = (cursor != null) && !(주식 && interval == 1m)
⑥ 상한 확정:
     cursorApplies  → to := parsedCursor.minusMinutes(1)   (원래 to는 버린다)
     아니면          → 기존 from > to 검증 그대로            → 400 VALIDATION_ERROR
⑦ cursorApplies && from이 to보다 뒤  → 빈 봉투 즉시 반환 (200)   [§5 D-1]
⑧ provider 호출 (기존 그대로)
⑨ 봉투 조립: content → hasNext → nextCursor
```

- **④가 ②보다 뒤인 것이 CANDLE-PAGE-010의 요구다.** 잘못된 `interval`은 커서가 유효해도 400이고, 없는 종목은 커서가 잘못돼도 404다.
- **④와 ⑥의 상대 순서는 관측 가능한 계약을 바꾸지 않는다.** `from > to`와 잘못된 커서가 동시에 오는 유일한 경우, 어느 쪽을 먼저 판정해도 **둘 다 400 `VALIDATION_ERROR`**이고 메시지만 다르다. 커서를 먼저 파싱해야 ⑤·⑥을 결정할 수 있으므로 위 순서로 고정한다.
- **`to` 덮어쓰기 지점은 ⑥ 한 곳뿐이다.** 이 아래로는 `cursor`라는 개념이 존재하지 않는다 — provider도, `StockReplayService`도 "포함 상한 `to`"만 안다. 시장별로 커서 해석이 갈릴 자리가 구조적으로 없다는 것이 CANDLE-PAGE-008의 보증이다.
- **`hasNext` 계산 지점은 ⑨ 한 곳뿐이다.** provider는 자기가 마지막 페이지인지 알지 못하고, 알 필요도 없다.

## 8. 집계봉(`1d`·`1w`·`1M`) 기준점 이동 설계

### 8-1. 기준점은 `rangeEnd` 하나다 — `narrowRangeStart`는 손대지 않는다

`buildAggregatedCandles`(249~306행)의 값 흐름을 보면 **역산 기준이 전부 `rangeEnd`에서 파생된다.**

```
requestedEnd = toDate ?: sourceTradingDate
rangeEnd     = min(requestedEnd, sourceTradingDate)          ← 커서가 움직이는 지점
rangeStart   = fromDate ?: lookbackFloor(interval, rangeEnd) ← rangeEnd에서 파생
pastEnd      = min(rangeEnd, sourceTradingDate - 1일)        ← rangeEnd에서 파생
narrowRangeStart(instrumentId, interval, rangeStart, pastEnd) ← 역산 앵커가 pastEnd
```

`KisHistoricalReplayPriceProvider`가 `toDate = to.toLocalDate()`를 하고, §1의 정규화로 `to = cursor.minusMinutes(1)`이므로 커서가 `T00:00`일 때 `toDate = 커서날짜 - 1일`이 된다. 즉 **`rangeEnd`가 커서 직전으로 옮겨가고, `lookbackFloor`와 `narrowRangeStart`의 역산 기준이 함께 따라간다.** CANDLE-PAGE-018이 요구하는 "역산 기준점이 커서 위치로 옮겨간다"가 **`narrowRangeStart` 본문을 한 줄도 고치지 않고** 성립한다.

- 요구사항이 요구하는 것은 **동작**이지 특정 메서드의 수정이 아니다. "커서를 준 요청이 여전히 최신 200버킷만 읽고 잘라 버리는" 실패 모드는 `rangeEnd`가 안 움직일 때 생기는데, 이 설계에서는 움직인다.
- 확인 방법: 커서를 준 요청에서 `findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc`의 `queryEnd` 인자가 커서 직전 날짜인지를 단위 테스트에서 캡처해 단언한다(§12).

### 8-2. `subList` 200개 캡 — 방향은 그대로, 의미만 "커서보다 과거 방향 최신 200버킷"

305행 `subList(size - 200, size)`는 "**뒤(최신)에서 200개**"를 남긴다. 여기서 "최신"은 언제나 `rangeEnd` 쪽이고, 그 `rangeEnd`가 커서 직전으로 옮겨졌으므로 같은 코드가 자동으로 **"커서보다 과거 방향의 최신 200버킷"**을 의미하게 된다(CANDLE-PAGE-019). 잘려 나가던 더 과거 구간은 다음 페이지에서 `rangeEnd`가 또 옮겨가며 회수된다 — **영구 손실이 없다는 것이 이어받기 무결성의 근거**다.

### 8-3. 선두 partial 버킷 필터(299~301행)와 200개 캡의 적용 순서 — **바뀌지 않는다. 그리고 지금보다 더 중요해진다**

현재 순서는 **필터 → 캡**이고, 주석(294~298행)이 그 이유를 "캡이 실제로 응답에 남을 버킷 수를 기준으로 동작해야 한다"로 적어 두었다. 이 순서를 유지하는 이유가 페이징 도입으로 **하나 더 늘어난다.**

- 지금까지의 이유(OHLC 정확성): 시작일이 `rangeStart`보다 이른 반쪽 버킷이 완전한 캔들처럼 응답에 섞이면 안 된다.
- **새로 생기는 이유(`hasNext` 정확성)**: 순서를 뒤집어 캡을 먼저 걸면, 잘라낸 200개 안에 선두 partial 버킷이 포함될 수 있고 그 뒤 필터가 그것을 떨어뜨려 **199개짜리 페이지**가 나온다. `hasNext`는 "200개로 꽉 찼는가"이므로 **데이터가 더 있는데도 `false`가 되어 페이징이 조용히 조기 종료**된다. 즉 이 순서는 이제 응답 값뿐 아니라 **종료 판정의 정확성까지 떠받친다.**

> **리뷰 포인트**: 이 두 줄(필터·캡)의 순서를 바꾸는 리팩터링은 어떤 이유로도 받지 않는다. 그 취지를 코드 주석에 한 줄 추가한다("페이징 도입 후에는 hasNext 판정까지 이 순서에 의존한다 — 048").

### 8-4. 꼬리(tail) partial 버킷 — 새 필터를 만들지 않는다

커서가 버킷 경계가 아닌 값(예: `1w`인데 수요일)이면 `rangeEnd`가 버킷 중간이 되어 **응답의 가장 최신 버킷이 반쪽**이 된다. 이것은 **새 문제가 아니라 기존 `to`의 동작 그대로**다 — 현재 계약이 "미완성 버킷은 응답에 포함된다"이고, `to`를 주 중간으로 주면 지금도 같은 결과가 나온다. 따라서 **꼬리 방향 필터를 새로 만들지 않는다**(만들면 기존 `to` 계약과 어긋난다). 정상 페이징에서는 `nextCursor`가 항상 버킷 시작일 `T00:00`이므로 이 상황 자체가 발생하지 않는다.

### 8-5. 공개 상한(CANDLE-PAGE-021)이 커서로 뚫리지 않는 이유

257행 `rangeEnd = min(requestedEnd, sourceTradingDate)` 클램프가 **커서 경로에도 그대로 적용된다.** 커서가 재생거래일보다 미래를 가리켜도 `rangeEnd`는 재생거래일을 넘지 못하고, 재생거래일 당일 구간은 여전히 `resolveRevealCutoff`까지만 조회된다(284~292행). 커서는 상한을 **과거로만** 옮길 뿐 미래로 밀 수 없다 — 구조적으로 미공개 분봉에 도달할 경로가 없다.

### 8-6. CLOSED 폴백(038)과의 상호작용

`getRevealedAggregatedCandles`는 오늘 세션 결과가 비고 `marketStatus == CLOSED`이면 폴백 세션으로 다시 집계한다(240~243행). **커서는 두 분기가 공유하는 `buildAggregatedCandles`의 `toDate` 인자로만 들어가므로, 폴백 경로가 기본 경로와 다른 상한을 보는 일이 구조적으로 없다.** 폴백 세션의 `sourceTradingDate`가 오늘 세션보다 과거이므로 `rangeEnd` 클램프는 더 좁아질 뿐이고, 폴백이 커서 상한을 넘는 봉을 만들어 낼 수 없다. 이 성질은 §12에 테스트로 고정한다.

## 9. 코인 캐시·위임 경계 설계

`CachedCryptoCandleProvider.getCandles`(36~96행)에서 커서가 닿는 값은 **`effectiveTo` 하나**다(43행). `effectiveTo = to`이고 `to`는 이미 `cursor - 1분`이다. 44·49~51행 덕분에 `effectiveFrom`은 `effectiveTo - 199분`으로 클램프되어 **요청 창이 정확히 200분 폭**이 된다.

> **창은 200"분"이고 응답은 200"개"다 — 이 둘은 같지 않다.** 체결이 없는 분에는 봉이 없기 때문이다. 이 차이는 **빗썸 위임 경로에서는 저절로 메워지고(§9-1), 캐시 구간에서는 메워지지 않는다(§9-2).** `hasNext`가 개수 판정이므로 이 구분이 설계의 핵심이다.

### 9-1. 위임 경로 — 개수 기반 `hasNext`가 그대로 성립한다 (코드로 확인함)

`BithumbRestCandleProvider.getCandles`(71~83행)에는 **`from`으로 응답을 거르는 코드가 없다.** `from`은 `resolveCount`(98~118행)에서 `count`를 계산하는 데만 쓰이고, 응답은 빗썸이 준 것을 오름차순으로 뒤집기만 해서 그대로 반환한다. 빗썸(업비트 호환)은 **`to` 기준 최신 `count`개의 "존재하는 봉"**을 주므로, 체결이 없는 분이 섞여 있으면 200개를 채우려고 **200분 창보다 더 과거까지 거슬러 올라간다.**

거르는 코드가 없다는 것은 아래 세 지점 모두에서 확인했다.

- `CandleQueryService.getCandles` 코인 분기(39~41행): provider 결과를 `CandleResponse`로 매핑만 한다.
- `CachedCryptoCandleProvider.merge`(98~103행): `TreeMap`으로 `sourceTime` 중복만 제거한다.
- 실제로 거르는 곳은 **`CryptoPostSellFeedbackReader.minuteCandlesWithin`(208~212행) 하나뿐**이고, 그것은 매도 회고(spec 012) 경로다. `CachedCryptoCandleProvider` 69행 주석의 "호출부가 `[from, to]`로 다시 거르므로"가 가리키는 곳이 바로 거기이며 — 그 메서드의 javadoc이 "빠진 분이 있으면 그 개수만큼 `from` 이전 봉이 따라온다"고 이미 적고 있다 — **캔들 조회 API와는 무관하다.**

따라서 **전량 위임 페이지는 과거에 봉이 남아 있는 한 항상 200개가 찬다.** 200개에 못 미친다는 것은 "그 상한 아래로 빗썸에 봉이 더 없다"와 동치이고, 그것이 정확히 데이터 끝이다. `hasNext = (content.size() == 200)`이 의도대로 동작한다.

**응답이 창보다 과거로 뻗어도 `nextCursor`가 깨지지 않는 이유**: `nextCursor`는 요청 창이 아니라 **실제로 돌려준 가장 오래된 봉(`content[0].sourceTime`)**에서 나오고, 다음 페이지 상한은 그 봉의 직전이 된다. 즉 이미 나간 봉의 바로 아래에서 다음 페이지가 시작하므로 **누락도 중복도 생길 수 없다** — 창과 응답 폭이 어긋나는 것 자체가 커서 계산에 들어가지 않는다. 부수 효과로 `content[0]`이 요청한 `from`보다 과거일 수 있는데, 이는 페이징 이전부터의 기존 동작이다(§4의 계약 문서에 한 줄 적는다).

### 9-2. 캐시 구간 — 여기가 진짜 경계다 (결정 D-2)

`CryptoCandleStore.getCandles`(100~127행)는 **Redis에 실제로 있는 분봉만** 돌려준다. 체결이 없던 분은 키 자체가 없고, 0으로 채운 봉을 만들지 않는 것이 027의 확정 계약이다. 그래서 **캐시가 담당한 구간은 200분 창이어도 200개에 못 미칠 수 있다.**

이대로 두면 `hasNext = size == 200`이 **과거가 남아 있는데도 `false`**가 된다. 가장 눈에 띄는 실패 모드는 정상 페이징 도중이 아니라 **첫 페이지**다 — 서버가 200분 넘게 연결돼 있으면 `effectiveFrom ≥ S`라 첫 페이지가 전부 캐시 구간이고, 그 200분 안에 조용한 분이 하나만 있어도 `hasNext=false`가 되어 **차트가 애초에 과거로 스크롤되지 않는다.** (페이징 이전에는 같은 상황이 "봉 몇 개가 비어 보인다" 정도로 무해했다. `hasNext`가 개수에 걸리는 순간 성격이 바뀐다.)

> **결정 D-2 — `CachedCryptoCandleProvider`가 200개를 채워서 돌려준다.** 병합 결과가 200개에 못 미치고 요청 창이 200분 폭이면, `[effectiveFrom, effectiveTo]` 전체를 **위임으로 한 번 더 조회해 병합**한다(겹치면 기존대로 캐시 봉 우선 — 진행 중 분봉이 우리 쪽이 더 최신이다). 위임은 §9-1대로 200개를 채워 오므로 결과가 200개가 된다. 병합 후 200개를 넘으면 **최신 200개만 남긴다**(캐시에만 있는 진행 중 봉이 위임 200개에 더해질 수 있다).

- **spec을 뒤집지 않는다.** `hasNext` 규칙(`content` 200개)은 그대로이고, 오히려 **그 규칙이 코인에서도 참이 되도록 provider가 상한을 채우는** 변경이다. 그래서 §14(사용자 판단 대기)로 올리지 않는다.
- **`hasNext` 판정은 여전히 `CandleQueryService` 한 곳**이다(§7 ⑨). provider는 커서도 페이지도 모른 채 "주어진 구간의 봉을 최대 200개 준다"만 유지하므로 균질 계약(CANDLE-PAGE-008)과 ADR-0002가 그대로다.
- **비용은 캐시가 못 채운 페이지에서만 빗썸 호출 1회**다. 거래가 활발한 구간에서는 캐시가 200개를 채우므로 추가 호출이 없고, MKT-010의 캐시 목적이 유지된다. 반대로 체결이 뜸한 종목·심야 구간에서는 추가 호출이 자주 발생하는데, **조용한 조기 종료보다 호출 1회가 낫다**는 것이 이 결정의 맞바꿈이다.
- **200개 상한이 두 곳에 생기지 않는다.** 자르는 책임은 지금처럼 provider 안에만 있고(§6-4대로 service는 개수만 읽는다), 이번에 추가되는 것은 "모자라면 채운다"는 같은 자리의 대칭 규칙이다.

### 9-3. `since` 워터마크 경계 표

`since` 워터마크를 `S`, 커서를 `C`라 할 때(`effectiveTo = C-1분`):

| 커서 위치 | `cacheFrom = max(effectiveFrom, S)` | `delegateTo = min(S-1분, effectiveTo)` | 실제 동작 | 근거 |
|---|---|---|---|---|
| **`C ≤ S`** (커서가 워터마크보다 과거) | `S > effectiveTo` → **캐시 조회 자체를 건너뜀**(79행 조건 거짓) | `= effectiveTo` | **전량 빗썸 위임.** 캐시가 없다고 빈 배열이 되지 않는다 | CANDLE-PAGE-015 |
| **`C == S`** (정확히 워터마크) | `effectiveTo = S-1분` → 위와 동일 | `= S-1분` | 전량 위임. `S` 시각 봉은 **직전 페이지에 이미 나갔으므로** 배타 상한이 정확히 걸러 낸다 | CANDLE-PAGE-003·014 |
| **`C > S`, `effectiveFrom < S`** (경계를 걸침) | `= S` | `= S-1분` | 위임 `[effectiveFrom, S-1분]` + 캐시 `[S, C-1분]` — **두 구간이 1분 간격으로 정확히 맞닿고 겹치지 않는다.** 캐시 쪽이 성기면 D-2가 200개를 채운다 | CANDLE-PAGE-014 |
| **`effectiveFrom ≥ S`** (전부 캐시 구간) | `= effectiveFrom` | `< effectiveFrom` → **위임 건너뜀**(74행 조건 거짓) | 전량 캐시. **D-2가 걸리는 주 무대** — 조용한 분이 하나라도 있으면 위임 보충이 일어난다 | 027 유지 + §9-2 |

- **`merge`(98~103행)의 중복 제거는 커서 경계에서도 성립한다.** `TreeMap<LocalDateTime, ...>`이 `sourceTime`을 키로 쓰므로 두 구간이 실수로 겹쳐도 봉이 두 번 나가지 않고, 겹칠 때 캐시(우리 봉)를 채택하는 027의 우선순위도 그대로다. **페이지 간 중복**은 `merge`가 아니라 배타 상한이 막는다 — 다음 페이지의 상한이 `C-1분`이므로 `C` 이상의 봉은 애초에 조회되지 않는다.
- **Redis 장애**(56~59행·82~86행): `since` 조회 실패도, 캐시 구간 조회 실패도 모두 **`effectiveFrom`/`effectiveTo`(=커서가 이미 반영된 값)로 위임**하므로 폴백 상태에서도 페이징이 동작한다(CANDLE-PAGE-017).
- **빗썸 배타 상한 변환**: `BithumbRestCandleProvider.resolveToParam`(126~131행)이 우리 포함 상한에 `+1초`를 더해 빗썸의 배타 `to`로 바꾼다. 정규화된 `to = C-1분`이면 빗썸 `to = C-1분+1초`가 되어 **`C` 시각 봉이 확실히 빠지고 `C-1분` 봉은 들어온다.** 커서 전용 분기가 필요 없다.
- **D-2의 병합 안전성**: 보충 위임과 캐시가 겹쳐도 `merge`가 `sourceTime` 키로 중복을 없애고 캐시 봉을 채택하므로 값이 흔들리지 않는다. 보충 위임이 창보다 과거로 뻗는 것도 §9-1과 같은 이유로 `nextCursor`를 깨뜨리지 않는다.
- **상장 이전 구간**(CANDLE-PAGE-016): 빗썸이 봉을 주지 않으면 `content: []`·`hasNext=false`로 끝난다. 조회 **실패**(타임아웃·비정상 상태코드·파싱 불가)는 기존대로 502이며, 이 구분은 `BithumbRestCandleProvider`의 기존 예외 경로가 이미 하고 있다. **빗썸이 상장 이전 구간을 실제로 어떻게 응답하는지는 여전히 미확인이므로 외부 스모크로 관측해 관측한 그대로 계약에 적는다**(spec §아직 확정하지 않은 것, C-005). 추측으로 문서에 적지 않는다.
- 코인 `1d`·`1w`·`1M`은 `CachedCryptoCandleProvider` 39행에서 즉시 위임되므로 위 표와 무관하게 빗썸 경로만 탄다. 정규화된 `to`가 그대로 전달되어 같은 커서 계약이 성립한다(CANDLE-PAGE-024).

## 10. 주식 `1m` 처리 (CANDLE-PAGE-025·026)

- **커서를 조회 경로에 전달하지 않는다.** `getRevealedCandles`는 `from`·`to`의 **시각 성분만** 쓰므로, 커서를 `to`로 정규화해 넘기면 "커서 시각 이전의 분봉만" 잘려 나가 **CANDLE-PAGE-025("어떤 봉을 주는지 한 글자도 바뀌지 않는다")를 즉시 위반**한다. 그래서 §7 ⑤의 `cursorApplies` 판정에서 주식 `1m`을 제외한다.
- **`hasNext=false`·`nextCursor=null` 고정 위치는 `CandleQueryService`(§7 ⑨) 한 곳**이다. provider나 `StockReplayService`에 이 규칙을 두지 않는다 — 봉을 어떻게 만드느냐가 아니라 **"이 조합은 이어받을 과거가 없다"는 계약 판단**이므로 봉투를 만드는 자리에 있어야 한다(ADR-0002).
- 그래서 주식 `1m`은 **봉이 200개여도 `hasNext=false`**다. 이것이 CANDLE-PAGE-007의 유일한 예외이고, 필드 이름·형태는 같으므로 프론트는 분기 없이 같은 코드로 "여기서 끝"을 읽는다.
- **형식이 잘못된 커서는 주식 `1m`에서도 400이다**(§4-2). 무시되는 것은 "형식이 맞는 커서의 효과"이지 형식 검증이 아니다.
- 038 장외 폴백·세션 미준비 시 빈 배열·미마감 분봉 제외는 전부 그대로다. 유일한 변화는 그 봉들이 `content`에 담긴다는 것뿐이다.

## 11. 데이터 모델

**신규 테이블 없음. 신규 컬럼 없음. 신규 인덱스 없음. 신규 Flyway 마이그레이션 없음.**

- 집계 결과를 저장하지 않는다(CANDLE-PAGE-020) — `stock_candles` 1분봉 실시간 집계 구조를 그대로 유지한다.
- 과거 방향 조회는 기존 `findByInstrumentIdAndTradingDateBetween...`·`findDistinctTradingDate...`가 `(instrument_id, trading_date, candle_time)` UNIQUE 인덱스의 선두 컬럼을 그대로 타므로 인덱스 추가가 필요 없다.
- 커서는 서버 상태가 아니라 요청 파라미터이므로 저장할 것이 없다.
- 따라서 **ADR-0004가 요구하는 마이그레이션 동반 조건에 해당하지 않는다.** 엔티티 변경이 없으므로 파괴적 변경 2단계 배포(ADR-0021 §결정 7)도 대상이 아니다 — 이번 배포에서 나눠야 하는 것은 스키마가 아니라 **응답 형태**이고, 그것은 §13이 다룬다.

## 12. 테스트 계획 (ADR-0003)

### 12-1. 계층별 대상

**단위**

| 대상 | 검증 |
|---|---|
| `CandleCursorTest` (신규) | 유효 문자열 파싱, 초 생략형(`...T09:00`) 허용, 형식 오류·빈 문자열·날짜만·쓰레기 문자열 → `VALIDATION_ERROR`. **`encode` 결과가 초까지 찍히는지**(`2026-07-22T09:00:00`) |
| `CandleQueryServiceTest` (기존 갱신) | 검증 순서(§7 ①~④), `cursorApplies` 판정(주식 `1m` 제외), **`to` 덮어쓰기 — provider에 전달된 `to`가 `cursor-1분`인지 인자 캡처로 단언**, `hasNext = size==200`, `nextCursor == content[0].sourceTime`, `hasNext=false`면 `nextCursor=null`, 주식 `1m` 강제 `false`, D-1 조기 반환(빈 봉투·**provider 미호출**을 함께 단언) |
| `StockReplayServiceTest` (기존 갱신) | **커서 상한이 주어졌을 때 `narrowRangeStart`의 역산 앵커(`queryEnd`)가 커서 직전 날짜인지** — repository 인자 캡처로 단언(CANDLE-PAGE-018). 선두 partial 필터 → 200개 캡 **순서**가 유지되어 페이지가 199개가 되지 않는지. 커서가 재생거래일보다 미래여도 `rangeEnd`가 클램프되는지(CANDLE-PAGE-021) |
| `CachedCryptoCandleProviderTest` (기존 갱신) | §9-3 표의 4개 위치(`C≤S`·`C==S`·경계 걸침·전부 캐시) 각각에서 위임/캐시 구간 인자와 `merge` 결과에 `sourceTime` 중복 0·누락 0. Redis 장애 시 전량 위임에서도 구간이 커서 기준인지. **D-2(§9-2)**: ⓐ 캐시가 성긴 200분 창 → 보충 위임이 `[effectiveFrom, effectiveTo]`로 1회 호출되고 결과가 200개인지, ⓑ 캐시가 200개를 채운 창 → **보충 위임이 호출되지 않는지**(`verify(delegate, never())`), ⓒ 보충 위임 200개 + 캐시 전용 진행 중 봉 → 최신 200개로 잘리고 겹치는 시각은 캐시 값이 이기는지 |
| `BithumbRestCandleProviderTest` (기존 갱신) | `MockRestServiceServer`로 정규화된 `to`가 빗썸 배타 `to`(`+1초`)로 나가는지, `count`가 200을 넘지 않는지 |

**슬라이스**

| 대상 | 검증 |
|---|---|
| `@WebMvcTest InstrumentControllerTest` (기존 갱신) | 200 응답이 **봉투 3필드**인지(`$.content`·`$.nextCursor`·`$.hasNext`), 기존 배열 단언(`$[0].sourceTime` 등)을 `$.content[0]...`로 옮긴 **회귀 고정**, `nextCursor` 문자열이 `$.content[0].sourceTime`과 **동일 문자열**인지, 잘못된 `cursor` 400, 잘못된 `interval`+유효 커서 → 400, 없는 종목+잘못된 커서 → 404, 코인 실패 502, 미인증 401, 주식 `1m`+커서 → 200·`hasNext=false` |
| `@DataJpaTest` | **신규 쿼리가 없으므로 추가 없음.** 기존 `StockCandleRepositoryTest` 회귀 확인만 |

**통합(Testcontainers)**

| 대상 | 검증 |
|---|---|
| `CandleQueryServiceIntegrationTest` (기존 갱신) | 여러 거래일 분봉을 시드해 `1d`·`1w`·`1M` **다중 페이지 이어받기** — 1페이지 → `nextCursor` → 2페이지, 합집합에 `sourceTime` 중복 0·누락 0, 커서 없이 넓게 한 번에 조회한 결과(상한 안일 때)와 **집합·순서 일치**, 끝까지 페이징하면 `hasNext=false`, **정확히 200개인 마지막 페이지 뒤에 빈 페이지 1회**가 나오고 거기서 끝나는지 |
| 같은 파일 | 커서 없는 요청의 값·개수·정렬이 이전과 동일하고 차이가 `content` 포장뿐임을 4개 `interval` × 주식·코인 조합으로 회귀 고정 |
| `StockReplayHoldFallbackIntegrationTest` (기존 갱신) | CLOSED 폴백 + 커서 조합에서 상한이 커서를 넘지 않고 종료가 정상인지(§8-6) |
| 코인 | `FakeCryptoCandleProvider` 시드로 자동화. **실제 빗썸 호출은 자동 테스트에 넣지 않는다**(C-005) |

**외부 스모크(자동 테스트와 구분 보고 — C-005)**

- `SPRING_PROFILES_ACTIVE=crypto-real`로 실제 빗썸 연속 조회를 수행해 **① 상장 이전 구간의 실제 응답 형태(빈 배열인지 오류인지)**를 관측한다. **①은 여전히 미확인이므로 그대로 둔다.**
- **② 체결이 없는 분이 섞인 창에서도 응답이 정확히 `count`개인지, 그리고 가장 오래된 봉이 요청 `from`보다 과거인지**를 관측한다. 이것이 §9-1(위임 경로의 개수 기반 `hasNext`)이 기대는 **유일한 외부 사실**이므로, 판단 근거가 아니라 **설계 전제의 실측 확인**으로 성격이 바뀌었다. `BithumbRestCandleProviderTest`가 `MockRestServiceServer`로 같은 계약을 고정하지만 mock은 빗썸의 실제 동작을 증명하지 못한다(ADR-0003).
- 관측한 그대로 `docs/api-contracts.md`에 적고, **추측을 적지 않는다.** 스모크 결과는 PR 본문에 자동 테스트와 구분해 보고한다.

### 12-2. spec 완료 조건 ↔ 테스트 매핑

| spec 완료 조건 | 대응 테스트 |
|---|---|
| 커서 없는 기존 요청 값 회귀 없음(형태만 변경) | 통합 회귀(4 interval × 2 시장) + `@WebMvcTest` 배열→`content` 이관 단언 |
| 응답 봉투 3필드 균질 | `@WebMvcTest` 전 조합 + `CandleListResponse` 타입 자체 |
| 첫 페이지 | 통합(코인 `1m`·집계봉 각각) |
| 이어받기(중복 0·누락 0, 넓은 조회와 일치) | 통합 다중 페이지 |
| 데이터 끝 경계 + 200개 정확히일 때 빈 페이지 1회 | 통합 다중 페이지 종료 시나리오 |
| 커서 상한 배타 | 단위(`CandleQueryServiceTest` `to = cursor-1분`) + 통합(직전 페이지 마지막 봉 재등장 없음) |
| 읽기 상한 유지(200) | 단위(`CachedCryptoCandleProvider` 200분 클램프·`BithumbRestCandleProvider` count) + 통합 개수 단언 |
| 집계봉 기준점 이동 | `StockReplayServiceTest` 앵커 인자 캡처(§8-1) |
| 공개 상한 우회 불가 | `StockReplayServiceTest`(미래 커서 클램프·컷오프·`READY` 없음·09:01 이전) |
| 코인 캐시·위임 경계 | `CachedCryptoCandleProviderTest` §9-3 4개 위치 + Redis 장애 + D-2 3케이스(ⓐⓑⓒ) |
| 주식 `1m` | `@WebMvcTest`(200·`hasNext=false`) + 단위(`cursorApplies=false`로 provider 인자 무변경) |
| 커서 + `to` 동시 지정 | 단위(전달된 `to`가 커서 파생값) + `@WebMvcTest`(400 아님) |
| 잘못된 커서 400 + 검증 순서 | `CandleCursorTest` + `@WebMvcTest` 순서 케이스 3종 |
| 배포 순서 | §13 (테스트 아님 — PR 본문 기록으로 확인) |
| 문서 동기화(`api-routes`·`api-contracts`·`prd` §3·013·027 이력 표시) | 리뷰 체크 |
| `./gradlew build` 통과 | 마무리 빌드 |

## 13. 배포 순서 실행 계획 (CANDLE-PAGE-027~029)

프론트는 별도 레포(`FinPlay`)이고 S3에서 따로 배포되며(ADR-0022), `dev` 머지가 곧 백엔드 배포다(ADR-0021). **순서를 뒤집으면 백엔드 머지 순간 운영 차트가 깨진다.**

| 단계 | 레포 | 하는 일 | 완료 판정 |
|---|---|---|---|
| 1 | `FinPlay` | 캔들 응답을 `data.content ?? data`로 읽어 배열·봉투를 **모두** 처리하도록 수정 후 배포. **이 배포는 백엔드 변경 없이 현재 운영(배열 응답)에서 그대로 동작해야 한다** | 운영 차트가 배열 응답으로 정상 렌더 |
| 2 | `finplay-api` | 이 spec의 백엔드 변경을 `dev`에 머지 | 1단계 배포 반영 확인 **후에만** |
| 3 | `FinPlay` | 봉투 안정화 후 `?? data` 폴백 제거 | 별도 후속 작업, 백엔드 변경 없음 |

**백엔드 PR에서 확인·기록할 것** (프론트 레포에 손대지 않으므로 백엔드가 할 수 있는 것은 확인과 기록이다).

1. 프론트 폴백 PR **번호와 머지·배포 시각**을 백엔드 PR 본문에 적는다.
2. **운영에 실제로 반영됐음**을 확인한 근거를 적는다 — 배포된 프론트가 현재의 **배열 응답**으로 차트를 정상 렌더하는지 육안 확인(브라우저 네트워크 탭에서 캔들 응답이 아직 배열인 상태로 차트가 그려지는 것). "PR이 머지됐다"가 아니라 "배포가 반영됐다"가 판정 기준이다.
3. 위 두 줄이 PR 본문에 없으면 **머지하지 않는다** — 리뷰어의 차단 사유로 삼는다.
4. 3단계(폴백 제거)를 **후속 이슈로 등록**하고 번호를 PR 본문에 남긴다.
5. 머지 직후 운영 차트를 1회 육안 확인하고, 깨지면 앱만 되돌리는 롤백으로 복구 가능함을 확인한다(스키마 변경이 없으므로 롤백이 안전하다 — §11).

함께 남길 후속 작업 1건: **주식 `1m` 과거 거래일 조회를 위한 ADR 초안·이슈**(어느 시간축을 차트의 시간축으로 삼을지). 이 spec은 그 결정을 선점하지 않는다.

## 14. spec 재검토 필요 — 사용자 판단을 기다리는 항목

> plan에서 임의로 뒤집지 않았다. **현재 이 절에 착수를 막는 항목은 없다.** 이전 판(§14-1 "코인 `1m` 조기 종료")은 코드 재검증으로 전제가 틀린 것이 확인돼 내렸다 — 아래 14-0에 결과만 남긴다.

### 14-0. (내린 항목, 기록용) 코인 `1m` 조기 종료 우려 — 전제가 틀렸다

- 이전 판은 "빗썸이 체결 없는 분의 봉을 생략하면 200분 창에서 190개만 돌아와 `hasNext=false`가 된다"고 적었고, 그 전제는 **응답이 `[from, to]`로 잘린다**는 것이었다.
- **그 전제가 코드에 없다.** `BithumbRestCandleProvider.getCandles`(71~83행)는 `from`으로 응답을 거르지 않고, `from`은 `resolveCount`(98~118행)의 `count` 계산에만 쓰인다. 응답은 `to` 기준 최신 `count`개의 **존재하는 봉**이므로 빈 분이 있으면 창보다 더 과거까지 뻗어 200개를 채운다. `CandleQueryService`(39~41행)와 `CachedCryptoCandleProvider.merge`(98~103행)에도 구간 필터가 없다. 유일한 필터는 매도 회고 경로의 `CryptoPostSellFeedbackReader.minuteCandlesWithin`(208~212행)이고 캔들 조회 API와 무관하다(§9-1).
- 따라서 **전량 위임 경로에서는 개수 기반 `hasNext`가 의도대로 동작하며 spec 수정이 필요 없다.** (a)·(b) 선택지도 필요 없다.
- **다만 우려 자체가 사라진 것은 아니고 원인이 바뀌었다** — 실제 조기 종료 위험은 빗썸이 아니라 **Redis 캐시 구간**에 있다. 그쪽은 추측이 아니라 코드로 확인되므로 스모크를 기다리지 않고 §9-2 결정 D-2로 설계에 반영했다. **D-2는 spec의 `hasNext` 규칙을 바꾸지 않으므로 이 절로 올라오지 않는다.**

### 14-1. (확인만 필요, 계약 변경 아님) 커서의 분 단위 내림 해석

§1 마지막 박스의 내용이다. 분 경계가 아닌 커서를 주면 분 단위로 내림 해석된다 — 정상 페이징에서는 발생하지 않고, spec을 뒤집지도 않는다. **계약 문서에 한 줄 적는 것으로 충분하다고 판단했으나**, "커서는 소비자가 임의 시각을 넣어도 동작한다"를 spec이 성격으로 인정했으므로 명시적으로 남긴다.

## 15. 함께 갱신할 문서 (컨트롤러 변경과 같은 커밋 — CLAUDE.md 규칙 7·10)

| 문서 | 갱신 내용 |
|---|---|
| `ai/api-routes.md` | 라우트 표에 `&cursor=` 추가, 응답을 `CandleListResponse`로, "페이징은 1차 범위가 아니다" 서술 교체, 근거에 048·이슈 #473 추가 |
| `docs/api-contracts.md` | §4 전체(봉투 3필드·`cursor` 입력 명세·`to` 무시 규칙·빈 마지막 페이지·데이터 끝 판정·분 단위 내림 해석·**코인에서 `content[0]`이 `from`보다 과거일 수 있음**·오류 표) |
| `ai/prd.md` §3 | `CANDLE-PAGE-*` 행을 **"완료"**로 추가. 근거 칸에 **PR 번호 + "주식 `1m` 과거 거래일 조회는 별도 ADR·이슈"** |
| `ai/specs/013-candle-interval/`·`027-crypto-tick-candle-cache/` | "페이지네이션·커서 도입은 범위 제외" 문장에 **"2차 고도화(이슈 #473, 048)에서 도입됨"** 이력 표시만 추가(문장 삭제 금지) |
