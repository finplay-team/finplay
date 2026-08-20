# Tasks: 캔들 차트 과거 구간 탐색 — 과거 방향 커서 페이지네이션

> 근거 문서: `./spec.md`(CANDLE-PAGE-001~029), `./plan.md`(절 번호는 각 항목에 표기). 브랜치 `feat/473-candle-history-pagination`.
>
> **순서 의존성**: 1 → 2 → (3·4는 서로 독립, 둘 다 2 이후) → 5 → 6. 2번이 `CandleQueryService` 반환 타입을 봉투로 바꾸므로 컨트롤러·서비스는 같은 커밋이어야 컴파일된다. 3·4번은 provider·`StockReplayService` 시그니처가 바뀌지 않으므로(plan §6-7) 서로 충돌하지 않는다.
>
> **이 spec은 백엔드 커밋만으로 끝나지 않는다.** 맨 아래 "머지 전 확인" 절을 6번 항목에서 함께 처리한다.

- [x] **1. 커서 값 객체 + 봉투 DTO (순수 추가, 기존 경로 무변경)**
  `CandleCursor`(`com.finplay.api.market.service`, plan §6-2) — `parse`는 `DateTimeFormatter.ISO_LOCAL_DATE_TIME`,
  실패 시 `BusinessException(VALIDATION_ERROR)`. **`encode`는 `toString()`을 쓰지 않고 `yyyy-MM-dd'T'HH:mm:ss`로
  초를 항상 찍는다** — `sourceTime` 직렬화 표기와 갈리면 CANDLE-PAGE-001 위반이다.
  `CandleListResponse`(`market.dto.response`, plan §6-1) — `content`/`nextCursor`/`hasNext` 3필드,
  `OrderListResponse`와 필드 이름·순서·컴팩트 생성자 `List.copyOf`까지 동일. 항목 타입은 기존 `CandleResponse` 재사용.
  `CandleCursorTest` 신설(정상·초 생략형 `...T09:00`·빈 문자열·날짜만·쓰레기 문자열·`encode` 초 표기).
  대응: CANDLE-PAGE-001·005·009 / plan §6-1·6-2·12-1.
  **완료 판정**: `CandleCursorTest`가 통과하고, 두 신규 타입이 아직 어디에서도 참조되지 않은 채 기존 테스트가 전부 그대로 통과한다.

- [x] **2. `CandleQueryService` 커서 정규화·봉투 조립 + 컨트롤러 파라미터 + API 문서 동기화**
  `CandleQueryService.getCandles`에 `cursor`(String) 파라미터를 더하고 plan §7의 ①~⑨ 순서를 그대로 구현한다 —
  `interval` 400 → 종목 404 → 시장 판정 → 커서 파싱 400 → `cursorApplies`(주식 `1m` 제외, plan §10) →
  `to := cursor.minusMinutes(1)`(원래 `to`는 값·검증 모두에서 버린다) → **D-1 하한 역전 시 provider를 부르지 않고
  빈 봉투 즉시 반환**(plan §5) → provider 호출(기존 그대로) → `hasNext = 주식1m ? false : size==200`,
  `nextCursor = hasNext ? encode(content.get(0).sourceTime()) : null`.
  `InstrumentController.getCandles`는 반환 타입과 `@RequestParam(required=false) String cursor` **두 가지만** 바뀐다
  (`@DateTimeFormat` 금지 — 바인더 단계 파싱 실패가 검증 순서를 깬다, plan §3). `getCryptoCandles`는 손대지 않는다(plan §6-5).
  같은 커밋에서 `docs/api-routes.md`·`docs/api-contracts.md`를 갱신한다(CLAUDE.md 규칙 7, plan §15) —
  `&cursor=` 추가, 응답을 `CandleListResponse`로, **"200개를 넘는 구간을 이어붙이는 페이징은 1차 범위가 아니다" 서술 교체**,
  그리고 plan §4가 지정한 5가지(`to` 무시·빈 마지막 페이지 1회·끝 판정은 `hasNext`뿐·코인에서 `content[0]`이 `from`보다
  과거일 수 있음·커서의 분 단위 내림 해석)를 함께 적는다.
  테스트: `CandleQueryServiceTest` 갱신(검증 순서, `cursorApplies`, **provider에 전달된 `to`가 `cursor-1분`인지 인자 캡처**,
  `hasNext`/`nextCursor` 규칙, D-1 조기 반환 시 **provider 미호출**), `@WebMvcTest InstrumentControllerTest` 갱신
  (기존 `$[0]...` 단언을 `$.content[0]...`로 옮긴 회귀 고정, `nextCursor`가 `$.content[0].sourceTime`과 동일 문자열,
  잘못된 커서 400, 잘못된 `interval`+유효 커서 400, 없는 종목+잘못된 커서 404, 502, 401, 주식 `1m`+커서 → 200·`hasNext=false`).
  대응: CANDLE-PAGE-001~012·025·026 / plan §5·6-3·6-4·7·10·12-1·15.
  **완료 판정**: 커서 없는 요청의 봉 값·개수·정렬이 그대로인 채 모든 응답이 봉투 3필드가 되고, 두 API 문서가 같은 커밋에서 새 계약을 담는다.

