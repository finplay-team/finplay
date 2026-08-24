# Tasks: 012 AI 피드백 — 이슈 #275 (코인 매도 회고)

> **이 문서는 이슈 #275의 남은 범위만 담는다.** `./tasks.md`는 이슈 #225 전용, `./tasks-244.md`는 이슈 #244 전용이고 이 이슈도 `plan.md` §진행 상태 8개 분할 표 **밖의 별도 이슈**다(선례 #198·#244). 값·규칙의 정본은 `./spec.md` **§FEED-012**(결정 0~4·수수료)와 §C-1·§C-5, 계약은 `docs/api-contracts.md` "코인 체결의 차이" 소절이다. **여기에 값을 다시 적지 않는다.**
>
> **작성 규칙(`ai/specs/README.md`)** — 항목 하나 = implementer 1회 투입 = 커밋 1개. `run-log.md`에 시각·에이전트·실행 명령·근거를 1줄씩 남긴다. 테스트 레벨은 ADR-0003을 따른다.
>
> **완료 조건의 정본은 이슈 #275 본문**이다(1단계 결정 3건 + 2단계 구현 5건 + 공통 3건). 이 문서 §완료 조건 소유가 그 표와 항목의 대응이다.

## 이미 끝난 것 — 다시 계획하지 않는다

- **`016fb543`** — 1단계(결정) 전부. `spec.md` §FEED-012에 결정 0~4·코인 수수료율·"응답 계약이 시장별로 달라지는가"(달라지지 않음, `holdHighBasis` 공용 필드 1개만 추가)·"새 ADR이 필요한가"(불필요) 기록, `docs/api-contracts.md`에 코인 소절 추가, `context-notes.md` 갱신. **이슈 본문 1단계 3건이 이것으로 닫힌다.**
- **`3adb8192`** — 조회 경로 구현. 신설 `HoldHighBasis`·`PostSellArithmetic`·`HoldExtremes`·`CryptoPostSellFeedbackReader`, 변경 `PostSellFeedbackResponse`(`holdHighBasis`)·`PostSellFeedbackReader`(코인 400 제거 + 시장 분기 + 공용 산술 위임)·`PostSellFeedbackService`·`PriceMoveEventRepository`(`findByMarketAndOccurredAtBetween` — **이 이슈의 배치가 유일한 소비자이고 아직 아무도 부르지 않는다**)·`CandleQueryService`(`getCryptoCandles`)·`PeerStatsBatchService`(`aggregateCard`에 절대 시각 `at` 파라미터 추가 — 코인과 공유하려는 준비이고 **주식 동작은 불변**).

**남은 것은 셋이다** — 집단 비교 배치(§FEED-012 결정 3), 그 배치의 설정, 그리고 **테스트가 하나도 없다는 것**. 조회 경로는 코드가 있지만 지금은 어떤 테스트도 코인 경로를 지나가지 않는다.

## 이 이슈 전체에 걸리는 제약

- **주식 경로를 바꾸지 않는다** (이슈 §제외 범위). `PostSellFeedbackReader`·`PeerStatsBatchService`는 주식과 같은 파일이므로 **모든 항목이 주식 회귀 테스트를 통과한 채로 끝나야 한다.**
- **새 마이그레이션이 없다.** `price_move_peer_stats`는 이미 있고 코인 카드도 이미 저장된다. 유니크 축 `UNIQUE(price_move_event_id, service_date)`도 그대로 쓴다(§FEED-012 결정 3).
- **새 엔드포인트가 없다.** `ai/api-routes.md`는 대상이 아니다. `docs/api-contracts.md`는 `016fb543`에서 이미 갱신됐다 — **다시 쓰지 않는다.**
- **새 ADR을 쓰지 않는다.** §FEED-012 "새 ADR이 필요한가"가 판정을 마쳤다.
- **200봉 상한을 조정하지 않는다.** 조회 경로에서 `to`를 옮겨 가며 나눠 받는 것도 하지 않는다(§FEED-012 결정 4).
- **`aggregateCard`를 복제하지 않는다.** 주식·코인이 같은 메서드를 공유하고 호출부가 `serviceDate`·`at`만 달리 넘긴다(`3adb8192`가 그러라고 시그니처를 바꿔 두었다).

## 작업 항목

