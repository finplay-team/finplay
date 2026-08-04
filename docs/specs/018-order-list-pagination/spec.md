# Spec: 주문 목록 market 필수 · 커서 페이지네이션

> 단계: **2차 MVP (팀 회의 표현: 1차 고도화)**. 이슈 #182.
>
> PRD 근거: **PORT-003**(`docs/prd.md`, 2026-08-04 확정, 이슈 #177 — [PR #178](https://github.com/finplay-team/finplay/pull/178)). 선행 근거: PORT-002(`GET /api/trades` — 동일 검증 방식·조회 패턴의 형제 API), ACCT-002(계좌 소유권 검증 `AccountService.getAccountFor`).
>
> 선행 spec: `006-portfolio-query`. **이 spec은 006의 PORT-003 1차 계약("페이지네이션 없이 전체 주문 반환")을 대체한다** — 006 spec.md의 해당 문장은 지우지 않고 "1차 고도화(이슈 #182, 018)에서 확장됨" 이력 표시만 남긴다. 006은 1차 MVP 범위로 이미 완료된 spec이고, 그 문장은 **당시 계약을 기록한 이력**이다. 완료된 1차 spec을 사후 편집해 2차 요구사항을 섞으면 무엇이 1차 계약이었는지 추적할 수 없어진다. 003→013, 011, 012도 같은 이유로 후속 폴더를 썼다.
>
> **문서 동기화 상태**: 이 spec의 확정 계약은 구현 착수 시 `docs/api-routes.md`·`docs/api-contracts.md`의 `GET /api/orders` 행을 같은 커밋에서 갱신한다(CLAUDE.md 규칙 7). 현재 두 문서에는 아직 1차 계약(페이지네이션 없음)이 남아 있다.

## 개요

`GET /api/orders`에 `market` 필수 파라미터와 커서 기반 페이지네이션(`cursor`·`limit`)을 도입한다. 형제 API `GET /api/trades`(PORT-002)가 이미 구현한 검증 방식·조회 패턴과 동일하게 통일한다. 현재는 `market` 구분 없이 인증 사용자의 전체 주문을 한 번에 반환하는데, 이는 **이미 배포된 응답 계약을 바꾸는 breaking change**다 — 프론트엔드(FinPlay 레포)와 사전 조율이 필요하다(PR #178에서 확정한 위험).

## 사용자 시나리오

- 사용자는 `market=STOCK` 또는 `market=CRYPTO`로 본인의 해당 시장 주문만 최신순으로 조회한다.
- 사용자는 커서 없이 요청해 최신순 첫 페이지를 받고, 응답의 `nextCursor`로 다음 페이지를 이어 조회해 중복·누락 없이 이전 페이지 끝에서 이어받는다.
- 사용자는 마지막 페이지에서 `hasNext=false`·`nextCursor=null`을 받아 더 조회할 데이터가 없음을 안다.
- 사용자가 `market`을 생략하거나 지원하지 않는 값을 보내면 400으로 즉시 거부된다.
- 사용자가 `limit`을 1~100 밖의 값으로 보내면 클램핑 없이 400으로 거부된다.
- 사용자가 형식이 잘못된 `cursor`를 보내면 400으로 거부된다.
- 사용자가 다른 사용자 소유이거나 존재하지 않는 계좌의 `market`으로 조회하면 거부된다.

## 요구사항

### PORT-003(1차 고도화) `market` 필수 · 커서 페이지네이션

- [ ] `GET /api/orders`가 `market`(필수)·`cursor`(선택)·`limit`(선택, 기본 20)을 받는다.
- [ ] `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴은 400 `VALIDATION_ERROR`다 — `GET /api/trades`와 동일한 검증 방식.
- [ ] `limit`이 1~100 범위 밖이면 400 `VALIDATION_ERROR`다. 클램핑(범위 밖 값을 1 또는 100으로 보정)하지 않는다.
- [ ] `cursor` 형식 파싱에 실패하면 400 `VALIDATION_ERROR`다.
- [ ] 조회 대상은 요청에서 받지 않고 인증 사용자 본인 소유의 해당 `market` 계좌로만 결정한다(`AccountService.getAccountFor`로 소유권+시장 스코프 검증). 다른 사용자 소유이거나 존재하지 않는 계좌의 `market`이면 조회가 거부된다.
- [ ] 응답은 `content`/`nextCursor`/`hasNext` 형태의 페이지네이션 응답이다.
- [ ] 커서 없이 요청하면 최신순(동시각은 `id` 내림차순) 첫 페이지를 반환한다.
- [ ] `nextCursor`로 이어 조회하면 중복·누락 없이 이전 페이지 끝에서 이어진다.
- [ ] 마지막 페이지는 `hasNext=false`·`nextCursor=null`이다.
- [ ] 기존 응답 필드(`orderId`·`market`·`instrumentId`·`side`·`orderType`·`status`·`quantity`·`requestedAt`) 8개에 회귀가 없다.

## 비즈니스 규칙

- 조회 스코프는 항상 "인증 사용자 본인 + 요청 `market`의 계좌"다. path·body의 id로 타 계정 데이터에 접근하는 경로가 아니므로, 소유권 위반은 존재 자체를 숨기지 않고 `GET /api/trades`와 동일하게 판정한다(계좌를 찾지 못하면 404 `NOT_FOUND`).
- 정렬 기준은 `requestedAt DESC, id DESC`로 고정한다 — 동시각 주문의 순서를 안정적으로 보장한다.
- 페이지 경계에서 중복·누락이 없어야 한다 — 커서는 "이전 페이지 마지막 행보다 이 시각 이전이거나(동시각이면 이 id보다 작은)" 조건으로 다음 페이지를 이어받는다.
- 이 변경은 응답 형태를 배열(`OrderListItemResponse[]`)에서 페이지네이션 객체(`content`/`nextCursor`/`hasNext`)로 바꾸는 breaking change다 — 프론트엔드 대응은 이 spec의 범위가 아니다(별도 조율).

## 범위 제외

- 지정가(`PENDING`/`CANCELLED`) 상태 도입, `Order` 엔티티 상태 전이 로직 — `015-limit-order` 착수 시 별도로 다룬다(`OrderStatus`가 현재 `FILLED` 하나뿐이고 생성자에서 고정 배정됨).
- `GET /api/orders/pending`(LMT-004, 미체결 지정가 목록) 구현 — 별도 스펙·이슈.
- 프론트엔드(FinPlay 레포) 쪽 대응 변경 — 별도 조율·별도 작업.
- 스키마 변경·신규 Flyway 마이그레이션 — `Order` 엔티티에 이미 `account` 연관관계가 있어 필요 없다.
- `docs/prd.md` "API 계약" 섹션(790행 근처)의 `symbol`·`requestedQuantity` 필드 서술과 실제 코드(`quantity`, `symbol` 없음)의 드리프트 — 이번 이슈 이전부터 있던 것이며, 이 spec은 실제 코드·`docs/api-contracts.md` 정본 기준(`quantity`, `symbol` 없음)을 그대로 따른다. PRD 문구 정정은 이 spec의 범위가 아니다.

## 완료 조건

- [x] `market` 누락·미지원 리터럴이 400 `VALIDATION_ERROR`로 거부되는 테스트 통과. (`OrderControllerTest#getMyOrdersRejectsMissingMarketWithoutCallingService`, `#getMyOrdersRejectsInvalidMarketLiteralWithoutCallingService`)
- [x] `limit`이 1~100 범위 밖(클램핑 없음)이면 400 `VALIDATION_ERROR`로 거부되는 테스트 통과. (`OrderControllerTest#getMyOrdersRejectsLimitBelowMinimumWithoutCallingService`, `#getMyOrdersRejectsLimitAboveMaximumWithoutCallingService`)
- [x] `cursor` 형식 파싱 실패가 400 `VALIDATION_ERROR`로 거부되는 테스트 통과. (`OrderCursorTest`의 파싱 실패 케이스 + `OrderControllerTest#getMyOrdersReturnsBadRequestWhenServiceRejectsMalformedCursor` + `OrderServiceTest#getMyOrdersPropagatesExceptionThrownByCorruptedCursorWithoutQueryingRepository`)
- [x] 다른 사용자 소유이거나 존재하지 않는 계좌의 `market`으로 조회하면 거부되는 테스트 통과(계좌 소유권 검증). (`OrderListIntegrationTest#getMyOrdersRejectsWhenAccountForRequestedMarketDoesNotExist`)
- [x] 커서 없이 요청하면 최신순 첫 페이지를 반환하고, 응답의 `nextCursor`로 이어 조회하면 중복·누락 없이 이전 페이지 끝에서 이어지는 테스트 통과. 마지막 페이지는 `hasNext=false`·`nextCursor=null`인 테스트 통과. (`OrderRepositoryTest`의 커서 경계 테스트 3건 + `OrderListIntegrationTest#cursorPaginationAcrossPagesMatchesSinglePageFetchInSetAndOrderAndLastPageHasNoNext`)
- [x] 기존 8개 응답 필드(`orderId`·`market`·`instrumentId`·`side`·`orderType`·`status`·`quantity`·`requestedAt`)에 회귀가 없는 테스트 통과. (`OrderControllerTest#getMyOrdersReturnsOkWithEveryFieldWhenMarketIsStock`, `OrderListIntegrationTest#getMyOrdersReturnsOwnOrdersNewestFirstWithFieldContractAndExcludesOtherUsers`)
- [x] `docs/api-routes.md`·`docs/api-contracts.md`의 `GET /api/orders` 계약이 새 시그니처·응답 형태로 갱신됨(컨트롤러 변경과 같은 커밋, 커밋 `3bd1cb8`).
- [x] `docs/specs/006-portfolio-query/spec.md`에 이력 각주가 추가되고 본문은 보존됨(003→013 선례와 동일한 패턴, 커밋 `411877f`).
- [x] `OrderControllerTest`·`OrderServiceTest`·`OrderRepositoryTest`·`OrderListIntegrationTest` 4종이 갱신·추가됨(`docs/adr/0003-testing-strategy.md` 기준. `OrderCursorTest`도 신규 추가).
- [x] `./gradlew build` 통과. (죽은 코드 정리 후 최종 확인 완료)
