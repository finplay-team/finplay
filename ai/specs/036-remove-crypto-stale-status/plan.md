# Plan: 코인 시세 STALE 판정 완전 제거

## 관련 문서

- Spec: `./spec.md`
- 대체 대상: `docs/specs/032-price-quote-stale-split/spec.md` PRICE-STALE-001·002·004(되돌림), PRICE-STALE-005(무의미화), `docs/specs/034-crypto-price-rest-backup/spec.md` PRICE-REST-004(무의미화) — 상세 대응표는 spec.md 상단 참조.
- 관련 ADR: `docs/adr/0002-architecture.md`(레이어 규칙 — 판정 로직은 service, `PriceStore`는 key 조립·원자적 조회 전용 컴포넌트), `docs/adr/0003-testing-strategy.md`(테스트 레벨), `docs/adr/0021-continuous-deployment.md`(dev 머지 = 즉시 배포 — 아래 "배포 시 확인 사항" 참조).
- 선행 spec: `docs/specs/003-market-data`(MKT-003·MKT-004 원문), `docs/specs/027-crypto-tick-candle-cache`(각주로 원문 대체 사실만 남기는 선례).

## 설계 결정 — `PriceStatus`를 3값에서 2값으로 되돌린다

032는 boolean 플래그 방식(안 B)을 기각하고 enum 확장(안 A)을 택했다 — "여러 소비자가 자기가 이미 쓰던 이분법(`==AVAILABLE` vs `==UNAVAILABLE`)에 따라 자동으로 원하는 방향으로 분기된다"는 이유였다. 이 spec은 그 이분법 자체가 다시 완전히 맞아떨어지는 상황으로 되돌린다 — `STALE`이 발생할 조건 자체가 없어지므로, enum에 값을 남겨두면 **영원히 도달하지 않는 죽은 분기**가 여러 클래스에 남는다(conventions.md C-002 최소 구현 위반).

**따라서 `STALE`을 enum에서 완전히 제거한다** — spec.md "이 spec이 되돌리는 것" 표의 PRICE-STALE-002 원복. `PriceStatus.STALE`을 검사하는 테스트는 어차피 전부 다시 작성해야 한다(값을 남겨도 "STALE이 나오는지" 검증하던 단정이 전부 실패하므로) — 값을 지우는 쪽이 죽은 코드를 남기지 않고 컴파일러가 누락을 강제로 찾아준다.

```java
// 변경 전 (032·034)
public enum PriceStatus {
    AVAILABLE,
    STALE,       // 연결 유지 + 마지막 수신 틱이 10초 초과. price·sourceTime은 non-null.
    UNAVAILABLE
}

// 변경 후 (이 spec) — 032 이전으로 원복
public enum PriceStatus {
    AVAILABLE,
    UNAVAILABLE
}
```

## API 설계

엔드포인트 URL·메서드·응답 필드 구조는 바뀌지 않는다 — `status` 값 범위가 3값에서 2값으로 좁아질 뿐이다.

| Method | URL | 응답 변화 | 설명 |
|---|---|---|---|
| GET | /api/instruments/{instrumentId}/price | `status`에서 `"STALE"` 값이 더 이상 나오지 않는다 — 연결 유지+수신 이력 있음은 경과 시간과 무관하게 항상 `"AVAILABLE"` | PRICE-NOSTALE-001 |
| GET | /api/cryptos/stream (`snapshot` 이벤트) | `prices[].status`에서 `"STALE"` 값이 더 이상 나오지 않는다(코드 변경 없이 자동 반영) | PRICE-NOSTALE-003 |
| POST | /api/orders, /api/orders/limit | 코인 체결 판정 변화 없음(034에서 이미 표시 규칙에 위임) — 다만 그 표시 규칙 자체가 이제 항상 `AVAILABLE`만 주므로 결과적으로 `PRICE_UNAVAILABLE`이 뜨는 코인 케이스가 "연결 끊김·수신 이력 없음" 둘로만 좁아진다 | PRICE-NOSTALE-001 |

## 입력 명세

이번 spec은 새 요청 파라미터·요청 DTO를 추가하지 않는다(응답 상태값 범위가 좁아질 뿐).

## 코드 변경 설계

### `PriceStatus` (`market/service/PriceStatus.java`)

