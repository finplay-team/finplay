# Spec: 전체 랭킹 조회 (RANK-001)

> 단계: **2차 MVP (팀 회의 표현: 1차 고도화)**. 이슈 [#187](https://github.com/finplay-team/finplay/issues/187).
>
> PRD 근거: **RANK-001**(`docs/prd.md` "랭킹" 절, 2026-08-03 정책 확정 이슈 [#139](https://github.com/finplay-team/finplay/issues/139) — 완료). RANK-002(`GET /api/rankings/me`, 내 랭킹 조회)는 이 spec이 만드는 인프라·보정 로직을 재사용하는 후속 이슈에서 같은 spec 파일에 이어서 기록한다(이번 착수 범위 아님).
>
> **문서 동기화 상태**: 이 spec 확정 시점에 아직 컨트롤러가 없다. 구현 착수 시 `docs/api-routes.md`·`docs/api-contracts.md`에 `GET /api/rankings` 계약을 같은 커밋에서 추가한다(CLAUDE.md 규칙 7).

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
