# Spec: 뉴스 기반 변동 원인·매도 직후 피드백·뉴스 요약·개장 전 브리핑

> 단계: **2차 MVP (팀 회의 표현: 1차 고도화)**. Notion 1차 고도화 목록의 "ai 피드백 (뉴스를 통한 변동 원인 + 수익률 가져와서 피드백)"과 "뉴스 요약"에 대응한다.
>
> PRD 근거: C-004(AI 정책), 2차 MVP 범위의 "AI 피드백". ADR 근거: **ADR-0011**(LLM 연동은 Spring AI 추상화 뒤에 두고 프로바이더를 설정으로 교체). 선행: `003-market-data`(분봉·재생세션), `004-order-buy`·`005-order-sell`(체결·FIFO 실현손익), `011-order-ledger-schema`.
>
> **투자일기에 의존하지 않는다.** 원장의 확정 수치와 뉴스만으로 피드백을 만든다. 매수 시 기록한 목표가·손절가와 대조하는 "계획 대비 실제"는 `007-journal`(다른 팀원 범위)이 선행되어야 하므로 범위 밖이고, 투자일기 기반 분석은 3차 "AI 주간·월간 리포트"에서 다룬다.
>
> **정책 개정 완료 (2026-08-03)**: PRD C-004의 "외부 시장 정보 미사용" 조항을 개정 확정했다 (§정책 전제). 구현 착수를 막는 미결 사항은 없다.
>
> **기존 명세와의 관계**: Notion [api 명세서](https://app.notion.com/p/3a5b1fddfba980a99393c70908d19749) §6 "AI 복기 피드백"에 엔드포인트 6개가 이미 등록돼 있다. 이 spec은 그중 `ai/post-sell/{id}` 하나만 구현하고 나머지 5개(`pre-order`·`d7`·`weekly-report`·`basis-stats`·`similar`)는 다루지 않는다. 변동 원인 카드·뉴스 요약·개장 전 브리핑은 기존 명세에 없는 신규다.
>
> **2026-08-04 정정 (이슈 #136)**: 구현 착수 전 재검토를 3라운드 돌려 **차단 76건**을 고쳤다(문서 13 → 파트별 30 → 재검토 33). 대부분 "예외가 안 나고 빌드도 통과하는데 화면에 아무것도 안 나오는" 유형이었다 — 개장 전 배치가 분봉을 못 받아오는 문제, 전장 기사의 노출 시각이 장 마감 뒤로 밀리는 문제, 코인 σ 계산 데이터가 없는 문제가 대표적이다.
>
> **§확정값 절을 만든 것이 이 정정의 핵심이다.** 같은 사실이 요구사항·구현 사양·데이터 모델 주석·완료 조건·계약서 다섯 곳에 적혀 있어, 한 곳을 고칠 때마다 나머지를 놓쳤다. 리뷰 라운드가 줄지 않은 진짜 원인이 그것이었다. **이제 값은 §확정값에만 있고 나머지는 참조한다.** 값을 바꿀 때는 그 절만 고친다. 검토 기록은 `run-log.md`에 있다.
>
> **매도 회고 계열(FEED-007·010·011)은 2차에서 주식 전용이다.** 장 마감·종가·확정 집계 시점이 재생 시간축에 묶여 있어 코인에 대응 개념이 없다. Part A·C·D는 주식·코인 모두 지원한다.
>
> **이 문서는 구현 지시서다.** 임계값·클래스명·프롬프트·실패 처리를 확정값으로 적었다. 구현자는 이 문서만 읽고 멈추지 않고 끝까지 갈 수 있어야 한다. 값을 골라야 하는 자리는 전부 §C-7에 기본값과 프로퍼티 키가 있다.

## 개요

네 파트로 이루어진다.

- **Part A — 변동 원인** (FEED-001~006): 가격이 크게 움직인 구간을 서버가 탐지하고, 그 시각 기사를 근거로 붙여 관찰형 문장으로 서술한다.
- **Part B — 매도 직후 피드백** (FEED-007): 매도 시 FIFO 실현손익·수익률에 보유 구간의 변동 원인 카드를 결합한다.
- **Part C — 종목 뉴스 요약** (FEED-008): 수집한 기사를 종목·거래일 단위로 묶어 요약과 목록을 제공한다.
- **Part D — 개장 전 브리핑** (FEED-009): 개장 시점에 "간밤에 이런 일이 있었다"를 시장 단위로 제공한다.

네 파트는 같은 수집 파이프라인(`market_news_items`)을 공유한다. Part A가 만든 카드를 Part B가 재사용하고, Part A가 수집한 기사를 Part C·D가 다시 쓴다. **사용자 수가 늘어도 수집·생성 비용은 늘지 않는다.**

**Part A와 Part D의 역할이 다르다.** Part A는 가격이 움직인 **뒤에** 원인을 설명하므로 매매 판단에 쓸 수 없다. Part D는 개장 시점에 그때까지의 정보를 주므로 **뉴스를 보고 매매하는 사용자의 진입점**이 된다.

## 정책 전제

이 spec은 Notion 운영 정책의 "AI 피드백 — 종목 추천 금지, **외부 시장 정보(뉴스·공시) 미사용**"과 충돌했다. **2026-08-03 팀 결정으로 아래와 같이 개정을 확정했다** (결정자: 남동엽).

> **문구 위치를 정확히 적어 둔다** (PR #132 리뷰 [참고] 반영). "외부 시장 정보 미사용"은 **Notion 두 곳**(10 X TEN 운영 정책, api 명세서 §12 설계 원칙)에 있었고 **레포 `prd.md` C-004에는 없었다.** 레포 쪽은 기존 문구를 고친 게 아니라 개정 결과를 **새로 추가**한 것이다. 초기 서술에서 "세 곳"으로 뭉뚱그린 것을 바로잡는다.

| 구분 | 문구 |
|---|---|
| 기존 | 종목 추천 금지, 외부 시장 정보(뉴스·공시) 미사용 |
| 개정 | 종목 추천 금지. 뉴스·공시는 **시간적 동시 발생 서술에만** 사용하고 인과를 단정하지 않는다 |

**저작권 노출 범위도 함께 확정했다** — 기사는 **제목·언론사·원문 URL·발행시각**까지만 화면에 노출한다. 본문은 저장하지 않고 AI 입력으로만 쓰고 버리며, 네이버 API가 주는 요약 스니펫(`description`)도 노출하지 않는다. 원문 링크로 트래픽을 언론사에 보내는 구조라 포털·증권사 앱이 통상 하는 방식과 같다.

C-004의 나머지("숫자와 판정은 서버가 계산하고 AI는 관찰형 문장으로만 변환한다", "특정 종목의 매수·매도를 추천하지 않는다")는 그대로 유지되며 이 spec의 모든 요구사항이 그 제약 안에서 설계됐다.

**단계 조정 (Part C)**: 레포 `prd.md`는 "종목 뉴스 요약"을 3차로 잡고 있으나 Notion 1차 고도화 목록에는 있다. Part A가 수집 파이프라인을 이미 만들기 때문에 3차까지 미루면 같은 코드를 두 번 건드린다. **2차로 앞당겨 이 spec에 포함한다.**

## 사용자 시나리오

- 사용자는 개장 시점에 "간밤에 무슨 일이 있었는지"를 확인하고 그 정보로 매매를 판단한다.
- 사용자는 종목 상세에서 그날 가격이 크게 움직인 구간과 그 시각의 뉴스·공시를 확인한다.
- 사용자는 매도 후 자신의 매수가·매도가·수익률과 함께 보유하는 동안 무슨 일이 있었는지 확인한다.
- 사용자는 근거 기사의 원문 링크로 이동해 직접 확인한다.
- 사용자는 어떤 화면에서도 특정 종목의 매수·매도 권유나 가격 예측을 받지 않는다.

## 핵심 제약 — 재생 시간축

주식은 실시간이 아니라 **과거 거래일 분봉을 정규장 시간에 1배속으로 재생**한다(MKT-002). 따라서 노출되는 뉴스는 **오늘 뉴스가 아니라 재생 중인 원본 거래일의 뉴스**여야 한다. 오늘 뉴스를 붙이면 화면의 가격 방향과 기사 내용이 정반대가 되는 오정보가 발생한다.

여기서 네 가지가 따라온다.

1. 뉴스는 **원본 거래일에 수집해 저장**해야 한다 — 네이버 뉴스 검색 API에 날짜 범위 지정이 없어 사후 조회가 어렵다.
2. 장중 콘텐츠 노출은 **재생 진행 시각을 넘지 못한다** — 15:00 사건을 09:30에 보여주면 스포일러다.
3. **개장 전 브리핑에 장중 뉴스를 넣으면 안 된다** — 전일 장마감~당일 개장 구간만 담는다. 이 범위는 실제 투자자가 아침에 아는 정보와 동일하다.
4. 보유 구간이 **여러 재생일에 걸치면** 뉴스 타임라인이 불연속이라 Part B에서 카드를 붙이지 않는다.

**재생이 1배속이므로 원본 거래일의 시각과 서비스 날짜의 벽시계 시각이 1:1로 대응한다.** 원본 거래일 11:25 사건은 서비스 날짜 11:25에 공개된다. 노출 판정은 이 대응만으로 계산하고 별도 오프셋을 두지 않는다.

코인은 실시간이므로 위 제약이 없고, 대신 **개장 전이라는 고정 시점이 없어** 요약·브리핑을 매시 배치로 갱신한다.

---

## 확정값 — 단일 출처

> **이 절이 모든 숫자·이름·열거값의 유일한 출처다.** 아래 요구사항·알고리즘·데이터 모델·완료 조건·`docs/api-contracts.md`는 값을 **다시 적지 않고 이 절을 참조한다.**
>
> 이 절을 만든 이유가 있다. 같은 사실이 요구사항·구현 사양·데이터 모델 주석·완료 조건·계약서 다섯 곳에 적혀 있어, 한 곳을 고칠 때마다 나머지를 놓쳤다. 리뷰 6라운드 중 4라운드가 그 실수였다. **값을 바꿀 때는 이 절만 고친다.**

### C-1 스케줄

| 키 | 기본값 | 소유 | 하는 일 |
|---|---|---|---|
| `feedback.batch.cron` | `0 45 8 * * MON-FRI` | feedback | 개장 전 배치 (주식 카드·요약·브리핑) |
| `feedback.batch.peer-stats-cron` | `0 32 15 * * MON-FRI` | feedback | 장 마감 집단 비교 확정 집계 |
| `feedback.batch.crypto-cron` | `0 5 * * * *` | feedback | 코인 요약·브리핑 갱신 (매시 05분) |
| `feedback.batch.crypto-watch-cron` | `30 * * * * *` | feedback | 코인 변동 감시 (매 분 30초) |
| `feedback.news.collect-cron` | `0 0/30 * * * *` | feedback | 뉴스 수집 (24시간 30분 간격) |
| `feedback.news.disclosure-cron` | `0 0/30 8-20 * * MON-FRI` | feedback | 공시 수집 |
| `market.crypto.price-snapshot-cron` | `0 * * * * *` | **market** | 코인 가격 스냅샷 기록 (매 분 정각) |

**`cron` 기반 `@Scheduled`에 반드시 `zone = "Asia/Seoul"`을 붙인다.** `fixedRate`는 타임존과 무관하므로 대상이 아니다 — 기존 `BithumbFeedSimulator`·`BithumbRestTickerPoller`·`SseEmitterRegistry`가 그렇다.

```java
@Scheduled(cron = "${feedback.batch.cron}", zone = "Asia/Seoul")
```

기존 **cron** 스케줄러 3개(`KisHistoricalCandleCollector`·`StockPriceStreamService`·`StockReplaySessionScheduler`)가 전부 이렇게 돼 있다. `ClockConfig`의 `Clock` 빈은 KST지만 **`@Scheduled`는 그 빈을 쓰지 않고 JVM 기본 타임존을 따른다.** `Dockerfile`·`compose.deploy.yaml`에 `TZ`가 없어 배포 JVM 기본은 UTC이므로, 빠뜨리면 08:45 배치가 **KST 17:45에 돌아 장중 내내 화면이 비고** 예외도 로그도 남지 않는다.

**`spring.task.scheduling.pool.size`를 5 → 12로 올린다.**

```
기존 5   SseEmitterRegistry · StockPriceStreamService · KisHistoricalCandleCollector
         StockReplaySessionScheduler · BithumbFeedSimulator
신설 7   위 표 전부
합계 12
```

`application.yml` 주석이 "기본 프로필의 `@Scheduled` 작업은 5개다 … 각자 독립 스레드를 쓰도록 5로 맞춘다"라는 불변식을 적어 두었으므로 **주석의 개수 계산도 함께 갱신한다.** 풀은 앱 전역 단일 풀이라 소유 도메인과 무관하게 전부 센다 — `price-snapshot-cron`이 `market` 소유라고 빼면 안 된다. 풀이 부족하면 08:45 배치가 스레드를 길게 점유해 **SSE heartbeat와 매분 가격 push가 개장 시간대에 밀린다.** 이슈 #125가 정확히 이 개수를 잘못 센 사고였다.

**`crypto-watch-cron`을 30초로 오프셋한 이유** — `price-snapshot-cron`과 같은 시각이면 실행 순서가 보장되지 않아 감시가 그 분의 스냅샷을 못 볼 수 있다.

### C-2 시간 범위

원본 거래일을 `D`, `D`의 직전 거래일을 `D-1`이라 한다. **모든 시각은 원본 거래일 시간축이다.**

| 이름 | 범위 | 쓰는 곳 |
|---|---|---|
| `전장` | `[D-1 15:30, D 09:00]` | 브리핑, `PRE_MARKET` 요약, 시가 갭 근거 |
| `FULL` | `[D-1 15:30, D 15:30]` | `FULL` 요약 |
| `ROLLING_24H` | 최근 24시간 | 코인 요약·브리핑·카드 목록 |
| `근거창(주식 장중)` | `[windowEnd − news.match-before-minutes, windowEnd + news.match-after-minutes]` | 장중 카드 근거 |
| `근거창(코인)` | `[windowEnd − crypto.match-before-minutes, windowEnd]` | 코인 카드 근거 |

**주식 `items` 목록의 하한은 `전장`의 시작과 같고, 상한만 다르다.**

| 조회 시각 | `summaryScope` | 요약이 다루는 범위 | `items` 범위 |
|---|---|---|---|
| 09:00 이전 | `null` | — (`NOT_YET`) | `[]` |
| 09:00 이상 15:30 미만 | `PRE_MARKET` | `전장` | `[D-1 15:30, 현재 재생 시각]` |
| 15:30 이상 | `FULL` | `FULL` | `FULL` |

**"범위가 정확히 같다"가 아니라 "하한이 같다"이다.** 09:00~15:30에는 `items`가 요약보다 넓다 — 장중 기사가 재생 시각을 따라 하나씩 풀리기 때문이다. 요약이 `items`보다 **앞서지만 않으면** 된다. 반대로 `items`를 09:00에서 자르면 "재생 시각을 지난 기사만 노출"이 깨진다.

### C-2-1 벽시계와 분봉을 구분한다

`15:30`·`09:00`을 **어디에 쓰느냐로 규칙이 갈린다.** 섞으면 한쪽은 매일 0건이 되고 다른 쪽은 정의가 성립하지 않는다.

| 용도 | 표현 | 이유 |
|---|---|---|
| **기사·공시 구간 경계** (위 표 전부) | `15:30`·`09:00` **벽시계** | 뉴스 필터일 뿐 분봉을 찾지 않는다. 브리핑은 전 종목 단일 질의라 **종목별 분봉 시각을 하한으로 쓸 수 없다** |
| **노출 게이트** (§C-5의 장 마감 판정) | `15:30` **벽시계** | 분봉 존재 여부와 무관하다 |
| **가격 조회** (`prevClose`·`atClose`·`closePrice`·시가) | **"첫/마지막 분봉"** | 리터럴로 찾으면 `null` |

가격 조회에만 "분봉" 표현을 쓰는 이유는 이렇다. `003-market-data`가 분봉 timestamp를 **구간 시작 기준**으로 다루므로(`StockReplayService.findRevealedCandle`이 첫 분봉 09:00을 09:00~09:00:59 동안 "마감 전"으로 취급) **그날 마지막 분봉이 15:29일 수 있다.** 수집기는 `09:00~15:30`을 전부 허용하므로 15:30 분봉이 올 수도 있지만 보장되지 않는다 — `15:30 종가`를 리터럴로 찾는 코드는 없는 날 **`null`을 받고 예외는 안 난다.** 시가 갭 카드와 `atClose`가 조용히 비게 된다.

### C-3 공시 판정 (날짜 기준)

공시는 `rcept_dt`(`YYYYMMDD`)만 있어 `published_at`이 그 날짜 `00:00:00`이다. **datetime 구간으로 거르면 정확히 반대로 걸린다** — `D-1` 접수분은 `D-1 00:00:00`이라 `전장` 시작보다 이르러 빠지고, `D` 접수분은 `D 00:00:00`이라 `전장`에 들어오는데 거기엔 `D` 장중 접수분이 섞여 있다.

| 대상 | 공시 조건 |
|---|---|
| 브리핑 · `PRE_MARKET` 요약 · 시가 갭 근거 | `rcept_dt = D-1` |
| `FULL` 요약 | `rcept_dt ∈ {D-1, D}` |
| Part C `items` | 그 시각의 `summaryScope`와 같다 — `PRE_MARKET`이면 `rcept_dt = D-1`, `FULL`이면 `{D-1, D}` |
| 장중 카드 근거 | 매칭하지 않음 |
| 코인 | 공시 없음 |

**`D` 접수 공시는 `FULL`에서만 나온다** — `FULL`은 15:30 이후에만 노출되므로 장중 유출이 성립하지 않는다. §노출 판정의 `clamp`는 **`D-1` 접수 공시에만** 적용된다. 뉴스는 분 단위 발행시각이 있으므로 구간 필터를 그대로 쓴다.

### C-4 상태값

| 필드 | 값 | 언제 |
|---|---|---|
| `summaryStatus` (Part C) | `READY` | 요약 행이 있고 `summary`가 있다 |
| | `NOT_YET` | 주식, 09:00 이전 **또는** 재생세션 미준비 |
| | `EMPTY` | 기사 0건, **또는 요약 행이 없다**(배치 미실행·배포 당일) |
| | `UNAVAILABLE` | 행이 있고 `summary`가 `NULL` (LLM 실패 또는 후검증 재생성 후에도 위반) |
| `status` (Part D) | 같은 4종 | 아래 판정 순서를 따른다 |
| `postSellFlow.status` | `READY` \| `NOT_YET` | 게이트는 C-5 |
| `counterfactuals.status` | `READY` \| `NOT_YET` | 게이트는 C-5 |
| `peerComparison.status` | `READY` | 확정 집계 행이 있고 `holderCount >= 5` |
| | `INSUFFICIENT_SAMPLE` | 행은 있고 `holderCount < 5`. 모집단 지표 3종 `null`, `yourMinutesToSell`은 채움 |
| | `NO_EVENT` | 보유 구간에 변동 카드가 0건. `priceMoveId` 포함 전 필드 `null` |
| | `NOT_YET` | 그 밖 (배치 전) |
| `narrativeStatus` (Part B) | `READY` 뿐 | 템플릿이 있어 항상 채워진다. `UNAVAILABLE`이 **존재하지 않는다** |
| `narrative_source` | `LLM` \| `TEMPLATE` | 카드·매도 회고 |
| | `LLM` \| `NONE` | 요약·브리핑 (템플릿이 없다) |

`peerComparison`은 **`NO_EVENT`를 1순위로 판정한다** — 기준 카드가 없으면 확정 집계 행이 애초에 생기지 않으므로, 행 존재만 보면 영원히 `NOT_YET`이 된다.

**Part C·D의 `status` 판정 순서** — 두 조건이 동시에 성립하는 구간이 있으므로 순서를 그대로 따른다.

| 순서 | 조건 | 결과 |
|---|---|---|
| 1 | 재생세션 미준비 (주식) | `NOT_YET`(Part C) / `EMPTY`(Part D), `originTradeDate=null` |
| 2 | 09:00 이전 (주식) | `NOT_YET`, `originTradeDate` 채움 |
| 3 | 대상 기사·공시 0건 | `EMPTY`, `items=[]` |
| 4 | 행이 없다 | `EMPTY`, **`items`는 채운다** |
| 5 | 행이 있고 `summary`가 `NULL` | `UNAVAILABLE`, **`items`는 채운다** |
| 6 | 그 외 | `READY` |

Part C의 1번이 `NOT_YET`이고 Part D가 `EMPTY`인 것은 의도된 차이다 — Part D는 "브리핑이 아예 없는 날"이 정상이고, Part C는 "아직 열리지 않았다"가 맞다.

### C-5 노출 게이트

| 대상 | 게이트 |
|---|---|
| 카드 (주식) | `(서비스 날짜 + reveal_time) <= now()` |
| 카드 (코인) | 없음 (`reveal_time`이 `NULL`) |
| Part C `items` (주식) | `(서비스 날짜 + clamp(published_at)) <= now()` |
| Part C 요약·Part D (주식) | 09:00 이후 |
| `postSellFlow` · `counterfactuals` | `now() >= (그 매도 체결의 서비스 날짜) 15:30` |
| `peerComparison` | 확정 집계 행 존재 (시각 아님) |
| 서술 재생성 | `postSellFlow`가 `READY`이고 `peerComparison.status != NOT_YET` |

**"오늘 15:30"이 아니라 "그 체결의 서비스 날짜 15:30"이다.** 오늘로 잡으면 어제 판 체결을 오늘 오전에 열었을 때 `READY`였던 값이 `NOT_YET`으로 되돌아간다.

**재생성 게이트에 `NO_EVENT`·`INSUFFICIENT_SAMPLE`도 확정으로 친다.** 보유 구간 카드가 0건이면 `price_move_peer_stats` 행이 애초에 안 생기는데, 게이트를 "행 존재"로만 두면 그 흔한 경우에 **매도 후 흐름이 반영된 서술이 영원히 만들어지지 않는다.**

### C-6 신설 코드 요소

**`market`에 신설** (`feedback`은 전부 서비스를 경유한다. repository·store 직접 주입 금지)

| 메서드 | 반환 | 왜 필요한가 |
|---|---|---|
| `StockReplayService.getCurrentReplaySession()` | `(ready, sourceTradingDate)` | 지금은 `findReadySession`이 private이고 `getMarketStatus()`로는 원본 거래일을 알 수 없다 |
| `StockReplayService.getSourceTradingDate(serviceDate)` | 원본 거래일 (과거 날짜도) | 서비스 벽시계 ↔ 원본 거래일 변환 |
| `StockReplayService.getFullDayCandles(instrumentId, tradingDate)` | 하루치 분봉 | 기존 `getRevealedCandles`는 **현재 재생 시각까지만** 준다 |
| `StockReplayService.getPreviousTradingDayClose(instrumentId, tradingDate)` | 직전 거래일 **마지막 분봉의 close** | 다른 `trading_date`라 기존 경로로 못 얻는다 |
| `CryptoPriceSnapshotService.getSnapshots(symbol, from, to)` | `List<(시각, 가격)>` | `PriceStore`는 `@Component`이므로 서비스로 감싼다 |
| `BusinessDayCalendar.previousBusinessDay(date)` | **기존 public 메서드** | `D-1`(직전 거래일 날짜)을 얻는 유일한 경로다. §C-2의 `전장` 하한과 §C-3의 `rcept_dt = D-1` 판정이 이 값을 쓴다. 시장 단위라 종목별 `getPreviousTradingDayClose`로는 대체할 수 없다 |
| `CryptoPriceSnapshotService`의 매분 기록 스케줄 | — | `PriceStore`는 심볼 목록을 모른다 |

`getFullDayCandles`·`getPreviousTradingDayClose`는 **재생 노출 게이트를 우회한다.** 배치에서 호출하는 것이 기본이고, 조회 경로에서는 **C-5의 게이트를 통과한 뒤에만** 호출한다(반사실은 조회 시 계산하므로 이 예외가 필요하다). 게이트 판정은 호출부 책임이며 메서드 주석에 이 조건을 남긴다.

**`order`에 신설** — `Trade`·`TradeRepository`는 `order` 소유다.

| 메서드 | 쓰는 곳 |
|---|---|
| `TradeService`에 매도 체결 단건 + 소유권 검증 조회 | 매도 회고 (현재 `getMyTrades` 목록 조회뿐) |

**`portfolio`에 신설** — FIFO 배분(`trade_allocations`)과 lot(`holding_lots`)은 `order`가 아니라 `portfolio` 소유다.

| 메서드 | 쓰는 곳 |
|---|---|
| 배분·lot 조회 | 매도 회고 수치, 매수 시각 |
| 특정 시점 보유자 집계 (회원 식별자 없는 반환) | 집단 비교 |

**`feedback` 패키지**

```
feedback/
  controller/  PriceMoveController, InstrumentNewsController,
               MarketBriefingController, PostSellFeedbackController
  service/     PriceMoveDetector          변동 구간 탐지 (순수 계산, 외부 의존 없음)
               NewsMatcher                이벤트 시각 ↔ 기사 매칭
               NarrativeService           파트별 서술 확정 경로 (생성 → 검증 → 폴백)
               NarrativeGenerator         LLM 호출 (완성된 프롬프트 문자열만 받는다)
               NarrativePromptBuilder     파트별 프롬프트 조립 (§LLM 프롬프트)
               NarrativeValidator         후검증 — 적발된 표현 목록 반환 (§후검증)
               NarrativeTemplateBuilder   템플릿 문장 조립 (§템플릿 문장)
               NewsSearchQueryBuilder     종목별 검색 질의어 조립 (순수 계산, 코인 보정은 FEED-001)
               NewsTitleFilter            같은 시장 다른 종목명이 든 제목 제외 (순수 계산, FEED-001)
               NewsCollectionService      뉴스·공시 상시 수집 (수집기 호출 → 저장, 크론은 §C-1)
               FeedbackBatchService       개장 전 배치 오케스트레이션
               CryptoFeedbackBatchService 코인 요약·브리핑 갱신 (매시)
               PeerStatsBatchService      장 마감 집단 비교 확정 집계
               CryptoPriceMoveWatcher     코인 변동 감시
               PriceMoveQueryService, InstrumentNewsQueryService,
               MarketBriefingService, PostSellFeedbackService
  collector/   NewsCollector(interface), NaverNewsCollector, FakeNewsCollector
               DisclosureCollector(interface), DartDisclosureCollector, FakeDisclosureCollector
  domain/      MarketNewsItem, PriceMoveEvent, PriceMoveEventSource,
               InstrumentNewsSummary, MarketBriefing, TradeFeedback, PriceMovePeerStat
  dto/response/  PriceMoveListResponse, InstrumentNewsResponse,
                 MarketBriefingResponse, PostSellFeedbackResponse
                 (+ 중첩 레코드 — PostSellFlow, Counterfactuals, PeerComparison,
                    NewsItem, PriceMoveItem)
  repository/  각 도메인 JpaRepository
```

DTO는 `dto/response/` 하위에 둔다(`docs/conventions.md`, 이 spec에는 요청 DTO가 없다). 응답 DTO 클래스명은 `docs/api-contracts.md`에 이미 박혀 있으므로 그 이름을 쓴다. 엔티티를 컨트롤러 밖으로 노출하지 않는다.

**프롬프트와 템플릿 문장을 `NarrativeGenerator` 밖에 둔다.** `NarrativePromptBuilder`가 시스템 프롬프트 1종 + 파트별 사용자 프롬프트 4종 + 재생성 프롬프트 1종을 조립하고, `NarrativeGenerator`는 **완성된 문자열만 받아 호출**한다. `NarrativeTemplateBuilder`는 §템플릿 문장의 3종(장중 카드·시가 갭·매도 회고)을 수치로 조립한다. 프로바이더를 바꿔도 프롬프트가 딸려 가지 않게 하려는 것이며, ADR-0011의 "교체는 starter 의존성과 `feedback.llm.*` 설정 변경으로 끝난다"와 같은 의도다. 둘 다 외부 의존이 없어 단위 테스트로 문자열을 직접 단정할 수 있다.

**서술 확정 경로는 `NarrativeService` 하나가 담는다.** 위 넷을 주입받아 파트별로 §후검증의 흐름을 실행한다 — 요약·브리핑은 2단계(생성 → 검증 → 적발 시 재생성 1회 → 그래도 걸리면 서술 없음 + `NONE`), 카드·매도 회고는 1단계(생성 → 검증 → 걸리면 템플릿, 재생성 없음)다. **뒤 이슈의 조회·배치 서비스는 이 서비스 하나만 주입하면 되고 생성기·검증기를 직접 알 필요가 없다.**

**이 경로를 `NarrativeValidator`에 두지 않은 이유** — 템플릿 폴백은 이미 만들어 둔 문장을 고르는 국소적 동작이지만 재생성은 프로바이더를 다시 부르는 다른 층위라, 검증기가 `NarrativeGenerator`를 주입받는 순간 "검증만 하는 클래스"가 아니게 된다.

**질의어 조립과 제목 필터를 수집기 밖에 둔다.** 둘 다 FEED-001의 규칙이고 외부 의존이 없어 고정 픽스처로 단정할 수 있다 — 수집기 안에 두면 HTTP 응답을 고정해야만 검사할 수 있게 된다. `NaverNewsCollector`가 둘을 주입받아 쓴다. 필터는 **같은 시장의 종목명 목록**만 보므로 시장을 섞지 않는다.

`PriceMoveDetector`는 **분봉 리스트와 직전 거래일 종가를 받아 이벤트 리스트를 반환하는 순수 함수**로 만든다. DB·시계·LLM에 의존하지 않아야 고정 픽스처로 단위 테스트할 수 있다.

**개장 전 배치의 생성 순서** — 이 순서를 지킨다.

```
1. 브리핑          09:00 정각에 READY 여야 한다
2. PRE_MARKET 요약  09:00에 Part C가 열리는 즉시 필요하다
3. 시가 갭 카드     revealTime이 09:00이라 개장과 동시에 필요하다
4. 장중 카드        revealTime 게이트에 걸려 오전 중에 필요해진다
5. FULL 요약        15:30 이후에 쓰인다
```

LLM 호출이 약 81건(카드 ~48 + 요약 32 + 브리핑 1)이고 건당 타임아웃 20초라 **꼬리 케이스에서 09:00을 넘길 수 있다.** 순서가 뒤면 개장 직후에 브리핑과 전장 요약이 비는데, 그 상태는 C-4의 4번(`EMPTY`)으로 정의돼 있어 오류가 아니지만 사용자에게는 빈 화면이다. **호출 하나가 실패해도 다음으로 넘어간다.**

### C-7 설정값

`application.yml`에 기본값을 두고 `application-local.yml`에서 덮어쓴다. **모든 값에 기본값이 있으므로 설정 없이도 기동한다.**

```yaml
feedback:
  batch:                          # 크론은 C-1
  detection:
    z-score-k: 2.5                # 후보 판정 계수
    window-minutes: 5             # 누적 수익률 구간
    merge-window-minutes: 5       # 피크 병합 반경
    max-intraday-cards: 2         # 종목·거래일당 장중 카드 수
    opening-gap-threshold: 0.01   # 시가 갭 최소 절댓값 (1%)
  news:
    match-before-minutes: 30      # 주식 장중 카드 근거 탐색 (이전)
    match-after-minutes: 5        # 주식 장중 카드 근거 탐색 (이후)
    max-sources-per-card: 5       # 카드 1건에 붙일 최대 근거 수
    max-items-per-news-list: 50   # Part C 목록 응답 상한
    max-items-per-briefing: 30    # Part D 목록 응답 상한
    max-items-per-summary: 30     # 요약·브리핑 LLM 입력 기사 수 상한
  crypto:
    cooldown-minutes: 30
    daily-limit: 6
    rolling-window-minutes: 5
    sigma-lookback-hours: 24
    min-sample-count: 100         # σ 계산 최소 표본. 미만이면 카드 미생성
    match-before-minutes: 35      # 코인 카드 근거 탐색 (이후는 0)
  instruments:
    stock-count: 16             # V7 시드 기준. 호출량·튜닝 계산의 근거
    crypto-count: 12
  llm:
    model: gpt-5.4-mini
    timeout-seconds: 20
    max-tokens: 512
    max-regeneration: 1           # 요약·브리핑 후검증 재생성 횟수
    max-narrative-retry: 3        # 매도 회고 서술 재생성 누적 재시도 상한
```

**`llm.model` 기본값 근거** (2026-08-03 실호출 2회 실측) — `gpt-5.4-mini`와 `gpt-4.1-mini` 둘 다 §후검증을 통과했고, 추론 토큰 0에 완료 토큰 66·74로 `max-tokens: 512` 안에 들어왔다. `gpt-5.4-mini`가 더 짧고 프롬프트가 준 수치를 그대로 옮겨 기본값으로 잡았다. 폴백 후보는 §튜닝에 있다.

**자격증명 3종은 `feedback.*` 밖 최상위 블록에 둔다** — `naver-search.client-id`·`naver-search.client-secret`·`dart.api-key`. `kis.app-key`와 같은 형태이며 **yml에는 빈 기본값 플레이스홀더만 두고 `@DefaultValue`를 붙이지 않는다**(아래 `KisProperties` 문단과 같은 이유 — 시크릿이라 §튜닝 대상이 아니다). OpenAI 키는 `spring.ai.openai.api-key`에 이미 있다(§외부 API 호출 상세).

**`feedback.*` 블록은 yml과 `@DefaultValue` 양쪽에 값을 둔다** (PR #154 리뷰 권장, 2026-08-03). 뒤 이슈가 `detection`·`news`·`crypto`·`instruments` 블록을 추가할 때도 같은 패턴을 쓴다.

- 위 첫 줄의 "설정 없이도 기동한다"를 실제로 보장하는 것은 **record의 `@DefaultValue`**다. yml만 두면 파일이 비거나 키가 오타 나는 순간 기동이 깨진다.
- 운영 중 값을 바꿀 때는 **yml만 고친다.** yml이 항상 이기므로 `@DefaultValue`는 동작값이 아니라 바닥값이다.
- 다만 **드리프트를 잡는 통합 테스트를 함께 둔다** — 빈 값과 `Environment`의 키 경로를 각각 이 절과 대조한다. 값이 둘로 갈리면 그 테스트가 실패한다.

`market`의 `KisProperties`가 `@DefaultValue`를 거의 안 쓰는 것과 다른데, **값의 성격이 다르기 때문이다.** Kis 쪽은 환경변수에서 오는 시크릿·URL이라 기본값을 코드에 두면 안 되지만, `feedback.*`는 전부 §튜닝으로 조정하는 수치다.

`llm` 블록에는 이유가 하나 더 있다 — `spring.ai.openai.timeout`이 `${feedback.llm.timeout-seconds}s`로 이 값을 참조하므로 **이 블록은 지울 수 없다.** 지우면 `@DefaultValue`와 무관하게 `Duration` 변환에서 기동이 실패한다(실측).

`k`·윈도우·갭 임계치는 **실데이터로 검증한 뒤 조정할 대상**이지만 위 값으로 구현을 시작한다. 검증 방법은 §튜닝에 있다. **값이 미확정이라는 이유로 구현을 멈추지 않는다.**

### C-8 컬럼 타입

`ddl-auto=validate`(ADR-0004)라 엔티티와 조금만 어긋나도 기동이 실패한다.

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| `title` | `VARCHAR(500)` | N | 네이버 제목에 HTML 엔티티가 섞여 온다 |
| `publisher` | `VARCHAR(100)` | N | 공시는 `DART` 고정 |
| `url` | `VARCHAR(500)` | N | **접두 길이 없이 전체 컬럼에 유니크.** `mysql:8.4`는 DYNAMIC row format이라 인덱스 키 상한이 3072B이고 `VARCHAR(500)` utf8mb4(2000B)+`BIGINT`(8B)면 들어간다. `url(191)` 접두로 두면 앞 191자가 같고 쿼리 파라미터만 다른 링크를 중복 판정해 **근거 기사를 조용히 버린다** |
| `published_at` | `DATETIME(6)` | N | 공시는 `00:00:00` |
| `type`·`event_type`·`scope`·`market`·`narrative_source` | `VARCHAR(20)` | N | `@Enumerated(STRING)` (V10 관례). `market`은 **`market/domain/Market`**을 쓴다 — `account/domain/Market`과 값 이름이 같아 저장 문자열은 동일하지만, 이 컬럼들은 계좌가 아니라 종목·시장 축이다 |
| `origin_trade_date` | `DATE` | **N** | 코인도 채운다 (C-9) |
| `window_start`·`window_end` | `TIME` | **Y** | **주식 전용.** 원본 거래일 시각. 코인은 `NULL` |
| `occurred_at` | `DATETIME(6)` | Y | **코인 전용.** 탐지 시각(= `windowEnd`). 주식은 `NULL` |
| `reveal_time` | `TIME` | Y | 코인은 `NULL` |
| `change_rate` | `DECIMAL(10,6)` | N | C-003 |
| `detection_score` | `DECIMAL(10,4)` | N | 갭 카드도 채운다 |
| `narrative`·`summary` | `TEXT` | Y | `NONE`이면 `NULL` |
| `median_minutes_to_sell` | `INT` | Y | 전원 미매도면 `NULL` |
| `narrative_finalized` | `BOOLEAN` | N | 기본 `FALSE`. C-5의 재생성 게이트를 통과한 서술인지 |
| `regeneration_attempts` | `INT` | N | 기본 `0`. 체결 1건당 **누적** 상한 |
| `service_date` | `DATE` | N | `price_move_peer_stats` 전용 (C-9) |
| `created_at`·`generated_at`·`aggregated_at` | `DATETIME(6)` | N | 기존 관례 |

FK는 V10 관례를 따른다 — `instrument_id`→`instruments(id)`, `trade_id`→`trades(id)`, `price_move_event_id`→`price_move_events(id)`, `market_news_item_id`→`market_news_items(id)`.

### C-9 코인의 시각 처리

**코인은 `window_start`/`window_end`(TIME) 대신 `occurred_at`(DATETIME)을 쓴다.**

```
occurred_at     = 탐지 시각 (= windowEnd)
windowStart     = occurred_at − rolling-window-minutes   (응답에서 계산)
originTradeDate = occurred_at 의 KST 날짜               (일일 상한 카운트용)
```

**TIME으로 두면 자정을 못 넘긴다.** 00:03에 탐지되면 구간이 `23:58 ~ 00:03`인데 TIME 비교로는 시작이 끝보다 늦다. 어느 날짜를 붙여도 한쪽이 24시간 어긋난다. 절대 시각 하나만 저장하면 복원이 항상 맞는다.

`origin_trade_date`를 `NULL`로 두지 않는 이유는 **일일 상한을 셀 컬럼이 필요하기 때문**이다. 코인 카드의 중복을 실제로 막는 것은 유니크가 아니라 쿨다운·일일 상한이다 — 코인은 `window_start`가 `NULL`이라 MySQL 유니크가 중복을 허용하기 때문이다. 응답에서는 코인의 `originTradeDate`를 `null`로 내린다.

**코인 요약·브리핑 행의 `origin_trade_date`는 배치 실행 시점의 KST 날짜다.** 이 행들에는 `occurred_at`이 없으므로 카드와 규칙이 다르다. 컬럼이 `NOT NULL`이고 유니크 키의 일부라 값이 미정이면 UPSERT가 성립하지 않는다.

`price_move_peer_stats`에 `service_date`를 두는 이유도 재재생이다. 같은 원본 거래일이 두 번 재생되면 **카드 행이 재사용**되므로(`StockReplaySessionScheduler`가 검증된 거래일을 찾을 때까지 과거로 거슬러 올라간다) `UNIQUE(price_move_event_id)` 단독이면 두 번째 재생일 집계가 첫 번째를 덮어써 **첫날 매도자의 통계가 사라지고, 둘째 날 사용자는 남의 날 통계로 서술이 굳는다.** `UNIQUE(price_move_event_id, service_date)`로 두고 조회는 **그 체결의 서비스 날짜 행만** 본다.

---

## 요구사항

> 값·범위·상태값·크론·클래스명은 **§확정값**에 있다. 아래는 **무엇을 왜 하는지**만 적고 값을 다시 적지 않는다.

### FEED-001 뉴스·공시 수집

- [ ] 주식·코인 전 종목(§C-7) 뉴스를 `market_news_items`에 저장한다. 공시는 주식만 해당한다.
- [ ] **수집은 재생 시점이 아니라 기사가 나오는 당일에, 종일 돌린다**(크론은 §C-1). **장중으로 한정하면 안 된다** — `전장` 구간(§C-2)이 약 17.5시간인데 장중만 돌리면 그 대부분이 **영영 수집되지 않는다.** 저녁·야간 기사가 다음 날 아침 브리핑의 주재료다.
- [ ] **수집 단계에서 발행일자로 거르지 않는다.** 받은 기사를 `published_at` 그대로 저장하고 구간 필터는 조회·매칭 시점에만 적용한다. 수집 시점에 "원본 거래일 것만" 남기면 D-1 저녁 기사와 D 새벽 기사 중 한쪽이 반드시 버려진다.
- [ ] **하루 한 번 몰아서 긁으면 안 된다** — 네이버 API가 날짜 범위 지정을 지원하지 않고 `display` 상한이 100이라, 대형주는 그날 기사만으로 100건을 넘겨 소급 수집하면 앞부분이 잘린다.
- [ ] 결과적으로 원본 거래일 D의 기사와 그 전후 야간 기사가 **D 당일에 이미 DB에 있다.** D+1에 D를 재생할 때는 조회만 한다.
- [ ] **코인 질의어는 `instruments.name`에 `" 코인"`을 붙인다** (예: `비트코인 코인`). 시드의 코인명에는 `리플`·`에이다`·`트론`·`폴카닷`·`체인링크`처럼 일반명사·인명과 충돌하는 단어가 많아 이름만으로는 무관 기사가 근거로 붙는다. 근거 0건은 카드를 막지만 **오탐 근거는 아무것도 막지 않는다** — 무관 기사가 제목·URL로 노출되고 LLM 입력에도 들어간다.
- [ ] **`이더리움`·`비트코인` 질의는 `이더리움클래식`·`비트코인캐시` 기사를 함께 끌어온다.** 제목에 같은 시장의 다른 `instruments.name`이 포함된 기사는 저장 시 제외한다.
- [ ] 뉴스는 네이버 뉴스 검색 API, 공시는 OpenDART 공시검색 API에서 가져온다 (§외부 API 호출 상세).
- [ ] **기사 본문은 저장하지 않는다** — `title`·`publisher`·`url`·`published_at`만 저장한다.
- [ ] 중복 저장은 `UNIQUE(instrument_id, url)`이 막는다. 중복은 오류가 아니라 무시한다.
- [ ] 수집 실패는 그날의 카드·요약·브리핑 생성을 건너뛸 뿐, 분봉 수집·재생세션 확정·주식 시장 개장에 영향을 주지 않는다.
- [ ] 외부 API 키가 없어도 애플리케이션 기동과 자동 테스트가 정상 동작한다 (§실패 처리의 Fake 구현).
- [ ] **`.env.example`과 `compose.deploy.yaml`에 신설 환경변수 4종을 추가한다** — `NAVER_SEARCH_CLIENT_ID`·`NAVER_SEARCH_CLIENT_SECRET`·`DART_API_KEY`·`OPENAI_API_KEY`. 빠뜨리면 배포에서 조용히 빈 값으로 뜬다.

### FEED-002 변동 구간 탐지 (서버 계산, LLM 미사용)

- [ ] §탐지 알고리즘의 의사코드를 그대로 구현한다.
- [ ] 종목별·거래일별로 표준편차를 매번 다시 계산한다 — 종목 간 고정 임계치를 쓰지 않는다.
- [ ] 장중 변동은 점수 내림차순으로 `max-intraday-cards`건만 채택한다.
- [ ] 시가 갭은 별도 1건으로 추가한다. **직전 거래일 마지막 분봉이 없으면 생성하지 않는다(오류 아님).**
- [ ] 판정에 쓰는 모든 수치는 서버가 계산하며 LLM 응답으로 대체하지 않는다.

### FEED-003 근거 매칭과 서술 생성

- [ ] 근거 후보 범위는 §C-2, 공시 판정은 §C-3을 따른다.
- [ ] **공시는 장중 카드에 매칭하지 않는다** — OpenDART가 접수일자만 제공해 분 단위 매칭이 불가능하다.
- [ ] **근거가 하나도 없으면 카드를 생성하지 않는다.**
- [ ] LLM 입력은 §LLM 프롬프트 그대로 구성한다.
- [ ] LLM 출력을 §후검증으로 거르고, 걸리면 **카드를 버리지 않고 §템플릿 문장으로 대체**한다. `narrative_source`를 기록한다(§C-4).
- [ ] LLM 호출 실패·타임아웃은 그 카드만 템플릿으로 대체하고 배치 전체를 실패시키지 않는다.

### FEED-004 주식 개장 전 배치

- [ ] 재생세션이 `READY`로 확정된 뒤 그 원본 거래일 전체에 대해 FEED-002·003·008·009를 실행한다. **생성 순서는 §C-6에 있다.**
- [ ] **기존 재생세션 확정 배치와 같은 크론·같은 트랜잭션에 이어 붙이지 않는다.** `StockReplaySessionScheduler.resolveTodaySession()`은 `@Scheduled(cron = "0 40 8 * * MON-FRI")`이고 `@Transactional`이다. 이어 붙이면 두 가지가 동시에 깨진다.
  - 같은 시각 크론 두 개는 **실행 순서가 보장되지 않아** 세션이 아직 `PREPARING`인 채 배치가 돌면 매일 조용히 0건이 된다.
  - LLM 호출(약 81건 × 타임아웃 20초)이 세션 확정 트랜잭션 안에 들어가 **최대 27분간 트랜잭션이 열린 채 유지된다.**
- [ ] 따라서 §C-1의 별도 크론으로 분리하고, 시작 시 재생세션이 `READY`인지 먼저 확인한다. LLM 호출은 세션 확정 트랜잭션 밖에서 일어난다.
- [ ] LLM 호출은 **하나가 실패해도 다음으로 넘어간다.** 배치 전체를 실패시키지 않는다.
- [ ] **FEED-001 수집은 이 배치에 포함하지 않는다.** 수집은 기사가 나오는 당일에 상시로 돌고, 이 배치는 이미 저장된 기사를 읽어 카드·요약·브리핑을 만들기만 한다.
- [ ] 각 카드에 `reveal_time`을 부여한다 (§노출 판정).
- [ ] 재생세션이 `READY`가 아니면 아무것도 생성하지 않는다.
- [ ] 같은 서비스 날짜에 배치가 두 번 실행돼도 중복 생성되지 않는다 (`UNIQUE` 제약 + 존재 시 건너뜀).

### FEED-005 코인 실시간 경로

- [ ] 코인 전 종목(§C-7)의 롤링 수익률을 감시해 임계치를 넘으면 카드를 생성한다 (§탐지 알고리즘(코인)).
- [ ] **감시에 쓸 가격 스냅샷을 `market`이 보관한다**(§코인 가격 스냅샷, §C-6). 현재 코드에는 이 데이터가 없고, 기존 조회로 대체하면 **예외 없이 3.3시간 표본으로 계산돼 임계치 판정이 조용히 틀린다.**
- [ ] **표본이 `min-sample-count` 미만이면 카드를 만들지 않는다** — 기동 직후에는 스냅샷이 비어 있다. 오류가 아니다.
- [ ] 종목당 쿨다운과 일일 상한을 적용한다(§C-7). 쿨다운만으로는 상한이 보장되지 않는다.
- [ ] 코인 카드의 시각 저장은 §C-9를 따른다 — `occurred_at` 하나만 저장하고 `window_start`/`window_end`는 `NULL`이다.
- [ ] 코인 카드는 `reveal_time`을 `NULL`로 둔다 (실시간이라 스포일러가 성립하지 않는다).
- [ ] 근거 매칭 범위는 §C-2의 `근거창(코인)`이고 **근거가 없으면 카드를 만들지 않는다**(FEED-003과 동일). 코인 뉴스 수집 주기가 30분이라 근거 0건이 흔하므로, **쿨다운·일일 상한에 도달하기 전에 근거 부족으로 먼저 걸러지는 것이 정상 동작이다.** 카드가 적게 나온다는 이유로 이 규칙을 완화하지 않는다 — 완화하면 근거 없는 서술을 LLM이 지어낸다.

### FEED-006 변동 원인 조회 API

- [ ] `GET /api/instruments/{instrumentId}/price-moves`로 조회한다.
- [ ] 노출 게이트는 §C-5를 따른다.
- [ ] 코인은 최근 24시간 카드를 반환한다(§C-2).
- [ ] 카드가 없으면 빈 배열과 200으로 응답한다. **재생세션이 `READY`가 아니면 `originTradeDate=null`·빈 배열**이다 (오류 아님).
- [ ] 각 카드는 근거 목록(제목·언론사·원문 URL·발행시각)을 포함한다.

### FEED-007 매도 직후 피드백

> 경로는 새로 만들지 않는다. Notion 명세 §6의 `GET /ai/post-sell/{id}`에 레포 Base URL 규칙(`/api`)을 적용한다. 기존 명세가 이 경로에 적어 둔 "계획 대비 실제 대조"는 투자일기가 선행되어야 하므로 구현하지 않는다. 나중에 `plan`·`planOutcome` 필드를 **같은 응답에 추가**하면 되고 기존 필드는 유지되므로 계약이 깨지지 않는다.
>
> **2차에서는 주식 전용이다 (2026-08-04 확정).** FEED-007·010·011의 게이트가 전부 장 마감과 원본 거래일에 묶여 있는데 24시간 거래인 코인에는 둘 다 없다. 억지로 열면 `postSellFlow`가 영원히 `NOT_YET`인지 즉시 `READY`인지, `atClose`의 "종가"가 무엇인지, 집단 비교를 언제 확정하는지가 전부 미정인 채 구현자가 임의로 정하게 된다. 게다가 코인 분봉 공급자는 200봉(약 3.3시간) 상한이라 **그보다 오래 보유한 코인은 보유 구간 최고가를 계산할 방법이 없다.** 코인 매도 회고는 3차로 미룬다.

- [ ] `GET /api/ai/post-sell/{tradeId}`로 본인 매도 체결 1건의 회고를 조회한다.
- [ ] **코인 체결은 400 `VALIDATION_ERROR`로 거부한다** — 2차 범위 밖이다. 조용히 빈 값을 채운 200을 돌려주지 않는다.
- [ ] **매수 시각은 배분된 lot 중 가장 이른 `executed_at`으로 정한다.** 한 매도 체결이 `holding_lots` 여러 개에 배분되므로 "매수 시각"이 단일하지 않다. 이 값 하나가 `holdingMinutes`·`buyToNewsMinutes`·`minutesAfterBuy`·보유 구간 극값·반사실의 기준을 전부 결정하므로 구현자가 고르게 두지 않는다.
- [ ] **배분된 lot이 서로 다른 원본 거래일에 걸치면 `sameSessionCompleted=false`다** — 가장 이른 lot 하나만 보고 판정하지 않는다.
- [ ] **수치는 기존 원장에서 가져온다** — FIFO 배분 매수단가, 매도가, 수량, 수수료, 실현손익, 수익률, 보유기간. 재계산하거나 LLM에게 계산시키지 않는다.
- [ ] 매수·매도가 **같은 원본 거래일 안에서 완결된 경우**에만 보유 구간의 카드와 극값을 포함한다(`sameSessionCompleted=true`). 여러 재생일에 걸친 매매는 수치 요약만 제공한다.
- [ ] 매도가 아닌 체결은 400, 없는 체결은 404, 타인 체결은 403으로 거부한다.
- [ ] **§파생 사실을 서버가 계산해 응답과 LLM 입력에 함께 넣는다.** 수치를 다시 읽어주는 것은 조회일 뿐이며, 사용자가 직접 계산하지 않은 관계를 보여주는 것이 피드백이다.
- [ ] **매도 후 가격 흐름·반사실의 노출 게이트는 §C-5**다. 그 전에는 `status="NOT_YET"`이며 가격 필드를 비운다 — 매도 직후에 그날 종가를 알려주면 재매수 판단에 미래 정보를 쓰게 된다.
- [ ] `priceMoves`에도 카드 노출 게이트(§C-5)를 적용한다 — 근거 기사가 `windowEnd` 이후에 발행될 수 있어, 게이트를 빼면 Part A·C보다 먼저 그 기사를 보게 된다.
- [ ] 서술은 최초 조회 시 생성해 `trade_feedbacks`에 저장하고 이후 재사용한다. **회원별 산출물이라 사전 배치 대상이 아니며, `docs/conventions.md`의 "GET은 부수효과 없음"에 대한 이 spec의 유일한 예외다** — 체결 1건당 1회이고 이후에는 조회만 한다. 전 회원이 공유하는 카드·요약·브리핑은 전부 배치가 만든다.
- [ ] **재생성은 §C-5의 재생성 게이트를 통과한 뒤 첫 조회에서 1회** 한다. 통과 시 `narrative_finalized`를 `TRUE`로 바꾼다.
- [ ] **재생성이 LLM 실패로 끝나면 기존 서술을 유지하고 `narrative_finalized`를 `FALSE`로 남긴다.** 재시도는 **체결 1건당 누적** `max-narrative-retry`회까지다(`regeneration_attempts`). 날짜 단위로 리셋하지 않는다 — 실패 시 `generated_at`을 갱신하지 않으므로 날짜 기준이 성립하지 않는다.
- [ ] LLM 실패·후검증 위반 시에도 200이며 **서술은 §템플릿 문장으로 대체된다.** `narrativeStatus`는 항상 `READY`다(§C-4).

### FEED-008 종목 뉴스 요약 (Part C)

- [ ] `GET /api/instruments/{instrumentId}/news`로 종목의 기사 목록과 AI 요약을 조회한다.
- [ ] **`items` 범위와 `summaryScope` 대응은 §C-2**, 상태값과 판정 순서는 §C-4를 따른다.
- [ ] 주식은 `published_at`이 현재 재생 시각을 지난 기사만 노출한다(§C-5). 공시는 §C-3의 날짜 규칙을 따른다.
- [ ] **주식은 09:00 이전에 `NOT_YET`이다.** Part D와 같은 전장 기사군을 다루므로 하한이 없으면 08:41에 이 API로 조회해 **브리핑이 09:00까지 감추는 기사를 20분 먼저 볼 수 있다.** 두 API의 하한을 같게 맞춘다.
- [ ] **주식은 요약을 두 개 만든다** — `PRE_MARKET`과 `FULL`. 각각 `(종목, 원본 거래일, 범위)` 단위로 1건이며 개장 전 배치에서 함께 생성해 전 회원이 공유한다.
- [ ] **`FULL`을 09:00에 노출하면 안 된다.** 하루 전체를 요약한 문장은 장중 기사를 언급하므로, `items`에 게이트를 걸어도 **요약 한 문장이 그날 오후를 통째로 알려준다**(예: "오후에는 생산 차질 보도가 이어졌습니다"). `items` 게이트보다 유출 폭이 크다.
- [ ] **코인은 `ROLLING_24H` 한 범위뿐이다.** 24시간 거래라 '전장'도 '거래일 경계'도 없어 `PRE_MARKET`/`FULL` 구분이 성립하지 않는다.
- [ ] **코인 요약은 §C-1의 코인 배치가 갱신한다. 조회 시 생성하지 않는다.** GET이 외부 LLM을 호출하고 DB에 쓰면 `docs/conventions.md`의 "GET은 부수효과 없음"을 어기고, 갱신 직후 동시 요청이 전부 LLM을 호출하며, 그 순간의 첫 사용자가 최대 40초를 기다린다. 배치로 옮기면 셋 다 사라지고 호출량이 사용자 수와 무관하게 고정된다.
- [ ] **코인 조회는 `generated_at`이 가장 최신인 행 1건을 반환한다.** "오늘 날짜 행"으로 찾으면 매일 00:00~00:05와 배치 실패 시각마다 빈다.
- [ ] **직전 생성 이후 새 기사가 없으면 재생성하지 않는다.** 판정 기준은 `market_news_items.created_at`(수집 시각)이 직전 `generated_at`보다 나중인 행이 있는지다. **`published_at`으로 비교하면 안 된다** — 수집이 30분 주기라 10:03 발행 기사가 10:30에 저장되는데, 10:05 배치가 남긴 `generated_at`과 비교하면 그 기사는 **영원히 요약에 들어가지 못한다.**
- [ ] 코인 `items`의 24시간 창은 조회 시각 기준이고 요약은 마지막 배치 기준이라 최대 65분 어긋난다. **코인은 이 어긋남을 허용한다** — 미래 정보가 아니므로 스포일러가 아니고, 맞추려면 조회 시 생성으로 되돌아가야 한다.
- [ ] 요약은 여러 기사를 종합한 **3~5문장**이며 특정 기사의 문장을 그대로 옮기지 않는다.
- [ ] 각 기사는 제목·언론사·원문 URL·발행시각만 노출하고 본문은 노출하지 않는다.

### FEED-009 개장 전 브리핑 (Part D)

- [ ] `GET /api/market/briefing?market=`로 시장 단위 브리핑을 조회한다.
- [ ] 주식은 §C-2의 `전장` 구간 기사·공시만 담는다. **장중 기사를 절대 포함하지 않는다.** 공시는 §C-3의 날짜 규칙을 따른다.
- [ ] 상태값과 판정 순서는 §C-4를 따른다.
- [ ] **`items`는 저장하지 않고 조회 시 같은 구간 질의로 다시 만든다.** `max-items-per-briefing` 상한과 발행시각 내림차순 정렬이 이 질의에 걸린다.
- [ ] 저장된 브리핑 행만으로는 `EMPTY`와 `UNAVAILABLE`이 구분되지 않는다(둘 다 `summary=NULL`). **조회 시 `items` 개수와 행 존재 여부로 판정한다**(§C-4).
- [ ] 코인은 '개장 전'이 없으므로 최근 24시간 기준이며 `NOT_YET`이 되지 않고 재생세션과 무관하다. 생성 주체는 §C-1의 코인 배치이고, 조회 규칙은 FEED-008의 코인 항목과 같다.
- [ ] 브리핑은 `(시장, 원본 거래일)` 단위로 1건만 생성해 전 회원이 공유한다.
- [ ] 요약은 **3~6문장**이며 종목명을 언급하되 매수·매도를 권유하지 않는다.
- [ ] **배포 직후 이틀은 비어 있을 수 있다** — 근거 구간이 과거 17.5시간이라 수집 이력이 쌓여야 한다. 시가 갭 카드와 같은 이유이며 정상 동작이다.

### FEED-010 반사실 시뮬레이션 (차별화)

> **재생 서비스가 아니면 구조적으로 못 하는 기능이다.** 실제 증권사는 오후에 무슨 일이 일어날지 모르므로 "안 팔았다면 어땠을지"를 그날 안에 계산할 수 없다. 기존 명세에 `ai/d7`(매도 D+7)이 있는 이유가 그것이다. 우리는 개장 전에 그날 마지막 분봉까지를 이미 갖고 있으므로 **장 마감 직후 즉시** 보여줄 수 있다.

- [ ] 매도 회고 응답에 `counterfactuals`를 포함한다 — 같은 수량을 다른 시점에 팔았다면 수익률이 얼마였을지.
- [ ] 시나리오는 셋이다 — `atClose`(**그 거래일 마지막 분봉의 close**까지 보유), `atHoldHigh`(보유 중 극값), `atFirstMoveAfterBuy`(매수 후 첫 변동 카드 시점).
- [ ] 각 시나리오의 수익률은 **수수료를 다시 계산해** 산출한다 (매도금액 비례라 가격이 바뀌면 수수료도 바뀐다).
- [ ] 노출 게이트는 §C-5다. 그 전에는 `status="NOT_YET"`이고 시나리오는 빈 값이다.
- [ ] `sameSessionCompleted=false`이면 전부 `null`이다.
- [ ] **AI 서술에 넣지 않는다.** 구조화된 필드로만 내려보내 화면이 표로 그린다 (§왜 반사실은 AI 문장에 넣지 않는가).

### FEED-011 집단 비교 (차별화)

> **모든 회원이 같은 분봉을 보기 때문에 성립하는 통계다.** 실제 시장은 각자 다른 종목·다른 시점이라 "같은 상황의 다른 사람"을 정의할 수 없다. PRD 핵심 가치의 "혼자 복기하지 않는다"에 직접 대응한다.

- [ ] 매도 회고 응답에 `peerComparison`을 포함한다 — 같은 변동 구간을 겪은 다른 회원들의 행동 분포.
- [ ] **기준 카드는 보유 구간 안의 첫 변동 카드 하나다**(`atFirstMoveAfterBuy`와 같은 카드). 응답에 `priceMoveId`를 함께 내려보낸다.
- [ ] 모집단은 그 카드 시점에 해당 종목을 보유 중이던 회원이다. **복원 방법은 §반사실·집단 비교 계산에 있다** — `holdings`로는 구할 수 없다.
- [ ] 모집단 지표는 셋이다 — 모집단 크기, 카드 시점 이후 30분 내 매도한 비율, 매도까지 걸린 시간의 중앙값. 여기에 비교 기준으로 **본인의 매도까지 걸린 시간**(`yourMinutesToSell`)을 함께 내려보낸다.
- [ ] 상태값(`READY`·`INSUFFICIENT_SAMPLE`·`NO_EVENT`·`NOT_YET`)과 각각의 필드 처리는 §C-4를 따른다.
- [ ] 개인을 식별할 수 있는 값(회원 ID, 닉네임, 개별 체결)은 어떤 형태로도 포함하지 않는다.
- [ ] **확정 집계는 §C-1의 장 마감 배치가 `price_move_peer_stats`에 저장한다.** 조회 시점에 집계하지 않는다 — 전 회원 체결을 훑는 쿼리라 조회마다 돌면 응답이 느려지고 값도 조회 시각에 따라 달라진다.
- [ ] 노출 게이트는 §C-5다 — **시각이 아니라 확정 집계 행의 존재**다.
- [ ] 이 배치도 재생세션이 `READY`가 아니면 아무것도 하지 않고, 두 번 실행돼도 §C-9의 유니크로 중복되지 않는다.
- [ ] 집단 비교는 사실 진술이므로 **AI 서술에 넣어도 된다** (반사실과 다르다).

---

## 구현 사양

구현자가 값을 고민할 필요가 없도록 확정값을 적는다. 조정이 필요한 값은 전부 프로퍼티로 빠져 있어 코드를 고치지 않고 바꿀 수 있다.

### 코드 배치와 설정

패키지 구조·신설 메서드·클래스 목록은 **§C-6**, 설정값은 **§C-7**, 스케줄과 타임존·스레드 풀은 **§C-1**에 있다. 여기서 다시 적지 않는다.

기존 레이어 규칙(`controller → service → repository`)을 따르고, **다른 도메인 데이터는 서비스를 경유한다**(`docs/conventions.md` — 다른 도메인의 repository·store를 직접 주입하지 않는다).

**기존 분봉 조회 메서드를 재사용하면 안 된다.** `market`이 외부에 노출하는 유일한 분봉 경로는 `StockPriceProvider.getCandles` → `StockReplayService.getRevealedCandles`인데, 이 메서드는 `resolveRevealCutoff`로 **현재 재생 시각까지만** 반환한다. 개장 전 배치가 08:45에 호출하면 항상 빈 리스트이고, **예외가 아니라 정상 응답이라 매일 조용히 카드 0건이 된다.** 그래서 §C-6에 배치용 메서드를 따로 신설한다.

**집단 비교는 전 회원 체결을 훑는 집계라 `feedback` 안에서 네이티브 쿼리를 짜기 쉬운 자리다. 그렇게 하지 않는다** — 소유 도메인(`portfolio`)에 집계 메서드를 만들고 결과만 받는다. 반환에 회원 식별자가 없어야 한다.

### 탐지 알고리즘 (주식)

`PriceMoveDetector`가 구현할 내용이다. 입력은 원본 거래일 하루치 분봉(시각 오름차순)과 직전 거래일 마지막 분봉의 close, 출력은 이벤트 리스트다.

```
W = window-minutes,  k = z-score-k   (값은 §C-7)

※ 모든 인덱싱은 행 번호가 아니라 시각 기준이다. close(T)는 시각 T의 분봉 종가이며
   그 시각 분봉이 없으면 null이다 (아래 [결측] 참고).

1. 로그수익률
   각 분봉 t에 대해 close(t - 1분)이 있으면
     r[t] = ln(close(t) / close(t - 1분))
   없으면 그 t는 표본에서 제외한다

2. 표준편차 (표본, n-1)
   σ = stddev(r)
   σ == 0 또는 표본 2개 미만이면 장중 카드 없이 종료

3. 구간 점수
   각 분봉 t에 대해 close(t - W분)이 있으면
     cum[t]   = ln(close(t) / close(t - W분))
     score[t] = |cum[t]| / (σ * sqrt(W))
   close(t - W분)이 없으면 그 t는 후보에서 제외한다

4. 후보
   candidates = { t : score[t] >= k }

5. 병합 (겹치는 피크 제거)
   score 내림차순 정렬
   순회하며, 이미 선택된 피크와 merge-window-minutes 이내면 버림

6. 채택
   상위 max-intraday-cards 건

7. 카드 필드
   windowStart    = t - W분
   windowEnd      = t
   changeRate     = exp(cum[t]) - 1       (로그수익률 → 단순수익률)
   detectionScore = score[t]
   eventType      = INTRADAY
```

**[결측] 분봉은 연속이 아닐 수 있다.** 수집기가 거래 없는 분을 생략할 수 있고 그것을 오류로 보지 않는다(`003-market-data`의 미확정 결정 ③). **행 번호로 인덱싱하면 결측 구간에서 실제 경과 시간이 W분이 아닌데도 `σ·√W`로 나누어 점수가 조용히 부풀어 오른다.** `PriceMoveDetector`는 순수 함수라 예외도 나지 않으므로, 위처럼 **시각 조회 + 없으면 그 t 제외**로 구현한다.

시가 갭은 별도로 1건 만든다.

```
prevClose = 직전 거래일의 "마지막" 분봉 종가
  → 없으면 갭 카드를 만들지 않고 종료 (오류 아님)
open      = 원본 거래일의 "첫" 분봉 시가
gap       = (open - prevClose) / prevClose

|gap| >= opening-gap-threshold 이면:
   windowStart = windowEnd = 첫 분봉 시각
   changeRate     = gap
   detectionScore = |gap| / opening-gap-threshold
   eventType      = OPENING_GAP
```

리터럴을 쓰지 않는 근거는 **§C-2-1**에 있다. "없으면 생략(오류 아님)" 규칙 때문에 리터럴로 찾으면 **시가 갭 카드가 매일 영구히 0건이 되고 로그조차 남지 않는다.**

`detectionScore`는 갭 카드에도 반드시 채운다 — 컬럼이 `NOT NULL`이고, 비워 두면 구현자가 0이나 임의값을 넣는다.

### 탐지 알고리즘 (코인)

```
매 분 실행 (feedback.batch.crypto-watch-cron):
  now       = 현재 시각
  snapshots = market에서 [now - sigma-lookback-hours, now] 스냅샷 조회 (§코인 가격 스냅샷)

  p_now  = snapshots에서 now 에 가장 가까운 값
  p_past = snapshots에서 (now - rolling-window-minutes) 에 가장 가까운 값
             — 허용 오차 ±1분. 벗어나면 없는 것으로 본다

  p_now 또는 p_past 없음 → 종료 (기동 직후·피드 단절)

  σ 표본 = snapshots를 rolling-window-minutes 간격으로 자른 "겹치지 않는" 구간의
           로그수익률 집합. 24시간이면 최대 288개다.
           매 분 슬라이딩으로 만들면 표본이 1440개가 되어 min-sample-count 도달
           시점과 σ 값이 통째로 달라지므로, 반드시 겹치지 않게 자른다.
           각 표본도 양 끝 스냅샷이 있을 때만 만든다.
  표본 수 < min-sample-count 이면 종료
  σ24 = stddev(표본)  (표본, n-1)
  σ24 == 0 이면 종료

  r5 = ln(p_now / p_past)
  |r5| / σ24 < k 이면 종료
  마지막 카드 생성 후 cooldown-minutes 이내면 종료
  occurred_at 기준 KST 당일 생성 건수 >= daily-limit 이면 종료
  근거 기사 0건이면 종료 (FEED-003과 동일)

  → 카드 생성 (§C-9)
     occurred_at     = now                      ← 절대 시각 하나만 저장
     window_start    = NULL
     window_end      = NULL
     originTradeDate = occurred_at 의 KST 날짜
     eventType = INTRADAY, revealTime = NULL

  응답 조립 시
     windowEnd   = occurred_at
     windowStart = occurred_at - rolling-window-minutes
```

**`occurred_at` 하나만 저장하는 이유는 자정이다.** `window_start`/`window_end`를 `TIME`으로 저장하면 00:03 탐지 시 구간이 `23:58 ~ 00:03`인데 TIME 비교로는 시작이 끝보다 늦고, 어느 날짜를 붙여도 한쪽이 24시간 어긋난다. 절대 시각 하나에서 계산하면 복원이 항상 맞는다. 자세한 근거는 §C-9에 있다.

### 코인 가격 스냅샷

위 알고리즘이 요구하는 데이터가 **현재 코드에 없다.** 확인한 사실은 셋이다.

| 후보 | 왜 못 쓰나 |
|---|---|
| `PriceStore` | 심볼당 **최신 틱 하나만** 보관한다 (`getLatestPrice`). 5분 전 가격도 24시간 이력도 없다 |
| `BithumbRestCandleProvider` | `MAX_COUNT = 200`이라 1분봉 200개 ≈ **3.3시간**. 24시간(1440분)을 못 덮는다 |
| 매분 REST 호출 | 12종목 × 1440분 = **17,280회/일**. 저장·캐시가 없어 호출마다 외부로 나간다 |

**가장 나쁜 것은 예외가 나지 않는다는 점이다.** 3.3시간짜리 표본으로 σ가 계산되고 임계치 판정만 조용히 틀린다.

**`market`이 코인 가격 스냅샷을 보관한다.**

```
소유     market. 키 문자열 조립은 PriceStore 안에서만 한다 (conventions.md).
         CryptoPriceSnapshotService 는 심볼·시각만 넘기고 스케줄을 소유한다 (§C-6)
키       price:crypto:{symbol}:snapshots     (Redis Sorted Set)
score    기록 시각의 epoch millis
member   "{epochMillis}:{price}"
기록     매 분 1건 (market.crypto.price-snapshot-cron)
보관     sigma-lookback-hours 를 넘은 원소는 기록할 때마다 제거한다 (약 1440건)
조회     CryptoPriceSnapshotService.getSnapshots(symbol, from, to)
         → List<(시각, 가격)>. feedback 은 이 서비스만 안다
```

**수익률이 아니라 가격과 시각을 저장한다.** 수익률만 저장하면 알고리즘이 요구하는 `p_past`(5분 전 가격)를 꺼낼 수 없고, 애초에 **기록하는 쪽도 "5분 전 가격"을 알 수 없다** — `PriceStore`에는 최신 틱 하나뿐이기 때문이다. 가격 스냅샷으로 두면 5분이든 다른 창이든 조회 시점에 계산된다.

**원소마다 시각을 갖는 것이 핵심이다.** 값만 나열한 리스트로 두면 `sigma-lookback-hours`(시간 기준)를 판정할 수 없다. 게다가 **Redis는 앱과 별도 컨테이너라 재기동해도 데이터가 남으므로**, 시각이 없으면 3일 전 표본으로 `min-sample-count`를 통과해 σ를 계산하고 "최근 24시간"이라고 취급하게 된다. 피드가 몇 시간 끊겼던 구간도 구분되지 않는다.

**기록 시 `PriceStore.isPriceAvailable`이 `false`면 건너뛴다.** 빗썸 피드가 끊기면 동결된 최신 틱이 계속 읽혀 `r5 = 0` 표본이 쌓이고, σ가 0에 수렴한 뒤 복구 첫 틱에서 `|r5|/σ24`가 폭발해 **허위 카드가 무더기로 생성된다.** 예외 없이 반대 방향으로 틀리는 경로다.

**기동 직후에는 스냅샷이 비어 있다.** 표본이 `min-sample-count`(100건 ≈ 8.3시간) 미만이면 카드를 만들지 않는다. 오류가 아니며 `DEBUG` 로그면 충분하다.

`feedback`이 Redis 키를 직접 조립하지 않는 이유는 `docs/conventions.md`의 "Redis 키는 소유 도메인의 전용 컴포넌트에서만 조립한다"에 따른 것이다. 코인 시세는 `market`의 것이다. **기록 스케줄도 `market`이 소유한다** — `PriceStore`는 `@Component`이고 심볼 목록도 모르므로, `CryptoPriceSnapshotService`가 매 분 12종목을 훑어 기록한다. 프로퍼티 네임스페이스가 `market.crypto.*`인 것도 같은 이유다.

### 노출 판정

재생이 1배속이므로 원본 거래일 시각이 곧 서비스 날짜의 벽시계 시각이다. **게이트 목록은 §C-5**에 있고, 여기서는 `reveal_time`을 어떻게 계산하는지만 정한다.

```
[클램프]  기사·공시 발행시각 t → 노출 시각
  clamp(t) = t가 원본 거래일 09:00 이전이면  09:00
             그 외                          t의 시각 부분
  ※ 공시에는 §C-3의 날짜 규칙을 먼저 적용한 뒤 클램프한다.
     그 규칙을 통과한 공시는 전부 D-1 접수분이므로 항상 09:00이 된다.

[카드의 reveal_time]
  eventType = INTRADAY     → max(windowEnd + 1분, clamp(가장 늦은 근거 publishedAt))
  eventType = OPENING_GAP  → clamp(가장 늦은 근거 publishedAt)   ← +1분 없음
  코인                     → NULL
```

**장중 카드에 `+1분`을 더하는 이유.** 캔들 API의 공개 컷오프가 `현재분 − 1분`이라(`StockReplayService.resolveRevealCutoff`) 11:25:00 시점에 사용자가 볼 수 있는 마지막 종가는 11:24 봉이다. `revealTime = windowEnd = 11:25`로 두면 카드가 `close(11:25)` 기반 변동률을 **다른 어떤 API보다 1분 먼저** 알려준다.

**시가 갭 카드에는 `windowEnd`가 아예 들어가지 않는다.** 근거가 정의상 `전장`(상한 `D 09:00`)이라 `clamp` 결과가 **항상 09:00**이고, 갭 카드는 개장과 동시에 열린다. 첫 분봉의 시가는 개장 시점부터 가격 API로 정당하게 공개되므로 앞설 위험도 없다. `+1분`을 더하면 첫 분봉 시각에 따라 09:01~09:02로 밀려 **개장 직후에 갭 카드가 비는데**, 갭 카드는 개장과 동시에 필요한 산출물이다(§C-6의 생성 순서).

**근거 기사 시각까지 포함해 최댓값을 쓴다.** 주식 장중 근거창이 `windowEnd + 5분`까지라 **카드보다 늦게 발행된 기사가 붙을 수 있는데**, `revealTime = windowEnd`로 두면 그 기사를 Part C보다 5분 먼저 보게 된다.

**그 최댓값에는 반드시 클램프가 붙어야 한다.** 클램프 없이 최댓값만 쓰면 전장 기사에서 정반대로 작동한다.

> 시가 갭 카드의 근거는 정의상 `전장` 구간(§C-2)이다. 직전 거래일 18:40 기사를 근거로 붙인 갭 카드는 `max(09:00, 18:40) = 18:40`이 되어 **장중 내내 안 보이고 장 마감 뒤에 나타난다.** 같은 규칙이 Part C `items`에도 걸리는데 `PRE_MARKET` **요약은 09:00부터 나가므로**, 요약이 목록보다 앞서는 비대칭이 방향만 바뀌어 재현된다.

전장 기사는 "개장 시점에 이미 알려진 정보"이므로 09:00으로 당기는 것이 맞다.

**`reveal_time`을 날짜가 아니라 `TIME`으로 저장하는 이유는 재재생이다.** `StockReplaySessionScheduler`는 검증된 거래일을 찾을 때까지 과거로 거슬러 올라가므로(수집이 하루 실패하면) 서로 다른 서비스 날짜가 같은 `source_trading_date`를 재생할 수 있다. 절대 시각으로 저장하면 두 번째 재생일에는 노출 시각이 이미 과거라 **09:00에 하루치 카드가 전부 열린다.**

### 파생 사실 계산 (FEED-007)

매도 회고가 조회일 뿐인 응답이 되지 않게, 서버가 아래를 계산해 응답과 LLM 입력에 넣는다. **전부 시각 비교와 뺄셈이라 AI 판단이 들어가지 않는다.**

```
[보유 구간 극값]  매수 체결시각 ~ 매도 체결시각의 분봉에서 (양 끝 포함)
  ※ 전부 분봉의 close 만 쓴다. high/low 컬럼을 쓰지 않는다.
  holdHighPrice, holdHighAt      close 최댓값과 그 시각
  holdLowPrice,  holdLowAt       close 최솟값과 그 시각
  sellVsHighRate = (매도가 - holdHighPrice) / holdHighPrice
  sellVsLowRate  = (매도가 - holdLowPrice)  / holdLowPrice

[뉴스 대비 매매 타이밍]  보유 구간 카드의 근거 기사 중 가장 이른 발행시각 T0
  buyToNewsMinutes = (T0 - 매수시각) 분
    양수 → 매수가 기사보다 그만큼 앞섰다
    음수 → 기사가 나온 뒤 그만큼 지나 매수했다
  근거 기사가 없으면 null

[카드와 매매의 간격]  보유 구간에 걸친 카드 각각에
  minutesAfterBuy   = (카드 windowEnd - 매수시각) 분
  minutesBeforeSell = (매도시각 - 카드 windowEnd) 분

[매도 후 흐름]  게이트는 §C-5
  status = READY | NOT_YET
  closePrice, closeAt            원본 거래일 "마지막 분봉"의 close 와 그 시각
                                 (리터럴로 찾으면 없는 날 null 이다 — §C-2-1)
  sellToCloseRate = (closePrice - 매도가) / 매도가
  postSellHighPrice, postSellHighAt   매도 후 ~ 마지막 분봉의 close 최댓값
```

**극값을 `high`/`low`가 아니라 `close`로 잡는 이유가 있다.** 이 서비스의 시장가 체결은 **직전 완료 분봉의 종가로만** 이루어진다(`StockReplayService`). `high`로 계산하면 `atHoldHigh`가 **사용자가 애초에 얻을 수 없었던 가격**이 되고, 그걸 "안 팔았다면" 표에 올리면 실현 불가능한 수익률로 후회를 유도하는 셈이 된다. 나머지 두 시나리오(`atClose`·`atFirstMoveAfterBuy`)도 종가 기준이므로 기준이 일관된다.

`sameSessionCompleted=false`(여러 재생일에 걸친 매매)이면 위 전부를 `null`로 둔다 — 분봉이 불연속이라 계산이 성립하지 않는다.

**매도 후 흐름의 노출 게이트가 중요하다.** 14:40에 매도하고 14:41에 조회하면 장 마감까지의 가격은 아직 재생되지 않은 미래다. 그걸 보여주면 사용자가 같은 종목을 재매수할 때 답을 아는 상태가 된다. §C-5의 게이트를 통과했을 때만 채운다. **기준 날짜가 "오늘"이 아니라 그 체결의 서비스 날짜라는 점**이 핵심이다 — 오늘로 잡으면 어제 판 체결을 오늘 오전에 열었을 때 `READY`였던 값이 `NOT_YET`으로 되돌아간다.

### 반사실·집단 비교 계산 (FEED-010·011)

```
[반사실]  같은 수량을 다른 가격에 팔았다면
  각 시나리오 가격 P에 대해
    매도금액   = P × 수량
    매도수수료 = FLOOR(매도금액 × 수수료율)  (가격이 바뀌면 수수료도 바뀐다)
                 수수료율은 시장별로 다르다 — STOCK 0.00015 / CRYPTO 0.0005
                 원 미만 내림(setScale(0, FLOOR)). OrderExecutionService와 같은 식이어야 한다
                 2차는 주식 전용이므로 실제로 쓰는 값은 0.00015다
    실현손익   = (매도금액 - 매도수수료) - (배분 매수원가 + 배분 매수수수료)
    수익률     = 실현손익 / (배분 매수원가 + 배분 매수수수료)

  시나리오 3개
    atClose             P = 원본 거래일 "마지막 분봉"의 close (§C-2-1)
    atHoldHigh          P = 보유 구간 최고가 (시각도 함께)
    atFirstMoveAfterBuy P = 보유 구간(매수~매도) 안의 첫 변동 카드의 windowEnd 종가
                          보유 구간에 카드가 없으면 null
                          (매도 이후의 카드는 쓰지 않는다 — 보유하지 않은 구간이다)

[집단 비교]  (종목, 원본 거래일, 변동 카드) 단위
  기준 카드 = 보유 구간 안의 첫 변동 카드 (atFirstMoveAfterBuy와 동일)
              없으면 status = NO_EVENT, 전 필드 null

  T = 카드 windowEnd 를 그 카드가 재생된 서비스 날짜에 붙인 절대 시각
  모집단 = T 시점에 그 종목을 보유 중이던 회원

  ※ holdings 테이블을 쓰면 안 된다 (아래 참고). lot·배분으로 재구성한다.
    회원 m 이 T 시점 보유 중 ⟺
      Σ(holding_lots.original_quantity  where lot.executed_at <= T)
      − Σ(trade_allocations.allocated_quantity
            where alloc.sellTrade.executed_at <= T)
      > 0

  ※ 컬럼명 주의 — holding_lots 에는 quantity 가 없다.
    original_quantity(불변) 와 remaining_quantity(가변) 두 개다.
    remaining_quantity 를 쓰면 현재 상태라 아래 holdings 문제가 그대로 재현된다.
  ※ 매도 시각의 정본은 trade_allocations.created_at 이 아니라
    그 배분이 속한 매도 체결의 executed_at 이다 (아래 지표들과 기준을 맞춘다).
  holderCount            모집단 크기
  soldWithin30MinRate    카드 windowEnd 후 30분 내 매도한 비율
  medianMinutesToSell    카드 windowEnd → 매도까지 걸린 시간의 중앙값
                         (장 마감까지 안 판 회원은 중앙값 계산에서 제외)

  yourMinutesToSell      본인의 (매도시각 - 카드 windowEnd) 분
                         모집단 통계가 아니라 비교 기준값이므로
                         INSUFFICIENT_SAMPLE 일 때도 채운다

  soldWithin30MinRate 의 분모는 보유자 전체, medianMinutesToSell 은 매도자만이다.
  15:00 이후 카드는 30분 창이 장 마감에 잘려 비율이 구조적으로 낮게 나온다 —
  값 자체는 그대로 두고 화면이 카드 시각을 함께 보여준다.

  holderCount < 5 이면 status = INSUFFICIENT_SAMPLE
                       holderCount·soldWithin30MinRate·medianMinutesToSell 은 null
                       yourMinutesToSell 만 남긴다
```

둘의 게이트는 **§C-5**다. 반사실은 시각 게이트를 쓰고(아직 재생되지 않은 가격이라 미래 정보다), 집단 비교는 **확정 집계 행이 있을 때** 채운다. 둘 다 기준 날짜는 "오늘"이 아니라 **그 체결의 서비스 날짜**다.

**`holdings`로는 "그때 보유 중이던 회원"을 구할 수 없다.** `holdings`는 현재 상태만 갖는 가변 행이라(`applyBuy`/`applySell`이 같은 행을 갱신) 과거 시점 스냅샷이 없다. 배치가 `holdings`를 그대로 조회하면 "카드 시점 보유자"가 아니라 **"배치 시점 보유자"**가 집계되고, 그러면 **정확히 그 카드를 보고 판 사람들이 모집단에서 빠진다** — `soldWithin30MinRate`가 구조적으로 과소 집계된다. 예외는 나지 않는다.

**시각 기준이 두 개라는 점도 주의한다.** 카드의 `windowEnd`는 원본 거래일 시각이고 `trades.executed_at`·`holding_lots.executed_at`은 서비스 벽시계다. 재생이 1배속이므로 시각 부분을 그 카드가 재생된 **서비스 날짜**에 붙여 비교한다(`getSourceTradingDate`의 역방향).

집단 비교는 장 마감 배치에서 `price_move_peer_stats`에 확정 집계로 저장한다(§C-1). 반사실은 분봉만 있으면 계산되므로 저장하지 않고 조회 시 계산한다 — 이때 §C-6의 배치용 분봉 메서드를 쓰지만, **§C-5의 게이트를 통과한 뒤에만** 호출한다.

**같은 원본 거래일이 두 번 재생되면 서비스 날짜별로 따로 쌓는다** — `UNIQUE(price_move_event_id, service_date)`. 덮어쓰면 첫 재생일 매도자의 통계가 사라지고, 둘째 날 사용자는 남의 날 통계로 서술이 굳는다. 조회는 **그 매도 체결의 서비스 날짜 행만** 본다. 근거는 §C-9에 있다.

### 왜 반사실은 AI 문장에 넣지 않는가

`"안 팔았다면 -1.17%였습니다"`는 사실이지만 **"팔지 말걸"을 암시**한다. §후검증의 `판단·훈수` 줄이 가정법 어미(`았다면`)를 막고 있는데, 반사실을 문장으로 쓰면 그 규칙과 정면으로 부딪힌다.

그래서 **숫자를 표로만 내려보낸다.**

| | 수익률 |
|---|---|
| 실제 (14:40 매도) | -2.17% |
| 종가까지 보유 시 | -1.17% |
| 보유 중 최고가 시 | +1.11% |

나란히 놓기만 하고 해석은 사용자가 한다. AI가 개입하지 않으므로 훈수가 되지 않고 후검증 규칙도 깨지지 않는다.

**집단 비교는 다르다.** `"이 종목 보유자 중 38%가 30분 안에 매도했습니다"`는 가정이 아니라 관측된 사실이라 AI 서술에 넣어도 된다.

### 장 마감 후 재방문 설계

매도 직후에는 수치·파생 사실·뉴스 카드만 보인다. 반사실과 집단 비교는 **장 마감 후 다시 열어야** 나타난다.

이는 스포일러 차단의 부수 효과이자 의도된 UX다 — "장 마감 후 다시 확인해보세요"가 자연스러운 재방문 유도가 된다. 화면은 `status="NOT_YET"`일 때 그 안내를 노출한다.

### 뉴스 매칭 범위

구간 정의는 **§C-2**, 공시의 날짜 판정은 **§C-3**에 있다. 여기서 다시 적지 않는다.

근거가 `max-sources-per-card`를 넘으면 발행시각이 이벤트에 가까운 순으로 자른다. 목록 응답과 LLM 입력 상한은 §C-7의 `max-items-*`를 따르며 **발행시각 내림차순으로 자른다.**

**코인 근거창에 `news.match-after-minutes`를 더하지 않는 이유** — 코인 탐지는 `windowEnd` 시점에 실시간으로 도므로 그 이후 기사는 존재할 수 없다. 게다가 수집이 30분 주기라 마지막 수집 이후 기사는 아직 DB에 없다. `crypto.match-before-minutes`를 더 넓게 잡아 마지막 수집분을 확실히 포함시킨다. 그래도 근거 0건이면 카드를 만들지 않으며(FEED-003), **다음 수집 때 근거가 들어와도 그 카드를 되살리지 않는다** — 지나간 실시간 이벤트를 소급 생성하지 않는 것이 설계 의도다.

### LLM 프롬프트

시스템 프롬프트는 네 파트 공통이다.

```
너는 모의투자 교육 서비스의 관찰자다. 주어진 수치와 기사 목록을 한국어로 서술한다.

규칙:
- 주어진 수치만 쓴다. 계산하거나 바꾸지 않는다.
- 기사와 가격 변동의 인과를 단정하지 않는다. "같은 시간대에 이런 기사가 있었다" 수준으로만 쓴다.
- 특정 종목의 매수·매도를 권유하지 않는다.
- 앞으로의 가격을 예측하지 않는다.
- 조언하지 않는다. 관찰한 사실만 서술한다.
- 모든 문장을 "~습니다"로 끝낸다.
- **기사 제목을 그대로 옮기지 않는다.** 제목에 담긴 전망·기대·예측 표현을 따라 쓰지 말고,
  무엇을 다룬 기사인지만 네 말로 서술한다.
```

마지막 줄이 요약·브리핑에서 특히 중요하다. 기사 제목에는 `전망`·`예상`이 흔한데 §후검증이 그 표현을 차단하므로, 제목을 인용하면 출력이 통째로 버려진다.

사용자 프롬프트는 파트별로 다르다. 변동 원인 카드 예시다.

```
종목: 삼성전자
구간: 11:20 ~ 11:25
변동률: -1.82%

같은 시간대 기사:
- 삼성전자, 반도체 공장 가동 일시 중단 (한국경제, 11:15)
- 반도체 업황 둔화 우려 확산 (매일경제, 11:02)

위 내용을 2~3문장으로 서술해줘.
```

매도 회고는 파생 사실을 함께 넘긴다. **수치만 나열하면 조회가 되므로, 프롬프트에서 관계를 앞세우도록 지시한다.**

```
종목: 삼성전자
매수: 09:30, 70,000원 10주
매도: 14:40, 68,500원 10주
수익률: -2.17% (실현손익 -15,207원)

보유 중 최고가: 11:05의 70,800원 (매도가가 3.25% 낮음)
보유 중 최저가: 14:20의 68,100원 (매도가가 0.59% 높음)
매수는 첫 근거 기사(11:15)보다 105분 앞섰습니다.

보유 구간에 걸친 변동:
- 11:20~11:25 -1.82% (매수 115분 뒤, 매도 195분 전)
  근거: 삼성전자 반도체 공장 가동 일시 중단 (한국경제, 11:15)

매도 후 흐름: 마감 종가 69,200원 (매도가보다 1.02% 높음)

위 내용을 3~4문장으로 서술해줘. 수치를 그대로 나열하지 말고,
매수·매도 시각이 변동·기사와 어떤 순서였는지를 중심으로 써줘.
```

**아래 프롬프트 문자열의 시각 리터럴은 §C-2 참조 규칙의 의도된 예외다** — 모델에게 주는 자연어라 값을 그대로 적어야 한다. §C-2를 바꾸면 이 문자열도 함께 고친다.

종목 뉴스 요약은 범위를 명시해 넘긴다. **`PRE_MARKET` 생성에 `FULL` 기사를 넣는 실수를 막으려면 프롬프트가 범위를 갖고 있어야 한다.**

```
종목: 삼성전자
범위: 직전 거래일 장 마감(15:30) 이후 ~ 당일 개장(09:00) 전

기사:
- 반도체 업황 둔화 우려 확산 (매일경제, 전일 18:40)
- 삼성전자, 유상증자 결정 (DART 공시, 전일 접수)

위 기사들을 종합해 3~5문장으로 서술해줘.
특정 기사의 문장을 그대로 옮기지 말고, 무엇을 다룬 기사들인지 써줘.
```

`FULL`은 범위 줄만 "직전 거래일 15:30 ~ 원본 거래일 15:30"으로, `ROLLING_24H`(코인)는 "최근 24시간"으로 바꾼다.

개장 전 브리핑은 전 종목이 대상이라 종목명을 함께 넘긴다.

```
시장: 국내 주식
범위: 직전 거래일 장 마감(15:30) 이후 ~ 당일 개장(09:00) 전

기사:
- [삼성전자] 반도체 업황 둔화 우려 확산 (매일경제, 전일 18:40)
- [SK하이닉스] 미국 증시 반도체 업종 강세 (한국경제, 당일 06:20)
- [LG화학] 주요사항보고서 유상증자결정 (DART 공시, 전일 접수)

위 내용을 3~6문장으로 서술해줘.
종목명을 언급해도 되지만 사거나 팔라고 하지 마라.
어떤 종목에 어떤 소식이 있었는지만 써줘.
```

문장 수는 파트별로 다르다 — 변동 카드 2~3문장, 매도 회고 3~4문장, 종목 뉴스 요약 3~5문장, 개장 전 브리핑 3~6문장.

### 후검증

`NarrativeValidator`가 LLM 출력에서 아래 표현을 **부분 문자열로** 찾아 거른다. 하나라도 걸리면 **템플릿 문장으로 대체**하고 `narrative_source=TEMPLATE`으로 기록한다.

**정규식을 쓰지 않는 것이 의도다.** 아래 표현에는 메타문자가 하나도 없어 정규식과 결과가 같은데, 정규식으로 두면 어간으로 일반화(`매도하(세요)?`)하는 길이 문법적으로 열린다. 리터럴 목록에는 그 자리가 없어 "임의로 넓히지 않는다"가 코드 형태만으로 지켜진다.

```
인과 단정   때문에 | 영향으로 | 여파로 | 덕분에 | 로 인해 | 탓에
투자 권유   매수하세요 | 매도하세요 | 사야 | 팔아야 | 추천 | 주목할 | 유망 | 비중 확대
가격 예측   오를 것 | 내릴 것 | 전망 | 예상됩니다 | 기대됩니다 | 상승할 것 | 하락할 것
조언·후회   하세요 | 했으면 | 좋았을 | 아쉽 | 권장
판단·훈수   버티 | 놓치 | 실수 | 잘못 | 다행 | 기회를 | 았다면 | 었다면 | 였다면 | 했다면 | 렸다면
```

**`판단·훈수` 줄만 파트를 가린다.**

| 파트 | 적용 규칙 |
|---|---|
| 변동 카드·매도 회고 | 5줄 전부 |
| 뉴스 요약·브리핑 | `판단·훈수`를 뺀 4줄 (인과 단정 · 투자 권유 · 가격 예측 · 조언·후회) |

`판단·훈수` 줄의 `기회를`·`실수`·`잘못`·`다행`은 기사 내용을 서술할 때 자연스럽게 나오는데, 요약에는 템플릿이 없어 걸리면 **기능이 통째로 사라진다.** 반면 `조언·후회` 줄(`하세요`·`권장`)은 요약·브리핑에서도 그대로 막는다 — C-004의 "조언하지 않는다"가 여기서 사라지면 안 된다.

**`판단·훈수` 줄은 파생 사실을 넣으면서 추가했다.** "하락 이후에도 3시간 보유했습니다"는 사실이지만 "3시간이나 버티셨네요"는 판단이고, "더 기다렸다면"은 후회 유도다. 파생 사실이 늘어날수록 이 경계를 넘기 쉬워지므로 가정법 어미도 막는다 — **다만 `~다면` 전부가 아니라 아래 다섯 형태만이다.**

**가정법 어미가 처음에 셋(`았다면`·`었다면`·`였다면`)뿐이어서 이 절의 대표 예시를 스스로 못 잡았다 (2026-08-03 구현 중 발견, 이슈 #147).** 셋은 `팔았다면`·`있었다면`·`뒤였다면`은 잡지만 **`기다렸다면`(렸다면)과 `보유했다면`(했다면)은 못 잡는다** — 초성이 달라 부분 문자열로 겹치지 않는다. 바로 위 문단과 `docs/api-contracts.md`의 문구 제약이 둘 다 "더 기다렸다면"을 막아야 할 대표 예로 드는데 목록이 통과시키고 있었다. 그래서 `했다면`·`렸다면` 둘을 추가했다. **이것은 "목록을 임의로 넓히지 않는다"의 예외가 아니라 목록과 예시의 불일치를 메운 것이다** — 새 유형을 막는 것이 아니라 이미 막기로 한 가정법을 실제로 막는다.

**그래도 가정법이 다 막히는 것은 아니다. 이 한계를 알고 쓴다.** 부분 문자열 판정이라 어간에 따라 축약형이 갈리는데, 다섯 어미로는 **`샀다면`·`봤다면`·`왔다면`·`줬다면`·`뒀다면`·`썼다면`처럼 축약된 형태를 잡지 못한다.** 매매 회고 도메인에서 "그때 샀다면"은 "더 기다렸다면"만큼 흔하므로 실제로 새어 나갈 수 있다. **여기서 목록을 또 넓히지 않는다** — 어미를 하나씩 좇으면 끝이 없고, 그러다 `샀`·`봤` 같은 어간으로 일반화하면 "매수했습니다"류 서술형까지 걸려 이 절이 지키려는 경계가 무너진다. 실제로 새는지는 §튜닝의 관측 항목으로 확인하고, 빈도가 유의미하면 그때 목록이 아니라 **프롬프트를 손본다**(바로 아래 원칙과 같다).

**오탐의 비용이 카드와 요약에서 다르다.** 카드·매도 회고는 걸려도 템플릿 문장으로 바뀔 뿐이지만, **뉴스 요약과 브리핑에는 템플릿이 없어서 걸리면 기능 자체가 사라진다**(`summary=null`).

이 차이가 실제로 문제가 되는 이유는 프롬프트가 기사 제목을 그대로 입력으로 넣기 때문이다. **경제 기사 제목에 `전망`은 흔하다.** LLM이 제목을 인용하는 순간 요약 전체가 폐기된다.

그래서 요약·브리핑에만 2단계를 적용한다.

```
1차 생성 → 후검증 통과      → 저장 (narrative_source = LLM)
        → 걸림 → 재생성 1회 → 통과   → 저장 (narrative_source = LLM)
                            → 또 걸림 → summary = NULL
                                        narrative_source = NONE
                                        summaryStatus / status = UNAVAILABLE
```

- 재생성은 **1회만** 한다. 무한 재시도로 비용이 늘지 않게 한다.
- **재생성 프롬프트에는 실제로 걸린 표현을 그대로 넣는다.** 무엇에 걸렸는지 알려주지 않으면 2차 시도도 같은 단어를 쓸 확률이 높고, 요약에는 템플릿이 없어 곧바로 `NONE`이다. 확정 문구는 아래와 같다.

  ```
  직전 출력이 아래 금지 표현에 걸려 폐기됐다: {적발된 표현들}

  같은 표현을 쓰지 말고 다시 써라. 기사 제목을 그대로 인용하지 마라 —
  제목에 든 전망·기대·예측 표현이 그대로 따라 들어온다.
  "업황 전망을 다룬 기사"처럼 쓰지 말고 "업황을 다룬 기사"처럼 써라.
  ```

  적발 사유가 제목 인용이 아닐 수도 있다(예: `전망`은 제목을 인용하지 않아도 나온다). 그래서 사유를 지정하지 않고 **걸린 표현 자체**를 넘긴다.
- 카드·매도 회고는 기존대로 **재생성 없이 곧바로 템플릿**이다. 대체할 문장이 있으므로 재시도할 이유가 없다.

`narrative_source`에 `NONE`을 추가한 것이 이 때문이다 — `LLM`도 `TEMPLATE`도 아닌 "서술 없음" 상태가 요약·브리핑에만 존재한다.

**목록은 느슨하게 만들지 않는다.** `TEMPLATE` 비율이 30%를 넘거나 요약의 `NONE` 비율이 10%를 넘으면 목록이 아니라 **프롬프트를 손본다.**

"매도했습니다" 같은 서술형은 걸리지 않아야 한다 — 위 목록에 `매도하세요`만 있고 `매도하`는 없다. **목록을 임의로 넓히지 않는다.**

### 템플릿 문장

LLM이 실패하거나 후검증에 걸렸을 때 서버가 수치로 조립한다.

| 파트 | 템플릿 |
|---|---|
| 장중 카드 | `{HH:mm}부터 {W}분간 {X.XX}% {상승\|하락}했습니다. 같은 시간대에 기사 {N}건이 있었습니다.` |
| 시가 갭 | `직전 거래일 종가 대비 {X.XX}% {높게\|낮게} 시작했습니다. 개장 전 뉴스·공시 {N}건이 있었습니다.` |
| 매도 회고 | `{매수가}원에 매수해 {매도가}원에 매도했습니다. 수익률은 {±X.XX}%입니다. 보유 중 최고가는 {HH:mm}의 {최고가}원이었습니다.` |
| 뉴스 요약·브리핑 | **템플릿 없음** — 재생성 1회 후에도 걸리면 `summary=null`, `narrative_source=NONE`, 상태값 `UNAVAILABLE` |

**방향 단어가 있는 자리는 숫자를 절댓값으로 쓴다** — `{X.XX}%`인 두 줄이 그것이다. `{±}`를 그대로 따르면 `-1.82% 하락했습니다`가 되어 부호가 두 번 적용되고 뜻이 뒤집힌다. 방향 단어가 없는 `수익률은 {±X.XX}%입니다`만 부호를 찍는다. `docs/api-contracts.md`의 카드 예시 서술도 부호 없이 "1.82% 하락했습니다"다.

**보유 구간 극값이 없으면 매도 회고의 셋째 문장을 뺀다.** `sameSessionCompleted=false`면 `holdHighPrice`가 `NULL`이다(§파생 사실 계산). 앞 두 문장은 원장 수치라 그때도 성립하므로 서술이 비지 않고, `narrativeStatus`가 항상 `READY`라는 §C-4 보장이 유지된다.

요약·브리핑에 템플릿을 두지 않는 이유는, 여러 기사를 종합하는 것이 요약의 본질이라 수치 조립으로 대체할 수 없기 때문이다.

### 실패 처리

모든 실패 경로에 대응이 정의돼 있다. 구현자가 판단할 여지를 남기지 않는다.

| 상황 | 처리 |
|---|---|
| 네이버·DART 키 없음 | `Fake*Collector`가 빈 목록 반환. 기동·테스트 정상 |
| OpenAI 키 없음 | `NarrativeGenerator`가 즉시 실패 반환 → **카드·매도 회고는 템플릿, 요약·브리핑은 `NONE`** |
| 뉴스 API 호출 실패 | 그 종목만 건너뛰고 나머지 계속. `WARN` 로그 |
| DART 호출 실패 | 공시 없이 뉴스만으로 진행 |
| LLM 타임아웃·오류 | 그 카드만 템플릿. 배치 계속 |
| 요약·브리핑의 **생성 호출** 실패 | 재생성 횟수를 쓰지 않고 곧바로 `summary=null`·`NONE`. 재생성은 **후검증 적발에만** 쓴다 — 키 없음·타임아웃은 다시 불러도 같은 이유로 실패한다 |
| 분봉 없음 / σ=0 | 장중 카드 0건. 오류 아님 |
| 직전 거래일 종가 없음 | 시가 갭 카드 생략. 오류 아님 |
| 근거 기사 0건 | 카드 미생성 (설계 의도) |
| 재생세션 READY 아님 | 주식 배치 건너뜀. 조회는 빈 결과 200. **코인 경로는 영향 없음** |
| 배치 중복 실행 | `UNIQUE` 제약으로 무시, 기존 데이터 유지 |
| 코인 스냅샷 표본 부족 | 카드 미생성. `DEBUG` 로그. 오류 아님 (기동 직후 정상 상태) |
| 코인 피드 단절(`isPriceAvailable=false`) | 스냅샷 기록 건너뜀. 표본이 줄어 자연히 카드도 안 나온다 |
| 개장 전 배치가 09:00을 넘김 | 브리핑을 먼저 만들므로 브리핑은 정상. 나머지는 게이트에 걸려 어차피 오전 중 필요 |
| 코인 배치(매시 05분) 실패 | 직전 요약·브리핑이 그대로 조회된다. 다음 시각에 재시도 |
| 요약·브리핑 후검증 2회 연속 실패 | `summary=null`, `narrative_source=NONE`, 상태값 `UNAVAILABLE`. `items`는 그대로 채운다 |
| 장 마감 집단 비교 배치 실패 | 해당 카드의 `peerComparison`이 `NOT_YET`으로 남는다. 매도 회고 조회 자체는 200 |
| 코인 매도 체결로 회고 조회 | 400 `VALIDATION_ERROR` (2차 범위 밖, FEED-007) |

**어떤 실패도 주식 시장 개장·주문·체결을 막지 않는다.** 이 기능은 부가 정보이고 원장이 본체다.

### 외부 API 호출 상세

| 항목 | 값 |
|---|---|
| 네이버 엔드포인트 | `GET https://openapi.naver.com/v1/search/news.json?query={종목명}&display=100&sort=date` |
| 네이버 헤더 | `X-Naver-Client-Id`, `X-Naver-Client-Secret` — 값은 `NAVER_SEARCH_*` 환경변수에서 온다. **`NAVER_CLIENT_ID`·`NAVER_CLIENT_SECRET`는 이미 네이버 OAuth 로그인이 쓰고 있으므로 재사용하지 않는다** (`.env.example`, `application.yml`의 `oauth.naver`). 검색 API용 애플리케이션을 따로 발급받는 순간 둘 중 하나가 깨진다 |
| 네이버 제약 | **날짜 범위 지정 불가**, `display` 상한 100. 최신순으로 받아 **거르지 않고 그대로 저장**한다 (구간 필터는 조회 시점에만). 종일 30분 간격이라 놓치는 구간이 없다 |
| DART 엔드포인트 | `GET https://opendart.fss.or.kr/api/list.json?crtfc_key={키}&corp_code={8자리}&bgn_de={수집일−1}&end_de={수집일}` — `YYYYMMDD`. 전일부터 훑어 접수 지연분을 잡는다 |
| DART 제약 | `corp_code`는 종목코드가 아님. `corpCode.xml`로 16종목 매핑을 미리 만들어 리소스로 둔다 |
| DART 시각 | `rcept_dt`는 `YYYYMMDD`. `published_at`은 그 날짜 `00:00:00`으로 저장 |
| LLM | Spring AI `spring-ai-starter-model-openai` **2.0.0 이상**. 모델은 §C-7 |
| LLM 기동 조건 | OpenAI 스타터는 chat 외에 embedding·image·moderation·audio 빈까지 자동 등록하고 그중 audio speech가 **기동 시점에** 키를 요구한다. `spring.ai.model.*`로 안 쓰는 유형을 끄고 `spring.ai.openai.api-key`에 자리표시자 기본값을 둬야 키 없이 컨텍스트가 뜬다 (2026-08-03 실측, 커밋 `d311b87`) |

Spring AI 1.x는 Spring Boot 3.x 전용이라 이 프로젝트(Boot 4.1.0)에서 컨텍스트가 기동하지 않는다. 반드시 2.0.0 이상을 쓴다. 프로바이더 교체·실패 처리·테스트 방침은 **ADR-0011**에 있다 — 이 spec은 그 결정을 전제로 한다.

환경변수는 `NAVER_SEARCH_CLIENT_ID`·`NAVER_SEARCH_CLIENT_SECRET`·`DART_API_KEY`·`OPENAI_API_KEY`이며, **없어도 기동과 테스트가 정상 동작해야 한다** (KIS·Resend 키와 같은 방식).

## 데이터 모델

Flyway `V13__create_ai_feedback_tables.sql` 하나로 추가한다 (ADR-0004). **컬럼 타입·NULL·FK는 §C-8**, 코인의 시각 컬럼과 유니크 근거는 **§C-9**에 있다. 여기서 다시 적지 않는다.

```
market_news_items              뉴스·공시 통합
  id, instrument_id, type(NEWS|DISCLOSURE), title, publisher, url,
  published_at, created_at
  UNIQUE(instrument_id, url)
  INDEX(instrument_id, published_at)
  -- 본문 컬럼 없음 (저작권)
  -- url 단독 유니크로 두면 안 된다. "반도체 업황 둔화" 같은 기사는
  -- 삼성전자·SK하이닉스 검색 결과에 모두 나오는데, 먼저 저장된 한 종목만
  -- 남고 나머지는 근거 0건이 되어 카드가 생성되지 않는다.
  -- created_at 은 수집 시각이다. 요약 재생성 판정에 쓰인다 (FEED-008).

price_move_events              변동 구간 원장 + 서술
  id, instrument_id, market, event_type(INTRADAY|OPENING_GAP),
  origin_trade_date, window_start, window_end, occurred_at,
  change_rate, detection_score, narrative, narrative_source,
  reveal_time, created_at
  UNIQUE(instrument_id, origin_trade_date, event_type, window_start)
  INDEX(instrument_id, occurred_at)     -- 코인 "최근 24시간" 조회용
  -- 주식은 window_start/window_end 를 채우고 occurred_at 은 NULL.
  -- 코인은 반대다 (§C-9).
  -- event_type 이 유니크에 들어가야 한다. 장중 루프의 첫 후보는 t = 09:05 이고
  -- windowStart = t - W = 09:00 인데, 시가 갭 카드의 windowStart 도 09:00 이다.
  -- event_type 이 없으면 둘이 충돌해 FEED-004의 "존재 시 건너뜀"에 걸려
  -- 나중에 삽입되는 쪽이 조용히 사라진다. 갭이 큰 날은 개장 직후도 급변하는
  -- 날이라 하필 고신호 종목에서 이 충돌이 난다.

price_move_event_sources       이벤트 ↔ 근거 (N:M)
  id, price_move_event_id, market_news_item_id
  UNIQUE(price_move_event_id, market_news_item_id)

instrument_news_summaries      종목·거래일 요약 (전 회원 공유)
  id, instrument_id, origin_trade_date, scope(PRE_MARKET|FULL|ROLLING_24H),
  summary, narrative_source, generated_at
  UNIQUE(instrument_id, origin_trade_date, scope)
  INDEX(instrument_id, scope, generated_at)   -- 코인 최신 1행 조회용
  -- 주식: 개장 전 배치에서 PRE_MARKET·FULL 2건 (§C-1).
  -- 코인: ROLLING_24H 1건. 코인 배치가 같은 행을 UPSERT 한다.
  --       조회 시 생성하지 않는다 (FEED-008).
  -- 코인도 origin_trade_date 를 채운다 — NULL 이면 MySQL 유니크가 중복을
  -- 허용해 갱신이 아니라 새 행이 쌓인다 (§C-9).

market_briefings               시장·거래일 개장 전 브리핑 (전 회원 공유)
  id, market, origin_trade_date, summary,
  narrative_source, generated_at
  UNIQUE(market, origin_trade_date)
  INDEX(market, generated_at)                 -- 코인 최신 1행 조회용
  -- 주식: 개장 전 배치가 (STOCK, 원본 거래일)로 1건.
  -- 코인: 코인 배치가 같은 행을 UPSERT. origin_trade_date 는 위와 같은 이유로 채운다.
  -- items 는 저장하지 않는다. 조회 시 §C-2 구간으로 다시 질의한다.
  -- 응답에서는 코인의 originTradeDate 를 null 로 내린다 (두 테이블 공통).

price_move_peer_stats          카드별 집단 행동 집계 (장 마감 배치)
  id, price_move_event_id, service_date, holder_count,
  sold_within_30min_count, median_minutes_to_sell, aggregated_at
  UNIQUE(price_move_event_id, service_date)
  -- 회원 식별자 없음. 집계 결과만 저장한다.
  -- service_date 가 없으면 같은 원본 거래일 재재생 시 첫날 집계가 덮어써진다 (§C-9).

trade_feedbacks                매도 직후 서술 (회원별)
  id, trade_id, narrative, narrative_source,
  narrative_finalized, regeneration_attempts, generated_at
  UNIQUE(trade_id)
  -- narrative_finalized = §C-5의 재생성 게이트를 통과한 서술인지.
  --   "장 마감이 지났는지"가 아니다 — 집단 비교 확정까지 반영해야 TRUE 다.
  -- regeneration_attempts = 체결 1건당 누적 재시도 횟수. 날짜로 리셋하지 않는다
  --   (재생성 실패 시 generated_at 을 갱신하지 않아 날짜 기준이 성립하지 않는다).
```

`market_news_items` 하나를 네 파트가 공유한다. 계획 대조를 하지 않으므로 `007-journal`의 어떤 테이블도 참조하지 않는다.

## 튜닝

아래 항목을 §C-7의 기본값으로 **구현을 완료한 뒤** 실데이터로 검증해 조정한다. 조정은 `application.yml` 수정이며 코드 변경이 아니다. **값은 여기 적지 않는다 — §C-7이 정본이다.**

| 항목 | 확인 방법 | 조정 기준 |
|---|---|---|
| `detection.z-score-k` | 주식 전 종목 하루치 배치 실행 후 카드 수 집계 | 종목당 후보 2~5개면 유지. 0~1개면 낮추고, 10개 이상이면 높인다 |
| `detection.window-minutes` | 카드의 `changeRate` 분포 확인 | 대부분 0.5% 미만이면 늘린다 |
| `detection.opening-gap-threshold` | 갭 분포 확인 | 매일 전 종목이 걸리면 높인다 |
| `news.match-before/after-minutes` | 카드당 근거 수 확인 | 평균 0건에 가까우면 넓힌다 |
| `crypto.cooldown-minutes`·`daily-limit` | 하루 생성 건수 확인 | 상한에 매일 걸리면 완화 |
| `crypto.min-sample-count` | 기동 후 카드가 나오기까지 걸린 시간 확인 | 너무 길면 낮춘다 |
| `llm.model` | `TEMPLATE` 비율 확인 | 30% 넘으면 프롬프트 수정, 그래도 높으면 상위 모델 |
| `llm.model` (폴백 후보) | 기동·첫 호출에서 `max-tokens` 매핑 확인 | GPT-5 계열은 `max_completion_tokens` 파라미터를 쓴다. Spring AI 2.0의 매핑이 어긋나면 `gpt-4.1-mini`로 내린다 — 2026-08-03 실호출에서 §후검증 통과와 완료 토큰 74를 확인해 둔 후보다 |
| `llm.model` (요약) | 요약의 `NONE` 비율 확인 | 10% 넘으면 프롬프트 수정 |
| 코인 질의어 보정 | 근거 기사 목록을 눈으로 훑어 무관 기사 비율 확인 | 높으면 종목별 질의어 오버라이드를 도입한다 |
| `판단·훈수`의 축약 가정법 | 매도 회고 서술에서 `샀다면`·`봤다면`·`왔다면`·`줬다면`·`뒀다면`·`썼다면` 출현 빈도를 센다 (§후검증의 한계) | 유의미하게 나오면 **목록이 아니라 프롬프트를 손본다.** 어미를 하나씩 좇으면 끝이 없고, 어간으로 일반화하면 서술형까지 걸린다 |

**시가 갭은 직전 거래일 분봉을 요구한다.** 현재 수집 배치가 하루치만 받아온다면 갭 카드는 데이터가 이틀 쌓인 뒤부터 나온다. 브리핑도 같은 이유로 배포 직후 이틀은 비어 있을 수 있다. 정상 동작이며 오류로 처리하지 않는다.

## 범위 제외

- **계획 대비 실제 대조**(목표가·손절가와 매도 비교) — `007-journal`이 선행되어야 한다.
- **기존 명세 §6의 나머지 AI 엔드포인트 5개** — `ai/pre-order`·`ai/d7/{id}`·`ai/weekly-report`·`ai/basis-stats`·`ai/similar/{id}`.
- **AI 주간·월간 리포트와 투자 습관 분석**(3차) — 투자일기를 1주~1개월 모아 분석하는 기능.
- 투자일기 작성·수정·목록·상세·매도 회고 (`007-journal`, 다른 팀원 범위).
- 뉴스 감성 분석·중요도 점수·홍보성 기사 분류 모델.
- 거래량 기반 이벤트 탐지 (거래량은 3차 항목).
- **뉴스 열람 여부 피드백**("이 기사는 매수 전에 공개돼 있었으나 열어보지 않았습니다") — 조회 로그 테이블과 프론트 이벤트 연동이 필요해 별도 spec으로 다룬다.
- 반사실의 "다음 거래일까지 보유했다면" 시나리오 — 재생일이 바뀌면 가격이 불연속이라 성립하지 않는다.
- 카드·요약의 커뮤니티 공유·게시물 첨부.
- 미실현 보유 종목에 대한 피드백 (매도 완료 건만 대상).
- 브리핑·요약의 푸시 알림 (알림은 별도 2차 항목).

## 완료 조건

> 규칙 자체는 여기 다시 적지 않는다. **"§X의 규칙이 지켜지는가"**를 검증한다. 규칙과 조건이 어긋나면 규칙(§확정값)이 정본이다.
>
> 테스트 레벨은 ADR-0003을 따른다 — 서비스 로직은 단위, Repository·제약은 `@DataJpaTest`, API 계약은 `@WebMvcTest`, 핵심 시나리오는 Testcontainers 통합.

### 탐지 (단위)

- [ ] 고정 분봉 픽스처로 `PriceMoveDetector`가 장중 상위 `max-intraday-cards`건 + 시가 갭을 정확히 산출한다.
- [ ] σ=0(전 구간 동일가), 분봉 부족, 직전 거래일 마지막 분봉 없음에서 예외 없이 빈 결과를 낸다.
- [ ] **분봉이 결측된 구간에서 점수가 부풀지 않는다** — 중간 분봉을 비운 픽스처로 행 기반 인덱싱과 결과가 달라지는지 확인한다.
- [ ] **첫 분봉이 09:00이 아니어도(09:01) 시가 갭 카드가 생성된다** — 리터럴에 의존하면 그날 0건이 된다(§C-2-1).
- [ ] 시가 갭 카드의 `detection_score`가 채워진다 (`NOT NULL`).
- [ ] 근거 후보가 없으면 카드를 생성하지 않는다.
- [ ] **(`@DataJpaTest`) 장중 첫 후보(`window_start=09:00`)와 시가 갭 카드가 같은 날 함께 저장된다** — 유니크에 `event_type`이 없으면 나중에 삽입되는 쪽이 조용히 사라진다(§데이터 모델).

### 코인 탐지 (단위)

- [ ] **가격 스냅샷에서 5분 전 가격을 실제로 꺼낸다** — 수익률만 저장하면 꺼낼 수 없다.
- [ ] σ 표본이 **겹치지 않는 구간**으로 만들어진다 — 슬라이딩으로 만들면 표본 수와 σ가 통째로 달라진다.
- [ ] `sigma-lookback-hours` 밖 스냅샷이 표본에서 제외되고, 기록 시 그 원소가 제거된다. Redis가 앱과 별도 컨테이너라 재기동해도 옛 표본이 남는다.
- [ ] 표본이 `min-sample-count` 미만이면 카드를 만들지 않고 예외도 나지 않는다.
- [ ] `isPriceAvailable=false`일 때 스냅샷을 기록하지 않는다 — 동결 틱이 쌓이면 σ가 0에 수렴했다가 복구 첫 틱에서 허위 카드가 무더기로 생성된다.
- [ ] 쿨다운·일일 상한이 초과 생성을 막는다.
- [ ] **자정을 넘긴 카드(`occurred_at` 00:03)의 `windowStart <= windowEnd`가 유지되고 `origin_trade_date` 컬럼이 그날 KST 날짜다** (§C-9). 응답의 `originTradeDate`는 코인이면 `null`이다.

### 노출 게이트 (통합, 고정 `Clock`)

- [ ] `reveal_time`이 지나지 않은 카드가 조회에서 제외된다.
- [ ] **직전 거래일 저녁 기사가 09:00에 노출된다** — D-1 18:40 기사를 시가 갭 카드 근거로 붙이고 09:01에 조회해 **카드와 Part C `items` 양쪽에** 나타나는지 확인한다. 클램프가 없으면 둘 다 18:40까지 감춰진다(§노출 판정).
- [ ] **시가 갭 카드의 `revealTime`이 09:00이다** — 첫 분봉이 09:01인 픽스처에서도 09:01·09:02가 아니어야 한다. 근거가 `전장`이라 `clamp` 결과가 항상 09:00이고 `windowEnd`는 식에 들어가지 않는다.
- [ ] **장중 카드에는 `+1분`이 붙는다** — 캔들 API 컷오프(`현재분 − 1분`)보다 먼저 종가 기반 변동률이 노출되면 안 된다.
- [ ] 근거 기사가 `windowEnd`보다 늦게 발행되면 `revealTime`이 그 시각까지 밀린다.
- [ ] **같은 원본 거래일이 두 번 재생돼도 게이트가 작동한다** — 서비스 날짜를 하루 넘겨 같은 `source_trading_date`를 재생하고 09:30에 오후 카드가 안 보이는지 확인한다. `reveal_time`을 절대 시각으로 저장하면 실패한다.
- [ ] **Part C와 Part D의 09:00 하한이 같다** — 08:41에 두 API를 모두 호출해 어느 쪽에서도 전장 기사가 나오지 않는다.
- [ ] **Part C·D 목록에 `max-items-*` 상한과 발행시각 내림차순 정렬이 적용된다.**
- [ ] Part C `items`가 §C-2의 범위대로 반환된다 — 09:00~15:30에 직전 거래일 저녁 기사와 그때까지의 장중 기사가 들어 있고, **다른 원본 거래일 기사는 섞이지 않는다.**
- [ ] **Part C 요약이 장중에 장중 기사를 언급하지 않는다** — 오후 기사를 픽스처에 넣고 11:00에 조회해 `summaryScope="PRE_MARKET"`이며 요약에 그 내용이 없음을 확인한다. 15:30 이후 다시 조회하면 `FULL`로 바뀐다.
- [ ] **Part D 브리핑에 장중 기사가 한 건도 없다.**
- [ ] **`D` 접수 공시가 09:01 조회에 나오지 않고 `FULL`에서만 나온다.** `D-1` 접수 공시는 09:00에 브리핑·`PRE_MARKET`·`items`에 나온다 (§C-3).
- [ ] **매도 후 흐름·반사실이 게이트 전에는 `NOT_YET`이고 가격 필드가 비어 있다** — 14:41과 15:31을 각각 재현한다.
- [ ] **전날 매도 건을 다음 날 09:30에 조회해도 `READY`를 유지한다** — 게이트를 "오늘 15:30"으로 잡으면 `NOT_YET`으로 되돌아간다(§C-5).
- [ ] `priceMoves`에도 카드 게이트가 걸린다 — 매도 직후 조회에서 Part A·C보다 먼저 근거 기사를 보면 안 된다.

### 상태값 (통합)

- [ ] Part D `status` 6단계 판정이 §C-4의 표대로 동작한다. 특히 **07:00(세션 미준비) 조회가 `EMPTY`·`originTradeDate=null`**이고 `NOT_YET`이 아니다.
- [ ] Part C가 세션 미준비 시 `NOT_YET`이다 (Part D와 다른 값이며 의도된 차이다).
- [ ] **행이 없는데 기사는 있는 상태(배포 당일)에서 `EMPTY`이고 `items`가 채워진다.**
- [ ] 행이 있고 `summary`가 `NULL`이면 `UNAVAILABLE`이고 `items`가 채워진다.
- [ ] 매도 회고 `narrativeStatus`가 LLM 실패 시에도 `READY`이고 `narrativeSource="TEMPLATE"`이다 — 이 엔드포인트에 `UNAVAILABLE`은 없다.
- [ ] 보유 구간에 카드가 0건이면 `peerComparison.status="NO_EVENT"`이고 전 필드가 `null`이다.
- [ ] 모집단 5명 미만이면 `INSUFFICIENT_SAMPLE`이고 모집단 지표 3종이 `null`, `yourMinutesToSell`은 채워진다.

### 배치 (통합)

- [ ] **개장 전 배치가 하루치 분봉을 실제로 받아온다** — 08:45 고정 `Clock`으로 돌려 카드가 1건 이상 생성된다. `getRevealedCandles`를 쓰면 빈 리스트가 와서 **0건이 되고 예외는 안 난다.**
- [ ] 배치가 재생세션 확정 트랜잭션과 **분리된 크론**에서 돌고, 세션이 `READY`가 아니면 아무것도 하지 않는다.
- [ ] **생성 순서가 §C-6과 같다** — 브리핑이 가장 먼저다.
- [ ] LLM 호출 하나가 실패해도 나머지가 계속된다.
- [ ] 같은 서비스 날짜에 배치를 두 번 실행해도 카드·요약·브리핑이 중복 생성되지 않는다.
- [ ] 장 마감 배치가 `price_move_peer_stats`에 확정 집계를 저장하고, 두 번 실행돼도 중복되지 않는다.
- [ ] **같은 원본 거래일 재재생 시 집계가 서비스 날짜별로 따로 쌓인다** — 덮어쓰면 첫날 매도자의 통계가 사라진다(§C-9).
- [ ] **집단 비교 배치 전(15:31)에 조회하면 `peerComparison.status="NOT_YET"`이고 서술이 재생성되지 않는다** — 재생성되면 집단 비교가 빠진 문장으로 굳는다.
- [ ] **코인 요약·브리핑이 배치에서만 생성된다** — 조회를 반복해도 LLM 호출이 늘지 않는다.
- [ ] 코인 배치가 **직전 생성 이후 수집된 기사가 없으면** LLM을 호출하지 않는다. 판정 기준이 `created_at`이라 30분 주기 수집분이 누락되지 않는다.
- [ ] **코인 조회가 `generated_at` 최신 1행을 반환한다** — 00:00~00:05와 배치 실패 시각에도 비지 않는다.
- [ ] 코인 요약·브리핑이 UPSERT로 하루 1행을 유지한다 — `origin_trade_date`는 배치 실행 시점의 KST 날짜다(§C-9).
- [ ] **코인 Part C가 `ROLLING_24H` 한 범위만 쓰고 `PRE_MARKET`/`FULL`을 쓰지 않는다.**
- [ ] **`cron` 기반 `@Scheduled`에 전부 `zone`이 붙어 있다** — 리플렉션으로 `feedback`·`market` 두 패키지를 훑되 **`fixedRate`는 대상에서 제외**하고, `cron`이 있는데 `zone`이 비어 있으면 실패시킨다. UTC 컨테이너에서 08:45가 17:45가 되는 사고를 코드로 막는다.
- [ ] `spring.task.scheduling.pool.size`가 등록된 `@Scheduled` 개수 이상이다 (§C-1).

### 수집 (통합 · `@DataJpaTest`)

- [ ] 수집이 **재생 시점이 아니라 기사 당일**에 이루어진다 — D의 기사가 D+1 배치 시점에 이미 DB에 있다.
- [ ] **`전장` 구간이 빠짐없이 수집된다** — 저녁 18:00, 심야 02:00, 아침 07:00 발행 기사가 전부 저장·조회된다. 장중 크론만으로는 이 구간이 통째로 빈다.
- [ ] 수집 단계에서 발행일자로 거르지 않는다.
- [ ] **(`@DataJpaTest`) 같은 기사 URL이 두 종목에 각각 저장된다** — `UNIQUE(instrument_id, url)`. 단위 mock으로는 제약을 검증할 수 없다.
- [ ] **(`@DataJpaTest`) 앞 191자가 같고 쿼리 파라미터만 다른 URL 2건이 모두 저장된다** — 접두 유니크를 쓰면 실패한다(§C-8).
- [ ] 코인 질의어에 보정이 붙고, 제목에 같은 시장의 다른 종목명이 있는 기사가 제외된다 (FEED-001).

### 매도 회고 (단위 · 통합)

- [ ] **코인 매도 체결로 조회하면 400**이다 — 빈 값을 채운 200을 돌려주지 않는다.
- [ ] **여러 lot에 배분된 매도의 매수 시각이 가장 이른 `executed_at`이다** — 2개 lot 픽스처로 `holdingMinutes`·`minutesAfterBuy`가 그 기준인지 확인한다.
- [ ] 배분된 lot이 **서로 다른 원본 거래일에 걸치면 `sameSessionCompleted=false`**다.
- [ ] `sameSessionCompleted=false`일 때 파생 사실·반사실·집단 비교가 전부 `null`이다.
- [ ] 파생 사실이 정확히 계산된다 — 보유 구간 극값, `buyToNewsMinutes` 부호(매수가 기사보다 앞이면 양수), 카드별 `minutesAfterBuy`·`minutesBeforeSell`.
- [ ] **보유 구간 극값이 분봉 `close` 기준이다** — `high`/`low`를 쓰면 체결 불가능한 가격이 반사실에 들어간다.
- [ ] **`atClose`·`closePrice`가 그 거래일 "마지막 분봉"의 close다** — 리터럴로 찾으면 없는 날 `null`이 된다(§C-2-1).
- [ ] 반사실 3개 시나리오의 수익률이 **수수료를 다시 계산해** 산출된다. 수수료는 `FLOOR(매도금액 × 시장별 요율)`이며, 반올림하면 `api-contracts.md`의 예시 값과 어긋난다.
- [ ] 반사실 값이 **AI 서술 문자열에 포함되지 않는다** — 구조화 필드로만 나간다.
- [ ] **`narrative_finalized=false` 서술이 §C-5의 재생성 게이트 통과 후 첫 조회에서 재생성되고, 두 번째 조회에서는 재생성되지 않는다.**
- [ ] 재생성 실패가 **체결 1건당 누적** `max-narrative-retry`회를 넘지 않는다.
- [ ] **카드 0건인 매도도 재생성이 일어난다** — 게이트가 "행 존재"뿐이면 이 흔한 경우에 영원히 재생성되지 않는다(§C-5).
- [ ] **(`@DataJpaTest`) 카드 시점에 보유 중이었으나 그 뒤 전량 매도한 회원이 모집단에 포함된다** — `holdings`를 그대로 조회하면 정확히 이 회원들이 빠져 `soldWithin30MinRate`가 과소 집계된다.
- [ ] 집단 비교 응답에 회원 식별자가 어떤 형태로도 포함되지 않는다.
- [ ] LLM 호출이 실패해도 수치 요약과 파생 사실이 200으로 반환된다.
- [ ] 투자일기 없이 매수·매도한 건도 정상 200이다.

### 문구 (단위)

- [ ] 금지 표현 5줄이 **§후검증의 파트별 적용 표대로** 동작한다 — 카드·매도 회고는 5줄 전부, 요약·브리핑은 `판단·훈수`를 뺀 4줄.
- [ ] `판단·훈수` 표현(`버티`·`놓치`·`았다면`·`했다면`·`렸다면`)이 매도 회고에서 템플릿으로 대체된다.
- [ ] 요약에 "기회를"이 들어가도 통과한다 (`판단·훈수` 미적용).
- [ ] 요약에 "하세요"가 들어가면 걸린다 (`조언·후회`는 유지).
- [ ] "매도했습니다" 같은 서술형은 통과한다.
- [ ] **기사 제목에 "전망"이 들어 있어도 요약이 살아남는다** — 후검증에 걸린 뒤 재생성 1회로 통과하는 경로를 확인한다.
- [ ] 재생성 프롬프트에 **적발된 표현이 실제로 포함된다** — 무엇에 걸렸는지 안 알려주면 2차도 같은 단어를 쓴다.
- [ ] 재생성이 `max-regeneration`회를 넘지 않는다.
- [ ] 재생성 후에도 걸리면 `summary=null`·`narrative_source=NONE`·상태값 `UNAVAILABLE`이고 **`items`는 채워진다.**

### API 계약 (`@WebMvcTest`)

- [ ] 네 엔드포인트 모두 인증 없이 호출하면 401이다.
- [ ] `instrumentId` 미존재는 404, 타인 체결은 403, 매수·코인 체결은 400이다.
- [ ] `market` 파라미터가 없거나 `STOCK`·`CRYPTO` 외 값이면 400이다.
- [ ] 카드가 0건이면 빈 배열과 200이고, 재생세션 미준비면 `originTradeDate=null`·빈 배열과 200이다 (FEED-006).
- [ ] 응답 직렬화가 `docs/api-contracts.md`의 필드 집합과 일치한다 — `priceMoveId`·`narrativeSource`·`buyAt`·`sellAt` 포함.

### 원장 불변·기동

- [ ] 카드 생성·조회 전후로 주문·체결·계좌·잔액·보유·손익 원장이 변하지 않는다.
- [ ] 외부 API 키(네이버 검색·DART·OpenAI) 없이 `./gradlew build` 통과.
- [ ] `.env.example`·`compose.deploy.yaml`에 신설 환경변수 4종이 들어 있다.
- [ ] V13 마이그레이션이 `ddl-auto=validate`를 통과한다 (§C-8의 타입표와 엔티티 일치).