- [x] **3. 집계봉 기준점 이동 검증 (`1d`·`1w`·`1M`) — 코드 변경은 주석 한 줄뿐**
  `rangeEnd`가 커서 직전으로 옮겨지면 `lookbackFloor`·`narrowRangeStart`·`subList` 캡이 자동으로 따라오므로
  `StockReplayService` **본문은 고치지 않는다**(plan §8-1·8-2). 선두 partial 필터 → 200개 캡 **순서에
  "페이징 도입 후에는 hasNext 판정까지 이 순서에 의존한다 — 048" 주석 한 줄만 추가**한다(plan §8-3).
  꼬리 partial 필터를 새로 만들지 않는다(plan §8-4).
  테스트(`StockReplayServiceTest` 갱신): 커서 상한이 주어졌을 때 **`narrowRangeStart`의 역산 앵커(`queryEnd`)가
  커서 직전 날짜인지 repository 인자 캡처로 단언**, 필터·캡 순서가 유지되어 페이지가 199개로 새지 않는지,
  커서가 재생거래일보다 미래여도 `rangeEnd`가 클램프되는지, `READY` 세션 없음·09:01 이전에서 커서 유무와
  무관하게 013 계약이 유지되는지.
  대응: CANDLE-PAGE-018~024 / plan §8·12-1.
  **완료 판정**: 커서를 준 요청이 "최신 200버킷"이 아니라 "커서보다 과거 방향 최신 200버킷"을 읽는 것이 인자 캡처로 단언되고, 어떤 커서로도 미공개 분봉이 새지 않는다.