`STALE` 값 제거, 2값으로 되돌림(위 "설계 결정" 참조).

### `PriceQueryService` (`market/service/PriceQueryService.java`)

`getCryptoDisplayPriceQuote(Instrument)` — stale 분기 제거.

```java
// 변경 전 (032·034)
private PriceQuoteDto getCryptoDisplayPriceQuote(Instrument instrument) {
    if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
        return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null);
    }
    return priceStore.getLatestPrice(instrument.getSymbol())
        .map(p -> new PriceQuoteDto(p.price(), p.receivedAt(),
            priceStore.isStale(p.observedAt()) ? PriceStatus.STALE : PriceStatus.AVAILABLE, null))
        .orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));
}

// 변경 후 (이 spec) — 연결 유지+수신 이력 있음이면 경과 시간과 무관하게 항상 AVAILABLE
private PriceQuoteDto getCryptoDisplayPriceQuote(Instrument instrument) {
    if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
        return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null); // 연결 끊김 — 이번 spec 대상 아님
    }
    return priceStore.getLatestPrice(instrument.getSymbol())
        .map(p -> new PriceQuoteDto(p.price(), p.receivedAt(), PriceStatus.AVAILABLE, null))
        .orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null)); // 받은 적 없음 — 이번 spec 대상 아님
}
```

