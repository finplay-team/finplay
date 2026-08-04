# Plan: 매수 체결별 투자일기 작성 (JOUR-001)

> 범위는 **엔드포인트 1개**다. 이슈 [#159](https://github.com/finplay-team/finplay/issues/159) = PRD `JOUR-001`.
> 요구사항·규칙의 정본은 `./spec.md`이고 PRD가 그 상위다. 여기서 새 규칙을 만들지 않는다.

## 관련 문서

| 문서 | 담는 것 |
|---|---|
| `./spec.md` | 요구사항·비즈니스 규칙·완료 조건 (정본) |
| `./tasks.md` | 커밋 단위 작업 분해 |
| `docs/prd.md` JOUR-001 | 상위 요구사항. JOUR-002·005의 Decision Gate 서술도 여기 |
| `docs/api-routes.md` · `docs/api-contracts.md` | **아직 미반영.** 이 spec의 마지막 작업에서 같은 커밋으로 반영한다 (CLAUDE.md 규칙 7) |
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

**마이그레이션 파일: `V14__create_buy_trade_journals.sql`.** 조사 시점(2026-08-04) `dev`의 최신 버전이 `V13__create_ai_feedback_tables.sql`이므로 다음 번호는 14다. **착수 시점에 `src/main/resources/db/migration/`을 다시 확인해** 그 사이 병합된 파일이 있으면 그 다음 번호로 올린다(ADR-0004 — 머지된 파일은 절대 수정하지 않는다).

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

## 이 spec에서 하지 않는 것

`./spec.md` §범위 제외가 정본이다. 특히 **JOUR-002(수정)·JOUR-005(상세)는 PRD가 Decision Gate 미해결로 표시**했으므로, 그 계약을 미리 반영한 컬럼·필드·URL을 이번 구현에 넣지 않는다.
