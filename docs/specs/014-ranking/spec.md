# Spec: 전체 랭킹 조회 (RANK-001)

> 단계: **2차 MVP (팀 회의 표현: 1차 고도화)**. 이슈 [#187](https://github.com/finplay-team/finplay/issues/187).
>
> PRD 근거: **RANK-001**(`docs/prd.md` "랭킹" 절, 2026-08-03 정책 확정 이슈 [#139](https://github.com/finplay-team/finplay/issues/139) — 완료). RANK-002(`GET /api/rankings/me`, 내 랭킹 조회)는 이 spec이 만드는 인프라·보정 로직을 재사용하는 후속 이슈에서 같은 spec 파일에 이어서 기록한다(이번 착수 범위 아님).
>
> **문서 동기화 상태**: 이 spec 확정 시점에 아직 컨트롤러가 없다. 구현 착수 시 `docs/api-routes.md`·`docs/api-contracts.md`에 `GET /api/rankings` 계약을 같은 커밋에서 추가한다(CLAUDE.md 규칙 7).
>
> **RANK-002 착수 (2026-08-05, 이슈 [#233](https://github.com/finplay-team/finplay/issues/233))**: 아래 "RANK-002 내 랭킹 조회" 절에 요구사항·완료 조건을 이어서 기록한다. RANK-002도 착수 시점에 아직 컨트롤러가 없다 — 구현 착수 시 `docs/api-routes.md`·`docs/api-contracts.md`에 `GET /api/rankings/me` 계약을 같은 커밋에서 추가한다.

## 개요

시장별(STOCK/CRYPTO) 실현손익 기준 전체 랭킹을 조회하는 기능이다. 순위는 Redis ZSET으로 관리하고, score는 매도 체결로 `accounts.realized_pnl`이 갱신된 뒤 **커밋 이후(after-commit)** 이벤트로 갱신한다 — 매 조회마다 `trades`를 재집계하지 않는다. 실현손익의 정본은 MySQL `accounts.realized_pnl`이고, Redis ZSET은 조회 성능을 위한 파생 데이터다.

## Decision Gate 확정 결과 (이번 착수 시점, 다시 논의하지 않음)

- **닉네임 노출**: 전체 노출(마스킹·익명화 없음). — `plan.md` "응답 DTO 필드 확정" 참고.
- **응답 DTO 필드**: 순위(rank)·닉네임(nickname)·실현손익(realizedPnl)·market. userId는 노출하지 않는다 — 근거는 `plan.md`.
- **다중 인스턴스 동시성 경합(1차)**: after-commit 이벤트에 손익값을 싣지 않고, 이벤트 처리 시점에 `accounts.realized_pnl`을 DB에서 다시 조회해 절댓값으로 ZADD한다(이벤트 도착 순서가 뒤바뀌어도 최종 수렴). 문제가 실제로 확인되면 분산 락 등으로 후속 고도화한다 — 지금 단계에서 과설계하지 않는다.
- **Redis 갱신 실패 시(1차)**: 재시도(backoff) 후에도 실패하면 로그만 남기고 버린다(Outbox 없음). 매도 체결 자체(주문·체결·계좌 갱신)는 after-commit 리스너 실패의 영향을 받지 않고 정상 성공한다.
- **오류 코드**: 기존 `market`/`limit` 검증 패턴(`VALIDATION_ERROR`)을 재사용한다. 추가 오류 코드 없음.
- **Redis 키·자료구조**: 시장별 ZSET `ranking:{market}`(예: `ranking:STOCK`). 세부는 `plan.md`.
- **공동 순위 계산**: 애플리케이션 계층에서 보정(Redis ZSET 기본 순위 커맨드는 그대로 쓰지 않음). 세부는 `plan.md`.
- **Redis 유실 시 MySQL 원장으로부터의 재구성 절차**: 이 spec의 범위 밖(아래 "범위 제외" 참고, PRD RANK-001 원문에도 "세부 구현은 착수 시 확정"이라 되어 있으나 이번 이슈 #187의 제외 범위 지정에 따라 실제 배치/복구 잡 구현은 별도 이슈로 넘긴다).

## 사용자 시나리오

- 사용자는 `market=STOCK` 또는 `market=CRYPTO`로 해당 시장의 실현손익 상위 랭킹을 조회한다.
- 사용자는 `limit`을 생략해 기본 10건을 받거나, 값을 지정해 더 많거나 적은 건수를 받는다(범위는 비즈니스 규칙 참고).
- 사용자는 한 번도 매도 체결이 없어 랭킹에 없는 다른 회원을 랭킹 목록에서 볼 수 없다.
- 사용자는 동점자가 있으면 같은 순위 번호로 묶여 표시되고, 다음 순위 번호가 동점자 수만큼 건너뛰어짐을 확인한다.
- 사용자가 매도를 체결하면, 그 트랜잭션이 커밋된 뒤 랭킹 조회 결과에 갱신된 실현손익이 반영된다(체결 직후 즉시 반영되지 않을 수 있음 — 이벤트 처리는 비동기적 시점차가 있을 수 있으나 커밋 순서를 전제로 최종적으로는 반영된다).

## 요구사항

### RANK-001 전체 랭킹 조회

- [ ] `GET /api/rankings?market=&limit=`가 `market`(필수)·`limit`(선택, 기본 10)을 받는다.
- [ ] `market` 누락·미지원 리터럴은 400 `VALIDATION_ERROR`로 거부된다.
- [ ] `limit`이 50을 초과하면 400이 아니라 **50으로 클램핑**한다.
- [ ] `limit`이 1 미만(0 이하)이면 오류가 아니라 **10으로 클램핑**한다.
- [ ] 매도 체결 이력이 한 번도 없는 회원은 랭킹 대상에서 제외된다 — 실현손익이 정확히 0이어도 매도 이력이 있으면 포함되고 다른 회원과 동일한 동점 규칙이 적용된다.
- [ ] 정렬은 시장별 실현손익 내림차순이다.
- [ ] 동점자는 공동 순위를 부여받고, 다음 순위는 동점자 수만큼 건너뛴다(예: 공동 1위 2명 다음 순위는 3위).
- [ ] score는 시장별 계좌(`accounts`, `UNIQUE(user_id, market)`)의 `realized_pnl` 컬럼 값을 그대로 쓴다.
- [ ] 매도 체결로 `accounts.realized_pnl`이 갱신되는 시점(`OrderExecutionService.execute()`의 FIFO 배분 확정, `account.addRealizedPnl(...)` 호출 직후)에 **커밋 이후(after-commit)**에만 랭킹에 반영된다 — 커밋 전 갱신·롤백 시 Redis 오염이 없다.
- [ ] 닉네임은 전체 노출한다(마스킹·익명화 없음).

## 비즈니스 규칙

- **매도 이력 유무가 포함 기준이다, 손익 값이 아니다.** 이력 없는 회원까지 포함하면 대다수가 0으로 동점 처리되어 랭킹의 의미가 없어진다.
- **정본은 MySQL, Redis는 파생 데이터다.** 실현손익의 정본은 `accounts.realized_pnl`이며 Redis ZSET은 조회 성능을 위한 파생 데이터다. 매 조회마다 `trades`를 재집계하지 않는다.
- **커밋 이후에만 반영한다.** Redis는 MySQL 트랜잭션에 참여하지 않으므로, 커밋 전에 갱신하면 롤백 시 Redis에만 반영이 남을 수 있다. 반드시 커밋이 확정된 뒤에 갱신한다.
- **이벤트 도착 순서가 뒤바뀌어도 최종 수렴해야 한다.** 같은 사용자의 연속 매도가 커밋 순서와 다르게 이벤트로 처리되어도, 이벤트 처리 시점에 DB에서 최신 `realized_pnl`을 다시 조회해 절댓값으로 갱신하므로 최종 결과는 DB 값과 일치한다.
- **Redis 갱신 실패가 매도 체결을 되돌리지 않는다.** after-commit 리스너가 재시도 후에도 실패하면 로그만 남기고 버린다. 이미 커밋된 매도 체결(주문·체결·계좌 갱신)에는 영향을 주지 않는다.
- **동점자는 공동 순위를 받는다.** 실현손익이 완전히 같은 회원은 같은 순위 번호를 받고, 다음 순위는 동점자 수만큼 건너뛴다. Redis ZSET 기본 순위 커맨드는 동점이어도 멤버를 사전순으로 순차 배정해 이 규칙과 다르게 동작하므로, 공동 순위 계산은 애플리케이션 계층에서 별도로 보정한다.

## 범위 제외

- RANK-002(`GET /api/rankings/me`, 내 랭킹 조회) — 이 spec이 만드는 인프라·보정 로직을 재사용하는 후속 이슈로 분리한다. 이 spec의 다음 착수분에서 같은 파일에 이어서 기록한다.
- Redis 유실 시 MySQL 원장으로부터의 재구성 절차 세부 구현(실제 배치/복구 잡) — 트리거·절차 확정 자체도 이 spec의 범위가 아니며, 필요해지면 별도 이슈로 분리한다.
- 지정가(LMT) 체결로 인한 랭킹 반영 — 지정가가 아직 구현되지 않았으므로 시장가 체결(ORD-005)만 대상으로 한다.
- SSE·클라이언트 push — REST 조회만 제공한다(2026-08-03 확정).
- 프론트엔드(FinPlay 레포) 대응 — 별도 조율·별도 작업.

## 완료 조건

- [ ] `GET /api/rankings?market=&limit=`가 `market`(필수)·`limit`(선택, 기본 10)을 받는다
- [ ] `limit`이 50을 초과하면 400이 아니라 50으로 클램핑, 1 미만(0 이하)이면 10으로 클램핑한다(`GET /api/trades`의 범위 밖 400 `VALIDATION_ERROR`와 의도적으로 다름 — 이 차이를 테스트로 명시한다)
- [ ] `market` 누락·미지원 리터럴은 400 `VALIDATION_ERROR`로 거부된다
- [ ] 매도 체결 이력이 없는 회원은 랭킹 목록·순위 산정에서 제외된다(실현손익 0이지만 매도 이력이 있는 회원은 포함)
- [ ] 실현손익 내림차순 정렬이며, 동점자는 공동 순위를 부여하고 다음 순위는 동점자 수만큼 건너뛴다(ZSET 기본 순위와 다른 애플리케이션 계층 보정 — 이 보정 로직은 RANK-002에서도 재사용할 수 있는 형태로 분리한다)
- [ ] 매도 체결(`OrderExecutionService`의 FIFO 배분 확정, `accounts.realized_pnl` 갱신)이 커밋된 이후에만 랭킹에 반영된다(커밋 전 갱신·롤백 시 Redis 오염이 없다)
- [ ] 같은 사용자의 매도 체결 두 건이 커밋 순서와 다르게 랭킹 갱신 이벤트로 처리되어도(순서 역전 시뮬레이션), 최종 ZSET score가 DB의 최신 `realized_pnl`과 일치한다
- [ ] after-commit 랭킹 갱신이 Redis 장애로 재시도 후에도 실패하면 로그만 남기고, 매도 체결 자체(주문·체결·계좌 갱신)는 영향받지 않고 정상 성공한다(랭킹 갱신 실패가 체결을 롤백시키지 않는다)
- [ ] 위 Decision Gate 항목(응답 필드·오류코드·Redis 키 설계·동시성 경합·보정 로직·Redis 갱신 실패 시 보상 경로)이 확정되어 `docs/specs/014-ranking/spec.md`·`plan.md`에 기록된다
- [ ] `docs/api-routes.md`·`docs/api-contracts.md`에 `GET /api/rankings` 계약이 추가된다(컨트롤러 변경과 같은 커밋, CLAUDE.md 규칙 7)
- [ ] 신규 `docs/specs/014-ranking/spec.md`를 작성해 RANK-001 요구사항 및 Decision Gate 확정 결과를 정식 spec으로 기록한다(RANK-002는 후속 이슈에서 같은 spec에 이어서 기록)
- [ ] 단위(서비스 로직, Redis mock)·슬라이스(`@DataJpaTest`/`@WebMvcTest`)·통합(Testcontainers MySQL + Redis, 매도 체결→커밋→ZSET 반영 전체 흐름) 테스트를 작성한다(`docs/adr/0003-testing-strategy.md` 기준)
- [ ] `./gradlew build` 통과

## RANK-002 내 랭킹 조회 (2차 MVP)

> 이슈 [#233](https://github.com/finplay-team/finplay/issues/233). PRD 근거: `docs/prd.md` "RANK-002 내 랭킹 조회" 절(2026-08-05 정책 확정). 아래 요구사항·완료 조건은 위 RANK-001이 만든 Redis ZSET(`ranking:{market}`)·공동 순위 보정 로직(`countStrictlyGreater`)을 재사용한다 — 새 Redis 키·자료구조를 만들지 않는다(근거는 `plan.md` "RANK-002 설계").

### 개요

인증 사용자 본인의 시장별 실현손익 순위를 단건으로 조회하는 기능이다. RANK-001의 전체 랭킹 목록과 달리 상위 `limit`건 안에 들지 않아도 본인 순위는 항상 계산·반환된다.

### 사용자 시나리오

- 사용자는 `market=STOCK` 또는 `market=CRYPTO`로 본인의 실현손익 순위를 조회한다.
- 사용자가 RANK-001 전체 랭킹의 상위 노출 구간(예: 기본 10건) 안에 들지 않아도, 본인 순위는 정확한 보정값으로 반환된다.
- 사용자가 한 번도 매도 체결을 한 적이 없으면, 순위 필드가 null인 응답을 받는다 — 오류가 아니며 닉네임·실현손익(0)은 정상적으로 채워진다.
- 사용자는 자신의 순위만 조회할 수 있다. 다른 회원의 accountId·userId를 지정해 조회하는 기능은 없다.

### 요구사항

- [ ] `GET /api/rankings/me?market=`가 `market`(필수, RANK-001과 동일)을 받는다. 대상 사용자는 인증 토큰의 본인으로 고정되며, 다른 사용자를 지정하는 파라미터는 받지 않는다.
- [ ] `market` 누락·미지원 리터럴은 RANK-001과 동일하게 400 `VALIDATION_ERROR`로 거부된다.
- [ ] 인증되지 않은 요청은 401로 거부된다(다른 인증 필요 GET과 동일, `SecurityConfig` 기본 보호 재사용 — 별도 설정 추가 없음).
- [ ] 상위 `limit`건 안에 들지 않아도 본인의 정확한 보정 순위를 반환한다(순위표 노출 여부와 무관하게 계산).
- [ ] 순위는 RANK-001과 동일한 공동 순위 보정(`countStrictlyGreater` 기반)을 재사용해 계산한다 — 별도의 새 보정 공식을 만들지 않는다.
- [ ] 매도 체결 이력이 없는 사용자는 전용 상태값·오류 코드를 새로 만들지 않고 순위 필드만 null로 반환한다. 닉네임·실현손익은 매도 이력 유무와 무관하게 정상 값을 반환한다(근거는 아래 "비즈니스 규칙").
- [ ] 응답의 닉네임은 RANK-001과 동일하게 마스킹 없이 노출한다.

### 비즈니스 규칙

- **본인만 조회 가능하다.** 인증 토큰의 userId만 사용하고 요청 파라미터로 대상 사용자를 지정하지 않는다 — 구조적으로 타인 조회가 불가능하다(별도 권한 검사 로직 불필요).
- **순위 산정은 RANK-001과 동일하게 ZSET 상태를 기준으로 한다.** 매도 이력 유무 판정과 순위 계산에 쓰는 score는 모두 Redis ZSET에서 조회한 값을 쓴다 — DB `accounts.realized_pnl`을 직접 재사용하지 않는다. 근거: RANK-001 목록 조회와 동일한 순간의 ZSET 상태를 기준으로 계산해야 두 엔드포인트가 서로 다른 순위를 보여주는 불일치가 생기지 않는다(세부는 `plan.md`).
- **닉네임·실현손익 필드는 매도 이력과 무관하게 항상 채운다.** 닉네임은 회원 정보에서 채운다. **실현손익은 위 순위 산정과 같은 출처(ZSET score)에서 채운다** — DB `accounts.realized_pnl`을 그대로 노출하면 rank가 근거로 삼은 값과 응답 안에서 서로 어긋날 수 있기 때문이다(PR #234 리뷰 반영, 세부는 `plan.md`). 매도 이력이 없으면 두 값(ZSET score·DB realized_pnl) 모두 0이므로 이 경우엔 값이 갈리지 않는다. "랭킹 목록 대상에서 제외됨"(RANK-001)과 "본인 조회 응답에 참고 정보가 없음"은 다른 개념이다 — null이 되는 것은 순위(rank) 필드뿐이다.

### 범위 제외

- Redis 유실 시 MySQL 원장으로부터의 재구성 배치 — 이슈 #233이 명시적으로 제외했다. RANK-001에도 동일하게 남아 있는 미해결 Decision Gate로, 별도 이슈로 남긴다.
- 신규 오류 코드 — 기존 `VALIDATION_ERROR`(market 검증)·401(미인증)을 재사용한다. 추가 코드 없음(`NOT_FOUND`는 정상 가입 사용자라면 발생하지 않는 방어적 경로 — 아래 `plan.md` 참고).
- 신규 Redis 키·자료구조 — RANK-001의 `ranking:{market}` ZSET을 그대로 재사용한다. 새 키 설계는 이 이슈의 범위가 아니다.

### 완료 조건

- [ ] `GET /api/rankings/me?market=`가 `market`(필수)을 받고, 인증 사용자 본인의 순위만 반환한다
- [ ] `market` 누락·미지원 리터럴은 400 `VALIDATION_ERROR`로 거부된다
- [ ] 인증 없이 요청하면 401로 거부된다
- [ ] 상위 limit건에 들지 않아도 정확한 보정 순위를 반환한다(RANK-001의 `countStrictlyGreater` 로직 재사용, 세부는 `plan.md`)
- [ ] 매도 체결 이력이 없는 사용자는 순위 필드만 null이고, 닉네임·실현손익(0)은 정상 값을 반환한다(오류가 아님)
- [ ] 닉네임은 마스킹 없이 노출한다
- [ ] `docs/api-routes.md`·`docs/api-contracts.md`에 `GET /api/rankings/me` 계약이 추가된다(컨트롤러 변경과 같은 커밋, CLAUDE.md 규칙 7)
- [ ] `docs/prd.md` §3 구현 현황의 "랭킹 — 내 랭킹 조회(RANK-002)" 행이 완료로 갱신된다(근거는 이 PR 번호, CLAUDE.md 규칙 10)
- [ ] 단위(`RankingStoreTest`/`RankingServiceTest`, Redis·AccountService mock)·슬라이스(`@WebMvcTest RankingControllerTest`) 테스트를 작성한다 — 이 기능이 재사용하는 ZSET 쓰기·이벤트 흐름 자체는 RANK-001 통합 테스트가 이미 검증했으므로, 신규 Testcontainers 통합 테스트 추가는 필수로 요구하지 않는다(근거는 `plan.md` "테스트 계획")
- [ ] `./gradlew build` 통과