- [x] **1. `feedback.batch.crypto-peer-stats-cron` 설정 추가 (풀 크기는 2번에서)**

  §C-1 표의 `0 5 0 * * *`를 `FeedbackBatchProperties`(`src/main/java/com/finplay/api/feedback/config/FeedbackBatchProperties.java`)의 5번째 컴포넌트로 더하고(`@DefaultValue`, 기존 4개와 같은 형태 — 한 줄 주석에 "매일 00:05, 전날 KST 카드" 근거를 남긴다), `application.yml`의 `feedback.batch` 블록(`crypto-watch-cron` 다음)에 같은 값을 추가한다.

  - **`spring.task.scheduling.pool.size`는 이 항목에서 건드리지 않는다.** §C-1이 "풀 크기 = 그 시점에 등록된 `@Scheduled` 수"를 불변식으로 두었는데, 이 항목이 끝난 시점에는 아직 `@Scheduled`가 없다. 2번 항목이 스케줄 등록과 같은 커밋에서 올린다.
  - `FeedbackBatchPropertiesTest`(yml ↔ `@DefaultValue` 드리프트 테스트)가 새 필드도 덮도록 갱신한다.
  - 검증 — 단위(`FeedbackBatchPropertiesTest`에 새 키의 바인딩·드리프트 케이스 추가).

- [x] **2. `PeerStatsBatchService.runCryptoPeerStatsBatch` 신설 + 풀 크기 12 → 13**

  같은 클래스에 코인 진입점을 더한다(§FEED-012 결정 3). `@Scheduled(cron = "${feedback.batch.crypto-peer-stats-cron}", zone = "Asia/Seoul")` — **`zone`을 빠뜨리면 배포 JVM 기본이 UTC라 KST 09:05에 돈다**(§C-1).

  - **재생세션을 보지 않는다.** 주식 진입점 첫 줄인 `stockReplayService.getCurrentReplaySession().ready()` 확인을 코인에는 넣지 않는다 — 코인에 해당 개념이 없다. 이유를 Javadoc에 남긴다(주식 배치와 나란히 놓이는 코드라 "빠뜨린 것"으로 읽히기 쉽다).
  - 대상 카드는 `priceMoveEventRepository.findByMarketAndOccurredAtBetween(Market.CRYPTO, 전날 KST 00:00, 전날 KST 24:00 직전)`이다(`3adb8192`가 이미 추가한 질의). 경계는 그 질의 시그니처가 정하는 대로 쓰되 **전날 23:59:59.999999 카드가 빠지지 않는지**를 3번 항목의 테스트로 고정한다.
  - 카드 1건마다 `aggregateCard(card, card.getOccurredAt().toLocalDate(), card.getOccurredAt())`을 부른다 — **`serviceDate`는 배치 실행일이 아니라 그 카드 `occurred_at`의 KST 날짜**이고, `at`(모집단 기준 시각 `T`)은 `occurred_at` 그대로다(§FEED-012 결정 3). `CryptoPostSellFeedbackReader.buildPeerComparison`이 `card.windowEnd().toLocalDate()`로 찾으므로 **저장 키와 조회 키가 같은 규칙이어야 한다** — 어긋나면 오류 없이 `peerComparison`이 영원히 `NOT_YET`이다.
  - 카드 1건 실패가 배치를 죽이지 않는 `try/catch(RuntimeException)`·시작·종료 로그는 주식 진입점과 같은 모양으로 둔다.
  - `application.yml`의 `spring.task.scheduling.pool.size`를 12 → **13**으로 올리고 **주석의 개수 계산도 함께 갱신한다**(§C-1 — "신설 8 / 합계 13", 이 스케줄 1줄 추가). `crypto-real`·`prod` 프로필 개수를 적은 문장도 같이 맞춘다. 이슈 #125가 이 개수를 잘못 센 사고였다.
  - 검증 — `FeedbackBatchScheduleTest`에 새 진입점의 cron 표현식·`zone`이 §C-1과 같은지 확인하는 케이스 추가(기존 5개와 같은 패턴). 동작 검증은 3번 항목.

- [x] **3. 코인 집단 비교 배치 테스트**

  `PeerStatsBatchServiceIntegrationTest`와 같은 픽스처 자산을 쓰되 **코인 케이스는 새 클래스**(예: `CryptoPeerStatsBatchIntegrationTest`)에 둔다 — 기존 클래스는 재생세션 준비를 `setUp`에서 하고 있어 "세션을 보지 않는다"를 같은 파일에서 보이기 어렵다.

  - `service_date`가 **카드 `occurred_at`의 KST 날짜**로 저장되는지 — 배치 실행 시각(00:05, 즉 **다음 날**)과 다르다는 것이 이 테스트의 핵심이다. 고정 `Clock`으로 실행 시각을 통제한다.
  - **조회 키 정합** — 배치가 저장한 행을 `CryptoPostSellFeedbackReader`가 실제로 찾아 `peerComparison.status`가 `NOT_YET`에서 벗어나는지. 두 규칙이 어긋나도 컴파일·실행이 성공하므로 **이 대조가 없으면 회귀를 못 잡는다.**
  - 대상 범위 — 전날 KST 하루 안의 코인 카드만 집계되고 그제·당일 카드는 제외되는지(경계 시각 카드 포함).
  - 재생세션이 `READY`가 아니어도 코인 배치는 정상 동작하는지.
  - 같은 날 두 번 돌려도 중복 저장이 없는지(`UNIQUE(price_move_event_id, service_date)` 축의 사전 확인).
  - **주식 배치 회귀** — 코인 배치를 돌려도 주식 카드에 대한 행이 생기지 않고, `PeerStatsBatchServiceIntegrationTest`가 그대로 통과하는지(기존 테스트 실행으로 확인, 수정 금지).
  - 검증 — Testcontainers(MySQL) 통합 + 고정 `Clock`.

