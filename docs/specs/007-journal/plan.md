# Plan: 체결별 투자일기 작성·수정 (JOUR-001 · JOUR-003 · JOUR-004 · JOUR-002)

> 요구사항·규칙의 정본은 `./spec.md`이고 PRD가 그 상위다. 여기서 새 규칙을 만들지 않는다.
>
> | 절 | 범위 | 이슈 | 상태 |
> |---|---|---|---|
> | §JOUR-001 ~ §JOUR-003 앞까지 | 매수 회고 작성 엔드포인트 1개 | [#159](https://github.com/finplay-team/finplay/issues/159) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | §JOUR-003 매도 회고 작성 설계 | 매도 회고 작성 엔드포인트 1개 | [#183](https://github.com/finplay-team/finplay/issues/183) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | §JOUR-004 매도 회고 수정 설계 | 매도 회고 수정 엔드포인트 1개 | [#190](https://github.com/finplay-team/finplay/issues/190) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | **§JOUR-002 매수 회고 수정 설계** | 매수 회고 수정 엔드포인트 1개 | [#197](https://github.com/finplay-team/finplay/issues/197) | **이번 착수** |
>
> 아래 §관련 문서부터 §JOUR-003 절 직전까지는 **JOUR-001 설계**, 이어지는 §JOUR-003·§JOUR-004 절은 각각 그 요구사항의 설계다. 셋 다 구현 완료된 기록이며 이번 착수에서 손대지 않는다. JOUR-002 설계는 §JOUR-004 절 뒤, §이 spec에서 하지 않는 것 앞에 있는 새 절이다.

## JOUR-001 매수 회고 작성 설계 (이슈 #159, 구현 완료)

## 관련 문서

| 문서 | 담는 것 |
|---|---|
| `./spec.md` | 요구사항·비즈니스 규칙·완료 조건 (정본) |
| `./tasks.md` | 커밋 단위 작업 분해 |
| `docs/prd.md` JOUR-001 | 상위 요구사항. JOUR-005의 Decision Gate 서술도 여기 (JOUR-002의 게이트는 2026-08-04 이슈 #197에서 해제) |
| `docs/api-routes.md` · `docs/api-contracts.md` | JOUR-001 반영 완료 (2026-08-04). **JOUR-003는 미반영** — 컨트롤러 커밋과 같은 커밋에서 반영한다 |
| [ADR-0002](../../adr/0002-architecture.md) | `controller → service → repository`, 도메인 패키지. **도메인 간 참조는 service 경유** — journal이 `TradeRepository`를 직접 주입하지 않는 근거 |
| [ADR-0003](../../adr/0003-testing-strategy.md) | 서비스 로직=단위, 쿼리·제약=`@DataJpaTest`, API 계약=`@WebMvcTest`, 핵심 시나리오=Testcontainers 통합 |
| [ADR-0004](../../adr/0004-flyway-migrations.md) | 스키마는 마이그레이션으로만. `ddl-auto=validate`, 머지된 파일 수정 금지 |
| `docs/conventions.md` | DTO record·엔티티 정적 팩토리·레이어 규칙·오류 응답 포맷 |
| 선행 spec `../004-order-buy/`, `../011-order-ledger-schema/` | 참조 대상인 `trades` 원장의 출처 |

## API 설계

| Method | URL | 요청 | 성공 응답 | 설명 |
|---|---|---|---|---|
| POST | `/api/trades/{buyTradeId}/journal` | `BuyJournalCreateRequest` (JSON 본문) | **201** `BuyJournalResponse` | 본인 소유 매수 체결 1건에 투자일기 1건 작성 |

- 인증: Access Bearer 필수. 작성자는 요청 본문이 아니라 Access Token의 인증 사용자(`AuthenticatedUser#userId`)로 결정한다.
- 201인 이유는 `docs/conventions.md` API 규칙("생성 201")이다. `Location` 헤더는 이번 계약에 넣지 않는다 — 단건 조회(JOUR-005)가 아직 없어 가리킬 URL이 없다.
- URL에 버전 프리픽스를 쓰지 않는다(컨벤션). 경로 변수명은 PRD 표기 그대로 `buyTradeId`다.

### 요청 예시

```json
{ "content": "실적 발표 전 분할 매수. 5% 빠지면 손절 계획." }
```

### 응답 예시 (201)

```json
{ "journalId": 1, "buyTradeId": 12, "content": "실적 발표 전 분할 매수. 5% 빠지면 손절 계획.", "createdAt": "2026-08-04T10:12:33" }
```

응답 필드는 **4개로 고정**이다 — `journalId`·`buyTradeId`·`content`·`createdAt`. PRD JOUR-001의 "투자일기 ID, 매수 체결 ID, 본문, 작성시각"과 1:1이다. 종목·가격·수량 등 체결 정보는 넣지 않는다(`GET /api/trades`가 이미 제공한다). 목표가·손절가·예상보유기간 필드는 범위 제외다.

## 입력 명세

| 필드 | 위치 | 필수 | 검증 |
|---|---|---|---|
| `buyTradeId` | path | 필수 | 숫자(`Long`). 숫자로 파싱 불가하면 400 `VALIDATION_ERROR`(전역 핸들러의 `MethodArgumentTypeMismatchException` 경로). 존재하지 않으면 404 `NOT_FOUND`, 인증 사용자 소유가 아니면 403 `FORBIDDEN`, `side != BUY`면 400 `VALIDATION_ERROR` |
| `content` | body | 필수 | `@NotBlank` — `null`·빈 문자열·공백만 있는 문자열은 400 `VALIDATION_ERROR`. `@Size(max = 5000)` 초과 시 400. 메시지는 한글 + 마침표(컨벤션) |

- **본문 검증이 경로 검증보다 먼저 일어난다.** `@Valid`는 컨트롤러 메서드 진입 전에 평가되므로, 없는 체결 + 공백 본문 요청은 404가 아니라 **400**이다. 계약 문서에도 이 순서를 적는다.
- 앞뒤 공백은 트림하지 않고 원문 그대로 저장한다. 검증만 `@NotBlank`로 한다(트림 저장을 도입하면 수정(JOUR-002) 시 저장값과 입력값이 달라 비교가 흐려진다).
- `content` 상한 5000자는 **PRD가 정하지 않은 값**이다. 컨벤션이 문자열에 `@Size(max = N)`을 항상 요구하므로, 같은 성격의 장문 텍스트인 `community_posts.content`(`VARCHAR(5000)`, V3) 선례를 따랐다. 팀이 다른 값을 원하면 **DTO와 컬럼 정의를 함께** 바꾼다.

## 오류 매핑

| 상황 | 상태 | 코드 |
|---|---|---|
| `content` 누락·공백·5000자 초과, `buyTradeId` 타입 불일치 | 400 | `VALIDATION_ERROR` |
| 대상 체결의 `side`가 `BUY`가 아님(=매도 체결) | 400 | `VALIDATION_ERROR` |
| Access 인증 실패·미첨부 | 401 | `UNAUTHORIZED` |
| 타인 소유 체결 | 403 | `FORBIDDEN` |
| `buyTradeId`에 해당하는 체결 없음 | 404 | `NOT_FOUND` |
| 해당 매수 체결에 투자일기가 이미 존재(선제 조회 또는 유니크 위반) | 409 | `DUPLICATE_RESOURCE` |

**검증 순서는 `존재(404) → 소유(403) → 매수 여부(400) → 중복(409)`으로 고정한다.** 타인의 매도 체결이면 403이 먼저다 — 소유하지 않은 체결의 속성(매수/매도)을 오류 코드로 흘리지 않기 위해서다. 오류 본문은 전역 핸들러의 공통 포맷(`{"error":{"code","message","requestId"}}`)을 그대로 쓴다. 새 `ErrorCode` 상수를 추가하지 않는다 — 위 6개는 모두 기존 enum에 있다.

## 데이터 모델

### 신규 테이블 `buy_trade_journals`

```sql
CREATE TABLE buy_trade_journals (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    buy_trade_id  BIGINT        NOT NULL,
    content       VARCHAR(5000) NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_buy_trade_journals_buy_trade UNIQUE (buy_trade_id),
    CONSTRAINT fk_buy_trade_journals_buy_trade
        FOREIGN KEY (buy_trade_id) REFERENCES trades (id)
);
```

**마이그레이션 파일: `V15__create_buy_trade_journals.sql`.** 조사 시점(2026-08-04) 착수 당시 `dev`의 최신 버전은 `V13__create_ai_feedback_tables.sql`이라 처음엔 V14로 만들었으나, PR #181 병합 전 `dev`에 `V14__create_favorites.sql`(#163·#168)이 먼저 병합돼 번호가 충돌해 V15로 재번호화했다(ADR-0004 — 이미 만든 V14 파일은 수정하지 않고 새 번호로 대체, `docs/agent-mistakes.md` 2026-08-03 행과 같은 패턴).

FK 대상은 **실측**한 값이다.

| 항목 | 실제 값 | 확인처 |
|---|---|---|
| 체결 테이블 | `trades` | `V10__create_order_ledger_tables.sql` |
| 체결 PK | `id BIGINT NOT NULL AUTO_INCREMENT` | 같은 파일 |
| 체결 엔티티 | `com.finplay.api.order.domain.Trade`, `@Table(name = "trades")`, `Long id` (`GenerationType.IDENTITY`) | `order/domain/Trade.java` |
| 매수/매도 구분 | `Trade.side` = `com.finplay.api.order.domain.OrderSide` (`BUY`/`SELL`), 컬럼 `side VARCHAR(10)`, `@Enumerated(STRING)` | 같은 파일 |
| 소유 경로 | `Trade.account`(`accounts.id`) → `Account.user`(`users.id`) — `Trade`에 `user_id`가 직접 없다 | `order/domain/Trade.java`, `account/domain/Account.java` |
| 같은 FK의 선례 | `holding_lots.buy_trade_id BIGINT NOT NULL` + `uk_holding_lots_buy_trade UNIQUE` + `fk_holding_lots_buy_trade → trades(id)` | `V10` |

설계 결정과 근거.

- **`UNIQUE(buy_trade_id)`가 이 기능의 핵심 제약이다.** PRD JOUR-001이 이 이름 그대로 명시했고, 동시 중복 작성의 최종 방어선이다. 애플리케이션 선제 조회만으로는 동시 요청을 막을 수 없다.
- **`user_id`를 비정규화해 넣지 않는다.** 소유권은 `trades → accounts → users`로 판정 가능하고, 중복 저장하면 두 경로가 어긋날 수 있다. 목록 조회(JOUR-006)에서 성능 문제가 실측되면 그때 인덱스·컬럼을 새 마이그레이션으로 더한다.
- **`updated_at`을 지금 만들지 않는다.** 수정(JOUR-002)은 Decision Gate 미해결로 범위 밖이다. 착수 시 새 번호 마이그레이션으로 추가한다.
- **추가 인덱스를 만들지 않는다.** 이번 범위의 조회는 `buy_trade_id` 단건뿐이고 유니크 인덱스가 그대로 쓰인다. 목록 정렬용 인덱스는 JOUR-006이 자기 쿼리와 함께 만든다.
- **테이블명이 `buy_trade_journals`인 이유** — 매수·매도 회고를 한 테이블로 합칠지는 JOUR-005의 식별자 체계 Decision Gate에 걸려 있다. PRD가 명시한 컬럼명이 `buy_trade_id`이므로, 지금은 매수 전용 테이블로 두고 통합 여부는 게이트가 풀린 뒤 결정한다. (`holding_lots`가 같은 방식으로 `buy_trade_id`를 쓴다.)

### 엔티티 `BuyTradeJournal` (`com.finplay.api.journal.domain`)

- `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "buy_trade_id", nullable = false) private Trade buyTrade;` — V10 원장 엔티티 관례(`Trade` 선례)를 따른다.
- `@Getter` + `@NoArgsConstructor(access = PROTECTED)`, setter 없음, 생성은 정적 팩토리 `of(Trade buyTrade, String content, LocalDateTime now)`로만 (컨벤션 Entity 규칙).
- `createdAt`은 `LocalDateTime`이며 서비스가 주입받은 `Clock`(`common/ClockConfig`, `Asia/Seoul`)으로 만들어 넘긴다 — 테스트에서 시각을 고정할 수 있어야 한다.
- 엔티티에서 `Trade`를 **읽기만** 한다. 이 엔티티가 원장을 쓰는 경로를 만들지 않는다.

## 구성요소 설계

신규 도메인 패키지 `com.finplay.api.journal`을 만든다 (ADR-0002 — 도메인 기준 최상위, 그 안에서 계층 하위 패키지).

```
com.finplay.api.journal
├── controller/JournalController.java
├── service/JournalService.java
├── repository/BuyTradeJournalRepository.java
├── domain/BuyTradeJournal.java
└── dto/
    ├── request/BuyJournalCreateRequest.java
    └── response/BuyJournalResponse.java
```

DTO 이름에 `Buy`를 남기는 이유는 매도 회고(JOUR-003)가 별도 계약으로 오기 때문이다. 지금 `JournalCreateRequest`로 두면 나중에 두 계약이 한 이름을 다투게 된다.

### `JournalController`

- `@RestController @RequestMapping("/api/trades")`, `@PostMapping("/{buyTradeId}/journal")`.
- `@AuthenticationPrincipal AuthenticatedUser principal`, `@PathVariable Long buyTradeId`, `@Valid @RequestBody BuyJournalCreateRequest request`를 받아 서비스에 위임하고 `ResponseEntity.status(CREATED).body(...)`를 반환한다.
- 비즈니스 판단·repository 호출·try-catch를 두지 않는다(컨벤션 레이어 규칙).

### `JournalService`

```
@Transactional
BuyJournalResponse createBuyJournal(Long userId, Long buyTradeId, String content)
```

1. `Trade trade = tradeService.getOwnedTrade(userId, buyTradeId);` — 존재하지 않으면 404 `NOT_FOUND`, 인증 사용자 소유가 아니면 403 `FORBIDDEN`.
2. `trade.getSide() != OrderSide.BUY`면 400 `VALIDATION_ERROR`.
3. `buyTradeJournalRepository.existsByBuyTradeId(buyTradeId)`가 참이면 409 `DUPLICATE_RESOURCE` (선제 조회 — 일반적인 재작성 시도를 예외 없이 걸러낸다).
4. `saveAndFlush`로 저장한다. 동시 요청 경합으로 `DataIntegrityViolationException`이 나면 **409 `DUPLICATE_RESOURCE`로 변환**해 던진다.
5. `BuyJournalResponse.from(entity)`를 반환한다.

- **트랜잭션 경계는 이 메서드 하나다**(spec의 "저장과 소유권·중복 검증은 한 트랜잭션"). 4단계에서 제약 위반을 잡은 뒤에는 트랜잭션이 rollback-only 상태이므로 **추가 DB 작업을 하지 않고 즉시 예외를 던진다** — 잡아서 기존 일기를 다시 읽어 반환하는 식으로 성공 처리하지 않는다(PRD는 중복을 409로 거부하라고 정했다).
- 실패 경로 전부가 롤백된다. 애초에 이 서비스는 `buy_trade_journals`에만 쓰고 원장은 읽기만 하므로 원장은 어떤 경로에서도 변하지 않는다.
- 3·4단계를 **둘 다** 둔다. 선제 조회만 두면 동시성에서 새고, 제약만 두면 흔한 실패가 예외 경로로 처리된다.

### `TradeService`에 추가할 조회 (order 도메인)

```
@Transactional(readOnly = true)
Trade getOwnedTrade(Long userId, Long tradeId)
```

- `TradeRepository.findById` → 없으면 `BusinessException(NOT_FOUND)`, `trade.getAccount().getUser().getId()`가 `userId`와 다르면 `BusinessException(FORBIDDEN)`.
- **journal이 `TradeRepository`를 직접 주입하지 않기 위한 메서드다** (ADR-0002 — 도메인 간 참조는 service 경유). 소유권 판정은 order 원장의 규칙이므로 order가 갖는다.
- **`side` 검증은 여기 넣지 않는다.** "매수 체결에만 쓴다"는 투자일기의 규칙이고, JOUR-003(매도 회고)이 같은 메서드를 반대 조건으로 재사용한다.
- 소유 경로가 `Trade → Account → User` 2단 lazy이므로, 필요하면 `@EntityGraph` 또는 join fetch 쿼리로 `account`(와 `user`)를 함께 로드해 지연 로딩 문제를 피한다. 판정 결과(404/403)는 로딩 방식과 무관하게 동일해야 한다.
- 파생 쿼리로 충분하다 — QueryDSL을 쓰지 않는다(컨벤션 QueryDSL 사용 기준).

### `BuyTradeJournalRepository`

`JpaRepository<BuyTradeJournal, Long>` 상속 + `boolean existsByBuyTradeId(Long buyTradeId)` 하나. 목록·상세용 조회는 JOUR-005·006이 자기 완료 조건과 함께 추가한다 — 지금 추측으로 만들지 않는다.

## 테스트 계획 (ADR-0003)

- **단위** (`JournalServiceTest`, Mockito)
  - 정상 작성 시 저장 인자(체결·본문·`Clock` 고정 시각)와 반환 DTO 필드 4개.
  - 없는 체결 → 404, 타인 체결 → 403, 매도 체결 → 400, 이미 존재 → 409.
  - **검증 순서**: 타인 소유의 매도 체결이 403이고 400이 아니다.
  - 저장소 예외(`DataIntegrityViolationException`) → 409 `DUPLICATE_RESOURCE` 변환.
  - `TradeService`(단위) — 없는 체결 404, 타인 체결 403, 본인 체결은 그대로 반환.
- **슬라이스**
  - `@DataJpaTest` — ① 같은 `buy_trade_id`로 2건 저장 시 유니크 위반 ② 서로 다른 체결 2건은 공존 ③ `existsByBuyTradeId`가 저장 전후로 `false`→`true` ④ 존재하지 않는 `trades.id`를 참조하면 FK 위반. 실제 MySQL 제약이 대상이라 mock으로 대체 불가.
  - `@WebMvcTest`(`JournalControllerTest`) — 201 응답 본문 `jsonPath` 4필드, 공백·누락·5000자 초과 본문 400, 미인증 401, 서비스 예외의 상태·코드 매핑(403·404·409), `buyTradeId`가 숫자가 아니면 400.
- **통합** (`@SpringBootTest` + Testcontainers, `JournalIntegrationTest`)
  - 매수 체결을 만든 뒤 작성 → 201, DB 1행.
  - **순차 중복** 2회 요청 → 두 번째 409, 행 수 1 유지.
  - **동시 중복** — 같은 체결에 동시 요청 2건 → 정확히 1건 성공, 나머지 409, 행 수 1.
  - 없는 체결 404 · 타인 체결 403 · 매도 체결 400 · 공백 본문 400.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`(현금·실현손익)·`holdings`·`holding_lots`·`trade_allocations`의 행 수와 값이 동일하다.
  - 신규 마이그레이션 적용 후 `ddl-auto=validate` 기동이 통과한다(기존 `@SpringBootTest`가 확인).

## JOUR-003 매도 회고 작성 설계 (이슈 #183, 이번 착수)

> 이 절은 **매도 체결 기준의 같은 유스케이스**다. 위 JOUR-001 설계와 동일한 항목(응답 필드 4개 고정, 오류 본문 공통 포맷, `Clock` 주입, 트랜잭션 경계, 원장 읽기 전용)은 반복하지 않고 그대로 따른다. **다른 점만** 아래에 적는다.

### API 설계

| Method | URL | 요청 | 성공 응답 | 설명 |
|---|---|---|---|---|
| POST | `/api/trades/{sellTradeId}/sell-journal` | `SellJournalCreateRequest` (JSON 본문) | **201** `SellJournalResponse` | 본인 소유 매도 체결 1건에 매도 회고 1건 작성 |

- URL은 **PRD JOUR-003이 정한 그대로** `sell-journal`이다. 매수 회고의 `/journal`과 경로가 다르므로 두 엔드포인트는 서로의 라우팅에 영향을 주지 않는다.
- 경로 변수명도 PRD 표기 그대로 `sellTradeId`다.
- 인증·201·`Location` 헤더 미포함 방침은 JOUR-001과 같다.

#### 요청 예시

```json
{ "content": "목표가 도달해서 전량 매도. 다음엔 분할 매도 시도." }
```

#### 응답 예시 (201)

```json
{ "journalId": 1, "sellTradeId": 34, "content": "목표가 도달해서 전량 매도. 다음엔 분할 매도 시도.", "createdAt": "2026-08-04T15:20:41" }
```

응답 필드는 **4개로 고정**이다 — `journalId`·`sellTradeId`·`content`·`createdAt`. 매수 회고와 대칭이며, 체결 ID 필드명만 `sellTradeId`다. **실현손익·배분된 매수 lot 등 매도 결과 정보는 넣지 않는다** — `GET /api/trades`(PORT-002)가 이미 제공하고, 여기 넣으면 원장 계산 결과가 두 계약에 중복된다.

### 입력 명세

| 필드 | 위치 | 필수 | 검증 |
|---|---|---|---|
| `sellTradeId` | path | 필수 | 숫자(`Long`). 파싱 불가 400 `VALIDATION_ERROR`, 없으면 404 `NOT_FOUND`, 타인 소유면 403 `FORBIDDEN`, `side != SELL`면 400 `VALIDATION_ERROR` |
| `content` | body | 필수 | `@NotBlank` + `@Size(max = 5000)`. 메시지·트림 정책은 JOUR-001과 동일 |

- `content` 상한 5000자는 **매수 회고와 같은 값을 쓴다.** 두 회고의 본문 성격이 같은데 상한이 다르면 사용자에게 설명할 근거가 없다. 팀이 값을 바꾸면 **두 DTO와 두 컬럼을 함께** 바꾼다.
- 본문 검증이 경로 검증보다 먼저 일어나는 순서(없는 체결 + 공백 본문 = 400)도 동일하다.

### 오류 매핑

| 상황 | 상태 | 코드 |
|---|---|---|
| `content` 누락·공백·5000자 초과, `sellTradeId` 타입 불일치 | 400 | `VALIDATION_ERROR` |
| 대상 체결의 `side`가 `SELL`이 아님(=매수 체결) | 400 | `VALIDATION_ERROR` |
| Access 인증 실패·미첨부 | 401 | `UNAUTHORIZED` |
| 타인 소유 체결 | 403 | `FORBIDDEN` |
| `sellTradeId`에 해당하는 체결 없음 | 404 | `NOT_FOUND` |
| 해당 매도 체결에 매도 회고가 이미 존재 | 409 | `DUPLICATE_RESOURCE` |

검증 순서는 `존재(404) → 소유(403) → 매도 여부(400) → 중복(409)`이다. 타인의 매수 체결은 403이 먼저다. **새 `ErrorCode` 상수를 추가하지 않는다** — 6개 모두 기존 enum에 있다.

### 데이터 모델

#### 신규 테이블 `sell_trade_journals`

```sql
CREATE TABLE sell_trade_journals (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    sell_trade_id BIGINT        NOT NULL,
    content       VARCHAR(5000) NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sell_trade_journals_sell_trade UNIQUE (sell_trade_id),
    CONSTRAINT fk_sell_trade_journals_sell_trade
        FOREIGN KEY (sell_trade_id) REFERENCES trades (id)
);
```

**마이그레이션 파일: `V17__create_sell_trade_journals.sql`.** 조사 시점(2026-08-04) `dev`의 최신 버전은 `V16__create_practice_progresses_and_intentions.sql`이다. **구현 착수 시 `dev`를 다시 확인해** 그때의 최신 다음 번호를 쓴다 — `buy_trade_journals`가 V14→V15로 재번호화된 전례(ADR-0004, `docs/agent-mistakes.md` 2026-08-03)가 있으므로 병합 직전에 한 번 더 대조한다.

- FK 대상 `trades(id)`와 컬럼 타입은 JOUR-001 §데이터 모델의 실측 표를 그대로 따른다. 체결 테이블·PK·`side` enum·소유 경로(`Trade → Account → User`)는 변하지 않았다.
- 제약·인덱스 방침도 동일하다 — `UNIQUE(sell_trade_id)`가 동시 중복의 최종 방어선이고, `user_id` 비정규화·`updated_at`·추가 인덱스는 만들지 않는다.
- **매수·매도 회고 테이블을 합치지 않는 이유.** 통합 테이블(`trade_journals` + `side` 구분 등)은 JOUR-005의 **식별자 체계 Decision Gate를 미리 결정해 버리는** 설계다. 게이트가 열리기 전에 굳히지 않기 위해, 지금은 PRD가 명시한 컬럼명(`buy_trade_id`·`sell_trade_id`)을 각각 쓰는 전용 테이블 2개로 둔다. `holding_lots.buy_trade_id`도 같은 방식이다.
- **알려진 트레이드오프**: 테이블이 둘이므로 `journalId`가 두 시퀀스에서 각각 발급돼 **전역 유일하지 않다.** JOUR-005(상세 조회)가 `GET /api/journal/{journalId}` 단일 URL로 확정되면 식별자 통합(별도 키·타입 접두어·테이블 병합 중 택1)이 필요하다. **이번 범위에서는 상세·목록 조회가 없어 노출되지 않는 문제**이므로 게이트 결정에 넘긴다 — 이 문단이 그 결정의 입력이다.

#### 엔티티 `SellTradeJournal` (`com.finplay.api.journal.domain`)

`BuyTradeJournal`과 같은 형태다 — `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "sell_trade_id", nullable = false) private Trade sellTrade;`, `@Getter` + `@NoArgsConstructor(access = PROTECTED)`, setter 없음, 정적 팩토리 `of(Trade sellTrade, String content, LocalDateTime now)`, `createdAt`은 서비스가 주입받은 `Clock`으로 만든 값, 원장은 읽기만.

**두 엔티티의 공통 상위 클래스(`@MappedSuperclass`)를 만들지 않는다.** 지금 공유되는 것은 필드 3개뿐이고, 상속을 넣으면 JOUR-005 게이트에서 테이블 구조를 다시 정할 때 상속 구조까지 함께 풀어야 한다. 중복이 실제로 아플 때(수정·조회가 붙을 때) 다시 판단한다.

### 구성요소 설계

기존 `com.finplay.api.journal` 패키지에 **추가**한다. 새 도메인 패키지를 만들지 않는다.

```
com.finplay.api.journal
├── controller/JournalController.java        (기존 — 메서드 1개 추가)
├── service/JournalService.java              (기존 — 메서드 1개 추가)
├── repository/SellTradeJournalRepository.java   (신규)
├── domain/SellTradeJournal.java                 (신규)
└── dto/
    ├── request/SellJournalCreateRequest.java    (신규)
    └── response/SellJournalResponse.java        (신규)
```

- **`JournalController`**: `@PostMapping("/{sellTradeId}/sell-journal")` 메서드를 추가한다. 기존 `@RequestMapping("/api/trades")`를 그대로 쓴다. 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는 규칙은 동일하다.
- **`JournalService`**: `@Transactional SellJournalResponse createSellJournal(Long userId, Long sellTradeId, String content)`를 추가한다. 절차는 매수 회고의 1~5단계와 같고 2단계 조건만 `trade.getSide() != OrderSide.SELL`이다. `tradeService.getOwnedTrade(userId, sellTradeId)`를 그대로 재사용한다 — **`TradeService`는 이번 착수에서 변경하지 않는다** (JOUR-001이 `side` 판정을 일부러 넣지 않은 이유가 이 재사용이다).
- **선제 조회 + 유니크 위반 변환을 둘 다 둔다.** `existsBySellTradeId` → 409, `saveAndFlush`의 `DataIntegrityViolationException` → 409 변환. 제약 위반을 잡은 뒤에는 추가 DB 작업 없이 즉시 던진다.
- **`SellTradeJournalRepository`**: `JpaRepository<SellTradeJournal, Long>` + `boolean existsBySellTradeId(Long sellTradeId)` 하나. 목록·상세 조회 메서드는 만들지 않는다.
- **DTO 2개**: `SellJournalCreateRequest(content)`, `SellJournalResponse(journalId, sellTradeId, content, createdAt)` + `from(SellTradeJournal)` 정적 팩토리. record로 만든다(컨벤션).
- **매수 경로를 리팩터링하지 않는다.** 두 메서드의 공통 흐름을 지금 추상화하면(제네릭 헬퍼 등) 이미 리뷰·머지된 JOUR-001 동작을 건드리게 된다. 이번 PR의 diff는 **추가 위주**로 두고, 기존 매수 회고 테스트가 전부 그대로 통과하는 것을 확인한다.

### 테스트 계획 (ADR-0003)

매수 회고 테스트와 **대칭**으로 만든다. 기존 파일에 추가할지 새 파일로 나눌지는 구현자가 판단하되, 매수 케이스를 수정하지 않는다.

- **단위** (`JournalServiceTest`, Mockito)
  - 정상 작성 시 저장 인자(체결·본문·고정 `Clock` 시각)와 반환 DTO 필드 4개.
  - 없는 체결 404, 타인 체결 403, **매수 체결 400**, 이미 존재 409.
  - **검증 순서**: 타인 소유의 매수 체결이 403이고 400이 아니다.
  - `DataIntegrityViolationException` → 409 `DUPLICATE_RESOURCE` 변환.
- **슬라이스**
  - `@DataJpaTest` — ① 같은 `sell_trade_id` 2건째 유니크 위반 ② 서로 다른 체결 2건 공존 ③ `existsBySellTradeId` false→true ④ 없는 `trades.id` 참조 시 FK 위반.
  - `@WebMvcTest`(`JournalControllerTest`) — 201 본문 `jsonPath` 4필드, 공백·누락·5000자 초과 400, 숫자 아닌 `sellTradeId` 400, 미인증 401, 서비스 예외의 403·404·409 매핑.
- **통합** (`@SpringBootTest` + Testcontainers)
  - 매도 체결을 만든 뒤 작성 → 201, DB 1행. (매수 → 매도 순서로 체결을 만들어야 매도 체결이 생긴다 — `005-order-sell` 경로를 그대로 탄다.)
  - **순차 중복**·**동시 중복** 모두 정확히 1건 성공·나머지 409, 최종 행 수 1.
  - 없는 체결 404 · 매수 체결 400 · 타인 체결 403 · 공백 본문 400.
  - **교차 검증** — 같은 매수 체결에 `/sell-journal`은 400, 같은 매도 체결에 `/journal`은 400. 한 종목을 매수·매도한 뒤 두 회고를 각각 작성하면 둘 다 201이고 서로 간섭하지 않는다.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`(현금·실현손익)·`holdings`·`holding_lots`·`trade_allocations`의 행 수와 값이 동일하다.
  - 신규 마이그레이션 적용 후 `ddl-auto=validate` 기동 통과.

### 문서 갱신 (CLAUDE.md 규칙 7)

- `docs/api-routes.md` — `journal` 도메인에 `POST /api/trades/{sellTradeId}/sell-journal` 행 추가 (근거 열: `007 JOUR-003, Issue #183`).
- `docs/api-contracts.md` `## journal` 절 — 요청·응답·오류 계약 행 추가. 매수 회고 행 아래에 두고, 두 회고의 차이(경로, 체결 구분 검증 대상, 응답 체결 ID 필드명)를 본문에 한 줄로 적는다.
- 컨트롤러 변경과 **같은 커밋**에서 갱신한다.

## JOUR-004 매도 회고 수정 설계 (이슈 #190, 구현 완료)

> 요구사항·비즈니스 규칙의 정본은 `./spec.md` JOUR-004 절이다. 특히 "매도 회고 수정 잠금 없음" 결정과 그 근거 3가지는 `spec.md`가 이미 확정했으므로 여기서 재논의하지 않는다. 이 절은 위 §JOUR-003 설계와 **같은 항목**(응답 필드 고정, 오류 본문 공통 포맷, `Clock` 주입, 트랜잭션 경계, 원장 읽기 전용, `TradeService` 재사용)을 반복하지 않고 **다른 점만** 적는다.

### API 설계

| Method | URL | 요청 | 성공 응답 | 설명 |
|---|---|---|---|---|
| PATCH | `/api/trades/{sellTradeId}/sell-journal` | `SellJournalUpdateRequest` (JSON 본문) | **200** `SellJournalUpdateResponse` | 본인 소유 매도 체결 1건에 이미 작성된 매도 회고의 본문을 교체 |

- URL은 작성(JOUR-003)과 **동일한 리소스 경로**를 쓴다 — PATCH가 "이미 있는 걸 부분 수정"이라는 HTTP 메서드 의미(컨벤션 API 규칙)와 spec.md가 정한 URL이 일치한다. 별도 하위 경로(`/sell-journal/edit` 등)를 만들지 않는다.
- 200인 이유는 컨벤션 API 규칙("수정 200") — 생성이 아니므로 201이 아니다.
- 경로 변수명은 작성과 동일하게 `sellTradeId`다. 회고 자체의 PK(`journalId`)를 경로에 노출하지 않는다 — 상세 조회(JOUR-005) 식별자 체계 Decision Gate를 앞당기지 않기 위해서다(JOUR-003 §데이터 모델의 "알려진 트레이드오프"와 같은 이유).

#### 요청 예시

```json
{ "content": "돌아보니 목표가 도달 전에 일부 익절했어야 했다." }
```

#### 응답 예시 (200)

```json
{ "journalId": 1, "sellTradeId": 34, "content": "돌아보니 목표가 도달 전에 일부 익절했어야 했다.", "createdAt": "2026-08-04T15:20:41", "updatedAt": "2026-08-05T09:03:12" }
```

응답 필드는 **5개로 고정**이다 — `journalId`·`sellTradeId`·`content`·`createdAt`·`updatedAt`. PRD·spec.md JOUR-004의 "투자일기 ID, 매도 체결 ID, 본문, 작성시각, 수정시각"과 1:1이다. `sellTradeId`·`journalId`·`createdAt`은 요청으로 지정할 수 없고 응답에서도 원본 값 그대로다(spec.md "수정은 본문 교체다").

### 응답 DTO를 분리하는 이유 (spec.md가 지정한 판단)

spec.md §비즈니스 규칙(JOUR-004)이 "이미 배포된 작성 계약(`POST .../sell-journal`)의 응답 필드를 바꾸지 않는다"고 이미 못박았으므로, 여기서 하는 일은 그 지시를 실제 타입으로 옮기는 것뿐이다.

- **`SellJournalResponse`(작성, 4필드)는 그대로 둔다.** PR #189로 이미 배포됐고 프론트엔드가 그 계약으로 통합됐다. 필드를 늘리면 그 자체가 계약 변경이라, spec.md가 "불필요한 변경이 번진다"고 명시한 상황이 실제로 생긴다.
- **수정 응답은 새 레코드 `SellJournalUpdateResponse`(5필드)로 만든다.** 컨벤션 DTO 접미사 표에 "수정 응답" 행은 없지만, "수정 요청 → `~UpdateRequest`" 관례(`NicknameUpdateRequest`, `CommunityPostUpdateRequest` 선례)를 응답에도 대칭으로 적용한다. 대안(기존 4필드 응답에 `updatedAt`을 옵셔널로 얹기)은 위 이유로 배제한다.
- 참고로 `community` 도메인의 `CommunityPostResponse`는 생성 시점부터 `updatedAt`을 포함해 조회·생성·수정에서 하나의 DTO를 공유한다 — 그건 **처음 설계할 때부터** 필드를 넣은 경우라 이번과 전제가 다르다(이미 4필드로 배포된 계약이 없었다). JOUR-004는 배포 이후에 필드가 필요해진 경우이므로 같은 패턴을 그대로 가져올 수 없다.

### 입력 명세

| 필드 | 위치 | 필수 | 검증 |
|---|---|---|---|
| `sellTradeId` | path | 필수 | 숫자(`Long`). 파싱 불가 400 `VALIDATION_ERROR`, 없으면 404 `NOT_FOUND`, 타인 소유면 403 `FORBIDDEN`, `side != SELL`면 400 `VALIDATION_ERROR`, 매도 회고가 아직 없으면 404 `NOT_FOUND` |
| `content` | body | 필수 | `@NotBlank` + `@Size(max = 5000)` — 작성(JOUR-003)과 **같은 상한**. 다른 상한을 쓸 근거가 없다(spec.md "수정 가능한 필드는 본문 하나뿐이고 작성과 같은 검증을 받는다") |

- **본문 검증이 경로 검증보다 먼저 일어나는 순서는 작성과 동일하다.** 없는 체결 + 공백 본문 요청은 404가 아니라 400이다.
- 앞뒤 공백은 트림하지 않고 원문 그대로 저장한다 — 작성과 저장 정책을 다르게 두면 "왜 작성 때는 트림 안 했는데 수정 때는 하냐"는 불일치가 생긴다.
- `content` 검증 애노테이션은 `SellJournalCreateRequest`와 값이 같지만, 컨벤션의 "수정 요청은 `~UpdateRequest`" 접미사 규칙에 따라 **별도 레코드**(`SellJournalUpdateRequest`)로 만든다 — 생성 요청 타입을 수정에 재사용하지 않는다(의미상 다른 유스케이스다).

### 오류 매핑

| 상황 | 상태 | 코드 |
|---|---|---|
| `content` 누락·공백·5000자 초과, `sellTradeId` 타입 불일치 | 400 | `VALIDATION_ERROR` |
| 대상 체결의 `side`가 `SELL`이 아님(=매수 체결) | 400 | `VALIDATION_ERROR` |
| Access 인증 실패·미첨부 | 401 | `UNAUTHORIZED` |
| 타인 소유 체결 | 403 | `FORBIDDEN` |
| `sellTradeId`에 해당하는 체결 없음 | 404 | `NOT_FOUND` |
| 체결은 있으나 그 체결에 매도 회고가 아직 없음 | 404 | `NOT_FOUND` |

**검증 순서는 `체결 존재(404) → 소유(403) → 매도 여부(400) → 회고 존재(404)`로 고정한다** (spec.md JOUR-004). 작성(JOUR-003)의 앞 3단계와 같고 마지막 단계만 "중복이면 409"에서 "없으면 404"로 뒤집힌다. **작성 때와 달리 409는 이 계약에 없다** — 수정은 새 행을 만들지 않으므로 유니크 제약을 위반할 경로가 없다. **새 `ErrorCode` 상수를 추가하지 않는다** — 5개 모두 기존 enum에 있고, "체결 없음"과 "회고 없음"은 서로 다른 상황이지만 같은 `NOT_FOUND` 코드를 재사용한다(메시지 본문은 공통 포맷을 그대로 쓰고 별도 코드로 구분하지 않는다 — PRD·기존 컨벤션이 리소스별 `NOT_FOUND` 세분화를 요구하지 않는다).

### 데이터 모델

#### 마이그레이션 — `sell_trade_journals`에 `updated_at` 추가

**착수 시점 `dev`의 최신 마이그레이션 번호를 실제로 확인한 결과(2026-08-04) `V17__create_sell_trade_journals.sql`이 마지막이다.** 다음 번호는 `V18`이다. **구현 착수 직전에 `ls src/main/resources/db/migration`으로 한 번 더 대조한다** — `buy_trade_journals`가 V14→V15로 재번호화된 전례(ADR-0004, `docs/agent-mistakes.md` 2026-08-03)와 같은 패턴으로, 이 spec 자체가 이미 두 번(V15, V17) 재확인을 거쳤다.

기존 행에 `NOT NULL` 컬럼을 추가하려면 상수 `DEFAULT`로는 "행마다 다른 값"(그 행의 `created_at`)을 채울 수 없으므로, 컬럼을 잠깐 nullable로 추가 → 기존 값을 `created_at`으로 백필 → `NOT NULL`로 좁히는 3단계를 한 마이그레이션 파일 안에서 순서대로 실행한다.

```sql
ALTER TABLE sell_trade_journals
    ADD COLUMN updated_at DATETIME(6) NULL;

UPDATE sell_trade_journals
    SET updated_at = created_at
    WHERE updated_at IS NULL;

ALTER TABLE sell_trade_journals
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL;
```

**작성 직후(아직 한 번도 수정하지 않은) 회고의 `updated_at`은 `created_at`과 같은 값으로 둔다** (spec.md가 "작성시각과 동일 / 비어 있음" 중 결정하라고 넘긴 항목). 비워두는(`NULL`) 대안을 쓰지 않는 이유는 셋이다.

1. **컬럼을 `NOT NULL`로 유지할 수 있다.** 응답 DTO가 `LocalDateTime updatedAt`(non-null)을 항상 채워 반환할 수 있어, 컨트롤러·DTO에서 널 분기를 만들지 않는다.
2. **"수정 여부"를 이 컬럼의 널 여부로 판단하는 코드가 생기지 않는다.** 이번 범위(JOUR-004)에 "수정 이력·최초 수정 여부 노출" 요구가 없으므로(spec.md 범위 제외), 널로 구분해봐야 아무도 읽지 않는 신호다. 필요해지면 그때 별도 필드(`editedAt` 등)를 판단해 추가한다.
3. **백필이 단순해진다.** `created_at`으로 채우면 마이그레이션이 한 컬럼의 값만 다루면 되고, `NULL` 허용이었으면 엔티티·응답 양쪽에서 Optional 처리를 해야 했다.

- 이 컬럼이 유일한 스키마 변경이다. 원장 테이블에는 손대지 않는다.
- `buy_trade_journals`에는 이 컬럼을 추가하지 않는다 — 매수 회고 수정(JOUR-002)은 여전히 Decision Gate 미해결이고, spec.md 범위 제외가 "매수 회고 테이블의 `updated_at`은 잠금 게이트가 풀린 뒤 JOUR-002가 자기 마이그레이션과 함께 추가한다"고 명시했다.

#### 엔티티 `SellTradeJournal` — 필드·수정 메서드 추가

현재 엔티티(`src/main/java/com/finplay/api/journal/domain/SellTradeJournal.java`)는 `id`·`sellTrade`·`content`·`createdAt`만 있고 정적 팩토리 `of(Trade, String, LocalDateTime)`로만 생성된다. 여기에 추가한다.

```java
@Column(name = "updated_at", nullable = false)
private LocalDateTime updatedAt;
```

- `of(Trade sellTrade, String content, LocalDateTime now)`는 **`updatedAt`도 같은 `now`로 채운다** — 위 데이터 모델 결정과 일치해야 하므로 생성자 시그니처는 바꾸지 않고 내부에서 `this.updatedAt = now`를 함께 설정한다. 호출부(`JournalService.createSellJournal`)는 수정할 필요가 없다.
- 수정은 **setter로 두지 않는다**(컨벤션 Entity 규칙 — "상태 변경은 `fill`, `revoke`, `consume`처럼 의도가 드러나는 메서드로만"). 의도가 드러나는 이름으로 `updateContent(String content, LocalDateTime updatedAt)`를 추가한다.

```java
public void updateContent(String content, LocalDateTime updatedAt) {
    this.content = content;
    this.updatedAt = updatedAt;
}
```

- `sellTrade`·`createdAt`·`id`를 바꾸는 메서드는 만들지 않는다 — spec.md "매도 체결 ID·작성시각·투자일기 ID는 바뀌지 않는다".
- 저장은 JPA 변경 감지(dirty checking)에 맡긴다 — 트랜잭션 안에서 조회한 관리 상태 엔티티에 `updateContent`를 호출하면 커밋 시점에 자동 반영되므로, 서비스에서 별도 `save()` 호출이 필요 없다(호출해도 무해하지만 이번 설계는 생략한다 — 관례상 dirty checking을 쓰는 다른 update 유스케이스가 있으면 그 선례를 따른다. 없으면 명시적 `save()` 호출로 의도를 드러내는 쪽을 구현자가 택해도 된다).

#### `SellTradeJournalRepository` — 조회 메서드 추가

```java
Optional<SellTradeJournal> findBySellTradeId(Long sellTradeId);
```

- 기존 `existsBySellTradeId`(작성에서 씀)는 그대로 둔다. 수정은 존재 여부가 아니라 **엔티티 자체**가 필요하므로 파생 쿼리를 하나 더 추가한다 — 하나로 합치지 않는다(용도가 다르다: 작성은 boolean만 필요, 수정은 로드해서 dirty checking 대상으로 삼아야 한다).
- 유니크 제약(`uk_sell_trade_journals_sell_trade`)이 있으므로 `sellTradeId` 1건당 결과가 최대 1건임이 스키마로 보장된다 — `List` 대신 `Optional` 단건 반환이 맞다.
- QueryDSL을 쓰지 않는다(컨벤션 QueryDSL 사용 기준 — 단순 단건 조회).

### 구성요소 설계

기존 `com.finplay.api.journal` 패키지에 **추가**한다. 새 도메인 패키지·새 컨트롤러 클래스를 만들지 않는다.

```
com.finplay.api.journal
├── controller/JournalController.java              (기존 — PATCH 메서드 1개 추가)
├── service/JournalService.java                    (기존 — updateSellJournal 1개 추가)
├── repository/SellTradeJournalRepository.java      (기존 — findBySellTradeId 1개 추가)
├── domain/SellTradeJournal.java                    (기존 — updatedAt 필드 + updateContent 메서드 추가)
└── dto/
    ├── request/SellJournalUpdateRequest.java       (신규)
    └── response/SellJournalUpdateResponse.java     (신규)
```

#### `JournalController`

- `@PatchMapping("/{sellTradeId}/sell-journal")` 메서드를 추가한다. 기존 `@RequestMapping("/api/trades")`와 POST 메서드는 그대로 둔다.
- `@AuthenticationPrincipal AuthenticatedUser principal`, `@PathVariable Long sellTradeId`, `@Valid @RequestBody SellJournalUpdateRequest request`를 받아 서비스에 위임하고 `ResponseEntity.ok(...)`(200)를 반환한다.
- 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는 규칙은 동일하다.

#### `JournalService`

```java
@Transactional
public SellJournalUpdateResponse updateSellJournal(Long userId, Long sellTradeId, String content)
```

1. `Trade trade = tradeService.getOwnedTrade(userId, sellTradeId);` — 존재하지 않으면 404 `NOT_FOUND`, 타인 소유면 403 `FORBIDDEN`. **`TradeService`는 변경하지 않는다** — JOUR-003과 같은 메서드를 그대로 재사용한다.
2. `trade.getSide() != OrderSide.SELL`이면 400 `VALIDATION_ERROR`.
3. `SellTradeJournal journal = sellTradeJournalRepository.findBySellTradeId(sellTradeId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));` — 아직 회고가 없으면 404. **여기서 새 회고를 만들지 않는다(upsert 금지, spec.md 명시).**
4. `journal.updateContent(content, LocalDateTime.now(clock));` — `Clock`은 기존 필드를 그대로 쓴다(추가 주입 없음).
5. `return SellJournalUpdateResponse.from(journal);`

- **트랜잭션 경계는 이 메서드 하나다**(spec.md "수정 조회·검증·저장은 한 트랜잭션에서 처리"). 3단계 조회부터 4단계 수정까지 같은 영속성 컨텍스트 안에서 일어나므로 별도 락 없이 커밋 시점에 한 번에 반영된다.
- **낙관적 락(`@Version`)을 두지 않는다** — spec.md "본인만 수정할 수 있어 동시 편집자가 한 사람"이 근거이고, last-write-wins를 명시적으로 허용했다.
- 작성(`createSellJournal`)의 3~4단계에 있던 "선제 조회 + 유니크 위반 예외 변환" 패턴은 여기 없다 — 수정은 `INSERT`가 아니라 `UPDATE`라 유니크 제약을 위반할 경로가 없다. `DataIntegrityViolationException` catch를 추가하지 않는다.
- 이 메서드가 `sell_trade_journals` 외에 쓰는 테이블은 없다. 원장은 1단계에서 읽기만 한다.

#### DTO 2개

- `SellJournalUpdateRequest(content)` — `SellJournalCreateRequest`와 검증 애노테이션은 같지만 별도 record. record로 만든다(컨벤션).
- `SellJournalUpdateResponse(journalId, sellTradeId, content, createdAt, updatedAt)` + `from(SellTradeJournal)` 정적 팩토리. 위 "응답 DTO를 분리하는 이유" 절 참고.

#### 기존 코드 변경 범위

- **작성 경로(`createSellJournal`, `SellJournalResponse`, `SellJournalCreateRequest`)를 리팩터링하지 않는다.** `SellTradeJournal.of(...)`가 내부적으로 `updatedAt`도 채우도록 바뀌는 것 외에는 작성 경로의 동작·계약이 그대로다.
- **매수 회고 경로(JOUR-001)는 전혀 건드리지 않는다.**
- **`TradeService`를 변경하지 않는다.**

### 테스트 계획 (ADR-0003)

- **단위** (`JournalServiceTest`, Mockito)
  - 정상 수정 시 `updateContent` 호출 인자(새 본문·고정 `Clock` 시각)와 반환 DTO 필드 5개(특히 `createdAt`은 원본 유지, `updatedAt`만 갱신).
  - 없는 체결 → 404, 타인 체결 → 403, 매수 체결 → 400, **회고 미작성 → 404**.
  - **검증 순서**: 타인 소유의 매수 체결이 403이고 400이 아니며, 회고가 없어도 소유·매도 여부 판정이 먼저다.
  - `findBySellTradeId`가 빈 `Optional`을 반환하는 경우와 체결 자체가 없는 경우가 **서로 다른 이유의 404**임을 각각의 테스트로 구분한다(둘 다 같은 `ErrorCode.NOT_FOUND`지만 트리거 지점이 다르므로 회귀 시 어느 단계가 깨졌는지 알 수 있어야 한다).
  - 연속 2회 수정 시 두 번째 호출의 `updatedAt`이 첫 번째보다 이후이고 `content`는 두 번째 값만 남는다(spec.md "잠금 없음"의 근거 테스트).
- **슬라이스**
  - `@DataJpaTest` — ① `findBySellTradeId`가 존재/부재에서 각각 값 있음/`empty()` ② `updateContent` 호출 후 같은 트랜잭션 내에서 flush하면 `content`·`updated_at`이 바뀌고 `created_at`·`sell_trade_id`·`id`는 그대로 ③ 신규 마이그레이션 적용 후 기존 행(V17 시점 데이터가 있다고 가정한 픽스처)의 `updated_at`이 `created_at`과 같은 값으로 백필됐는지(마이그레이션 자체를 검증하는 테스트가 아니라, 백필 로직을 옮긴 별도 케이스로 시뮬레이션할지는 구현자가 판단 — 실제 마이그레이션 SQL 실행 검증은 Testcontainers 기동이 대신한다).
  - `@WebMvcTest`(`JournalControllerTest`) — 200 응답 본문 `jsonPath` 5필드, 공백·누락·5000자 초과 본문 400, 미인증 401, 숫자 아닌 `sellTradeId` 400, 서비스 예외의 400·403·404 매핑(409 케이스 없음 — 이 엔드포인트엔 없다).
- **통합** (`@SpringBootTest` + Testcontainers, 기존 `JournalIntegrationTest`에 추가하거나 신규 파일)
  - 매도 체결을 만들고 회고를 작성한 뒤 PATCH로 본문 수정 → 200, DB 여전히 1행, `content` 갱신, `updatedAt > createdAt`(또는 `Clock`을 진행시켜 두 값이 다름을 보장).
  - **연속 2회 수정** 모두 200이고 마지막 본문만 남는다(수정 횟수 제한·잠금 없음의 근거).
  - 없는 체결 404 · **매도 회고 미작성 404**(체결은 있지만 아직 작성 안 함) · 타인 소유 403 · 매수 체결 400 · 공백 본문 400.
  - **upsert 아님 확인** — 회고 미작성 상태에서 PATCH가 404로 실패한 뒤 `sell_trade_journals` 행 수가 0임을 확인한다(수정 요청이 새 회고를 만들지 않음).
  - 매수 회고 계약(`POST .../journal`)과 매도 회고 작성 계약(`POST .../sell-journal`) 기존 테스트가 그대로 통과.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 동일하다.
  - 신규 마이그레이션 적용 후 `ddl-auto=validate` 기동 통과.

### 문서 갱신 (CLAUDE.md 규칙 7)

- `docs/api-routes.md` — `journal` 도메인에 `PATCH /api/trades/{sellTradeId}/sell-journal` 행 추가 (근거 열: `007 JOUR-004, Issue #190`).
- `docs/api-contracts.md` `## journal` 절 — 매도 회고 작성 절 아래에 "매도 회고 수정" 소절을 추가해 요청·응답·오류 계약을 적는다. 작성 절과의 차이(메서드 PATCH, 상태 200, 응답 5필드, 회고 없으면 404, 409 없음)를 본문에 한 줄로 요약한다.
- 컨트롤러 변경과 **같은 커밋**에서 갱신한다(이 항목은 tasks.md에서 컨트롤러 커밋에 포함한다).
- 이 갱신은 planner의 동기화 모드가 실제 controller 코드를 보고 확정하며, 여기 적은 문구는 설계 의도이지 최종 표현이 아니다.

## JOUR-002 매수 회고 수정 설계 (이슈 #197, 이번 착수)

> 요구사항·비즈니스 규칙의 정본은 `./spec.md` JOUR-002 절이다. 특히 "매수 회고 수정 잠금 없음" 결정과 그 근거는 `spec.md`가 이미 확정했으므로 여기서 재논의하지 않는다.
>
> **이 절은 위 §JOUR-004 매도 회고 수정 설계의 매수 체결 판(版)이다.** 구조·계약·트랜잭션 경계·테스트 구성이 전부 대칭이므로, JOUR-004 절과 **같은 항목은 반복하지 않고 "JOUR-004와 동일"로 참조**하고 **다른 점만** 적는다. 다른 점은 실질적으로 넷이다 — ① 대상 테이블·엔티티가 `buy_trade_journals`/`BuyTradeJournal` ② `side` 검증 조건이 `BUY` ③ 경로가 `/journal` ④ **매도 배분이 일어난 lot도 수정 가능하다는 잠금 없음 회귀 테스트가 추가된다.**

### API 설계

| Method | URL | 요청 | 성공 응답 | 설명 |
|---|---|---|---|---|
| PATCH | `/api/trades/{buyTradeId}/journal` | `BuyJournalUpdateRequest` (JSON 본문) | **200** `BuyJournalUpdateResponse` | 본인 소유 매수 체결 1건에 이미 작성된 매수 회고의 본문을 교체 |

- 작성(JOUR-001)과 **동일한 리소스 경로**를 PATCH로 재사용한다. 별도 하위 경로(`/journal/edit` 등)를 만들지 않는다 — JOUR-004가 `POST`·`PATCH .../sell-journal`로 같은 처리를 했다.
- 200인 이유는 컨벤션 API 규칙("수정 200") — 생성이 아니므로 201이 아니다.
- 경로 변수명은 작성과 동일하게 `buyTradeId`다. 회고 자체의 PK(`journalId`)를 경로에 노출하지 않는다 — 상세 조회(JOUR-005) 식별자 체계 Decision Gate를 앞당기지 않기 위해서다.

#### 요청 예시

```json
{ "content": "실적 발표 전 분할 매수였다. 진입 근거를 더 좁게 적었어야 했다." }
```

#### 응답 예시 (200)

```json
{ "journalId": 1, "buyTradeId": 12, "content": "실적 발표 전 분할 매수였다. 진입 근거를 더 좁게 적었어야 했다.", "createdAt": "2026-08-04T10:12:33", "updatedAt": "2026-08-05T09:41:07" }
```

응답 필드는 **5개로 고정**이다 — `journalId`·`buyTradeId`·`content`·`createdAt`·`updatedAt`. PRD·spec.md JOUR-002의 "투자일기 ID, 매수 체결 ID, 본문, 작성시각, 수정시각"과 1:1이다. **매도 배분·실현손익 등 후속 원장 정보는 넣지 않는다** — 잠금이 없어 그 값을 판정에 쓰지도 않고, `GET /api/trades`가 이미 제공한다.

### 응답 DTO를 분리하는 이유

JOUR-004 §"응답 DTO를 분리하는 이유"와 **같은 판단을 그대로 적용한다.** `BuyJournalResponse`(작성, 4필드)는 PR #181로 이미 배포됐으므로 필드를 늘리지 않고, 수정 응답은 새 레코드 `BuyJournalUpdateResponse`(5필드)로 만든다. 두 회고가 같은 패턴을 쓰게 되어 프론트엔드가 예측하기 쉬워진다는 점이 이번에 추가되는 이유다.

### 입력 명세

| 필드 | 위치 | 필수 | 검증 |
|---|---|---|---|
| `buyTradeId` | path | 필수 | 숫자(`Long`). 파싱 불가 400 `VALIDATION_ERROR`, 없으면 404 `NOT_FOUND`, 타인 소유면 403 `FORBIDDEN`, `side != BUY`면 400 `VALIDATION_ERROR`, 매수 회고가 아직 없으면 404 `NOT_FOUND` |
| `content` | body | 필수 | `@NotBlank` + `@Size(max = 5000)` — 작성(JOUR-001)과 **같은 상한**. 네 계약(매수·매도 × 작성·수정)이 모두 같은 값을 쓴다 |

- 본문 검증이 경로 검증보다 먼저 일어나는 순서(없는 체결 + 공백 본문 = 400), 트림 없이 원문 저장, `~UpdateRequest` 별도 레코드 분리는 모두 JOUR-004와 동일하다.

### 오류 매핑

| 상황 | 상태 | 코드 |
|---|---|---|
| `content` 누락·공백·5000자 초과, `buyTradeId` 타입 불일치 | 400 | `VALIDATION_ERROR` |
| 대상 체결의 `side`가 `BUY`가 아님(=매도 체결) | 400 | `VALIDATION_ERROR` |
| Access 인증 실패·미첨부 | 401 | `UNAUTHORIZED` |
| 타인 소유 체결 | 403 | `FORBIDDEN` |
| `buyTradeId`에 해당하는 체결 없음 | 404 | `NOT_FOUND` |
| 체결은 있으나 그 체결에 매수 회고가 아직 없음 | 404 | `NOT_FOUND` |

**검증 순서는 `체결 존재(404) → 소유(403) → 매수 여부(400) → 회고 존재(404)`로 고정한다** (spec.md JOUR-002). **409는 이 계약에 없다** — 수정은 새 행을 만들지 않아 유니크 제약을 위반할 경로가 없다. **`JOURNAL_LOCKED`를 포함해 새 `ErrorCode` 상수를 추가하지 않는다** — 위 5개는 모두 기존 enum에 있고, 잠금 상태(423/409 등)에 해당하는 응답은 이 계약에 존재하지 않는다.

### 데이터 모델

#### 마이그레이션 — `buy_trade_journals`에 `updated_at` 추가

**조사 시점(2026-08-04) `dev`의 최신 마이그레이션은 `V18__add_updated_at_to_sell_trade_journals.sql`이므로 다음 번호는 `V19`다.** 파일명은 `V19__add_updated_at_to_buy_trade_journals.sql`. **구현 착수 직전에 `ls src/main/resources/db/migration | sort -V | tail`로 한 번 더 대조한다** — 이 spec은 이미 V14→V15 재번호화(ADR-0004, `docs/agent-mistakes.md` 2026-08-03)와 V16→V17 재확인을 겪었다.

DDL은 **JOUR-004의 `V18`과 같은 3단계**다(테이블명만 다르다). 기존 행에 `NOT NULL` 컬럼을 추가할 때 상수 `DEFAULT`로는 "행마다 다른 값"(그 행의 `created_at`)을 채울 수 없으므로 한 파일 안에서 순서대로 실행한다.

```sql
ALTER TABLE buy_trade_journals
    ADD COLUMN updated_at DATETIME(6) NULL;

UPDATE buy_trade_journals
    SET updated_at = created_at
    WHERE updated_at IS NULL;

ALTER TABLE buy_trade_journals
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL;
```

**작성 직후(아직 수정하지 않은) 회고의 `updated_at`은 `created_at`과 같은 값으로 둔다** — 근거 3가지(컬럼을 `NOT NULL`로 유지 / "수정 여부"를 널 여부로 판단하는 코드가 생기지 않음 / 백필 단순화)는 JOUR-004 §데이터 모델과 동일하다. **두 회고 테이블이 같은 규칙을 쓰는 것이 이번 착수의 추가 근거다** — 한쪽만 `NULL` 허용이면 JOUR-005·006이 두 테이블을 합쳐 조회할 때 분기가 생긴다.

- 이 컬럼이 유일한 스키마 변경이다. **원장 테이블(`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`)에는 `ALTER`·`DROP`이 없다.**
- 잠금이 없으므로 `buy_trade_journals`에 `locked_at`·`locked` 같은 컬럼이나 `trade_allocations` 참조 인덱스를 만들지 않는다.

#### 엔티티 `BuyTradeJournal` — 필드·수정 메서드 추가

현재 엔티티(`src/main/java/com/finplay/api/journal/domain/BuyTradeJournal.java`)는 `id`·`buyTrade`·`content`·`createdAt`만 있고 정적 팩토리 `of(Trade, String, LocalDateTime)`로만 생성된다. **`SellTradeJournal`이 JOUR-004에서 받은 변경과 같은 형태**로 바꾼다.

```java
@Column(name = "updated_at", nullable = false)
private LocalDateTime updatedAt;
```

- `of(Trade buyTrade, String content, LocalDateTime now)`는 **`updatedAt`도 같은 `now`로 채운다** — 시그니처는 바꾸지 않으므로 호출부(`JournalService.createBuyJournal`)를 수정할 필요가 없다. `SellTradeJournal.of(...)`가 이미 같은 방식이다.
- 수정은 setter가 아니라 의도가 드러나는 `updateContent(String content, LocalDateTime updatedAt)`로 한다(컨벤션 Entity 규칙, `SellTradeJournal.updateContent` 선례).
- `buyTrade`·`createdAt`·`id`를 바꾸는 메서드는 만들지 않는다.
- 저장은 JPA 변경 감지(dirty checking)에 맡긴다 — `updateSellJournal`이 이미 그렇게 동작하며, 같은 관례를 따른다.
- **두 엔티티의 공통 상위 클래스(`@MappedSuperclass`)는 이번에도 만들지 않는다.** 필드 4개가 같아졌지만 테이블 통합 여부가 JOUR-005 게이트에 걸려 있어, 상속을 넣으면 게이트 결정 때 두 번 푼다(JOUR-003 §엔티티 절의 판단을 유지한다).

#### `BuyTradeJournalRepository` — 조회 메서드 추가

```java
Optional<BuyTradeJournal> findByBuyTradeId(Long buyTradeId);
```

- 기존 `existsByBuyTradeId`(작성에서 씀)는 그대로 둔다. 용도가 다르다 — 작성은 boolean만, 수정은 엔티티를 로드해 dirty checking 대상으로 삼아야 한다. `SellTradeJournalRepository`가 `existsBySellTradeId` + `findBySellTradeId` 둘을 갖는 것과 같은 구성이다.
- `uk_buy_trade_journals_buy_trade` 유니크 제약이 결과 최대 1건을 스키마로 보장하므로 `Optional` 단건 반환이 맞다. QueryDSL을 쓰지 않는다.

### 구성요소 설계

기존 `com.finplay.api.journal` 패키지에 **추가**한다. 새 도메인 패키지·새 컨트롤러 클래스를 만들지 않는다.

```
com.finplay.api.journal
├── controller/JournalController.java              (기존 — PATCH 메서드 1개 추가)
├── service/JournalService.java                    (기존 — updateBuyJournal 1개 추가)
├── repository/BuyTradeJournalRepository.java      (기존 — findByBuyTradeId 1개 추가)
├── domain/BuyTradeJournal.java                    (기존 — updatedAt 필드 + updateContent 메서드 추가)
└── dto/
    ├── request/BuyJournalUpdateRequest.java       (신규)
    └── response/BuyJournalUpdateResponse.java     (신규)
```

#### `JournalController`

- `@PatchMapping("/{buyTradeId}/journal")` 메서드를 추가한다. 기존 `@RequestMapping("/api/trades")`와 나머지 3개 메서드는 그대로 둔다.
- `@AuthenticationPrincipal AuthenticatedUser principal`, `@PathVariable Long buyTradeId`, `@Valid @RequestBody BuyJournalUpdateRequest request`를 받아 서비스에 위임하고 `ResponseEntity.ok(...)`(200)를 반환한다. `updateSellJournal` 메서드와 형태가 같다.
- 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다.

#### `JournalService`

```java
@Transactional
public BuyJournalUpdateResponse updateBuyJournal(Long userId, Long buyTradeId, String content)
```

1. `Trade trade = tradeService.getOwnedTrade(userId, buyTradeId);` — 없으면 404 `NOT_FOUND`, 타인 소유면 403 `FORBIDDEN`. **`TradeService`는 변경하지 않는다.**
2. `trade.getSide() != OrderSide.BUY`면 400 `VALIDATION_ERROR`.
3. `BuyTradeJournal journal = buyTradeJournalRepository.findByBuyTradeId(buyTradeId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));` — 회고가 없으면 404. **여기서 새 회고를 만들지 않는다(upsert 금지).**
4. `journal.updateContent(content, LocalDateTime.now(clock));` — 기존 `Clock` 필드를 그대로 쓴다.
5. `return BuyJournalUpdateResponse.from(journal);`

- **잠금 판정 단계가 없다는 것이 이 설계의 핵심이다.** 2단계와 3단계 사이에 "이 매수 lot에 배분이 있는지" 같은 조회를 넣지 않는다 — `HoldingLotRepository`·`TradeAllocationRepository`를 주입하지 않고, `updateSellJournal`과 단계 수가 정확히 같다. 리뷰에서 이 대칭이 깨졌다면 잠금 로직이 새어 들어온 것이다.
- 트랜잭션 경계는 이 메서드 하나다. 낙관적 락(`@Version`)을 두지 않는다(last-write-wins, spec.md). 유니크 위반 변환(`DataIntegrityViolationException` → 409)도 없다 — `UPDATE`라 해당 경로가 없다.
- 이 메서드가 `buy_trade_journals` 외에 쓰는 테이블은 없다. 원장은 1단계에서 읽기만 한다.

#### DTO 2개

- `BuyJournalUpdateRequest(content)` — `BuyJournalCreateRequest`와 검증 애노테이션은 같지만 별도 record(컨벤션 `~UpdateRequest`).
- `BuyJournalUpdateResponse(journalId, buyTradeId, content, createdAt, updatedAt)` + `from(BuyTradeJournal)` 정적 팩토리. record로 만든다.

#### 기존 코드 변경 범위

- **작성 경로(`createBuyJournal`, `BuyJournalResponse`, `BuyJournalCreateRequest`)를 리팩터링하지 않는다.** `BuyTradeJournal.of(...)`가 내부적으로 `updatedAt`도 채우는 것 외에는 동작·계약이 그대로다.
- **매도 회고 경로(JOUR-003·004)는 전혀 건드리지 않는다.**
- **`TradeService`·`OrderService`·FIFO 배분 코드를 변경하지 않는다.**
- **매수·매도 네 유스케이스의 공통 추상화를 지금 만들지 않는다** (spec.md 범위 제외). 이번 PR의 diff는 추가 위주다.

### 테스트 계획 (ADR-0003)

JOUR-004 테스트와 **대칭**으로 만들되, 잠금 없음을 고정하는 통합 케이스가 추가된다.

- **단위** (`JournalServiceTest`, Mockito)
  - 정상 수정 시 `updateContent` 호출 인자(새 본문·고정 `Clock` 시각)와 반환 DTO 5필드(`createdAt`은 원본 유지, `updatedAt`만 갱신).
  - 없는 체결 → 404, 타인 체결 → 403, **매도 체결 → 400**, 회고 미작성 → 404.
  - **검증 순서**: 타인 소유의 매도 체결이 403이고 400이 아니며, 회고가 없어도 소유·매수 여부 판정이 먼저다.
  - 체결 없음 404와 회고 없음 404를 **별도 테스트로 구분**한다(같은 `ErrorCode`지만 트리거 지점이 달라 회귀 시 어느 단계가 깨졌는지 알 수 있어야 한다).
  - 연속 2회 수정 시 두 번째 `updatedAt`이 더 이후이고 `content`는 두 번째 값만 남는다.
- **슬라이스**
  - `@DataJpaTest`(`BuyTradeJournalRepositoryTest`) — ① `findByBuyTradeId`가 존재/부재에서 값 있음/`empty()` ② `updateContent` 후 flush하면 `content`·`updated_at`만 바뀌고 `created_at`·`buy_trade_id`·`id`는 그대로 ③ 신규 컬럼의 `NOT NULL` 제약.
  - `@WebMvcTest`(`JournalControllerTest`) — 200 본문 `jsonPath` 5필드, 공백·누락·5000자 초과 400, 숫자 아닌 `buyTradeId` 400, 미인증 401, 서비스 예외의 400·403·404 매핑(**409 케이스 없음**).
- **통합** (`@SpringBootTest` + Testcontainers, 기존 `JournalIntegrationTest`에 추가)
  - 매수 체결 → 회고 작성 → PATCH 수정 → 200, DB 여전히 1행, `content` 갱신, `updatedAt > createdAt`(`Clock`을 진행시켜 두 값이 다름을 보장).
  - **잠금 없음 회귀 (이 착수의 핵심)** — ① 매도한 적 없는 매수 체결 ② **부분 매도로 `trade_allocations` 행이 생긴** 매수 체결 ③ **전량 매도된** 매수 체결, 세 경우 모두 수정이 200이다. ②·③은 `005-order-sell` 경로를 실제로 태워 배분을 만든 뒤 수정한다 — 잠금 규정이 코드로 되살아나면 이 테스트가 먼저 깨진다.
  - **연속 2회 수정** 모두 200이고 마지막 본문만 남는다.
  - 없는 체결 404 · **회고 미작성 404** · 타인 소유 403 · 매도 체결 400 · 공백 본문 400.
  - **upsert 아님 확인** — 회고 미작성 상태에서 PATCH가 404로 실패한 뒤 `buy_trade_journals` 행 수가 0이다.
  - 매수 회고 작성(`POST .../journal`)·매도 회고 작성·수정(`POST`·`PATCH .../sell-journal`) 기존 테스트가 그대로 통과.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 동일하다. 특히 **배분이 있는 lot을 수정한 뒤에도 `trade_allocations`·`holding_lots`가 그대로**임을 확인한다.
  - 신규 마이그레이션 적용 후 `ddl-auto=validate` 기동 통과.

### 문서 갱신 (CLAUDE.md 규칙 7)

- `docs/api-routes.md` — `journal` 도메인에 `PATCH /api/trades/{buyTradeId}/journal` 행 추가 (근거 열: `007 JOUR-002, Issue #197`).
- `docs/api-contracts.md` `## journal` 절 — 매수 회고 작성 절 아래에 "매수 체결 투자일기 수정" 소절을 추가한다. 매도 회고 수정 절과의 대칭(메서드 PATCH, 상태 200, 응답 5필드, 회고 없으면 404, 409 없음)과 **잠금 없음(매도 배분 여부와 무관하게 항상 수정 가능)**을 본문에 적는다.
- **`docs/prd.md` JOUR-002·`docs/specs/005-order-sell/spec.md`의 잠금 규정 갱신은 이번 착수의 문서 커밋에서 이미 완료했다** (2026-08-04) — 구현 커밋에서 다시 손대지 않는다.
- 컨트롤러 변경과 **같은 커밋**에서 API 문서 2개를 갱신한다.

## 이 spec에서 하지 않는 것

`./spec.md` §범위 제외가 정본이다. 특히 **JOUR-002(수정)·JOUR-005(상세)는 PRD가 Decision Gate 미해결로 표시**했으므로, 그 계약을 미리 반영한 컬럼·필드·URL을 이번 구현에 넣지 않는다. 매도 회고 수정(JOUR-004)은 이번 이슈 다음의 별도 이슈이며, 그 때문에 `sell_trade_journals`에 `updated_at`을 미리 만들지 않는다.

> **2026-08-04 갱신**: 위 문단은 JOUR-003 착수 시점(이슈 #183)의 기록이며 그 시점 기준으로는 여전히 맞다("다음 별도 이슈" = 지금 이 JOUR-004). JOUR-004는 이제 이번 spec의 착수 범위이고, 실제 설계는 위 §JOUR-004 매도 회고 수정 설계를 따른다. 매수 회고 수정(JOUR-002)·투자일기 상세(JOUR-005)는 여전히 Decision Gate 미해결로 범위 밖이다.
>
> **2026-08-04 재갱신 (이슈 #197)**: **JOUR-002의 Decision Gate는 해제됐다** — 잠금을 두지 않기로 확정했고(`./spec.md` §비즈니스 규칙 "매수 회고 수정 잠금 없음", `docs/prd.md` JOUR-002), 설계는 위 §JOUR-002 매수 회고 수정 설계를 따른다. 따라서 `buy_trade_journals`의 `updated_at`을 이번 착수에서 추가한다. **투자일기 상세(JOUR-005)의 식별자 체계 게이트만 남았고**, 목록(JOUR-006)과 함께 여전히 범위 밖이다.
