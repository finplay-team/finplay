# Plan: 체결별 투자일기 작성·수정·조회 (JOUR-001 · JOUR-003 · JOUR-004 · JOUR-002 · JOUR-006 · JOUR-005)

> 요구사항·규칙의 정본은 `./spec.md`이고 PRD가 그 상위다. 여기서 새 규칙을 만들지 않는다.
>
> | 절 | 범위 | 이슈 | 상태 |
> |---|---|---|---|
> | §JOUR-001 ~ §JOUR-003 앞까지 | 매수 회고 작성 엔드포인트 1개 | [#159](https://github.com/finplay-team/finplay/issues/159) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | §JOUR-003 매도 회고 작성 설계 | 매도 회고 작성 엔드포인트 1개 | [#183](https://github.com/finplay-team/finplay/issues/183) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | §JOUR-004 매도 회고 수정 설계 | 매도 회고 수정 엔드포인트 1개 | [#190](https://github.com/finplay-team/finplay/issues/190) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | §JOUR-002 매수 회고 수정 설계 | 매수 회고 수정 엔드포인트 1개 | [#197](https://github.com/finplay-team/finplay/issues/197) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | §JOUR-006 투자일기 목록 조회 설계 | 목록 조회 엔드포인트 1개 | [#203](https://github.com/finplay-team/finplay/issues/203) | 구현 완료 (기록 보존용, 변경하지 않는다) |
> | **§JOUR-005 투자일기 상세 조회 설계** | 상세 조회 엔드포인트 2개 (매수·매도) | [#217](https://github.com/finplay-team/finplay/issues/217) | **이번 착수** |
>
> 아래 §관련 문서부터 §JOUR-003 절 직전까지는 **JOUR-001 설계**, 이어지는 §JOUR-003·§JOUR-004·§JOUR-002·§JOUR-006 절은 각각 그 요구사항의 설계다. 다섯 다 구현 완료된 기록이며 이번 착수에서 손대지 않는다. JOUR-005 설계는 §JOUR-006 절 뒤, §이 spec에서 하지 않는 것 앞에 있는 새 절이다.

## JOUR-001 매수 회고 작성 설계 (이슈 #159, 구현 완료)

## 관련 문서

| 문서 | 담는 것 |
|---|---|
| `./spec.md` | 요구사항·비즈니스 규칙·완료 조건 (정본) |
| `./tasks.md` | 커밋 단위 작업 분해 |
| `ai/prd.md` JOUR-001 | 상위 요구사항. JOUR-005의 Decision Gate 서술도 여기 (JOUR-002의 게이트는 2026-08-04 이슈 #197에서 해제) |
| `ai/api-routes.md` · `docs/api-contracts.md` | JOUR-001 반영 완료 (2026-08-04). **JOUR-003는 미반영** — 컨트롤러 커밋과 같은 커밋에서 반영한다 |
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

**마이그레이션 파일: `V15__create_buy_trade_journals.sql`.** 조사 시점(2026-08-04) 착수 당시 `dev`의 최신 버전은 `V13__create_ai_feedback_tables.sql`이라 처음엔 V14로 만들었으나, PR #181 병합 전 `dev`에 `V14__create_favorites.sql`(#163·#168)이 먼저 병합돼 번호가 충돌해 V15로 재번호화했다(ADR-0004 — 이미 만든 V14 파일은 수정하지 않고 새 번호로 대체, `ai/agent-mistakes.md` 2026-08-03 행과 같은 패턴).

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

**마이그레이션 파일: `V17__create_sell_trade_journals.sql`.** 조사 시점(2026-08-04) `dev`의 최신 버전은 `V16__create_practice_progresses_and_intentions.sql`이다. **구현 착수 시 `dev`를 다시 확인해** 그때의 최신 다음 번호를 쓴다 — `buy_trade_journals`가 V14→V15로 재번호화된 전례(ADR-0004, `ai/agent-mistakes.md` 2026-08-03)가 있으므로 병합 직전에 한 번 더 대조한다.

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

- `ai/api-routes.md` — `journal` 도메인에 `POST /api/trades/{sellTradeId}/sell-journal` 행 추가 (근거 열: `007 JOUR-003, Issue #183`).
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

**착수 시점 `dev`의 최신 마이그레이션 번호를 실제로 확인한 결과(2026-08-04) `V17__create_sell_trade_journals.sql`이 마지막이다.** 다음 번호는 `V18`이다. **구현 착수 직전에 `ls src/main/resources/db/migration`으로 한 번 더 대조한다** — `buy_trade_journals`가 V14→V15로 재번호화된 전례(ADR-0004, `ai/agent-mistakes.md` 2026-08-03)와 같은 패턴으로, 이 spec 자체가 이미 두 번(V15, V17) 재확인을 거쳤다.

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

- `ai/api-routes.md` — `journal` 도메인에 `PATCH /api/trades/{sellTradeId}/sell-journal` 행 추가 (근거 열: `007 JOUR-004, Issue #190`).
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

**조사 시점(2026-08-04) `dev`의 최신 마이그레이션은 `V18__add_updated_at_to_sell_trade_journals.sql`이므로 다음 번호는 `V19`로 예상했으나, PR 리뷰 중 `dev`에 `V19__drop_favorites_and_practice_intentions.sql`(#193)이 먼저 병합돼 `V20`으로 재번호화했고, 그 뒤 `dev`에 `V20__add_stock_replay_session_to_trades.sql`(#191)까지 먼저 병합되면서 다시 충돌해 최종적으로 `V21`로 재번호화했다.** 파일명은 `V21__add_updated_at_to_buy_trade_journals.sql`. 이 spec은 이미 V14→V15, V16→V17 재확인에 이어 이번 이슈에서만 두 번(V19→V20→V21) 재번호화했다(ADR-0004, `ai/agent-mistakes.md` 2026-08-03 패턴과 동일 — 착수 시점에 확인한 번호도 PR 머지 전에 다시 한번, 그리고 병합 대기 중 다시 대조해야 한다).

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

- `ai/api-routes.md` — `journal` 도메인에 `PATCH /api/trades/{buyTradeId}/journal` 행 추가 (근거 열: `007 JOUR-002, Issue #197`).
- `docs/api-contracts.md` `## journal` 절 — 매수 회고 작성 절 아래에 "매수 체결 투자일기 수정" 소절을 추가한다. 매도 회고 수정 절과의 대칭(메서드 PATCH, 상태 200, 응답 5필드, 회고 없으면 404, 409 없음)과 **잠금 없음(매도 배분 여부와 무관하게 항상 수정 가능)**을 본문에 적는다.
- **`ai/prd.md` JOUR-002·`ai/specs/005-order-sell/spec.md`의 잠금 규정 갱신은 이번 착수의 문서 커밋에서 이미 완료했다** (2026-08-04) — 구현 커밋에서 다시 손대지 않는다.
- 컨트롤러 변경과 **같은 커밋**에서 API 문서 2개를 갱신한다.

## JOUR-006 투자일기 목록 조회 설계 (이슈 #203, 이번 착수)

> 요구사항·비즈니스 규칙·완료 조건의 정본은 `./spec.md` JOUR-006 절이다. 특히 식별자 게이트 비선점, 정렬·커서 키, `market`·소유권 결합 조건, 404/200 빈 목록 구분은 `spec.md`가 이미 확정했으므로 여기서 재논의하지 않는다. 이 절은 `spec.md`가 `plan.md`로 넘긴 세 가지(① 테이블 병합 방식 ② 컨트롤러 배치 ③ 응답 DTO)를 확정한다.

### API 설계

| Method | URL | 요청 | 성공 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/journal?market=&cursor=&limit=` | 쿼리 파라미터 | **200** `JournalListResponse` | 인증 사용자 본인의 매수·매도 회고를 `createdAt` 내림차순(동점은 체결 ID 내림차순)으로 섞어 커서 페이지네이션 조회 |

- 인증: Access Bearer 필수. 조회 대상은 쿼리 파라미터가 아니라 Access Token의 인증 사용자(`AuthenticatedUser#userId`)로 결정한다(spec.md).
- URL에 버전 프리픽스를 쓰지 않는다(컨벤션). `market`·`cursor`·`limit` 세 파라미터 이름과 의미는 `GET /api/trades`(PORT-002)·`GET /api/orders`(PORT-003)와 동일하다 — 새 이름을 만들지 않는다.

#### 응답 예시 (200)

```json
{
  "content": [
    { "journalType": "SELL", "buyTradeId": null, "sellTradeId": 34, "content": "목표가 도달해서 전량 매도.", "createdAt": "2026-08-05T09:03:12", "updatedAt": "2026-08-05T09:03:12" },
    { "journalType": "BUY", "buyTradeId": 12, "sellTradeId": null, "content": "실적 발표 전 분할 매수.", "createdAt": "2026-08-04T10:12:33", "updatedAt": "2026-08-04T10:12:33" }
  ],
  "nextCursor": "2026-08-04T10:12:33_12",
  "hasNext": false
}
```

항목 필드는 **6개로 고정**이다 — `journalType`·`buyTradeId`·`sellTradeId`·`content`·`createdAt`·`updatedAt` (spec.md). wrapper는 `content`·`nextCursor`·`hasNext` 3필드로 `TradeListResponse`·`OrderListResponse`와 동일 형태다. **통합 `journalId`는 노출하지 않는다**(spec.md "JOUR-005 식별자 게이트를 선점하지 않는다").

### 입력 명세

| 필드 | 위치 | 필수 | 검증 |
|---|---|---|---|
| `market` | query | **필수** | `STOCK`\|`CRYPTO` 리터럴(`account.domain.Market` enum). 누락 시 `MissingServletRequestParameterException`, 미지원 리터럴은 `MethodArgumentTypeMismatchException` — 둘 다 전역 핸들러가 400 `VALIDATION_ERROR`로 매핑(`TradeController`·`OrderController`와 동일 경로, 컨트롤러에 별도 분기 없음) |
| `cursor` | query | 선택 | 미지정 시 첫 페이지. 지정 시 `{createdAt}_{tradeId}` 형식 — 파싱 실패(구분자 없음·`LocalDateTime`/`Long` 파싱 불가)는 400 `VALIDATION_ERROR` |
| `limit` | query | 선택, 기본 20 | 1~100. 범위를 벗어나면 **클램핑 없이** 400 `VALIDATION_ERROR`(`TradeController.validateLimit`과 동일한 수동 검증 — `@Min`/`@Max`를 DTO에 못 붙이는 이유도 같다: 쿼리 파라미터라 요청 DTO가 없다) |

- 세 파라미터의 필수 여부·기본값·오류 매핑은 `TradeController.getMyTrades`·`OrderController.getMyOrders`를 그대로 복제한다. 새 검증 로직을 만들지 않는다.

### 오류 매핑

| 상황 | 상태 | 코드 |
|---|---|---|
| `market` 누락·미지원 리터럴 | 400 | `VALIDATION_ERROR` |
| `limit` 범위 밖(1~100 벗어남) | 400 | `VALIDATION_ERROR` |
| `cursor` 형식 파싱 실패 | 400 | `VALIDATION_ERROR` |
| Access 인증 실패·미첨부 | 401 | `UNAUTHORIZED` |
| 인증 사용자의 해당 `market` 계좌 없음 | 404 | `NOT_FOUND` |
| 계좌는 있으나 회고 0건 | 200 | 빈 목록(`content: []`·`nextCursor: null`·`hasNext: false`) |

**새 `ErrorCode` 상수를 추가하지 않는다** — 4개 모두 기존 enum에 있다. 404는 `AccountService.getAccountFor`가 이미 던지는 예외를 그대로 전파한다(신규 분기 없음).

### 데이터 접근 설계 — 두 테이블 병합 방식 (spec.md가 plan.md로 넘긴 결정 ①)

**결정: 각 리포지토리에서 QueryDSL로 `limit + 1`건씩 커서 조건 조회 → `JournalService`가 애플리케이션 계층에서 병합·정렬한다.** 네이티브 `UNION ALL`은 채택하지 않는다.

근거는 넷이다.

1. **기존 커서 페이지네이션 관례가 이미 이 모양이다.** `TradeService.getMyTrades`(`order/service/TradeService.java`)가 `limit + 1`건 페치 → `hasNext` 판정 → `nextCursor` 인코딩의 3단계를 쓰고, `TradeRepositoryImpl.findByAccountIdWithCursor`(QueryDSL)가 단일 테이블 커서 조회를 맡는다. JOUR-006은 이 관례를 테이블 2개로 확장한 것뿐이다 — 새 패턴을 도입하지 않는다.
2. **`UNION ALL`은 이 코드베이스에 선례가 없고 QueryDSL의 엔티티 타입 안전성을 잃는다.** `BuyTradeJournal`과 `SellTradeJournal`은 컬럼 이름(`buy_trade_id`/`sell_trade_id`)과 엔티티 타입이 다르므로, `UNION ALL`로 합치려면 네이티브 SQL + 수동 `ResultSetMapping`(또는 DTO 프로젝션)이 필요하다. 컨벤션의 QueryDSL 사용 기준("동적 조건·커서 페이지네이션·다중 조인 목록 조회에 QueryDSL을 쓴다")과 맞지 않고, 두 테이블의 스키마가 갈라질 때(JOUR-005 게이트 이후) 마이그레이션 비용이 더 크다.
3. **정확성이 간단히 증명된다.** 병합 후 필요한 상위 `limit + 1`건은 어느 한쪽에서 전부 나오는 극단적인 경우에도 그 한쪽의 `limit + 1`건 페치로 이미 커버된다 — 그래서 "각 테이블에서 `limit + 1`건씩"이면 병합 결과의 상위 `limit + 1`건을 놓치지 않는다. 두 리스트를 합쳐 `(createdAt, tradeId)` 내림차순으로 정렬해 앞의 `limit + 1`건만 취하면 된다.
4. **ADR-0002 위반이 없다.** 두 쿼리 모두 journal 자체 테이블(`buy_trade_journals`/`sell_trade_journals`)만 읽고, 계좌 스코프 조건은 이미 엔티티에 걸린 `@ManyToOne Trade`(→`Account`) 관계를 JPQL/QueryDSL 조인으로 타는 것이다. `TradeRepository`를 journal이 직접 주입하는 것이 아니다 — `BuyTradeJournal.buyTrade`·`SellTradeJournal.sellTrade` 필드 자체가 이미 JOUR-001·003에서 `order.domain.Trade`를 참조하도록 확정된 관계이고(엔티티 레벨 참조는 이미 있는 설계), 이번에 새로 주입하는 건 `order` 패키지의 **repository**가 아니라 그 관계를 타는 journal 자신의 QueryDSL 쿼리다.

**알려진 트레이드오프**: 병합·정렬을 서비스 계층에서 하므로 DB가 아니라 애플리케이션 메모리에서 정렬한다. 한 요청당 최대 `2 × (limit + 1)`건(기본 42건, 최대 202건)만 로드하므로 이번 규모에서는 무시할 수준이다. 두 테이블이 훨씬 커지고 `limit`이 커지면 이 트레이드오프를 재평가한다 — 지금은 실측 없이 최적화하지 않는다.

### 커서 인코딩 형식

`spec.md`가 고정한 `{createdAt}_{tradeId}` 형식을 그대로 구현한다 — `TradeCursor`(`order/service/TradeCursor.java`)·`OrderCursor`(`order/service/OrderCursor.java`)와 완전히 같은 모양(`DateTimeFormatter.ISO_LOCAL_DATE_TIME` + `_` + `Long`)의 새 값 객체 `JournalCursor(LocalDateTime createdAt, Long tradeId)`를 `com.finplay.api.journal.service`에 만든다.

- **기존 `TradeCursor`를 재사용하지 않고 새로 만드는 이유.** `OrderCursor`가 `TradeCursor`와 필드 의미(`requestedAt` vs `executedAt`)가 달라 이미 별도 값 객체로 중복돼 있다 — 이 코드베이스는 커서 값 객체를 엔티티/유스케이스 단위로 두고 조기 추상화하지 않는 관례다(컨벤션 "공통화는 세 번째 중복이 보이고 책임이 명확할 때만 검토"). `JournalCursor`는 세 번째 사례이지만, 지금 셋을 묶는 제네릭 커서로 추상화하면 이번 PR의 diff가 추가 위주가 아니게 되고 기존 두 값 객체의 리팩터링까지 끌고 들어온다 — 이번 범위(spec.md 범위 제외 "네 유스케이스의 공통 추상화를 지금 만들지 않는다"와 같은 판단)에서 하지 않는다.
- `JournalCursor.parse(String raw)`: `null`/빈 문자열은 `null`(첫 페이지). 구분자 없음·파싱 실패는 `BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.")`.
- `JournalCursor.encode(LocalDateTime createdAt, Long tradeId)`: 커서를 만들 항목이 `BuyTradeJournal`·`SellTradeJournal` 어느 쪽이든 될 수 있으므로, `TradeCursor.encode(Trade)`처럼 엔티티를 받지 않고 **값 두 개를 직접 받는다** — 병합된 마지막 항목에서 `journalType`에 따라 `buyTradeId` 또는 `sellTradeId` 중 채워진 쪽을 골라 넘긴다.

### 구성요소 설계 — 컨트롤러 배치 (spec.md가 plan.md로 넘긴 결정 ②)

**결정: 기존 `JournalController`(`@RequestMapping("/api/trades")`)를 재사용하지 않고, 같은 `journal` 패키지에 새 컨트롤러 `JournalListController`(`@RequestMapping("/api/journal")`)를 추가한다.**

근거: `order` 도메인이 `OrderController`(`/api/orders`)와 `TradeController`(`/api/trades`)를 같은 패키지 안에서 리소스 경로별로 분리한 선례가 이미 있다. `journal` 도메인도 같은 방식으로 쓰기 리소스(`/api/trades/{tradeId}/journal`·`/sell-journal`, 기존 `JournalController`)와 읽기 리소스(`/api/journal`, 신규 `JournalListController`)를 분리한다. 기존 `JournalController`에 클래스 레벨 매핑이 다른 메서드를 억지로 끼워 넣지 않는다(`@RequestMapping` 클래스 레벨과 무관한 `@GetMapping("/api/journal")` 절대경로를 기존 클래스에 추가하는 대안은, `TradeController`/`OrderController` 선례와 다른 새 패턴이라 배제한다).

```
com.finplay.api.journal
├── controller
│   ├── JournalController.java                    (기존, 변경 없음)
│   └── JournalListController.java                (신규 — GET /api/journal)
├── service
│   ├── JournalService.java                       (기존 — getMyJournalEntries 메서드 추가)
│   └── JournalCursor.java                        (신규 — 커서 파싱/인코딩 값 객체)
├── repository
│   ├── BuyTradeJournalRepository.java             (기존 — BuyTradeJournalRepositoryCustom 상속 추가)
│   ├── BuyTradeJournalRepositoryCustom.java       (신규)
│   ├── BuyTradeJournalRepositoryImpl.java         (신규 — QueryDSL)
│   ├── SellTradeJournalRepository.java            (기존 — SellTradeJournalRepositoryCustom 상속 추가)
│   ├── SellTradeJournalRepositoryCustom.java      (신규)
│   └── SellTradeJournalRepositoryImpl.java        (신규 — QueryDSL)
└── dto/response
    ├── JournalListResponse.java                   (신규)
    └── JournalListItemResponse.java               (신규)
```

#### `BuyTradeJournalRepositoryCustom` / `BuyTradeJournalRepositoryImpl`

`TradeRepositoryCustom`/`TradeRepositoryImpl`(`order/repository/`)과 완전히 같은 모양이다.

```java
public interface BuyTradeJournalRepositoryCustom {
    List<BuyTradeJournal> findByAccountIdWithCursor(
        Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize);
}
```

```java
public List<BuyTradeJournal> findByAccountIdWithCursor(
    Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize) {
    QBuyTradeJournal journal = QBuyTradeJournal.buyTradeJournal;

    BooleanBuilder condition = new BooleanBuilder(journal.buyTrade.account.id.eq(accountId));
    if (cursorCreatedAt != null && cursorTradeId != null) {
        condition.and(
            journal.createdAt.lt(cursorCreatedAt)
                .or(journal.createdAt.eq(cursorCreatedAt).and(journal.buyTrade.id.lt(cursorTradeId))));
    }

    return queryFactory
        .selectFrom(journal)
        .join(journal.buyTrade).fetchJoin()
        .where(condition)
        .orderBy(journal.createdAt.desc(), journal.buyTrade.id.desc())
        .limit(fetchSize)
        .fetch();
}
```

- `journal.buyTrade.account.id.eq(accountId)` 한 조건이 spec.md의 "`market` 필터와 소유권 검증을 같은 조건 하나로 처리"를 그대로 구현한다 — `accountId`는 이미 `AccountService.getAccountFor(userId, market)`로 시장·소유자가 모두 확정된 값이다.
- `.join(journal.buyTrade).fetchJoin()`은 `TradeRepositoryImpl`이 `.join(trade.instrument).fetchJoin()`을 쓰는 것과 같은 이유(N+1 방지) — 응답 DTO가 `buyTrade.getId()`를 읽는다.
- `SellTradeJournalRepositoryCustom`/`Impl`은 `sell_trade_id`/`sellTrade` 기준으로 완전히 대칭이다(필드명만 다르다) — 반복해서 적지 않는다.

#### `JournalService.getMyJournalEntries`

```java
@Transactional(readOnly = true)
public JournalListResponse getMyJournalEntries(Long userId, Market market, String cursor, int limit)
```

1. `Account account = accountService.getAccountFor(userId, market);` — 계좌가 없으면 404 `NOT_FOUND`(spec.md). `JournalService`가 `AccountService`를 새로 주입받는다.
2. `JournalCursor parsedCursor = JournalCursor.parse(cursor);` — 형식 오류면 400.
3. `List<BuyTradeJournal> buyPage = buyTradeJournalRepository.findByAccountIdWithCursor(account.getId(), createdAt, tradeId, limit + 1);`
4. `List<SellTradeJournal> sellPage = sellTradeJournalRepository.findByAccountIdWithCursor(account.getId(), createdAt, tradeId, limit + 1);` — 같은 커서 값을 두 리포지토리에 그대로 넘긴다(두 쿼리의 정렬 키가 같으므로 커서 의미가 동일하다).
5. 두 리스트를 `JournalListItemResponse`로 매핑(각각 `from(BuyTradeJournal)`/`from(SellTradeJournal)`)한 뒤 하나로 합치고, `(createdAt, 체결 ID)` 내림차순으로 정렬해 상위 `limit + 1`건만 남긴다. 체결 ID는 `journalType`에 따라 `buyTradeId` 또는 `sellTradeId` 중 null이 아닌 쪽이다.
6. `hasNext = merged.size() > limit;` → `limit`건으로 자른다. `hasNext`면 마지막 항목의 `(createdAt, 체결ID)`로 `JournalCursor.encode(...)`해 `nextCursor`를 만든다.
7. `return JournalListResponse.of(content, nextCursor, hasNext);`

- **트랜잭션은 `readOnly = true`다**(spec.md "조회는 읽기 전용"). 어떤 테이블에도 쓰지 않는다.
- 5단계의 정렬·슬라이스는 두 리스트(각 최대 `limit + 1`건, 기본 21건)를 합친 뒤 `Comparator.comparing(createdAt).reversed().thenComparing(tradeId, reverseOrder())`로 스트림 정렬한다 — 두 소스가 각각 이미 정렬돼 있어 병합 정렬(merge)로 더 최적화할 수도 있지만, 이 크기(최대 202건)에서는 단순 정렬로 충분하고 코드가 더 읽힌다.
- `TradeService.getOwnedTrade`처럼 order 도메인 메서드를 호출하지 않는다 — 이번 조회는 journal 자신의 테이블만 읽고, 계좌 조회는 이미 `AccountService`(account 도메인, journal이 지금까지도 간접적으로 의존해 온 `Trade → Account` 관계와 다르지 않은 층)를 거친다.

#### DTO 2개

- `JournalListResponse(List<JournalListItemResponse> content, String nextCursor, boolean hasNext)` — `TradeListResponse`와 완전히 같은 모양(컴팩트 생성자에서 `List.copyOf`), 정적 팩토리 `of(...)`.
- `JournalListItemResponse(String journalType, Long buyTradeId, Long sellTradeId, String content, LocalDateTime createdAt, LocalDateTime updatedAt)` — 정적 팩토리 오버로드 2개.

```java
public static JournalListItemResponse from(BuyTradeJournal journal) {
    return new JournalListItemResponse(
        "BUY", journal.getBuyTrade().getId(), null,
        journal.getContent(), journal.getCreatedAt(), journal.getUpdatedAt());
}

public static JournalListItemResponse from(SellTradeJournal journal) {
    return new JournalListItemResponse(
        "SELL", null, journal.getSellTrade().getId(),
        journal.getContent(), journal.getCreatedAt(), journal.getUpdatedAt());
}
```

- `journalType`은 `order.domain.OrderSide`를 재사용하지 않고 리터럴 문자열 `"BUY"`/`"SELL"`을 직접 쓴다(값은 같지만) — 투자일기의 "회고 종류"는 체결의 "매매 방향"과 개념적으로 다르고, `TradeListItemResponse.side`도 `OrderSide`를 그대로 노출하지 않고 `trade.getSide().name()`으로 문자열화하는 같은 선례를 따른다. journal DTO가 order 도메인 enum 타입에 직접 의존하지 않는다.
- 접미사는 컨벤션 표의 "목록 응답 / 목록 항목" 행(`~ListResponse`/`~ListItemResponse`, 예시가 정확히 `TradeListResponse`)을 그대로 따른다 — 이 항목은 `JournalListResponse` 하나에만 실리므로 "여러 응답이 공유하는 항목" 예외(컨벤션 DTO 규칙 문단)에 해당하지 않는다.

#### `JournalListController`

```java
@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class JournalListController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final JournalService journalService;

    @GetMapping
    public ResponseEntity<JournalListResponse> getMyJournalEntries(
        @AuthenticationPrincipal AuthenticatedUser principal,
        @RequestParam Market market,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        validateLimit(limit);
        return ResponseEntity.ok(journalService.getMyJournalEntries(principal.userId(), market, cursor, limit));
    }

    private void validateLimit(int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~100 사이여야 합니다.");
        }
    }
}
```

- `TradeController.getMyTrades`를 그대로 복제한 형태다 — 새 검증 패턴을 만들지 않는다.
- 비즈니스 판단·repository 호출·try-catch를 두지 않는다(컨벤션 레이어 규칙).

#### 기존 코드 변경 범위

- **기존 `JournalController`(작성·수정 4개 엔드포인트)를 전혀 건드리지 않는다.**
- **`BuyTradeJournal`·`SellTradeJournal` 엔티티를 변경하지 않는다.** 이번 조회에 필요한 필드(`content`·`createdAt`·`updatedAt`·연관 `Trade`)는 이미 있다.
- **`TradeService`·`AccountService`를 변경하지 않는다.** `AccountService.getAccountFor`를 그대로 호출만 한다.
- **신규 Flyway 마이그레이션이 없다** — 기존 두 테이블을 읽기만 한다(spec.md).

### 테스트 계획 (ADR-0003)

- **단위** (`JournalCursorTest`)
  - `parse`: 정상 문자열 → 필드 2개, `null`/빈 문자열 → `null`, 구분자 없음·`LocalDateTime`/`Long` 파싱 실패 → 400 `VALIDATION_ERROR`.
  - `encode`: `{ISO_LOCAL_DATE_TIME}_{id}` 형식으로 정확히 조립.
- **단위** (`JournalServiceTest`, Mockito — 기존 파일에 메서드 추가)
  - 계좌 없음 → 404. `AccountService.getAccountFor`가 던지는 예외를 그대로 전파하는지.
  - 매수·매도 항목이 섞였을 때 `(createdAt, 체결 ID)` 내림차순 병합이 맞는지 — 두 리포지토리 mock에 각각 픽스처를 주고 병합 결과 순서를 검증.
  - **동시각 tie-break** — 매수·매도 항목이 같은 `createdAt`일 때 체결 ID가 더 큰 쪽이 먼저 오는지.
  - `limit + 1`건 초과 시 `hasNext=true`·`nextCursor`가 마지막 항목의 `(createdAt, 체결ID)`로 인코딩되는지, 이하일 때 `hasNext=false`·`nextCursor=null`.
  - 빈 목록(양쪽 리포지토리 모두 빈 리스트) → `content: []`·`hasNext=false`.
  - `buyTradeJournalRepository`·`sellTradeJournalRepository`에 넘기는 커서 인자가 두 호출에서 동일한지(같은 페이지 경계를 공유해야 한다).
- **슬라이스**
  - `@DataJpaTest`(`BuyTradeJournalRepositoryTest`·`SellTradeJournalRepositoryTest`, 기존 파일에 케이스 추가) — `findByAccountIdWithCursor`: ① 다른 계좌의 회고가 섞이지 않음 ② 커서보다 이전 항목만 반환 ③ `createdAt` 동일 시 체결 ID 내림차순 ④ `fetchSize`만큼만 반환.
  - `@WebMvcTest`(`JournalListControllerTest`, 신규 파일) — 200 응답 `jsonPath`(6필드, `journalId` 필드 **부재** 확인), `market` 누락·미지원 리터럴 400, `limit` 0·101 400, `cursor` 파싱 실패 400, 미인증 401, 계좌 없음(서비스 404 매핑) 404.
- **통합** (`@SpringBootTest` + Testcontainers, 기존 `JournalIntegrationTest`에 추가하거나 신규 `JournalListIntegrationTest`)
  - 매수·매도 회고를 섞어 작성한 뒤 목록 조회 → `createdAt` 내림차순, `journalType`·해당 없는 체결 ID `null` 확인.
  - **`market` 필터** — 같은 사용자의 `CRYPTO` 계좌 회고가 `market=STOCK` 결과에 섞이지 않음.
  - **커서 페이지네이션** — 첫 페이지 `nextCursor`로 다음 페이지 이어받기, 중복·누락 없음. **매수·매도 회고가 같은 `createdAt`으로 경계에 걸치는 픽스처**를 포함해 체결 ID tie-break가 실제로 페이지 경계를 가르는지 확인.
  - `limit` 0·101 400, `market` 누락·미지원 리터럴 400, `cursor` 파싱 실패 400, 미인증 401.
  - 다른 사용자의 회고가 섞이지 않음.
  - 회고가 하나도 없는 사용자 → 200 빈 목록.
  - **`journalId` 미노출 계약** — 응답 JSON에 `journalId` 키 자체가 없음을 확인(문자열 매칭 또는 `jsonPath("$.content[0].journalId").doesNotExist()`).
  - **원장·투자일기 불변** — 조회 전후 `buy_trade_journals`·`sell_trade_journals`·`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 전혀 변하지 않음.
  - 기존 4개 계약(`POST`·`PATCH .../journal`, `POST`·`PATCH .../sell-journal`) 기존 테스트가 그대로 통과.

### 문서 갱신 (CLAUDE.md 규칙 7)

- `ai/api-routes.md` — `journal` 도메인에 `GET /api/journal` 행 추가 (근거 열: `007 JOUR-006, Issue #203`).
- `docs/api-contracts.md` `## journal` 절 — 기존 4개 계약 아래에 "투자일기 목록 조회" 소절을 추가해 요청(쿼리 파라미터 3개)·응답(wrapper 3필드 + 항목 6필드)·오류(400×3·401·404·200 빈 목록) 계약을 적는다. `journalId` 미노출과 `market`+소유권 결합 조건을 본문에 한 줄로 요약한다.
- 컨트롤러 변경과 **같은 커밋**에서 갱신한다. 이 갱신은 planner의 동기화 모드가 실제 controller 코드를 보고 확정하며, 여기 적은 문구는 설계 의도이지 최종 표현이 아니다.

## JOUR-005 투자일기 상세 조회 설계 (이슈 #217, 이번 착수)

> 요구사항·비즈니스 규칙·완료 조건의 정본은 `./spec.md` JOUR-005 절이다. 특히 **경로를 타입별로 분리한다는 결정과 그 근거 3가지, 검증 순서, 응답 5필드, 읽기 전용·스키마 무변경**은 `spec.md`가 이미 확정했으므로 여기서 재논의하지 않는다. 이 절은 `spec.md`가 `plan.md`로 넘긴 세 가지(① 컨트롤러 배치 ② 응답 DTO ③ 서비스 메서드 구성)를 확정한다.
>
> **이 절은 §JOUR-002·§JOUR-004 수정 설계의 "읽기 판(版)"이다.** 트랜잭션 경계·`TradeService` 재사용·오류 본문 공통 포맷·원장 읽기 전용 등 같은 항목은 반복하지 않고 **다른 점만** 적는다. 다른 점은 실질적으로 넷이다 — ① 메서드가 GET이고 요청 본문이 없다 ② 경로가 `/api/journal/buy|sell/{tradeId}`다 ③ 엔티티를 수정하지 않는다(`updateContent` 호출 없음, `readOnly = true`) ④ **매수·매도 두 엔드포인트를 한 번에 연다.**

### API 설계

| Method | URL | 요청 | 성공 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/journal/buy/{buyTradeId}` | 경로 변수만 (본문 없음) | **200** `BuyJournalDetailResponse` | 본인 소유 매수 체결에 달린 매수 회고 1건 조회 |
| GET | `/api/journal/sell/{sellTradeId}` | 경로 변수만 (본문 없음) | **200** `SellJournalDetailResponse` | 본인 소유 매도 체결에 달린 매도 회고 1건 조회 |

- 인증: Access Bearer 필수. 조회자는 경로·쿼리가 아니라 Access Token의 인증 사용자(`AuthenticatedUser#userId`)로 결정한다.
- URL에 버전 프리픽스를 쓰지 않는다(컨벤션). 경로 변수명은 작성·수정 계약과 같은 `buyTradeId`·`sellTradeId`다.
- **`/api/journal` 하위에 두는 이유**: 목록(`GET /api/journal`)과 같은 읽기 리소스 트리이며, `GET /api/journal/buy/{id}`는 목록의 항목을 가리키는 자연스러운 하위 경로다. 쓰기 계약 4개가 `/api/trades/{tradeId}/journal`에 있는 것과 경로 계열이 다르지만, 그 4개는 "체결에 회고를 붙이는" 조작이라 체결 하위인 게 맞고 조회는 회고 자체가 리소스다. **기존 4개 경로를 옮기지 않는다**(이미 배포된 계약이다).
- **`/api/journal/{type}/{tradeId}`에서 `type`을 경로 변수(`@PathVariable String type`)로 받지 않는다.** 리터럴 두 개(`buy`·`sell`)를 각각 매핑해 잘못된 타입 문자열이 Spring 라우팅 단계에서 404가 되게 한다 — 컨트롤러 안에서 `type`을 문자열 비교해 분기하면 컨벤션의 "컨트롤러에 비즈니스 판단을 두지 않는다"에 걸린다.

#### 응답 예시 (200, 매수)

```json
{ "journalId": 1, "buyTradeId": 12, "content": "실적 발표 전 분할 매수. 5% 빠지면 손절 계획.", "createdAt": "2026-08-04T10:12:33", "updatedAt": "2026-08-05T09:41:07" }
```

#### 응답 예시 (200, 매도)

```json
{ "journalId": 1, "sellTradeId": 34, "content": "목표가 도달해서 전량 매도.", "createdAt": "2026-08-04T15:20:41", "updatedAt": "2026-08-04T15:20:41" }
```

두 응답의 `journalId`가 **둘 다 `1`일 수 있다** — 서로 다른 테이블의 시퀀스이기 때문이며, 이것이 단일 경로를 배제한 이유 그 자체다(`spec.md`). 경로가 타입을 확정하므로 응답 안에서는 값이 겹쳐도 해석이 흔들리지 않는다.

### 입력 명세

| 필드 | 위치 | 필수 | 검증 |
|---|---|---|---|
| `buyTradeId` / `sellTradeId` | path | 필수 | 숫자(`Long`). 파싱 불가 400 `VALIDATION_ERROR`(전역 핸들러의 `MethodArgumentTypeMismatchException` 경로), 없으면 404 `NOT_FOUND`, 타인 소유면 403 `FORBIDDEN`, `side`가 경로와 어긋나면 400 `VALIDATION_ERROR`, 회고가 아직 없으면 404 `NOT_FOUND` |

- 요청 본문·쿼리 파라미터가 **없다.** 수정 계약과 달리 `@Valid` 본문 검증 단계가 없으므로 "본문 검증이 경로 검증보다 먼저"라는 순서 이슈도 존재하지 않는다.

### 오류 매핑

| 상황 | 상태 | 코드 |
|---|---|---|
| 경로 변수 타입 불일치(숫자 파싱 실패) | 400 | `VALIDATION_ERROR` |
| 대상 체결의 `side`가 경로와 어긋남(매수 경로에 매도 체결, 매도 경로에 매수 체결) | 400 | `VALIDATION_ERROR` |
| Access 인증 실패·미첨부 | 401 | `UNAUTHORIZED` |
| 타인 소유 체결 | 403 | `FORBIDDEN` |
| 경로 변수에 해당하는 체결 없음 | 404 | `NOT_FOUND` |
| 체결은 있으나 그 체결에 회고가 아직 없음 | 404 | `NOT_FOUND` |

**검증 순서는 `체결 존재(404) → 소유(403) → 체결 구분(400) → 회고 존재(404)`로 고정한다** (spec.md). 수정 계약(JOUR-002·004)과 **정확히 같은 순서**이며, 마지막 단계에서 엔티티를 수정하지 않고 그대로 매핑해 반환하는 것만 다르다. **409는 이 계약에 없다** — 새 행을 만들지 않는다. **새 `ErrorCode` 상수를 추가하지 않는다**(5개 모두 기존 enum에 있다).

### 데이터 모델

**변경 없음.** 신규 Flyway 마이그레이션·엔티티 변경·리포지토리 메서드 추가가 **모두 없다.**

- 조회에 필요한 `findByBuyTradeId`·`findBySellTradeId`(단건 `Optional`)는 JOUR-002·004가 수정용으로 이미 추가했고, 유니크 제약(`uk_buy_trade_journals_buy_trade`·`uk_sell_trade_journals_sell_trade`)이 결과 최대 1건을 스키마로 보장한다 — 그대로 재사용한다.
- 응답에 필요한 필드(`id`·`content`·`createdAt`·`updatedAt`·연관 `Trade`)도 이미 전부 있다.
- **`BuyTradeJournal`·`SellTradeJournal` 엔티티를 변경하지 않는다.** 조회 전용 메서드를 엔티티에 추가하지 않는다.
- 지연 로딩: `journal.getBuyTrade().getId()`는 프록시의 식별자 접근이라 추가 쿼리를 유발하지 않는다 — `updateBuyJournal`이 같은 방식으로 응답을 만들고 있어 새로 검증할 것이 없다. 이번 조회는 단건이라 N+1도 없다(목록의 `fetchJoin`은 여기 필요 없다).

### 구성요소 설계 — 컨트롤러 배치 (spec.md가 plan.md로 넘긴 결정 ①)

**결정: 같은 `journal` 패키지에 새 컨트롤러 `JournalDetailController`(`@RequestMapping("/api/journal")`)를 추가한다. `JournalListController`에 메서드를 더하지 않는다.**

근거는 둘이다.

1. **이 코드베이스는 같은 base path를 여러 컨트롤러가 나눠 갖는 것을 이미 허용한다.** `/api/instruments`는 컨트롤러 3개, `/api/trades`(`TradeController`·`JournalController`)와 `/api/auth/oauth`는 각각 2개가 같은 클래스 레벨 매핑을 쓴다. Spring도 서로 다른 메서드+패턴이면 충돌하지 않는다(`GET /api/journal` vs `GET /api/journal/buy/{id}`).
2. **`JournalListController`는 이름 그대로 목록 전용이고, 이미 배포된 클래스다.** 상세 메서드를 끼워 넣으면 클래스 이름과 내용이 어긋나고(이름을 바꾸면 그건 그것대로 기존 클래스 변경이다), `DEFAULT_LIMIT`·`validateLimit` 같은 목록 전용 상수·검증과 상세 로직이 한 파일에 섞인다. **이번 PR의 diff를 추가 위주로 두는 원칙**(spec.md §범위 제외)에도 새 파일 쪽이 맞다.

```
com.finplay.api.journal
├── controller
│   ├── JournalController.java                    (기존, 변경 없음 — 작성·수정 4개)
│   ├── JournalListController.java                (기존, 변경 없음 — GET /api/journal)
│   └── JournalDetailController.java              (신규 — GET /api/journal/buy|sell/{tradeId})
├── service
│   └── JournalService.java                       (기존 — getBuyJournal·getSellJournal 2개 추가)
├── repository                                    (변경 없음)
└── dto/response
    ├── BuyJournalDetailResponse.java             (신규)
    └── SellJournalDetailResponse.java            (신규)
```

#### `JournalDetailController`

```java
@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class JournalDetailController {

    private final JournalService journalService;

    @GetMapping("/buy/{buyTradeId}")
    public ResponseEntity<BuyJournalDetailResponse> getBuyJournal(
        @AuthenticationPrincipal AuthenticatedUser principal,
        @PathVariable Long buyTradeId) {
        return ResponseEntity.ok(journalService.getBuyJournal(principal.userId(), buyTradeId));
    }

    @GetMapping("/sell/{sellTradeId}")
    public ResponseEntity<SellJournalDetailResponse> getSellJournal(
        @AuthenticationPrincipal AuthenticatedUser principal,
        @PathVariable Long sellTradeId) {
        return ResponseEntity.ok(journalService.getSellJournal(principal.userId(), sellTradeId));
    }
}
```

- 비즈니스 판단·repository 호출·try-catch를 두지 않는다(컨벤션 레이어 규칙). `buy`/`sell`은 리터럴 경로이며 컨트롤러가 타입을 분기하지 않는다.

### 응답 DTO (spec.md가 plan.md로 넘긴 결정 ②)

**결정: 새 record `BuyJournalDetailResponse`·`SellJournalDetailResponse`를 만든다. 기존 `~UpdateResponse`를 GET 응답으로 재사용하지 않는다.**

- 필드 구성은 `BuyJournalUpdateResponse`·`SellJournalUpdateResponse`와 **똑같이 5개**다(`journalId`·체결 ID·`content`·`createdAt`·`updatedAt`) — spec.md가 정한 계약이다. JSON 모양이 같으므로 프론트엔드는 타입 하나를 재사용할 수 있다.
- **그럼에도 타입을 나누는 이유**: 이 코드베이스는 유스케이스마다 응답 record를 따로 두는 관례가 이미 확립돼 있다(작성 4필드 `BuyJournalResponse` ↔ 수정 5필드 `BuyJournalUpdateResponse`를 일부러 분리했다 — `plan.md` §"응답 DTO를 분리하는 이유"). GET 응답 타입 이름이 `~UpdateResponse`면 계약 문서와 컨트롤러 시그니처가 "수정"이라고 읽힌다. 또 앞으로 상세에만 필드가 붙을 때(예: 종목 요약) 수정 응답이 딸려 바뀌는 결합이 생긴다.
- **알려진 트레이드오프**: 필드가 같은 record가 4개(작성·수정·상세 × 매수·매도 중 상세 2개 추가)로 늘어난다. 지금은 `from(엔티티)` 정적 팩토리 한 줄씩이라 비용이 작고, 통합 추상화는 spec.md §범위 제외가 별도 리팩터링 PR로 미뤘다.

```java
public record BuyJournalDetailResponse(
    Long journalId, Long buyTradeId, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static BuyJournalDetailResponse from(BuyTradeJournal journal) { ... }
}
```

`SellJournalDetailResponse`는 `sellTradeId`·`getSellTrade()` 기준으로 완전히 대칭이다.

### `JournalService` — 조회 유스케이스 2개 (spec.md가 plan.md로 넘긴 결정 ③)

```java
@Transactional(readOnly = true)
public BuyJournalDetailResponse getBuyJournal(Long userId, Long buyTradeId)

@Transactional(readOnly = true)
public SellJournalDetailResponse getSellJournal(Long userId, Long sellTradeId)
```

매수 기준 절차(매도는 `SELL`·`findBySellTradeId`로 대칭):

1. `Trade trade = tradeService.getOwnedTrade(userId, buyTradeId);` — 없으면 404 `NOT_FOUND`, 타인 소유면 403 `FORBIDDEN`. **`TradeService`는 변경하지 않는다**(네 번째 재사용이다).
2. `trade.getSide() != OrderSide.BUY`면 400 `VALIDATION_ERROR`.
3. `BuyTradeJournal journal = buyTradeJournalRepository.findByBuyTradeId(buyTradeId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));`
4. `return BuyJournalDetailResponse.from(journal);`

- **`updateBuyJournal`의 1~3단계와 완전히 같고 4단계만 다르다**(`updateContent` 호출 없음). 리뷰에서 이 대칭이 깨졌다면 조회 경로에 쓰기가 새어 들어온 것이다.
- **`@Transactional(readOnly = true)`가 이 설계의 안전장치다** — 실수로 엔티티를 변경해도 dirty checking이 flush되지 않는다. `getMyJournalEntries`가 이미 같은 방식이다.
- **두 메서드를 제네릭 헬퍼로 묶지 않는다.** 엔티티·리포지토리·`OrderSide` 조건·응답 타입이 각각 달라 묶으면 타입 파라미터 4개짜리 헬퍼가 되고, 이 spec의 다른 다섯 유스케이스가 이미 같은 이유로 대칭 중복을 유지하고 있다(spec.md §범위 제외).
- `Clock`을 쓰지 않는다 — 시각을 만들지 않고 저장된 값을 그대로 읽는다.

#### 기존 코드 변경 범위

- **`JournalController`·`JournalListController`를 전혀 건드리지 않는다.**
- **`BuyTradeJournal`·`SellTradeJournal`·두 리포지토리·`TradeService`·`AccountService`를 변경하지 않는다.**
- **목록 응답 DTO(`JournalListResponse`·`JournalListItemResponse`)를 변경하지 않는다** — 상세 링크용 필드를 더하지 않는다(spec.md §범위 제외).
- **신규 Flyway 마이그레이션이 없다.**

### 테스트 계획 (ADR-0003)

- **단위** (`JournalServiceTest`, Mockito — 기존 파일에 추가)
  - 정상 조회: 반환 DTO 5필드가 엔티티 값과 1:1이고, `updateContent`·`save`가 **호출되지 않음**을 검증(읽기 전용).
  - 매수·매도 각각 404(체결 없음)·403·400(반대 side)·404(회고 없음) 네 경로.
  - **검증 순서**: 타인 소유의 반대 side 체결이 403이고 400이 아니며, 회고가 없어도 소유·체결 구분 판정이 먼저다.
  - **체결 없음 404와 회고 없음 404를 별도 테스트로 구분**한다(같은 `ErrorCode`지만 트리거 지점이 달라 회귀 시 어느 단계가 깨졌는지 알 수 있어야 한다).
- **슬라이스**
  - `@WebMvcTest`(`JournalDetailControllerTest`, 신규) — 두 경로 각각 200 본문 `jsonPath` 5필드, 숫자 아닌 경로 변수 400, 미인증 401, 서비스 예외의 400·403·404 매핑(**409 케이스 없음**), 매수 응답에 `sellTradeId` 키가 없고 매도 응답에 `buyTradeId` 키가 없음.
  - `@DataJpaTest` **추가 없음** — 이번 착수는 쿼리·제약을 새로 만들지 않는다. 기존 `BuyTradeJournalRepositoryTest`·`SellTradeJournalRepositoryTest`가 `findBy...TradeId`를 이미 덮는다.
- **통합** (`@SpringBootTest` + Testcontainers, 기존 `JournalIntegrationTest`에 추가하거나 신규 `JournalDetailIntegrationTest`)
  - 매수 체결 → 회고 작성 → `GET /api/journal/buy/{buyTradeId}` 200, 응답이 작성 응답과 같은 값(+`updatedAt`).
  - 매도 체결 → 회고 작성 → `GET /api/journal/sell/{sellTradeId}` 200, 같은 기준.
  - **PK 충돌 픽스처 (이 착수의 핵심 회귀)** — 매수 회고와 매도 회고를 각각 1건씩 만들어 `buy_trade_journals.id`와 `sell_trade_journals.id`가 **같은 값**이 되게 한 뒤, 두 경로가 각각 자기 테이블의 본문을 반환하는지 확인한다. 단일 경로 설계였다면 구분할 수 없었던 상황을 고정하는 테스트다.
  - **수정 반영** — `PATCH`로 본문을 고친 뒤 상세를 조회하면 갱신된 `content`와 `updatedAt`이 보인다.
  - 없는 체결 404 · **회고 미작성 404** · 타인 소유 403 · **경로 교차 400**(매수 경로에 매도 체결 ID, 매도 경로에 매수 체결 ID) · 미인증 401.
  - **읽기 전용** — 조회 전후 `buy_trade_journals`·`sell_trade_journals`(행 수와 `updated_at` 포함)·`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 전혀 변하지 않는다.
  - 기존 5개 계약(`POST`·`PATCH .../journal`, `POST`·`PATCH .../sell-journal`, `GET /api/journal`)의 기존 테스트가 그대로 통과한다.

### 문서 갱신 (CLAUDE.md 규칙 7·10)

- `ai/api-routes.md` — `journal` 도메인에 `GET /api/journal/buy/{buyTradeId}`·`GET /api/journal/sell/{sellTradeId}` 2행 추가 (근거 열: `007 JOUR-005, Issue #217`).
- `docs/api-contracts.md` `## journal` 절 — 목록 조회 소절 아래에 "투자일기 상세 조회(매수·매도)" 소절을 추가한다. 경로 분리 이유 한 줄, 검증 순서, 응답 5필드가 수정 응답과 동일하다는 점, 409·본문 없음을 적는다.
- `ai/prd.md` — JOUR-005 절의 Decision Gate 문구를 경로 분리 결정으로 교체하고(문서 커밋에서 선행), §3 "구현 현황"의 투자일기 조회 행을 **구현 커밋에서** 완료로 갱신한다(규칙 10).
- API 문서 2개는 컨트롤러 변경과 **같은 커밋**에서 갱신한다. 이 갱신은 planner의 동기화 모드가 실제 controller 코드를 보고 확정하며, 여기 적은 문구는 설계 의도이지 최종 표현이 아니다.

## 이 spec에서 하지 않는 것

`./spec.md` §범위 제외가 정본이다. 특히 **JOUR-002(수정)·JOUR-005(상세)는 PRD가 Decision Gate 미해결로 표시**했으므로, 그 계약을 미리 반영한 컬럼·필드·URL을 이번 구현에 넣지 않는다. 매도 회고 수정(JOUR-004)은 이번 이슈 다음의 별도 이슈이며, 그 때문에 `sell_trade_journals`에 `updated_at`을 미리 만들지 않는다.

> **2026-08-04 갱신**: 위 문단은 JOUR-003 착수 시점(이슈 #183)의 기록이며 그 시점 기준으로는 여전히 맞다("다음 별도 이슈" = 지금 이 JOUR-004). JOUR-004는 이제 이번 spec의 착수 범위이고, 실제 설계는 위 §JOUR-004 매도 회고 수정 설계를 따른다. 매수 회고 수정(JOUR-002)·투자일기 상세(JOUR-005)는 여전히 Decision Gate 미해결로 범위 밖이다.
>
> **2026-08-04 재갱신 (이슈 #197)**: **JOUR-002의 Decision Gate는 해제됐다** — 잠금을 두지 않기로 확정했고(`./spec.md` §비즈니스 규칙 "매수 회고 수정 잠금 없음", `ai/prd.md` JOUR-002), 설계는 위 §JOUR-002 매수 회고 수정 설계를 따른다. 따라서 `buy_trade_journals`의 `updated_at`을 이번 착수에서 추가한다. **투자일기 상세(JOUR-005)의 식별자 체계 게이트만 남았고**, 목록(JOUR-006)과 함께 여전히 범위 밖이다.
>
> **2026-08-04 재갱신 (이슈 #203)**: **목록 조회(JOUR-006)는 이제 이번 spec의 착수 범위**이고, 실제 설계는 위 §JOUR-006 투자일기 목록 조회 설계를 따른다. JOUR-006은 식별자 게이트와 무관하게 착수했다(spec.md §비즈니스 규칙 "JOUR-005 식별자 게이트를 선점하지 않는다"). **투자일기 상세(JOUR-005)의 식별자 체계 게이트만 여전히 미해결이며 범위 밖이다.**
>
> **2026-08-05 재갱신 (이슈 #217)**: **JOUR-005의 식별자 체계 게이트가 해제됐다** — 타입별 경로 분리(`GET /api/journal/buy|sell/{tradeId}`)로 확정했고(spec.md §비즈니스 규칙 "상세 조회는 타입별 경로로 분리한다", `ai/prd.md` JOUR-005), 설계는 위 §JOUR-005 투자일기 상세 조회 설계를 따른다. **이로써 이 spec의 여섯 요구사항(JOUR-001~006)에 남은 Decision Gate가 없다.** 두 회고 테이블 통합·통합 `journalId`는 이 결정으로 영구히 배제됐다.