- [x] **4. 코인 조회 경로 테스트 — 게이트·200봉 경계·일봉 부재**

  `3adb8192`가 구현한 `CryptoPostSellFeedbackReader`의 분기를 고정한다. 지금 **코인 경로를 지나는 테스트가 하나도 없다.**

  - **게이트 전/후**(§C-5·결정 1) — `now()`가 `(매도 체결 KST 날짜 + 1일) 00:00` **직전**이면 `postSellFlow`·`counterfactuals`가 `NOT_YET`이고 가격 필드가 비어 있는지, **직후**면 `READY`로 전이하는지. 기준이 "오늘"이 아니라 **그 체결의 날짜**임을 보이려면 **이틀 뒤 조회에서도 `READY`가 유지되는지**를 같이 본다(오늘 기준 구현이면 여기서 깨진다).
  - **200봉 경계**(결정 4) — 보유 **199분 이하**면 `holdHighBasis="MINUTE"`이고 1분봉 close 최댓값인지, **199분 초과**면 `"DAILY"`이고 **매수일 ~ 매도 전날 일봉 close**만 표본에 들어오는지(**매도일 일봉이 섞이면 안 된다** — 보유하지 않은 구간의 가격이다). 경계값 두 개를 각각 케이스로 둔다.
  - **일봉 표본이 비는 경우** — 같은 날 안에서 199분 초과 보유. `holdHighPrice`·`holdHighAt`·`sellVsHighRate`·`counterfactuals.atHoldHigh`가 전부 `null`이고 **오류가 아닌지**(§실패 처리 표). `holdLowPrice`·`sellVsLowRate`도 같은 규칙인지.
  - **일봉 근사를 쓰지 않는 자리** — `postSellHighPrice`·`postSellHighAt`·`atFirstMoveAfterBuy`가 1분봉이 없으면 `null`로 남고 일봉으로 채워지지 **않는지**. 이것이 결정 4에서 가장 틀리기 쉬운 자리다.
  - **`atClose`** — 매도일 일봉 close이고 `closeAt`·`at`이 그 일자 `23:59:00`인지, 일봉을 못 받으면 `closePrice`·`closeAt`·`atClose`가 `null`이면서 `status`는 게이트대로 `READY`인지.
  - **`sameSessionCompleted`가 코인에서 항상 `true`**인지(결정 0), `buyAt`·`sellAt`이 체결 시각 그대로인지.
  - **코인 수수료율 0.0005**가 반사실 `returnRate`에 쓰이는지 — 주식 요율을 쓰면 값이 조용히 어긋난다. 주식 케이스(0.00015)와 나란히 둬 대조가 보이게 한다.
  - 검증 — 단위 위주(`CandleQueryService`를 mock해 봉 유무를 케이스별로 만든다. 실제 외부 호출로는 200봉 경계를 재현할 수 없다) + 게이트 전이는 고정 `Clock` 통합 1건. 기존 주식 테스트(`PostSellFeedbackBoundaryIntegrationTest`·`PostSellFeedbackGateIntegrationTest`)의 배치를 참고하되 **그 파일들을 수정하지 않는다.**

- [x] **5. E2E 고정 — 코인 체결이 200이고 주식 경로·원장이 그대로다**

  이슈 §완료 조건의 2단계 첫 항목과 공통 3건을 한 번에 고정한다.

  - 코인 매도 체결로 `GET /api/ai/post-sell/{tradeId}`가 **400이 아니라 200**이고, 원장 수치(배분 가중평균 매수단가·매도가·수량·수수료·실현손익·보유기간)가 주식과 같은 계산으로 채워지는지.
  - 보유 구간의 코인 변동 원인 카드가 `priceMoves`에 **노출 게이트 없이** 들어오는지(코인 카드는 `reveal_time`이 `NULL`).
  - **주식 경로 회귀** — 기존 `PostSellFeedback*` 통합 테스트가 전부 그대로 통과하는지(수정 없이). 회귀가 났다면 그 원인은 `3adb8192`의 공용화(`PostSellArithmetic` 위임)이므로 **테스트가 아니라 구현을 고친다.**
  - **원장 불변** — 조회 전후로 주문·체결·계좌·잔액·보유·손익 테이블 행이 변하지 않는지. `PeerStatsBatchServiceIntegrationTest.neverWritesOutsideThePriceMovePeerStatsTable`이 쓰는 테이블 카운트 대조 방식을 그대로 쓴다.
  - **LLM 폴백**(ADR-0011) — 코인 체결에서도 LLM 실패 시 템플릿 문장으로 대체되고 `narrativeStatus`가 `READY`, `narrativeSource="TEMPLATE"`인지. `FakeNarrativeGenerator`를 쓴다.
  - 검증 — Testcontainers(MySQL) 통합 + 고정 `Clock`.

