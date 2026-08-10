# Tasks: 시장가/지정가 매매 기반 3단계 투자 실습 완결 경로 (OCO 없이)

- [ ] **migration + 2단계 chain 해석 서비스** — `V{N}__create_practice_market_observations_and_reflections.sql`(착수 전 `origin/dev` 최신 V번호 재확인, plan.md 잠정 V27), `practice_market_observations`/`practice_market_reflections` 엔티티·repository, `favorite → intention → buyTrade → holding` chain 해석 로직(수량 정규화 비교, 최이른 buyTrade 선택, 우선순위 규칙) + 단위 테스트.
- [ ] **참조 가격선 계산 + evidence A/B 판정** — `019` PRICE/PERCENT 공식 재사용한 참조 손절가·익절가 계산(매 요청 재계산, snapshot 없음), evidence A(경계 접근)·B(2분 3회) 판정 로직 + 단위 테스트(경계값·PERCENT 반올림 포함).
- [ ] **`POST /api/education/practice/holding-observations`** — Controller·DTO·Service, holding 소유권 404, chain 실패 409 `PRACTICE_EVIDENCE_MISSING`, `PRICE_UNAVAILABLE` + `@WebMvcTest`.
- [ ] **`POST /api/education/practice/holding-reflections`** — Controller·DTO·Service, `practice_progresses` 잠금 + A/B 선행 검증 + `practice_completions` 원자 저장, 동시 요청 직렬화 테스트 + `@WebMvcTest`/경합 테스트.
- [ ] **`GET /api/education/practice`** — `InvestmentPracticeResponse`/`PracticeStepResponse`/`PracticeEvidenceResponse` 최초 구현(이 경로 기준), 완료 전 회귀·완료 후 불변 + `@WebMvcTest`.
- [ ] **통합 테스트 + 문서 동기화** — 즐겨찾기→의도→매수(시장가·코인 지정가 각각)→관찰→복기→완료 전체 흐름(주식 1개, 코인 1개), 재시작 유실 시나리오, `docs/api-routes.md`·`docs/api-contracts.md` 신규 3 엔드포인트 반영, `docs/prd.md` §2·§3 갱신(후속 확인 필요 항목 반영).
- [ ] **`./gradlew build` 통과 확인.**