- [x] **4. 코인 캐시·위임 경계 + 결정 D-2(200개 보충) + 외부 스모크**
  **독립 항목으로 두는 근거**: 코인 경로에만 적용되고, `CachedCryptoCandleProvider` 시그니처가 바뀌지 않아 2·3번과
  컴파일 의존이 없으며, 붙는 테스트가 §9-3 4개 위치 + D-2 3케이스로 다른 항목의 두 배다. 2번에 얹으면 커밋이
  "봉투 전환"과 "캐시 보충"이라는 성격 다른 두 변경으로 섞여 회귀 원인 추적이 어려워진다.
  `CachedCryptoCandleProvider.getCandles` 89행 `merge` 직후에 규칙 하나를 붙인다(plan §6-6·9-2) —
  **병합 결과가 200개 미만이고 요청 창이 200분 폭이면 `[effectiveFrom, effectiveTo]`로 위임을 1회 더 호출해 merge,
  200개를 넘으면 최신 200개만 남긴다.** 겹치면 기존대로 캐시 봉이 이긴다(027 우선순위 불변). 커서·페이지·`hasNext`는
  여전히 provider가 알지 못한다.
  테스트: `CachedCryptoCandleProviderTest` 갱신 — plan §9-3 표의 4개 커서 위치(`C≤S`·`C==S`·경계 걸침·전부 캐시)에서
  `sourceTime` 중복 0·누락 0, Redis 장애 시 전량 위임에서도 구간이 커서 기준인지, **D-2 ⓐ 성긴 캐시 → 보충 위임 1회·결과 200개 /
  ⓑ 캐시가 200개를 채운 창 → `verify(delegate, never())` / ⓒ 보충 200개 + 캐시 전용 진행 중 봉 → 최신 200개로 잘리고
  겹치는 시각은 캐시 값이 이김**. `BithumbRestCandleProviderTest`는 `MockRestServiceServer`로 정규화된 `to`가
  배타 `to`(`+1초`)로 나가고 `count`가 200을 넘지 않는지 확인한다. 자동 테스트의 코인은 전부 Fake다(C-005).
  **외부 스모크(자동 테스트와 구분 보고)**: `SPRING_PROFILES_ACTIVE=crypto-real`로 ① 상장 이전 구간의 실제 응답 형태
  (빈 배열인지 오류인지 — spec이 미확정으로 남긴 항목), ② 체결 없는 분이 섞인 창에서도 응답이 정확히 `count`개이고
  가장 오래된 봉이 `from`보다 과거인지를 관측하고, **관측한 그대로** `docs/api-contracts.md` 코인 절에 적는다. 추측 금지.
  대응: CANDLE-PAGE-013~017·024 / plan §6-6·9-1·9-2·9-3·12-1.
  **완료 판정**: 커서가 `since` 워터마크 앞·뒤·정확히 그 위치일 때 중복·누락이 0건이고, 성긴 캐시 구간에서 `hasNext`가 과거를 남긴 채 `false`가 되지 않으며, 스모크 관측 결과가 계약 문서에 기록된다.

- [ ] **5. Testcontainers 통합 — 다중 페이지 이어받기와 무커서 회귀**
  여러 항목(2·3·4)에 걸치는 시나리오이므로 별도로 둔다.
  `CandleQueryServiceIntegrationTest` 갱신 — 여러 거래일 분봉을 시드해 `1d`·`1w`·`1M` 다중 페이지 이어받기
  (1페이지 → `nextCursor` → 2페이지, 합집합에 `sourceTime` 중복 0·누락 0), 커서 없이 넓게 한 번에 조회한 결과
  (상한 안일 때)와 **집합·순서 일치**, 끝까지 페이징하면 `hasNext=false`·`nextCursor=null`,
  **마지막 페이지가 정확히 200개일 때 빈 `content` 페이지가 1회 더 나오고 거기서 끝나는 것**(CANDLE-PAGE-007),
  그리고 커서 없는 요청의 값·개수·정렬이 이전과 동일하고 차이가 `content` 포장뿐임을 **4개 `interval` × 주식·코인**으로 회귀 고정.
  `StockReplayHoldFallbackIntegrationTest` 갱신 — CLOSED 폴백 + 커서 조합에서 상한이 커서를 넘지 않고 종료가 정상인지(plan §8-6).
  `@DataJpaTest`는 신규 쿼리가 없어 추가하지 않는다(기존 회귀 확인만). 신규 Flyway 마이그레이션 없음(plan §11).
  대응: CANDLE-PAGE-006·007·019·022 / plan §12-1·12-2.
  **완료 판정**: 두 페이지 이상을 실제 MySQL 데이터로 이어받아 중복·누락 0건이 고정되고, 빈 마지막 페이지 1회가 계약대로 재현된다.

