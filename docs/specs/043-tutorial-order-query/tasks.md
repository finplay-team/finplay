# Tasks: 튜토리얼 attempt 전용 주문 조회

- [ ] order 도메인 조회 추가 — `OrderRepository.findPracticeRunOrders`(비잠금, instrument fetch join,
  `id` 오름차순) + `OrderService.getPracticeRunOrders(attemptId, runNumber)` + `@DataJpaTest`(다른
  run/attempt/일반 주문 제외, 정렬) + 단위 테스트(빈 결과·매핑)
- [ ] education 도메인 조회 서비스·컨트롤러 — `PracticeAttemptOrderQueryService.getCurrentRunOrders`
  (attempt 없음 → 빈 목록) + `PracticeAttemptOrderController`(`GET /api/education/practice/attempts/{market}/orders`)
  + `@WebMvcTest`(200 스키마, 401, 잘못된 `market` 400) + 단위 테스트(Mockito)
- [ ] 통합 테스트(Testcontainers) — 지정가 매수 생성→`PENDING` 조회 확인→tick 체결→`FILLED` 재조회
  확인→재시작→이전 run 주문 제외 확인, 기존 `GET /api/orders`·`GET /api/orders/pending` 샌드박스 제외
  회귀 재확인
- [ ] 문서 갱신 — `docs/api-routes.md`·`docs/api-contracts.md`에 새 엔드포인트 반영(CLAUDE.md 규칙 7),
  `docs/prd.md` §3 신규 행 여부 판단
