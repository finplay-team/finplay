# Tasks: 일반 리스크관리 OCO (intentionId 없는 손절·익절 예약)

## 문서 (이 이슈)

- [x] **spec 확정** — `intentionId` 선택 파라미터화, holding당 `PENDING` 1건 불변식, 코인 전용 범위, PERCENT 기준가(holding 평균매수가) 결정을 `spec.md`에 확정한다.
- [x] **엔진·스키마 계획 확정** — 라우트 재사용 근거, 일반 경로 검증 순서, 멱등성(경로별 복잡도 차이), 잠금 순서, `exit_plans` 최종 스키마, 오류 코드를 `plan.md`에 확정한다.
- [x] **문서 소유권 재배치** — `016`(교육 chain·튜토리얼 판정), `019`(PRICE/PERCENT), `020`(코인 delta)과의 소유권 경계를 `spec.md`에 항목별로 명시한다.

## 후속 production (착수 시 한 건씩 이슈 생성)

- [x] **`exit_plans`/`exit_plan_conditions`/`exit_plan_idempotency_keys` migration + 공통 엔진 골격** — 이 spec이 확정한 최종 스키마로 최초 migration을 만들고, holding→plan 잠금·예약 원장 연동(`015`가 이미 구현한 `Holding.reserveQuantity()` 등 재사용)·baseline 저장까지의 생성 골격을 구현한다. `intentionId` 분기는 다음 두 항목이 각각 채운다. (이슈 #347, PR 예정, V35 migration + `ExitPlanCreationService`/`ExitPricePolicy`)
- [x] **일반 경로 생성·취소** — `POST /api/exit-plans`(`intentionId` 생략)와 `DELETE /api/exit-plans/{id}`의 holding 소유권·시장 제한(코인만)·holding당 PENDING 1건 검증(`EXIT_PLAN_ALREADY_EXISTS`)·단순 멱등 재조회 폴백을 구현한다. (이슈 #348, PR 예정, `ExitPlanController`/`ExitPlanService`/`ExitPlanIdempotentCreationService`/`ExitPlanCancelService`)
- [ ] **교육 경로 재접합** — 기존 `016`·`019`가 설계한 `intentionId` 지정 경로(chain 검증, key-first coordinator)를 이 spec의 공통 엔진 위로 이식하고, holding당 PENDING 1건 검증이 교육 경로에도 회귀 없이 적용됨을 확인한다.
- [x] **가격 트리거·GTC 체결** — 유효 가격 이벤트에서 두 경로 공통으로 익절·손절 시장가 청산, 반대 조건 자동 취소, 정확히 한 번 규칙을 구현한다(주식 세션 만료 분기는 포함하지 않음). (이슈 #349, PR 예정, `ExitPlanTriggerListener`/`ExitPlanFillService`)
- [ ] **목록·응답 계약 전환** — `GET /api/exit-plans?status=`에 `holdingId` 필드를 포함한 응답을 실제로 제공하고 `docs/api-routes.md`·`docs/api-contracts.md`를 이 spec의 계약으로 동기화한다.
- [ ] **통합·경합 검증** — `plan.md` 테스트 계획의 통합 시나리오(홀딩당 1건, 재생성 허용, 교육 경로 회귀, 주식 거부, 경합, 멱등 재시도)를 Testcontainers로 확정한다.

## 후속 확인 필요 (production 아님, 문서 동기화)

- [x] **`docs/prd.md` §2 동기화** — 2026-08-06 결정으로 방향이 바뀌었다: "일반 리스크관리 OCO를 2차로 앞당김"이 아니라 **"코인 튜토리얼 OCO를 2차 우선 구현하기로 한 2026-08-05 결정을 철회하고, 튜토리얼 OCO·일반 리스크관리 OCO를 함께 3차 MVP로 이동"**으로 §2 상세 절을 정리했다.
- [ ] **`docs/prd.md` §4 요구사항 ID 부여 방식** — 3차 MVP 착수 시 이 spec의 `RISK-OCO-*`를 실제 요구사항 ID로 채택할지, 기존 `EDU-PRACTICE-005`·`006`·`010`·`013`과의 관계를 어떻게 표기할지는 3차 착수 이슈가 결정한다. 이 계획 모드에서는 임의로 결정하지 않는다.
- [x] **`docs/prd.md` §3 구현 현황 동기화** — "투자 실습 — OCO exit plan 생성·목록·취소·트리거"·"진행 조회·가격 관찰·복기"·"코인 튜토리얼 정책 확정" 세 행에 2026-08-06 3차 이동 결정을 반영하고, 이 spec(`021`) 문서 확정을 별도 행으로 추가했다. **실제 첫 production PR이 머지되는 시점에는 CLAUDE.md 규칙 10에 따라 그 PR이 "미착수→완료/일부 완료" 판정과 근거를 다시 갱신한다** — 이번 갱신은 문서 상태·차수 표기만 바꾼 것이다.