- [x] **6. `ai/prd.md` §3 "구현 현황" 갱신**

  **갱신 대상이다** (CLAUDE.md 규칙 10). 195·196행의 FEED-007·FEED-010·011 행은 **주식 전용으로 완료**였고 이 PR이 **코인 체결에 같은 기능을 연다** — 제공하는 기능의 범위가 실제로 늘어나므로 리팩터링·강화성 개선(#244가 비대상이었던 이유)과 다르다.

  - 두 행의 근거 칸에 **코인 지원과 이 PR 번호**를 더한다. 판정(`완료`)은 바꾸지 않는다 — 주식 기준으로 이미 완료였고 코인이 더해져도 완료다.
  - **새 행을 만들지 않는다.** FEED-012는 요구사항을 새로 만든 것이 아니라 FEED-007·010·011의 코인 차이를 규정한 절이다(§FEED-012 머리말).
  - 근거 칸에는 **무엇이 열렸는지**를 적는다 — "코인 체결도 200(§FEED-012 결정 1~4: KST 자정 게이트·일봉 종가·199분 정밀도 경계·00:05 확정 배치)". 판정만 있고 근거가 없으면 다음 사람이 검증할 수 없다.
  - 검증 — 없음(문서 대조뿐).

## 완료 조건 소유

정본은 **이슈 #275 본문**이다. `spec.md` §완료 조건에는 이 이슈 전용 절이 없다(§FEED-012가 규칙을 적고 완료 판정은 이슈가 쥔다). `plan.md` §완료 조건 배정의 "총 91건" 표에는 들어가지 않는다 — 그 표는 8개 분할(#147~#225) 전용이다.

| 단계 | 이슈 #275 완료 조건 | 항목 |
|---|---|---|
| 1단계 | 결정 1~4를 근거와 함께 `spec.md`에 기록 | **완료** (`016fb543`) |
| 1단계 | 계약이 시장별로 달라지는지 판정하고 다르면 `api-contracts.md`에 명시 | **완료** (`016fb543` — 달라지지 않음, `holdHighBasis` 공용 필드 1개만 추가) |
| 1단계 | 새 ADR 필요 여부 판단 | **완료** (`016fb543` — 불필요) |
| 2단계 | 코인 체결 조회가 400이 아니라 200 | **5** (구현은 `3adb8192`) |
| 2단계 | 원장 수치가 주식과 동일한 계산으로 채워짐 | **5** |
| 2단계 | 보유 구간 코인 카드가 `priceMoves`에 포함 | **5** |
| 2단계 | 게이트대로 `postSellFlow`·`counterfactuals`·`peerComparison` status 전이 | **2**(배치) + **3**(peer) + **4**(시각 게이트) |
| 2단계 | 계산 불가 값이 임의값이 아니라 `null`이고 그 조건이 계약에 있음 | **4** (계약 기술은 `016fb543`) |
| 공통 | 기존 주식 경로 동작·응답 불변 (회귀 테스트로 고정) | **3**(배치) + **5**(조회) |
| 공통 | 조회 전후로 원장 불변 | **5** |
| 공통 | LLM 실패 시 템플릿 폴백 (ADR-0011) | **5** |

## 이 이슈에서 하지 않는 것

- **주식 경로 변경** — 현재 동작이 정본이다(이슈 §제외 범위). 공용화로 값이 달라지면 그것은 회귀다.
- **200봉 상한 조정** — `013-candle-interval` 107행이 범위 제외로 두었고 §FEED-012 결정 4가 재확인했다. 필요하다는 결론이 나오면 별도 이슈다.
- **`CryptoCandleStore` TTL·Redis 보존기간 변경** — 결정 4가 캐시를 늘리는 대신 정밀도를 갈랐다.
- **코인 변동 원인 카드·뉴스 요약·브리핑** — 이미 코인을 지원한다.
- **조회 경로 LLM 호출량 계측** — `LlmCallStats`는 배치 스코프 전용이고(#198, 의도된 동작) 후속 이슈로 `plan.md` §후속에 이미 적혀 있다.
- **프론트 변경** — 목록 조회의 `market` 제한 해제는 `FinPlay` 레포의 몫이다.
