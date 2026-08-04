# Tasks: 체결별 투자일기 작성 (JOUR-001 · JOUR-003)

> 항목 하나 = implementer 1회 투입 = 커밋 1개. 값·규칙은 `./spec.md`, 설계는 `./plan.md`가 정본이다 — 여기에 값을 다시 정의하지 않는다.
> 테스트 레벨은 ADR-0003을 따른다.
>
> | 절 | 범위 | 이슈 | 상태 |
> |---|---|---|---|
> | §JOUR-001 매수 회고 작성 | 항목 1~6 | [#159](https://github.com/finplay-team/finplay/issues/159) | 완료 (기록 보존용) |
> | **§JOUR-003 매도 회고 작성** | 항목 S1~S4 | [#183](https://github.com/finplay-team/finplay/issues/183) | **이번 착수** |

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

- [ ] **S2. `JournalService.createSellJournal` — 작성 유스케이스**

  검증 순서 `존재 → 소유 → 매도 여부 → 중복`과 트랜잭션 경계가 핵심이다. `tradeService.getOwnedTrade`를 재사용한다.
  - 선제 조회(`existsBySellTradeId`)와 유니크 위반 변환(`DataIntegrityViolationException` → 409)을 **둘 다** 둔다. 제약 위반을 잡은 뒤 추가 DB 작업 없이 즉시 던진다.
  - `createdAt`은 주입받은 `Clock`으로 만든다.
  - 검증 — 단위 테스트: 정상 작성(고정 시각·저장 인자), 404·403·400·409 각 경로, **타인의 매수 체결이 403**(400 아님), 예외 변환.

- [ ] **S3. `JournalController` 엔드포인트 + DTO 2개 + 문서 갱신**

  `POST /api/trades/{sellTradeId}/sell-journal`을 기존 컨트롤러에 추가하고 record DTO 2개를 만든다. 응답 필드는 `journalId`·`sellTradeId`·`content`·`createdAt` 4개 고정이다.
  - 컨트롤러에 비즈니스 판단·repository 호출·try-catch를 두지 않는다.
  - **같은 커밋에서** `docs/api-routes.md`·`docs/api-contracts.md`를 갱신한다 (CLAUDE.md 규칙 7).
  - 검증 — `@WebMvcTest`: 201 본문 `jsonPath` 4필드, 공백·누락·상한 초과 400, 숫자 아닌 `sellTradeId` 400, 미인증 401, 서비스 예외의 403·404·409 매핑.

- [ ] **S4. 통합 테스트 + 빌드**

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

- 매수 회고 수정(JOUR-002)·**매도 회고 수정(JOUR-004)** — `updated_at` 컬럼도 만들지 않는다.
- 투자일기 **상세**(JOUR-005)·**목록**(JOUR-006) 조회와 그 전용 인덱스·쿼리. 매수·매도 회고 테이블 통합도 이 게이트에 속한다.
- 매도 회고 응답에 실현손익·배분 lot 등 매도 결과 정보 추가.
- 목표가·손절가·예상보유기간 등 구조화 필드.
- 투자일기 기반 AI 피드백·복기 (spec 012 범위).
