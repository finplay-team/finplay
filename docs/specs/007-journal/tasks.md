# Tasks: 체결별 투자일기 작성·수정·조회 (JOUR-001 · JOUR-003 · JOUR-004 · JOUR-002 · JOUR-006 · JOUR-005)

> 항목 하나 = implementer 1회 투입 = 커밋 1개. 값·규칙은 `./spec.md`, 설계는 `./plan.md`가 정본이다 — 여기에 값을 다시 정의하지 않는다.
> 테스트 레벨은 ADR-0003을 따른다.
>
> | 절 | 범위 | 이슈 | 상태 |
> |---|---|---|---|
> | §JOUR-001 매수 회고 작성 | 항목 1~6 | [#159](https://github.com/finplay-team/finplay/issues/159) | 완료 (기록 보존용) |
> | §JOUR-003 매도 회고 작성 | 항목 S1~S4 | [#183](https://github.com/finplay-team/finplay/issues/183) | 완료 (기록 보존용) |
> | §JOUR-004 매도 회고 수정 | 항목 U1~U4 | [#190](https://github.com/finplay-team/finplay/issues/190) | 완료 (기록 보존용) |
> | §JOUR-002 매수 회고 수정 | 항목 B0~B4 | [#197](https://github.com/finplay-team/finplay/issues/197) | 완료 (기록 보존용) |
> | §JOUR-006 투자일기 목록 조회 | 항목 L1~L5 | [#203](https://github.com/finplay-team/finplay/issues/203) | 완료 (기록 보존용) |
> | **§JOUR-005 투자일기 상세 조회** | 항목 D0~D4 | [#217](https://github.com/finplay-team/finplay/issues/217) | **이번 착수** |

# JOUR-001 매수 회고 작성 (이슈 #159, 완료)

## 이 이슈 전체에 걸리는 제약

- **원장을 건드리지 않는다.** 마이그레이션에 기존 원장 테이블(`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`)에 대한 `ALTER`·`DROP`이 없고, 코드는 `trades`를 읽기만 한다.
- **마이그레이션은 신규 번호 파일 1개**다 (ADR-0004). 착수 시점 `dev`의 최신 번호를 확인해 그 다음 번호를 쓰고, 머지된 파일은 수정하지 않는다.
- **journal이 `TradeRepository`를 직접 주입하지 않는다** (ADR-0002). 체결 조회·소유권 판정은 `TradeService` 경유다.
- **범위 밖 계약을 미리 만들지 않는다** — 수정(JOUR-002)·상세(JOUR-005)는 Decision Gate 미해결이므로 `updated_at`·`journalId` 조회 URL·목록 쿼리를 넣지 않는다.
- 새 `ErrorCode` 상수를 추가하지 않는다. 필요한 6개는 이미 있다.

## 작업 항목

- [x] **1. 신규 마이그레이션 + `BuyTradeJournal` 엔티티 + 리포지토리**

  `plan.md` §데이터 모델의 DDL 그대로 `buy_trade_journals`를 만들고, 엔티티·리포지토리를 붙인다. 컬럼·유니크·FK는 그 절이 정본이다.
  - 유니크 제약과 FK가 이 기능의 방어선이다 — 이름·대상(`trades(id)`)을 V10 선례에 맞춘다.
  - 검증 — `@DataJpaTest`: 같은 `buy_trade_id` 2건째 유니크 위반, 서로 다른 체결 2건 공존, `existsByBuyTradeId` false→true, 없는 체결 참조 시 FK 위반.
  - 검증 — `./gradlew test`로 기존 `@SpringBootTest`가 `ddl-auto=validate`를 통과하는지 본다.

- [x] **2. `TradeService.getOwnedTrade` — 체결 조회·소유권 판정**

  order 도메인에 본인 체결 조회 메서드를 추가한다(없으면 404, 타인이면 403). journal은 이것만 호출한다.
  - `side` 판정을 여기 넣지 않는다 — 투자일기 쪽 규칙이고 JOUR-003이 반대 조건으로 재사용한다.
  - `Trade → Account → User` 2단 지연 로딩이 판정 결과를 바꾸지 않게 한다.
  - 검증 — 단위 테스트 3건(본인·미존재·타인). 기존 `TradeService` 테스트가 그대로 통과하는지 함께 본다.

- [x] **3. `JournalService` — 작성 유스케이스**

  `plan.md` §구성요소 설계의 1~5단계를 구현한다. 검증 순서 `존재 → 소유 → 매수 여부 → 중복`과 트랜잭션 경계가 이 항목의 핵심이다.
  - 선제 조회와 유니크 위반 변환을 **둘 다** 둔다. 제약 위반을 잡은 뒤에는 추가 DB 작업 없이 즉시 409를 던진다.
  - 검증 — 단위 테스트: 정상 작성(고정 `Clock` 시각·저장 인자), 404·403·400·409 각 경로, **타인의 매도 체결이 403**(400 아님), `DataIntegrityViolationException` → 409 변환.

- [x] **4. `JournalController` + 요청·응답 DTO**

  `POST /api/trades/{buyTradeId}/journal`을 열고 record DTO 2개를 만든다. 응답 필드는 4개 고정이다.
  - 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다.
  - 검증 — `@WebMvcTest`: 201 본문 `jsonPath` 4필드, 공백·누락·상한 초과 본문 400, 숫자 아닌 `buyTradeId` 400, 미인증 401, 서비스 예외의 403·404·409 매핑.

- [x] **5. 통합 테스트 — 중복·거부·원장 불변**

  Testcontainers `@SpringBootTest`로 spec의 완료 조건 4건을 한 번에 확인한다.
  - 본인 매수 체결 작성 201.
  - **순차 중복**과 **동시 중복** 모두 정확히 1건만 성공하고 나머지는 409, 최종 행 수 1.
  - 없는 체결 404 · 매도 체결 400 · 타인 체결 403 · 공백 본문 400.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`(현금·실현손익)·`holdings`·`holding_lots`·`trade_allocations`가 동일하다.

- [x] **6. 문서 갱신 + 빌드**

  `docs/api-routes.md` 라우트 목록에 신규 행을, `docs/api-contracts.md` `## order` 절(또는 신설 `## journal` 절)에 요청·응답·오류 계약을 추가한다 (CLAUDE.md 규칙 7 — 원래는 컨트롤러 커밋과 같은 커밋이 원칙이므로, 4번에서 함께 넣었다면 이 항목은 누락분 확인으로 끝낸다).
  - `plan.md` §관련 문서의 "아직 미반영" 표기를 반영 완료로 바꾼다.
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 확인한다.

## 완료 조건 매핑 (이슈 #159)

| 이슈 완료 조건 | 항목 |
|---|---|
| 본인 매수 체결에 투자일기 작성 통합 테스트 | **5** |
| 순차·동시 중복이 한 건만 성공하고 나머지 409 | **5** (제약은 **1**, 변환 로직은 **3**) |
| 없는 체결·매도가 아닌 체결·타인 체결·공백 본문 거부 | **5** (단위 **3**, 계약 **4**) |
| 성공·실패 전후 원장 불변 | **5** (마이그레이션에 원장 `ALTER` 없음은 **1**) |
| 신규 Flyway 마이그레이션으로 투자일기 테이블 생성 (ADR-0004) | **1** |
| `docs/api-routes.md`·`docs/api-contracts.md` 반영 | **6** (컨트롤러 커밋 **4**와 같은 커밋이면 거기서) |
| `spec.md` 상태 서술 갱신 | **완료** — 2026-08-04 planner가 `spec.md` 머리말에서 반영 |
| `./gradlew build` 통과 | **6** |

## 이 이슈에서 하지 않는 것

- 매수 회고 **수정**(JOUR-002) — 잠금 기준 Decision Gate 미해결. `updated_at` 컬럼도 만들지 않는다.
- 매도 회고 작성·수정(JOUR-003·004)과 API `POST /api/trades/{id}/sell-journal`. → JOUR-003는 이슈 #183에서 착수한다(아래 절).
- 투자일기 **상세**(JOUR-005, 식별자 체계 Decision Gate 미해결)·**목록**(JOUR-006) 조회와 그 전용 인덱스·QueryDSL 쿼리.
- 목표가·손절가·예상보유기간 등 구조화 필드.
- 투자일기 기반 AI 피드백·복기 (spec 012 범위).

---

# JOUR-003 매도 회고 작성 (이슈 #183, 이번 착수)

> 설계 정본은 `./plan.md` §JOUR-003 매도 회고 작성 설계다. 값·DDL·오류 매핑을 여기서 다시 정의하지 않는다.

## 이 이슈 전체에 걸리는 제약

- **원장을 건드리지 않는다.** 마이그레이션에 원장 테이블(`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`)의 `ALTER`·`DROP`이 없고, 코드는 `trades`를 읽기만 한다.
- **마이그레이션은 신규 번호 파일 1개**다 (ADR-0004). 착수 시점 `dev`의 최신 번호를 확인해 그 다음을 쓴다(조사 시점 최신 V16). 머지된 파일은 수정하지 않는다.
- **`TradeService`를 변경하지 않는다.** `getOwnedTrade(userId, tradeId)`를 그대로 재사용한다. journal이 `TradeRepository`를 직접 주입하지 않는 규칙(ADR-0002)은 그대로다.
- **매수 회고 경로(JOUR-001)를 리팩터링하지 않는다.** 공통 추상화를 지금 만들지 않고, 기존 매수 테스트가 전부 그대로 통과하는지 확인한다.
- **범위 밖 계약을 미리 만들지 않는다** — 수정(JOUR-004)의 `updated_at`, 상세(JOUR-005)의 조회 URL·통합 식별자, 목록(JOUR-006) 쿼리를 넣지 않는다.
- 새 `ErrorCode` 상수를 추가하지 않는다. 필요한 6개는 이미 있다.

## 작업 항목

- [x] **S1. 신규 마이그레이션 + `SellTradeJournal` 엔티티 + 리포지토리**

  `plan.md` §JOUR-003 §데이터 모델의 DDL대로 `sell_trade_journals`를 만들고 엔티티·리포지토리(`existsBySellTradeId`)를 붙인다.
  - 착수 시점 `dev`의 마이그레이션 최신 번호를 **먼저 확인**한다 (V14→V15 재번호화 전례).
  - 유니크 제약(`uk_sell_trade_journals_sell_trade`)과 FK(`trades(id)`)가 이 기능의 방어선이다 — 이름·대상을 `buy_trade_journals` 선례에 맞춘다.
  - `@MappedSuperclass` 공통 상위 클래스를 만들지 않는다.
  - 검증 — `@DataJpaTest`: 같은 `sell_trade_id` 2건째 유니크 위반, 서로 다른 체결 2건 공존, `existsBySellTradeId` false→true, 없는 체결 참조 시 FK 위반.
  - 검증 — `./gradlew test`로 기존 `@SpringBootTest`의 `ddl-auto=validate` 통과 확인.

- [x] **S2. `JournalService.createSellJournal` — 작성 유스케이스**

  검증 순서 `존재 → 소유 → 매도 여부 → 중복`과 트랜잭션 경계가 핵심이다. `tradeService.getOwnedTrade`를 재사용한다.
  - 선제 조회(`existsBySellTradeId`)와 유니크 위반 변환(`DataIntegrityViolationException` → 409)을 **둘 다** 둔다. 제약 위반을 잡은 뒤 추가 DB 작업 없이 즉시 던진다.
  - `createdAt`은 주입받은 `Clock`으로 만든다.
  - 검증 — 단위 테스트: 정상 작성(고정 시각·저장 인자), 404·403·400·409 각 경로, **타인의 매수 체결이 403**(400 아님), 예외 변환.

- [x] **S3. `JournalController` 엔드포인트 + DTO 2개 + 문서 갱신**

  `POST /api/trades/{sellTradeId}/sell-journal`을 기존 컨트롤러에 추가하고 record DTO 2개를 만든다. 응답 필드는 `journalId`·`sellTradeId`·`content`·`createdAt` 4개 고정이다.
  - 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다.
  - **같은 커밋에서** `docs/api-routes.md`·`docs/api-contracts.md`를 갱신한다 (CLAUDE.md 규칙 7).
  - 검증 — `@WebMvcTest`: 201 본문 `jsonPath` 4필드, 공백·누락·상한 초과 400, 숫자 아닌 `sellTradeId` 400, 미인증 401, 서비스 예외의 403·404·409 매핑.

- [x] **S4. 통합 테스트 + 빌드**

  Testcontainers `@SpringBootTest`로 spec의 2차 완료 조건을 한 번에 확인한다.
  - 본인 매도 체결 작성 201 (매수 → 매도 순으로 체결을 만들어 매도 체결을 확보한다).
  - **순차 중복**·**동시 중복** 모두 1건만 성공·나머지 409, 최종 행 수 1.
  - 없는 체결 404 · 매수 체결 400 · 타인 체결 403 · 공백 본문 400.
  - **교차 검증** — 매수 체결에 `/sell-journal` 400, 매도 체결에 `/journal` 400, 한 종목의 매수·매도에 두 회고를 각각 작성하면 둘 다 201.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 동일하다.
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 확인한다.

## 완료 조건 매핑 (이슈 #183)

| 이슈 완료 조건 | 항목 |
|---|---|
| 본인 매도 체결에 투자일기 작성 통합 테스트 | **S4** |
| 순차·동시 중복이 한 건만 성공하고 나머지 409 | **S4** (제약은 **S1**, 변환 로직은 **S2**) |
| 없는 체결·매수인 체결·타인 체결·공백 본문 거부 | **S4** (단위 **S2**, 계약 **S3**) |
| 성공·실패 전후 원장 불변 | **S4** (마이그레이션에 원장 `ALTER` 없음은 **S1**) |
| 신규 Flyway 마이그레이션으로 매도 투자일기 테이블 생성 (ADR-0004) | **S1** |
| `docs/api-routes.md`·`docs/api-contracts.md` 반영 | **S3** |
| `docs/specs/007-journal/spec.md` 범위 제외 갱신 | **완료** — 2026-08-04 `spec.md`·`plan.md`·이 문서에 2차 착수 범위 반영 |
| `./gradlew build` 통과 | **S4** |

## 이 이슈에서 하지 않는 것

- 매수 회고 수정(JOUR-002)·**매도 회고 수정(JOUR-004)** — `updated_at` 컬럼도 만들지 않는다. → JOUR-004는 이슈 #190에서 착수한다(아래 절).
- 투자일기 **상세**(JOUR-005)·**목록**(JOUR-006) 조회와 그 전용 인덱스·쿼리. 매수·매도 회고 테이블 통합도 이 게이트에 속한다.
- 매도 회고 응답에 실현손익·배분 lot 등 매도 결과 정보 추가.
- 목표가·손절가·예상보유기간 등 구조화 필드.
- 투자일기 기반 AI 피드백·복기 (spec 012 범위).

---

# JOUR-004 매도 회고 수정 (이슈 #190, 완료)

> 설계 정본은 `./plan.md` §JOUR-004 매도 회고 수정 설계다. 값·DDL·오류 매핑·응답 DTO 분리 근거를 여기서 다시 정의하지 않는다.

## 이 이슈 전체에 걸리는 제약

- **원장을 건드리지 않는다.** 마이그레이션은 `sell_trade_journals`의 `updated_at` 컬럼 추가·백필뿐이고, 원장 테이블(`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`)에는 `ALTER`·`DROP`이 없다. 코드는 `trades`를 읽기만 한다.
- **마이그레이션은 신규 번호 파일 1개**다 (ADR-0004). 착수 시점 `dev`의 최신 번호를 **실제로 재확인**해 그 다음을 쓴다(조사 시점 최신은 `V17`, 다음은 `V18` 예정이지만 병합 순서에 따라 바뀔 수 있다 — `buy_trade_journals`의 V14→V15 재번호화, `sell_trade_journals`의 V16→V17 확인 전례와 같은 패턴). 머지된 파일은 수정하지 않는다.
- **`TradeService`를 변경하지 않는다.** `getOwnedTrade(userId, tradeId)`를 그대로 재사용한다. journal이 `TradeRepository`를 직접 주입하지 않는 규칙(ADR-0002)은 그대로다.
- **기존 매수·매도 회고 작성 경로(JOUR-001·JOUR-003)를 리팩터링하지 않는다.** `SellTradeJournal.of(...)`가 내부적으로 `updatedAt`을 함께 채우는 것 외에는 작성 경로의 동작·계약을 바꾸지 않는다. 기존 작성 테스트가 전부 그대로 통과하는지 확인한다.
- **작성 응답 계약(`SellJournalResponse`, 4필드)을 바꾸지 않는다.** 수정 응답은 새 레코드 `SellJournalUpdateResponse`(5필드)로 분리한다(plan.md "응답 DTO를 분리하는 이유").
- **잠금 조건을 두지 않는다.** spec.md가 이미 "매도 회고 수정 잠금 없음"으로 확정했다 — 새 오류 코드(`JOURNAL_LOCKED` 등)를 추가하지 않고, 수정 횟수·시간 제한 검증을 넣지 않는다.
- 새 `ErrorCode` 상수를 추가하지 않는다. 필요한 5개(`VALIDATION_ERROR`·`UNAUTHORIZED`·`FORBIDDEN`·`NOT_FOUND`×2)는 이미 있다.

## 작업 항목

- [x] **U1. 마이그레이션 + 엔티티 `updatedAt` 필드·수정 메서드 + 리포지토리 조회 메서드**

  `plan.md` §데이터 모델의 3단계 DDL(nullable 추가 → `created_at`으로 백필 → `NOT NULL`로 좁히기)대로 `sell_trade_journals.updated_at`을 추가한다. `SellTradeJournal` 엔티티에 `updatedAt` 필드, `of(...)` 내부에서 `updatedAt`도 `now`로 채우는 변경, `updateContent(String content, LocalDateTime updatedAt)` 메서드(setter 아님)를 추가한다. `SellTradeJournalRepository`에 `findBySellTradeId`를 추가한다.
  - 착수 시점 `dev`의 마이그레이션 최신 번호를 **먼저 확인**한다.
  - 백필 대상은 기존 행의 `created_at` 값이다 — 상수 `DEFAULT`를 쓰지 않는다.
  - `of(...)`의 시그니처는 바꾸지 않는다 — 호출부(`createSellJournal`)를 수정할 필요가 없어야 한다.
  - 검증 — `@DataJpaTest`: ① `findBySellTradeId` 존재/부재 각각 값 있음/`empty()` ② `updateContent` 호출 후 flush하면 `content`·`updated_at`만 바뀌고 `created_at`·`sell_trade_id`·`id`는 그대로 ③ 신규 컬럼이 `NOT NULL` 제약을 갖는지.
  - 검증 — `./gradlew test`로 기존 `@SpringBootTest`의 `ddl-auto=validate` 통과 확인.

- [x] **U2. `JournalService.updateSellJournal` — 수정 유스케이스 (단위 테스트 포함)**

  `plan.md` §구성요소 설계의 1~5단계를 구현한다. 검증 순서 `체결 존재(404) → 소유(403) → 매도 여부(400) → 회고 존재(404)`와 트랜잭션 경계가 핵심이다. `tradeService.getOwnedTrade`를 재사용한다.
  - 회고가 없으면 `findBySellTradeId`의 빈 `Optional`을 404로 변환한다 — 여기서 새 회고를 만들지 않는다(upsert 금지).
  - 유니크 위반 변환 로직을 넣지 않는다 — 수정은 `INSERT`가 아니라 `UPDATE`라 해당 경로가 없다.
  - `updatedAt`은 주입받은 `Clock`으로 만든다(기존 필드 재사용, 추가 주입 없음).
  - 검증 — 단위 테스트: 정상 수정(고정 시각·`updateContent` 호출 인자·반환 DTO 5필드), 404(체결 없음)·403·400(매수 체결)·404(회고 미작성) 각 경로, **타인의 매수 체결이 403**(400 아님), **체결 없음 404와 회고 없음 404를 별도 테스트로 구분**, 연속 2회 수정 시 두 번째 `updatedAt`이 더 이후이고 마지막 본문만 남음.

- [x] **U3. `JournalController` PATCH 엔드포인트 + DTO 2개 + 문서 갱신**

  `PATCH /api/trades/{sellTradeId}/sell-journal`을 기존 컨트롤러에 추가하고 record DTO 2개(`SellJournalUpdateRequest`, `SellJournalUpdateResponse`)를 만든다. 응답 필드는 `journalId`·`sellTradeId`·`content`·`createdAt`·`updatedAt` 5개 고정이다. 성공 상태는 200이다(생성이 아니므로 201 아님).
  - 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다.
  - **같은 커밋에서** `docs/api-routes.md`·`docs/api-contracts.md`를 갱신한다 (CLAUDE.md 규칙 7, plan.md §문서 갱신).
  - 검증 — `@WebMvcTest`: 200 본문 `jsonPath` 5필드, 공백·누락·상한 초과 400, 숫자 아닌 `sellTradeId` 400, 미인증 401, 서비스 예외의 400·403·404 매핑(409 케이스 없음).

- [x] **U4. 통합 테스트 + 빌드**

  Testcontainers `@SpringBootTest`로 spec의 3차 완료 조건을 한 번에 확인한다.
  - 매도 체결 작성 → 회고 작성 → PATCH 수정 → 200, DB 여전히 1행, `content` 갱신, `updatedAt`이 `createdAt`보다 이후.
  - **연속 2회 수정** 모두 200이고 마지막 본문만 남는다(수정 횟수 제한·잠금 없음의 근거).
  - 없는 체결 404 · **회고 미작성 404** · 타인 소유 403 · 매수 체결 400 · 공백 본문 400.
  - **upsert 아님 확인** — 회고 미작성 상태에서 PATCH 실패 후 `sell_trade_journals` 행 수가 0임을 확인한다.
  - 매수 회고 계약(`POST .../journal`)과 매도 회고 작성 계약(`POST .../sell-journal`) 기존 테스트가 그대로 통과.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 동일하다.
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 확인한다.

## 완료 조건 매핑 (이슈 #190)

| 이슈 완료 조건 (`spec.md` §완료 조건 3차 착수) | 항목 |
|---|---|
| 본인이 작성한 매도 회고 본문 수정 통합 테스트(본문·수정시각 갱신) | **U4** |
| 연속 2회 수정 모두 성공, 마지막 본문만 남음(잠금 없음의 근거) | **U4** (단위는 **U2**) |
| 없는 체결(404)·매도 회고 미작성(404)·타인 소유(403)·매수 체결(400)·공백 본문(400) 거부 | **U4** (단위 **U2**, 계약 **U3**) |
| upsert 아님(수정 요청이 새 회고를 만들지 않음)을 행 수로 확인 | **U4** |
| "그 외 잠금 조건" 여부 확정과 근거 기록 | **완료** — `spec.md` §비즈니스 규칙 "매도 회고 수정 잠금 없음"에 2026-08-04 확정. PR 본문에도 같은 근거를 적는다(U3 또는 U4 커밋 시) |
| 매수 회고 작성·매도 회고 작성 기존 계약과 테스트가 그대로 통과 | **U4** (경로 무변경은 **U1**·**U2**) |
| 신규 Flyway 마이그레이션으로 `sell_trade_journals`에 수정시각 컬럼 추가 (ADR-0004) | **U1** |
| 성공·실패 전후 원장 불변 | **U4** (마이그레이션에 원장 `ALTER` 없음은 **U1**) |
| `docs/api-routes.md`·`docs/api-contracts.md` 반영 | **U3** |
| `./gradlew build` 통과 | **U4** |

## 이 이슈에서 하지 않는 것

- 매수 회고 수정(JOUR-002) — 여전히 Decision Gate 미해결. `buy_trade_journals`에 `updated_at`을 추가하지 않는다.
- 투자일기 **상세**(JOUR-005)·**목록**(JOUR-006) 조회와 그 전용 인덱스·쿼리. 매수·매도 회고 테이블 통합도 이 게이트에 속한다.
- 매도 회고 **삭제**, 수정 이력·버전 보관(마지막 본문 1건만 남긴다).
- 매도 회고 수정 잠금 조건과 `JOURNAL_LOCKED` 오류 코드(spec.md가 "잠금 없음"으로 확정했으므로 이번 범위에서 필요 없다).
- 매도 회고 응답에 실현손익·배분 lot 등 매도 결과 정보 추가.
- 목표가·손절가·예상보유기간 등 구조화 필드.
- 투자일기 기반 AI 피드백·복기 (spec 012 범위).

---

# JOUR-002 매수 회고 수정 (이슈 #197, 이번 착수)

> 설계 정본은 `./plan.md` §JOUR-002 매수 회고 수정 설계다. 값·DDL·오류 매핑·응답 DTO 분리 근거를 여기서 다시 정의하지 않는다.
> **구조는 JOUR-004(U1~U4)와 대칭이다** — 매도 체결 기준을 매수 체결 기준으로 옮기고, 잠금 없음 회귀 테스트가 추가된다.

## 이 이슈 전체에 걸리는 제약

- **잠금 로직을 만들지 않는다.** `spec.md`가 "매수 회고 수정 잠금 없음"으로 확정했다(이 결정이 `005-order-sell/spec.md`의 잠금 규정을 대체한다). `JOURNAL_LOCKED` 등 새 오류 코드를 추가하지 않고, 수정 횟수·기간 제한을 넣지 않으며, **`HoldingLotRepository`·`TradeAllocationRepository`를 journal 쪽에 주입하지 않는다.** `updateBuyJournal`의 단계 구성이 `updateSellJournal`과 같아야 한다 — 달라졌다면 잠금이 새어 들어온 것이다.
- **원장을 건드리지 않는다.** 마이그레이션은 `buy_trade_journals`의 `updated_at` 컬럼 추가·백필뿐이고, 원장 테이블(`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`)에는 `ALTER`·`DROP`이 없다. 코드는 `trades`를 읽기만 한다.
- **마이그레이션은 신규 번호 파일 1개**다 (ADR-0004). 착수 시점 `dev`의 최신 번호를 **실제로 재확인**해 그 다음을 쓴다. 조사 시점 최신은 `V18`이라 `V19`로 만들었으나, PR 리뷰 중 `dev`에 `V19__drop_favorites_and_practice_intentions.sql`(#193)이 먼저 병합돼 `V20`으로 재번호화했고, 이후 `dev`에 `V20__add_stock_replay_session_to_trades.sql`(#191)까지 먼저 병합돼 **최종적으로 `V21`로 재번호화했다** — 병합 순서에 따라 번호가 바뀔 수 있다는 이 문서의 경고가 같은 이슈 안에서 두 번 실제로 발생한 사례다(V14→V15 재번호화 전례와 같은 패턴). 머지된 파일은 수정하지 않는다.
- **`TradeService`를 변경하지 않는다.** `getOwnedTrade(userId, tradeId)`를 그대로 재사용한다. journal이 `TradeRepository`를 직접 주입하지 않는 규칙(ADR-0002)은 그대로다.
- **기존 작성·수정 경로(JOUR-001·003·004)를 리팩터링하지 않는다.** `BuyTradeJournal.of(...)`가 내부적으로 `updatedAt`을 함께 채우는 것 외에는 기존 동작·계약을 바꾸지 않는다. 네 유스케이스의 공통 추상화도 만들지 않는다.
- **작성 응답 계약(`BuyJournalResponse`, 4필드)을 바꾸지 않는다.** 수정 응답은 새 레코드 `BuyJournalUpdateResponse`(5필드)로 분리한다.
- 새 `ErrorCode` 상수를 추가하지 않는다. 필요한 5개(`VALIDATION_ERROR`·`UNAUTHORIZED`·`FORBIDDEN`·`NOT_FOUND`×2)는 이미 있다.

## 작업 항목

- [x] **B0. PRD·spec·plan·tasks 문서 갱신 (문서 커밋, 구현 전 선행)**

  Decision Gate 해제 결정을 정본 문서에 반영한다. 코드 변경 없음.
  - `docs/prd.md` JOUR-002 — Decision Gate 문구를 "잠금 없음 확정 + 근거 3가지 + 향후 피드백·리포트 스펙에서 재검토"로 교체. JOUR-004 항목의 JOUR-002 참조도 함께 정정.
  - `docs/specs/005-order-sell/spec.md` — "첫 매도 배분이 발생한 매수 lot은 투자일기 수정이 잠긴다" 규칙에 철회 표시와 근거 링크를 단다(그 spec의 구현에는 영향 없음).
  - `docs/specs/007-journal/` `spec.md`·`plan.md`·`tasks.md` — 4차 착수(JOUR-002) 범위·요구사항·비즈니스 규칙·완료 조건·설계·작업 항목 추가, 범위 제외에서 JOUR-002 제거.
  - 검증 — 문서만 바뀌므로 빌드 불필요. `JOURNAL_LOCKED`·"잠금" 언급이 남은 위치를 grep으로 확인해 모순이 없는지 본다.

- [x] **B1. 마이그레이션 + 엔티티 `updatedAt` 필드·수정 메서드 + 리포지토리 조회 메서드**

  `plan.md` §JOUR-002 §데이터 모델의 3단계 DDL(nullable 추가 → `created_at`으로 백필 → `NOT NULL`로 좁히기)대로 `buy_trade_journals.updated_at`을 추가한다. `BuyTradeJournal`에 `updatedAt` 필드, `of(...)` 내부에서 `updatedAt`도 `now`로 채우는 변경, `updateContent(String content, LocalDateTime updatedAt)` 메서드(setter 아님)를 추가한다. `BuyTradeJournalRepository`에 `findByBuyTradeId`를 추가한다.
  - 착수 시점 `dev`의 마이그레이션 최신 번호를 **먼저 확인**한다 (`ls src/main/resources/db/migration | sort -V | tail`).
  - 백필 대상은 기존 행의 `created_at` 값이다 — 상수 `DEFAULT`를 쓰지 않는다.
  - `of(...)`의 시그니처는 바꾸지 않는다 — 호출부(`createBuyJournal`)를 수정할 필요가 없어야 한다.
  - `V18`(매도 회고)과 같은 형태이므로 참고하되, **복사하면서 테이블·컬럼명을 매수 쪽으로 바꾸는 것을 빠뜨리지 않는다.**
  - **실제 결과: `V21`.** 착수 시점엔 `V19`로 만들었으나, PR 리뷰 중 `dev`에 `V19__drop_favorites_and_practice_intentions.sql`(#193)이 먼저 병합돼 `V20`으로, 그 뒤 `dev`에 `V20__add_stock_replay_session_to_trades.sql`(#191)까지 먼저 병합돼 다시 `V21__add_updated_at_to_buy_trade_journals.sql`로 재번호화했다 — PR을 올린 뒤에도 병합 대기 중에는 `dev`를 계속 다시 대조해야 한다는 근거 사례다.
  - 검증 — `@DataJpaTest`(`BuyTradeJournalRepositoryTest`): ① `findByBuyTradeId` 존재/부재 각각 값 있음/`empty()` ② `updateContent` 후 flush하면 `content`·`updated_at`만 바뀌고 `created_at`·`buy_trade_id`·`id`는 그대로 ③ 신규 컬럼의 `NOT NULL` 제약.
  - 검증 — `./gradlew test`로 기존 `@SpringBootTest`의 `ddl-auto=validate` 통과 확인.

- [x] **B2. `JournalService.updateBuyJournal` — 수정 유스케이스 (단위 테스트 포함)**

  `plan.md` §구성요소 설계의 1~5단계를 구현한다. 검증 순서 `체결 존재(404) → 소유(403) → 매수 여부(400) → 회고 존재(404)`와 트랜잭션 경계가 핵심이다. `tradeService.getOwnedTrade`를 재사용한다.
  - 회고가 없으면 `findByBuyTradeId`의 빈 `Optional`을 404로 변환한다 — 새 회고를 만들지 않는다(upsert 금지).
  - **잠금 판정 단계를 넣지 않는다** — 배분·lot 조회가 없어야 하고, `updateSellJournal`과 단계 구성이 같아야 한다.
  - 유니크 위반 변환 로직을 넣지 않는다(수정은 `UPDATE`라 해당 경로가 없다). `updatedAt`은 주입받은 `Clock`으로 만든다(기존 필드 재사용).
  - 검증 — 단위 테스트: 정상 수정(고정 시각·`updateContent` 호출 인자·반환 DTO 5필드), 404(체결 없음)·403·400(매도 체결)·404(회고 미작성) 각 경로, **타인의 매도 체결이 403**(400 아님), **체결 없음 404와 회고 없음 404를 별도 테스트로 구분**, 연속 2회 수정 시 두 번째 `updatedAt`이 더 이후이고 마지막 본문만 남음.

- [x] **B3. `JournalController` PATCH 엔드포인트 + DTO 2개 + API 문서 갱신**

  `PATCH /api/trades/{buyTradeId}/journal`을 기존 컨트롤러에 추가하고 record DTO 2개(`BuyJournalUpdateRequest`, `BuyJournalUpdateResponse`)를 만든다. 응답 필드는 `journalId`·`buyTradeId`·`content`·`createdAt`·`updatedAt` 5개 고정이고 성공 상태는 200이다.
  - 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다.
  - **같은 커밋에서** `docs/api-routes.md`·`docs/api-contracts.md`를 갱신한다 (CLAUDE.md 규칙 7, plan.md §문서 갱신). 계약 본문에 **잠금 없음(매도 배분 여부와 무관하게 항상 수정 가능)**을 명시한다.
  - 검증 — `@WebMvcTest`: 200 본문 `jsonPath` 5필드, 공백·누락·상한 초과 400, 숫자 아닌 `buyTradeId` 400, 미인증 401, 서비스 예외의 400·403·404 매핑(409 케이스 없음).

- [x] **B4. 통합 테스트 (잠금 없음 회귀 포함) + 빌드**

  Testcontainers `@SpringBootTest`로 spec의 4차 완료 조건을 한 번에 확인한다.
  - 매수 체결 → 회고 작성 → PATCH 수정 → 200, DB 여전히 1행, `content` 갱신, `updatedAt`이 `createdAt`보다 이후.
  - **잠금 없음 회귀 (이 이슈의 핵심)** — ① 매도한 적 없는 매수 체결 ② **부분 매도로 `trade_allocations`가 생긴** 매수 체결 ③ **전량 매도된** 매수 체결, 셋 다 수정 200. ②·③은 `005-order-sell` 매도 경로를 실제로 태워 배분을 만든 뒤 수정한다.
  - **연속 2회 수정** 모두 200이고 마지막 본문만 남는다.
  - 없는 체결 404 · **회고 미작성 404** · 타인 소유 403 · 매도 체결 400 · 공백 본문 400.
  - **upsert 아님 확인** — 회고 미작성 상태에서 PATCH 실패 후 `buy_trade_journals` 행 수가 0이다.
  - 매수 회고 작성(`POST .../journal`)·매도 회고 작성·수정(`POST`·`PATCH .../sell-journal`) 기존 테스트가 그대로 통과.
  - **원장 불변** — 성공·실패 각 경로 전후로 `orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 동일하다. 배분이 있는 lot을 수정한 뒤에도 배분·lot이 그대로임을 확인한다.
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 확인한다.

## 완료 조건 매핑 (이슈 #197)

| 이슈 완료 조건 (`spec.md` §완료 조건 4차 착수) | 항목 |
|---|---|
| 본인 매수 회고 수정 통합 테스트 (매도 여부와 무관하게 항상 성공) | **B4** (단위는 **B2**) |
| 없는 회고(404)·타인 소유(403)·매도 체결(400)·공백 본문(400) 거부 | **B4** (단위 **B2**, 계약 **B3**) |
| 연속 2회 수정 모두 성공, 마지막 본문만 남음 | **B4** (단위 **B2**) |
| upsert 아님(수정 요청이 새 회고를 만들지 않음)을 행 수로 확인 | **B4** |
| 신규 Flyway 마이그레이션으로 `buy_trade_journals`에 수정시각 컬럼 추가 (ADR-0004) | **B1** |
| 성공·실패 전후 원장 불변 | **B4** (마이그레이션에 원장 `ALTER` 없음은 **B1**) |
| `docs/api-routes.md`·`docs/api-contracts.md` 반영 | **B3** |
| `docs/prd.md` JOUR-002 Decision Gate 문구 갱신 | **B0** |
| `docs/specs/007-journal/spec.md` 범위 제외에서 JOUR-002 제거 + 요구사항·완료 조건 절 추가 | **B0** |
| `./gradlew build` 통과 | **B4** |

## 이 이슈에서 하지 않는 것

- **수정 잠금 로직 전체** — `JOURNAL_LOCKED` 오류 코드, 배분·lot 기반 잠금 판정, 수정 횟수·기간 제한. 소비 기능(spec 012)이 생기기 전까지, 그리고 그 스펙의 별도 결정 전까지 만들지 않는다.
- 투자일기 **상세**(JOUR-005, 식별자 체계 Decision Gate 미해결)·**목록**(JOUR-006) 조회와 그 전용 인덱스·쿼리. 매수·매도 회고 테이블 통합도 이 게이트에 속한다.
- 투자일기 **삭제**, 수정 이력·버전 보관(마지막 본문 1건만 남긴다).
- 매수·매도 작성·수정 네 경로의 공통 추상화 리팩터링(`@MappedSuperclass` 포함).
- 매수 회고 응답에 매도 배분·실현손익 등 후속 원장 정보 추가.
- 목표가·손절가·예상보유기간 등 구조화 필드.
- 투자일기 기반 AI 피드백·복기 (spec 012 범위).

---

# JOUR-006 투자일기 목록 조회 (이슈 #203, 이번 착수)

> 설계 정본은 `./plan.md` §JOUR-006 투자일기 목록 조회 설계다. API 계약·데이터 접근·컴포넌트 구조·오류 매핑을 여기서 다시 정의하지 않는다.

## 이 이슈 전체에 걸리는 제약

- **원장·투자일기 어느 테이블에도 쓰지 않는다.** `@Transactional(readOnly = true)`이고, 신규 Flyway 마이그레이션이 없다(spec.md — 기존 두 테이블을 읽기만 한다).
- **테이블 병합은 애플리케이션 계층에서 한다** — 각 리포지토리가 QueryDSL로 `limit + 1`건씩 커서 조회하고, `JournalService`가 병합·정렬한다(plan.md 결정 ①). 네이티브 `UNION ALL`을 쓰지 않는다.
- **통합 `journalId`를 노출하지 않는다.** 항목 식별은 `journalType` + 원래 체결 ID(`buyTradeId`/`sellTradeId`)뿐이다(JOUR-005 식별자 게이트 비선점).
- **기존 4개 계약(작성·수정 ×2)을 리팩터링하지 않는다.** `JournalController`·`BuyTradeJournal`·`SellTradeJournal`·`TradeService`를 변경하지 않는다. 이번 PR의 diff는 추가 위주다.
- 새 `ErrorCode` 상수를 추가하지 않는다. 필요한 4개(`VALIDATION_ERROR`·`UNAUTHORIZED`·`NOT_FOUND`, 200은 오류 아님)는 이미 있다.

## 작업 항목

- [x] **L1. 커서 값 객체 + 두 리포지토리의 QueryDSL 커서 조회**

  `plan.md` §커서 인코딩 형식대로 `JournalCursor(LocalDateTime createdAt, Long tradeId)`를 `journal.service` 패키지에 만든다(`TradeCursor`·`OrderCursor`와 같은 모양의 `parse`/`encode`). `plan.md` §데이터 접근 설계의 QueryDSL 쿼리 그대로 `BuyTradeJournalRepositoryCustom`/`Impl`, `SellTradeJournalRepositoryCustom`/`Impl`을 추가하고 기존 리포지토리 인터페이스가 이를 상속하게 한다.
  - `journal.buyTrade.account.id.eq(accountId)`(매도 쪽은 `sellTrade.account.id`) 조건 하나로 `market` 필터와 소유권 검증을 동시에 처리한다 — 별도 소유권 조건을 추가하지 않는다.
  - `.join(...).fetchJoin()`으로 N+1을 막는다(`TradeRepositoryImpl` 선례).
  - 정렬은 `createdAt.desc()`, 체결 ID `desc()` 두 키다. `updatedAt` 기준으로 정렬하지 않는다.
  - 검증 — 단위 테스트(`JournalCursorTest`): 정상 파싱, `null`/빈 문자열 → `null`, 파싱 실패 400, `encode` 형식.
  - 검증 — `@DataJpaTest`(기존 `BuyTradeJournalRepositoryTest`·`SellTradeJournalRepositoryTest`에 케이스 추가): 다른 계좌 회고 미포함, 커서 이전 항목만 반환, `createdAt` 동점 시 체결 ID 내림차순, `fetchSize` 준수.

- [x] **L2. `JournalService.getMyJournalEntries` — 병합·정렬 유스케이스 (단위 테스트 포함)**

  `plan.md` §`JournalService.getMyJournalEntries`의 1~7단계를 구현한다. `AccountService`를 새로 주입받는다.
  - 계좌 없음 → 404(`AccountService.getAccountFor`가 던지는 예외 그대로 전파, 별도 catch 없음).
  - 두 리포지토리에 **같은 커서 값**(`createdAt`·`tradeId`)을 넘긴다 — 페이지 경계가 어긋나지 않아야 한다.
  - 병합 후 `(createdAt, 체결 ID)` 내림차순 정렬 → 상위 `limit + 1`건 → `hasNext` 판정 → `limit`건으로 자르기 → `nextCursor` 인코딩(마지막 항목의 `journalType`에 맞는 체결 ID 사용) 순서를 지킨다.
  - 검증 — 단위 테스트(Mockito, 기존 `JournalServiceTest`에 추가): 계좌 없음 404, 매수·매도 혼합 정렬 순서, **동시각 tie-break**(체결 ID 큰 쪽 우선), `hasNext`·`nextCursor` 경계값(`limit`건 이하/초과), 빈 목록(양쪽 리포지토리 빈 리스트), 두 리포지토리 호출에 전달되는 커서 인자가 동일한지.

- [x] **L3. `JournalListController` + 응답 DTO 2개 + 문서 갱신**

  `GET /api/journal`을 새 컨트롤러 `JournalListController`(`@RequestMapping("/api/journal")`)에 연다. `JournalListResponse`(`content`·`nextCursor`·`hasNext`)와 `JournalListItemResponse`(`journalType`·`buyTradeId`·`sellTradeId`·`content`·`createdAt`·`updatedAt`, `from(BuyTradeJournal)`/`from(SellTradeJournal)` 오버로드) record 2개를 만든다. `limit` 검증은 `TradeController.validateLimit`을 그대로 복제한다.
  - `journalType`은 `order.domain.OrderSide`를 재사용하지 않고 리터럴 `"BUY"`/`"SELL"` 문자열을 직접 쓴다.
  - 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다.
  - **같은 커밋에서** `docs/api-routes.md`·`docs/api-contracts.md`를 갱신한다 (CLAUDE.md 규칙 7, plan.md §문서 갱신).
  - 검증 — `@WebMvcTest`(`JournalListControllerTest`, 신규): 200 본문 `jsonPath` 6필드 + `journalId` 필드 **부재** 확인, `market` 누락·미지원 리터럴 400, `limit` 0·101 400, `cursor` 파싱 실패 400, 미인증 401, 서비스 404(계좌 없음) 매핑.

- [x] **L4. 통합 테스트 — 혼합 목록·market 필터·커서 경계·격리 + 빌드**

  Testcontainers `@SpringBootTest`로 spec의 5차 완료 조건을 한 번에 확인한다(기존 `JournalIntegrationTest`에 추가하거나 신규 `JournalListIntegrationTest`).
  - 매수·매도 회고가 섞인 목록이 `createdAt` 내림차순(동시각은 체결 ID 내림차순)으로 조회되고, 항목마다 `journalType`과 해당 없는 체결 ID `null`이 맞다.
  - **`market` 필터** — 같은 사용자의 다른 시장(`CRYPTO`) 회고가 `market=STOCK` 결과에 섞이지 않는다.
  - **커서 페이지네이션** — 첫 페이지 `nextCursor`로 다음 페이지를 이어 받아 중복·누락 없음, 마지막 페이지는 `nextCursor: null`·`hasNext: false`. **매수·매도 회고가 `createdAt` 동시각으로 페이지 경계에 걸치는 픽스처**로 체결 ID tie-break가 실제로 동작하는지 고정한다.
  - `limit` 0·101 400(클램핑 없음), `market` 누락·미지원 리터럴 400, `cursor` 파싱 실패 400, 미인증 401.
  - 다른 사용자의 투자일기가 섞이지 않는다.
  - 회고가 하나도 없는 사용자 → 200 빈 목록(`content: []`·`nextCursor: null`·`hasNext: false`).
  - **`journalId` 미노출 계약** — 응답 JSON에 `journalId` 키가 없음을 고정하는 테스트.
  - **원장·투자일기 불변** — 조회 전후 `buy_trade_journals`·`sell_trade_journals`·`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 전혀 변하지 않는다.
  - 기존 4개 계약(`POST`·`PATCH .../journal`, `POST`·`PATCH .../sell-journal`) 기존 테스트가 그대로 통과.
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 확인한다.

- [x] **L5. 문서 최종 확인 + 빌드**

  `docs/api-routes.md`·`docs/api-contracts.md`가 실제 컨트롤러(`JournalListController`)와 일치하는지 최종 대조한다(원래는 L3에서 컨트롤러 커밋과 같은 커밋이 원칙이므로, L3에서 함께 반영했다면 이 항목은 누락분 확인으로 끝낸다).
  - `plan.md` §관련 문서·§JOUR-006 절의 "미반영" 표기가 있다면 반영 완료로 바꾼다.
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 다시 확인한다.

## 완료 조건 매핑 (이슈 #203)

| 이슈 완료 조건 (`spec.md` §완료 조건 5차 착수) | 항목 |
|---|---|
| 매수·매도 회고 혼합 목록이 `createdAt` 내림차순(동점 체결 ID 내림차순)으로 조회, `journalType`·null 필드 정확 | **L4** (단위는 **L2**) |
| `market` 필터가 매수·매도 두 타입 모두에 적용 | **L4** (쿼리 조건은 **L1**) |
| 커서 페이지네이션(중복·누락 없음, 마지막 페이지 `nextCursor: null`), 동시각 경계 tie-break | **L4** (단위는 **L2**) |
| `limit` 범위 밖 400(클램핑 없음)·`market` 누락·미지원 400·커서 파싱 실패 400·미인증 401 | **L4** (계약은 **L3**) |
| 다른 사용자의 투자일기가 섞이지 않음 | **L4** (쿼리 조건은 **L1**) |
| 회고 0건 사용자가 200 빈 목록 | **L4** (단위는 **L2**) |
| 통합 `journalId` 미노출 계약 테스트 | **L4** (계약은 **L3**) |
| 조회 전후 투자일기·원장 데이터 불변 | **L4** |
| 기존 4개 계약과 그 테스트가 그대로 통과, 신규 마이그레이션 없음 | **L4** (경로 무변경은 **L1**·**L2**·**L3**) |
| `docs/api-routes.md`·`docs/api-contracts.md` 반영 | **L3** (최종 확인 **L5**) |
| `./gradlew build` 통과 | **L4**·**L5** |

## 이 이슈에서 하지 않는 것

- 투자일기 **상세 조회**(JOUR-005) — 식별자 체계 Decision Gate 미해결. 이 목록이 그 게이트를 선점하지 않도록 통합 `journalId`를 노출하지 않는다.
- **매수·매도 회고 테이블 통합(스키마 변경)** — 기존 두 테이블을 읽기만 한다. 신규 마이그레이션 없음.
- 목록 조회의 통합(전 시장) 축·기간/종목/회고종류 필터·정렬 옵션·키워드 검색 — `market` 축과 최신순 정렬 하나뿐이다.
- 투자일기 삭제, 수정 이력·버전 보관.
- 목표가·손절가·예상보유기간 등 구조화 필드.
- 매수·매도 회고 작성·수정 경로의 공통 추상화 리팩터링(`JournalCursor`를 `TradeCursor`·`OrderCursor`와 묶는 제네릭화 포함).
- 투자일기 기반 AI 피드백·가격 알림·주간 회고 (spec 012 범위).

---

# JOUR-005 투자일기 상세 조회 (이슈 #217, 이번 착수)

> 설계 정본은 `./plan.md` §JOUR-005 투자일기 상세 조회 설계다. API 계약·컨트롤러 배치·응답 DTO·오류 매핑을 여기서 다시 정의하지 않는다.
> **구조는 JOUR-002(B0~B4)와 대칭이다** — 문서 커밋을 선행하고, 서비스 → 컨트롤러 → 통합 테스트 순으로 간다. 다른 점은 마이그레이션·엔티티·리포지토리 변경이 **하나도 없고**, 엔드포인트를 매수·매도 **2개** 여는 것이다.

## 이 이슈 전체에 걸리는 제약

- **어떤 테이블에도 쓰지 않는다.** 두 서비스 메서드 모두 `@Transactional(readOnly = true)`이고, **신규 Flyway 마이그레이션이 없다.** 엔티티(`BuyTradeJournal`·`SellTradeJournal`)와 두 리포지토리도 변경하지 않는다 — 기존 `findByBuyTradeId`·`findBySellTradeId`를 그대로 재사용한다.
- **기존 5개 계약을 리팩터링하지 않는다.** `JournalController`(작성·수정 4개)·`JournalListController`(목록 1개)·`TradeService`·`AccountService`·목록 DTO 2개를 건드리지 않는다. 이번 PR의 diff는 추가 위주다.
- **단일 경로·통합 식별자를 만들지 않는다.** `GET /api/journal/{journalId}`, 접두사 문자열 ID(`"buy:42"`), 두 테이블 통합 마이그레이션은 모두 `spec.md` §비즈니스 규칙 "상세 조회는 타입별 경로로 분리한다"가 배제했다.
- **`buy`/`sell`은 경로 리터럴이다.** `@PathVariable String type`으로 받아 컨트롤러에서 문자열 분기하지 않는다(컨벤션 레이어 규칙).
- **검증 순서는 수정 계약과 같다** — `체결 존재(404) → 소유(403) → 체결 구분(400) → 회고 존재(404)`. `updateBuyJournal`/`updateSellJournal`의 1~3단계와 단계 구성이 같아야 하며, 달라졌다면 조회 경로에 쓰기나 새 판정이 새어 들어온 것이다.
- 새 `ErrorCode` 상수를 추가하지 않는다. 필요한 5개(`VALIDATION_ERROR`·`UNAUTHORIZED`·`FORBIDDEN`·`NOT_FOUND`×2)는 이미 있다.

## 작업 항목

- [x] **D0. PRD·spec·plan·tasks 문서 갱신 (문서 커밋, 구현 전 선행)**

  Decision Gate 해제 결정을 정본 문서에 반영한다. 코드 변경 없음.
  - `docs/prd.md` JOUR-005 — URL·Decision Gate 문구를 "타입별 경로 분리 확정 + 두 엔드포인트 + 근거 3가지"로 교체. §3 "구현 현황"의 투자일기 조회 행도 실제 상태에 맞게 정리한다(JOUR-006은 PR #213으로 이미 완료인데 행이 `JOUR-005·006 미착수`로 묶여 있어 사실과 어긋난다 — 행을 분리한다).
  - `docs/specs/007-journal/` `spec.md`·`plan.md`·`tasks.md` — 6차 착수(JOUR-005) 범위·요구사항·비즈니스 규칙·완료 조건·설계·작업 항목 추가, 범위 제외에서 JOUR-005 제거.
  - **`docs/api-routes.md`·`docs/api-contracts.md`는 이 커밋에서 건드리지 않는다** — 아직 없는 엔드포인트를 라우트 지도에 먼저 올리면 그 문서가 "실제 컨트롤러와 동기화된 지도"라는 성질을 잃는다. D3(컨트롤러 커밋)에서 함께 반영한다(CLAUDE.md 규칙 7).
  - 검증 — 문서만 바뀌므로 빌드 불필요. "Decision Gate"·"미해결"·"식별자 체계" 언급이 남은 위치를 grep으로 확인해 모순이 없는지 본다.

- [x] **D1. `JournalService.getBuyJournal`·`getSellJournal` — 조회 유스케이스 2개 (단위 테스트 포함)**

  `plan.md` §`JournalService` — 조회 유스케이스 2개의 1~4단계를 매수·매도 각각 구현한다. `tradeService.getOwnedTrade`와 기존 리포지토리 조회 메서드를 재사용한다.
  - 두 메서드 모두 `@Transactional(readOnly = true)`다. `Clock`을 쓰지 않고, `updateContent`·`save`를 호출하지 않는다.
  - 회고가 없으면 빈 `Optional`을 404로 변환한다. 여기서 회고를 만들지 않는다.
  - 리포지토리·엔티티에 메서드를 추가하지 않는다 — 필요한 것이 이미 다 있다.
  - 응답 DTO 2개(`BuyJournalDetailResponse`·`SellJournalDetailResponse`)는 서비스 반환 타입 컴파일에 필요하므로 이 항목에서 함께 만든다(`~UpdateResponse` 재사용 아님).
  - 검증 — 단위 테스트(기존 `JournalServiceTest`에 추가): 정상 조회(반환 DTO 5필드·`updateContent`/`save` 미호출), 404(체결 없음)·403·400(반대 side)·404(회고 없음) 각 경로 ×2, **타인 소유의 반대 side 체결이 403**(400 아님), **체결 없음 404와 회고 없음 404를 별도 테스트로 구분**.

- [x] **D2. `JournalDetailController` + API 문서 갱신**

  `GET /api/journal/buy/{buyTradeId}`·`GET /api/journal/sell/{sellTradeId}`를 신규 컨트롤러 `JournalDetailController`(`@RequestMapping("/api/journal")`)에 연다. 기존 두 컨트롤러는 건드리지 않는다.
  - 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다. 요청 본문·쿼리 파라미터가 없다.
  - **같은 커밋에서** `docs/api-routes.md`(2행)·`docs/api-contracts.md`(`## journal` 절에 상세 조회 소절)를 갱신한다 (CLAUDE.md 규칙 7, plan.md §문서 갱신).
  - 검증 — `@WebMvcTest`(`JournalDetailControllerTest`, 신규): 두 경로 각각 200 본문 `jsonPath` 5필드, 숫자 아닌 경로 변수 400, 미인증 401, 서비스 예외의 400·403·404 매핑(409 없음), 매수 응답에 `sellTradeId` 키 부재·매도 응답에 `buyTradeId` 키 부재.

- [x] **D3. 통합 테스트 — PK 충돌 픽스처·교차 경로·읽기 전용 + 빌드**

  Testcontainers `@SpringBootTest`로 spec의 6차 완료 조건을 한 번에 확인한다(기존 `JournalIntegrationTest`에 추가하거나 신규 `JournalDetailIntegrationTest`).
  - 매수 체결 → 회고 작성 → 상세 조회 200, 응답 5필드가 저장값과 일치. 매도도 같은 흐름으로 200.
  - **PK 충돌 픽스처 (이 이슈의 핵심 회귀)** — `buy_trade_journals.id`와 `sell_trade_journals.id`가 **같은 값**인 상태를 만들고, 두 경로가 각각 자기 테이블의 회고를 반환하는지 확인한다. 경로 분리 결정을 고정하는 테스트다.
  - **수정 반영** — `PATCH`로 본문을 고친 뒤 상세를 조회하면 갱신된 `content`·`updatedAt`이 보인다.
  - 없는 체결 404 · **회고 미작성 404** · 타인 소유 403 · **교차 경로 400**(매수 경로에 매도 체결 ID, 매도 경로에 매수 체결 ID) · 미인증 401 — 두 엔드포인트 모두.
  - **읽기 전용** — 조회 전후 `buy_trade_journals`·`sell_trade_journals`(행 수와 `updated_at` 포함)·`orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations`가 전혀 변하지 않는다.
  - 기존 5개 계약(`POST`·`PATCH .../journal`, `POST`·`PATCH .../sell-journal`, `GET /api/journal`)의 기존 테스트가 그대로 통과한다. **목록 응답에 `journalId`가 여전히 없는지**도 함께 확인한다(계약 무변경).
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 확인한다.

- [ ] **D4. PRD 구현 현황 갱신 + 문서 최종 확인 + 빌드**

  `docs/prd.md` §3 "구현 현황"의 투자일기 상세 조회 행을 **완료**로 바꾸고 근거에 이번 PR 번호를 적는다 (CLAUDE.md 규칙 10).
  - `docs/api-routes.md`·`docs/api-contracts.md`가 실제 컨트롤러(`JournalDetailController`)와 일치하는지 최종 대조한다(D2에서 함께 반영했다면 누락분 확인으로 끝낸다).
  - `spec.md` §완료 조건 6차 착수의 체크박스를 실제 상태로 갱신한다.
  - `./gradlew spotlessApply` 후 **`./gradlew build` 통과**를 다시 확인한다.

## 완료 조건 매핑 (이슈 #217)

| 이슈 완료 조건 (`spec.md` §완료 조건 6차 착수) | 항목 |
|---|---|
| `GET /api/journal/buy/{buyTradeId}` 본인 매수 회고 단건 조회 통합 테스트 | **D3** (단위는 **D1**, 계약은 **D2**) |
| `GET /api/journal/sell/{sellTradeId}` 본인 매도 회고 단건 조회 통합 테스트 | **D3** (단위는 **D1**, 계약은 **D2**) |
| 두 테이블 PK 충돌 픽스처에서 경로별로 올바른 회고 반환 | **D3** |
| 없는 투자일기 404 · 회고 미작성 404 · 타인 소유 403 (두 엔드포인트 모두) | **D3** (단위 **D1**, 계약 **D2**) |
| 경로·체결 구분 교차 400 · 숫자 아닌 경로 변수 400 · 미인증 401 | **D3** (계약 **D2**) |
| 수정 후 상세 조회에 갱신 본문·`updatedAt` 반영 | **D3** |
| 조회 전후 데이터 불변(읽기 전용), 신규 마이그레이션 없음 | **D3** (`readOnly` 설계는 **D1**) |
| 기존 5개 계약과 그 테스트가 그대로 통과, 목록 계약 무변경 | **D3** (경로 무변경은 **D1**·**D2**) |
| `docs/api-routes.md`·`docs/api-contracts.md` 반영 | **D2** (최종 확인 **D4**) |
| `docs/prd.md` JOUR-005 Decision Gate 문구 갱신 | **D0** |
| `docs/prd.md` §3 구현 현황 행 갱신 | **D0**(행 분리·사실 정정) → **D4**(완료 판정·PR 번호) |
| `docs/specs/007-journal/spec.md`에 JOUR-005 착수 반영 | **D0** (완료 체크는 **D4**) |
| `./gradlew build` 통과 | **D3**·**D4** |

## 이 이슈에서 하지 않는 것

- **단일 경로 상세 조회**(`GET /api/journal/{journalId}`)·통합 식별자·접두사 문자열 ID — `spec.md` §비즈니스 규칙이 배제했다.
- **매수·매도 회고 테이블 통합(스키마 변경)** — 신규 마이그레이션 없음. 경로 분리로 통합 동기가 사라졌다.
- **목록(JOUR-006) 응답에 상세 링크·식별자 추가** — 목록 계약을 바꾸지 않는다.
- 투자일기 **삭제**, 수정 이력·버전 보관, 수정 잠금(`JOURNAL_LOCKED`).
- 상세 응답에 종목·가격·수량·실현손익 등 체결 정보 추가 (`GET /api/trades`가 정본).
- 목표가·손절가·예상보유기간 등 구조화 필드.
- 여섯 유스케이스의 공통 추상화 리팩터링 — 필요하면 기능 변경 없는 별도 PR로 다룬다.
- 투자일기 기반 AI 피드백·복기 (spec 012 범위).