`getCryptoDisplayPriceQuotes(List<Instrument>)` — 동일한 모양으로 배치 버전에서도 `priceStore.isStale(...)` 삼항 분기를 제거하고 `PriceStatus.AVAILABLE`로 고정한다. 연결상태를 루프 밖에서 1회만 조회하는 기존 최적화(PR #97 리뷰 권장사항)는 그대로 유지한다.

`getOrderExecutionPrice(Instrument)` — **코드 변경 없음.** 코인 분기가 이미 `getCryptoDisplayPriceQuote`에 위임하므로(034), 위 변경만으로 체결 판정도 자동으로 항상 `AVAILABLE`만 받는다. `requireAvailable`도 코드 변경이 필요 없다 — `status == UNAVAILABLE`일 때만 던지는 로직이 2값 enum에서도 그대로 정확하다.

이 변경으로 `priceStore.isStale(...)`를 직접 호출하던 두 지점(단건·배치)이 모두 없어진다 — `PriceQueryService`는 더 이상 `isStale`을 호출하지 않는다.

### `PriceStore` (`market/store/PriceStore.java`) — **변경 없음**

`isStale(LocalDateTime)`·`STALE_THRESHOLD`·`observedAt`/`receivedAt` 필드 분리·`recordObservation(...)`은 그대로 둔다. 이유: `isPriceAvailable(symbol)`이 내부적으로 `isStale(...)`을 계속 쓰고, 이 메서드는 `CryptoPriceSnapshotService.recordSnapshot(...)`(AI 피드백 변동 카드의 재료 신뢰도 게이트, `market/service/CryptoPriceSnapshotService.java:49`)이 여전히 직접 호출한다 — 이 소비자는 화면 표시·체결과 무관한 별개 목적이라 이번 spec의 범위가 아니다(spec.md "범위 제외"). 확인한 결과 이 저장소 안에서 `PriceQueryService`를 거치지 않고 `PriceStore`를 직접 호출하는 소비자는 `CryptoPriceSnapshotService`뿐이다(grep으로 확인 — `isPriceAvailable`·`getLatestPrices`의 유일한 프로덕션 호출부).

### `BithumbRestTickerPoller` (`market/feed/BithumbRestTickerPoller.java`) — **변경 없음**

REST 폴링 백업(034 PRICE-REST-002)은 그대로 3초 주기로 관측 시각을 갱신한다. `CryptoPriceSnapshotService`의 신뢰도 게이트가 여전히 이 갱신을 소비하므로 끄지 않는다.

### 하위 소비자 — 코드 변경 없음, 테스트만 정리

| 클래스 | 조건식 | 이 spec 이후 |
|---|---|---|
| `HoldingValuationService.buildValuation` | `quote.status() != PriceStatus.AVAILABLE` | 그대로 정확 — 2값에서 `== UNAVAILABLE`과 동일한 뜻이 된다. 코드는 고치지 않는다(더 짧게 쓸 수 있지만 diff를 늘릴 이유가 없다) |
| `SyntheticPriceService`·`PracticePriceSessionService` | `quote.status() == PriceStatus.AVAILABLE` | 그대로 정확. **동작은 실제로 바뀐다** — 지금까지 stale이면 고정 `FALLBACK_START_PRICE`를 쓰던 경로가, 연결 유지+수신 이력 있음이면 항상 이 조건을 통과하게 되어 사실상 항상 실시세를 쓴다. 이건 이 spec의 의도된 결과다(마지막 가격을 경과 시간과 무관하게 정상 취급하므로) |
| `PracticeHoldingObservationService` | `PriceQueryService.getPrice(instrumentId)`를 그대로 호출, 조건식 없음 | 그대로. STALE 특수 취급이 사라지므로 "STALE 가격도 관찰 근거로 쓰인다"는 032의 그 서술 자체가 무의미해진다(늘 AVAILABLE만 나오므로) |
| `CryptoPriceStreamService` | `PriceQueryService.getPriceQuote`를 그대로 실어 나름 | 그대로. `snapshot`에서 `STALE`이 나오지 않게 된다 |

## 문서 동기화

- `docs/api-contracts.md` `### 종목 현재가 조회`: `STALE` 관련 서술("코인은 `status`가 `"STALE"`일 수도 있다"·"코인이 stale인 것만으로는 더 이상 409가 아니다")을 제거하고, "연결 유지+수신 이력 있음이면 경과 시간과 무관하게 항상 `AVAILABLE`"로 갱신한다. 오류 응답 칸의 409 조건을 "연결 끊김·수신 이력 없음"으로 단순화한다.
- `docs/api-contracts.md` 주문 절: "이 `STALE` 완화는 034부터 주문 체결 가격 판정에도 그대로 적용된다"는 서술을 "코인 체결 거부(409)는 연결 끊김·수신 이력 없음 두 경우뿐이며, 그 외에는 경과 시간과 무관하게 항상 마지막 가격으로 체결된다"로 갱신한다.
- `docs/api-contracts.md` `### 코인 SSE 스트림` `snapshot` 절: `STALE` 예시·서술 제거.
- `docs/api-routes.md`: `/api/instruments/{instrumentId}/price` 라우트 설명에서 `STALE` 언급 제거, "근거" 칸에 `036` 추가.
- `docs/specs/003-market-data/spec.md` MKT-004: 034가 재작성해 놓은 현재 5개 체크박스(연결 끊김·재연결 복귀·10초 초과해도 표시·체결 모두 사용·관측 시각은 나중 것을 씀·임의값 금지) 중 "관측 시각이 10초를 넘겼더라도(stale)" 관련 항목을 "관측 시각이 얼마나 오래됐든(경과 시간 무관)"으로 다시 고쳐, `STALE` 상태 표시 자체가 없어졌다는 사실을 반영한다. 003이 정본 계약 문서로서 계속 최신 상태를 유지해온 관례(032·034가 그렇게 해왔다)를 따른다 — 각주가 아니라 본문을 다시 쓴다.
- `docs/specs/032-price-quote-stale-split/spec.md`·`docs/specs/034-crypto-price-rest-backup/spec.md`: 각 파일 최상단(제목 바로 아래)에 한 줄 각주를 추가한다 — "이 spec의 [PRICE-STALE-001·002·004 / PRICE-REST-004]는 `036-remove-crypto-stale-status`로 [되돌려졌다/무의미해졌다] — 원문 체크박스는 이력으로 유지한다." 027이 003을 대체할 때 쓴 것과 같은 패턴. 체크박스 본문은 건드리지 않는다.
- `docs/prd.md` §3 "구현 현황": PRICE-STALE-001~005 행과 PRICE-REST-001~006 행을 갱신한다(근거: 이 spec/PR 번호) — 두 행 모두 "완료(원복됨)" 또는 유사한 표현으로 이 spec이 되돌린 부분을 명시하고, 그대로인 부분(PRICE-STALE-003·005 일부, PRICE-REST-001~003·005·006)은 유지된다고 적는다. 표 형식·근거 표기는 기존 다른 행들의 관례(PR 번호 또는 spec 폴더명)를 따른다.

## 테스트 계획

- **단위 (`PriceQueryServiceTest`)**: 032·034가 추가한 STALE 관련 케이스(약 6~7개 테스트 메서드, `PriceStatus.STALE`을 직접 단정하는 것들)를 전부 "연결 유지+수신 이력 있음이면 관측 시각이 오래돼도 AVAILABLE"로 재작성한다. 특히 `priceStore.isStale(...)`를 stub하던 테스트는 그 stub 자체가 더 이상 호출되지 않으므로 stub을 제거하고 결과만 AVAILABLE로 단정한다. 연결 끊김·수신 이력 없음(UNAVAILABLE) 케이스는 그대로 회귀 확인.
- **단위 (`HoldingValuationServiceTest`)**: 기존 "STALE quote를 넣으면 UNAVAILABLE 취급"(PRICE-STALE-005 고정) 테스트를 "관측 시각이 오래된(과거엔 STALE이었던) AVAILABLE quote를 넣어도 평가금액·수익률이 채워진다"로 교체한다 — 이게 완료 조건의 "-" 깜빡임 해소 확인이다.
- **단위 (`SyntheticPriceServiceTest`·`PracticePriceSessionServiceTest`)**: 기존 "stale quote는 fallback 시작가를 쓴다" 테스트는 stale이 더 이상 존재하지 않으므로 제거하거나, "관측 시각이 오래돼도 AVAILABLE이면 실시세를 쓴다"로 의미를 바꿔 재작성한다.
- **단위 (`PracticeHoldingObservationServiceTest`)**: `createObservationUsesLastKnownPriceWhenCryptoPriceIsStale`처럼 STALE을 이름에 건 테스트는 제거하거나 이름·단정을 갱신한다.
- **단위 (`CryptoPriceStreamServiceTest`)**: STALE quote를 stub해 snapshot에 그대로 실리는 것을 확인하던 테스트를 제거하거나 AVAILABLE로 재작성한다.
- **단위 (`OrderExecutionServiceTest`)**: STALE quote로 주문이 체결되는 것을 확인하던 테스트(034가 추가)를 "경과 시간과 무관하게 AVAILABLE quote로 체결된다"로 재작성한다.
- **슬라이스 (`InstrumentControllerTest`)**: `status == "STALE"` JSON을 단정하던 테스트를 제거하고, 오래된 관측 시각에서도 `"AVAILABLE"`이 나오는 케이스로 교체한다.
- **기타**: `MarketStatusEventTest`에서 `PriceStatus.STALE`을 참조하는 부분을 확인해 컴파일이 깨지지 않게 정리한다.
- **회귀 (`CryptoCandleAndPriceIndependenceTest`)**: 기존 두 테스트 그대로 통과 확인 — 이 spec과 무관한 계약이라 손대지 않는다.
- **통합 (`OrderListIntegrationTest`)**: `OrderExecutionServiceTest`는 협력자를 전부 mock한 순수 단위 테스트라 실제 Redis(`PriceStore`)·DB로 체결까지 이어지는지 실측하지 못한다(PR #380 리뷰 권장 반영). 관측 시각을 3시간 전으로 찍고 clock을 앞으로 돌린 뒤에도 실제 `OrderService.createOrder`가 그 마지막 가격으로 체결하는지를 검증하는 핵심 시나리오 통합 테스트를 추가한다(ADR-0003).

## 데이터 모델

변경 없음 — 새 테이블·마이그레이션·Redis 키 없음. 기존 Redis 해시(`price:crypto:{symbol}`)의 `observedAt` 필드도 그대로 유지한다(`CryptoPriceSnapshotService` 경로가 계속 읽는다).

## 배포 시 확인 사항

`dev` 머지가 곧 배포다(ADR-0021). 머지 전에 확인한다.

1. **로컬 실데이터 재현** — `SPRING_PROFILES_ACTIVE=local,crypto-real ./gradlew bootRun`으로 띄워 저유동성 코인이 10초는 물론 몇 분이 지나도 `AVAILABLE`을 유지하는지 확인한다.
2. **평가손익 화면** — 보유 중인 저유동성 코인의 평가금액·수익률이 시간이 지나도 "-"로 바뀌지 않는지 확인한다(완료 조건의 실제 체감 확인).
3. **롤백 경로** — 스키마 변경이 없어 `git revert -m 1 <머지커밋>`으로 깨끗하게 되돌아간다.
