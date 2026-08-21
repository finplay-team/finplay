# Tasks: 튜토리얼 손절·익절 자유 입력과 양쪽 체험 보장

항목 하나 = 커밋 하나(응집된 기능 조각). 1차(1~4)는 **이미 병렬로 구현 중**이므로 착수 전에 현재 브랜치 상태를
먼저 확인한다 — 이미 끝난 항목을 다시 만들지 않는다.

## 1차 — 제안 A (자유 입력)

- [ ] **1. 스키마 + 도메인 필드.** `V56`(착수 시점에
  `git ls-tree -r origin/dev --name-only -- src/main/resources/db/migration`으로 번호 재확인)으로
  `practice_attempts`·`practice_risk_snapshots`에 `exit_stop_loss_rate`·`exit_take_profit_rate`
  `DECIMAL(4,1) NULL` 추가(둘 다 NULL이거나 둘 다 양수 CHECK, 범위는 CHECK로 잠그지 않음, 백필 없음).
  엔티티 매핑과 **유효 비율 해석 접근자**(자유 비율 → `exit_preset` → 기본값 3/5)를 `PracticeAttempt`
  한 곳에만 만든다. `@DataJpaTest`로 영속과 NULL 해석 확인. `docs/erd.md` 같은 커밋 갱신.
- [ ] **2. 비율 확정 API.** `PUT /api/education/practice/attempts/{market}/exit-rates` + 요청 DTO.
  범위 [2,5]/[3,8] 양 끝 포함·소수 첫째 자리 검증(반올림해서 받지 않는다), 범위 상수는 한 곳에서만 정의.
  잠금·게이트 판정은 `selectExitPreset`과 **같은 경로를 공유**한다(409 집합 동일). `@WebMvcTest`로 400/409,
  단위 테스트로 경계값.
- [ ] **3. 진입 체결 시점 적용.** `createRiskSnapshotOnBuyFill`이 프리셋 대신 유효 비율로 기준선·자동 OCO를
  만들고, 적용 비율을 진입(snapshot)에 남긴다. 계산·반올림은 019 공식 그대로, 2단계 대본 제외(049)와
  진입당 1회(042)는 무변경. Testcontainers로 "프리셋에 없던 조합(2 + 8)이 그 선에서 자동 청산"과
  "재진입 대기 중 변경 → 재매수 시 새 값 적용"을 고정.
- [ ] **4. 응답 노출 + 단계 판정 확장 + 문서.** 진행 조회에 `exitStopLossRate`·`exitTakeProfitRate`(항상
  non-null)·`exitRateBounds` 추가, `entries[]`에 진입별 적용 비율 추가, `tutorialStageProgress.exitPresetSelected`
  판정을 "프리셋 또는 자유 비율을 정했는가"로 확장(게이트도 같은 산출식). 프리셋 필드는 **남긴다.**
  같은 커밋에서 `ai/api-routes.md`·`docs/api/education.md` 갱신(plan.md §문서 갱신의 1차 항목 전부)과
  `ai/prd.md` §3에 `EXITFREE-001~013` 행 추가 + 기존 `EXITPRESET` 행에 "001은 052가 뒤집었다" 한 줄.
- [ ] **5. 대본 도달 가능성 재확인.** 041 대본 검증 테스트에 경계 4값(손절 2·5, 익절 3·8) 부등식을 추가한다.
  **대본 파일은 바꾸지 않는다** — 통과하지 못하면 범위를 좁히는 것이 아니라 그 사실을 보고한다.

## 2차 — 제안 B·C (양쪽 체험 보장 · 체험 직후 반사실)

- [ ] **6. 겪음 판정과 권유 상태.** `exit_plans`의 attempt·run·status에서 파생하는 판정 서비스와 진행 조회의
  `exitExperience`(겪음 2값 + `bothExperienced` + `recommendedNext` = 먼저 겪은 쪽의 반대). **새 컬럼을
  만들지 않는다.** 수동 매도는 어느 쪽으로도 세지 않는다. 재시작 후 자동 초기화를 통합 테스트로 고정하고,
  둘 다 겪지 않아도 복기·완료·보상이 막히지 않음을 함께 고정한다.
- [ ] **7. 사용자 주도 예약 경로 + 체결 순간 노출.** `POST /api/education/practice/attempts/{market}/exit-plan`
  (현재 진입 전량, 진입당 write-once, 자동 예약과 배타 — 대본 식별자로 가른다)과 tick 응답의
  `exitPlanFills[]`. 반사실은 기존 `priceAfterSell`·`unrealizedPnlIfHeld`를 그대로 쓰고 **진행 중에 대본 종점
  가격을 쓰지 않는다.** 같은 커밋에서 `ai/api-routes.md`의 `POST /api/exit-plans` 교육 경로 서술과
  `docs/api/education.md`의 "OCO exit plan 생성 — 교육 경로 (계획)" 절을 이 결정으로 정리하고, `ai/prd.md`
  §3에 `EXITFREE-020~031` 반영.