- [ ] **6. 문서 마무리 · 후속 작업 등록 · 빌드**
  `docs/prd.md` §3 "구현 현황"에 `CANDLE-PAGE-*` 행을 **"완료"**로 추가하고 근거 칸에 **PR 번호 + "주식 `1m` 과거
  거래일 조회는 별도 ADR·이슈"**를 적는다(CLAUDE.md 규칙 10, plan §15).
  `docs/specs/013-candle-interval/`·`027-crypto-tick-candle-cache/`의 "페이지네이션·커서 도입은 범위 제외" 문장에
  **문장을 지우지 않고** "2차 고도화(이슈 #473, 048)에서 도입됨" 이력 표시만 덧붙인다.
  후속 이슈 2건을 등록하고 번호를 PR 본문에 남긴다 — ① **주식 `1m` 과거 거래일 조회의 시간축 ADR 초안**(이 spec은
  선점하지 않는다), ② **프론트 `?? data` 폴백 제거**(CANDLE-PAGE-029, 별도 레포).
  아래 "머지 전 확인" 절을 PR 본문에 옮겨 적고 전부 채운다.
  마지막에 `./gradlew build`를 실행해 통과를 확인한다(실패하면 고치고 재실행).
  대응: CANDLE-PAGE-027~029 / plan §13·15.
  **완료 판정**: §3 표·013·027 이력 표시·후속 이슈 2건이 모두 반영되고 `./gradlew build`가 통과한다.

## 머지 전 확인 (백엔드 커밋으로 끝나지 않는 것 — CANDLE-PAGE-027~029)

> 이 절은 코드 작업이 아니라 **머지 차단 조건**이다. 1~6번을 다 끝내도 아래가 비어 있으면 `dev`에 머지하지 않는다 —
> `dev` 머지가 곧 배포이고(ADR-0021) 프론트는 S3에서 따로 배포되므로(ADR-0022), 순서를 뒤집으면 그 순간 운영 차트가 깨진다.
> 프론트 작업 자체는 별도 레포(`FinPlay`)이며 이 tasks의 항목이 아니다.

- [ ] 프론트가 캔들 응답을 `data.content ?? data`로 읽도록 바꾼 **PR 번호와 머지·배포 시각**을 백엔드 PR 본문에 적었다.
- [ ] 그 배포가 **운영에 실제로 반영됐음**을 확인한 근거를 적었다 — 배포된 프론트가 **아직 배열인 현재 응답**으로 차트를 정상 렌더하는 것을 육안 확인(브라우저 네트워크 탭). 판정 기준은 "PR이 머지됐다"가 아니라 "배포가 반영됐다"다.
- [ ] 위 두 줄이 없으면 리뷰어가 머지를 차단한다는 것을 PR 본문에 명시했다.
- [ ] 3단계(프론트 폴백 제거)를 후속 이슈로 등록하고 번호를 남겼다(6번 항목).
- [ ] 외부 스모크 결과를 자동 테스트와 **구분해** PR 본문에 보고했다(C-005, 4번 항목).
- [ ] 머지 직후 운영 차트를 1회 육안 확인한다. 스키마 변경이 없으므로(plan §11) 문제 시 앱만 되돌리는 롤백으로 복구된다.

## 리뷰 체크리스트 (plan §6-7)

- [ ] `StockPriceProvider`·`CryptoCandleProvider` 인터페이스, `StockReplayService` 본문, `BithumbRestCandleProvider`, `LocalForcedOpenStockPriceProvider`, `FakeCryptoCandleProvider`, `KisHistoricalReplayPriceProvider`, `CandleResponse`, `CandleInterval`, `StockCandleAggregator`, `StockCandleRepository`가 diff에 **등장하지 않는다**. 등장하면 PR에서 "왜 필요했는가"를 설명한다.
- [ ] 선두 partial 버킷 필터 → 200개 캡 **순서를 바꾸는 리팩터링이 없다**(plan §8-3 — `hasNext` 정확성이 이 순서에 걸려 있다).
- [ ] 신규 Flyway 마이그레이션·신규 테이블·신규 오류 코드·`limit` 파라미터·`/candles/history` 엔드포인트가 **없다**.
