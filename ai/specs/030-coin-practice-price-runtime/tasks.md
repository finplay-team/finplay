# Tasks: 코인 튜토리얼 가상 가격 실행 환경

- [x] 세션 스키마·엔티티·Repository와 결정적 version 1 생성기를 구현하고 재기동 재현·ACTIVE unique를 검증한다. generator v1은 SHA-256·modulo·반올림·50% 하한을 독립 재계산한 값과 대조해 정확히 일치함을 확인했다. 테스트 41건 전부 통과(단위·`@DataJpaTest`·`@WebMvcTest`·Testcontainers 통합). (이슈 #318 — 생성·조회 API까지 포함, expectedTick next API는 후속 이슈로 이어짐)
- [x] 세션 생성·조회·expectedTick next API와 소유권·상태·경합 계약을 구현한다. (생성·조회는 이슈 #318, expectedTick next는 이슈 #319에서 완료 — owner 스코프 `SELECT FOR UPDATE`, tick 99 `COMPLETED` 전이, 단위·`@DataJpaTest`·`@WebMvcTest`·Testcontainers 동시성 테스트 포함)
- [x] 교육 전용 지정가 BUY API, 주문 session FK, 전용 가격 이벤트 체결과 마지막 tick 취소·예약 반환을 구현한다. (이슈 #320, 1안 — order가 세션 스코프 유스케이스 제공·education이 리스너 소유. `findPendingLimitOrdersToFill` 역방향 오염 차단 회귀 포함, 단위·`@DataJpaTest`·`@WebMvcTest`·Testcontainers 통합 테스트 전부 통과)
- [x] holding 관찰이 buyTrade/order에서 세션 가격을 역추적하고 세션 없는 기존 가격 경로를 유지하게 한다. (이슈 #321, 3안 — priceruntime 소유 `PracticePriceObservationService` 파사드가 `TradeService.findPracticePriceSessionId`로 세션 귀속을 역추적하고 owner·instrument 불일치는 409 `PRACTICE_EVIDENCE_MISSING`으로 거부. 단위·`@DataJpaTest`·Testcontainers 통합 테스트 전부 통과)
- [ ] 사용자·세션·일반 주문 격리와 다건 tick-vs-일반 취소 경합을 Testcontainers로 검증한다. 세션·지정가·관찰 production 구현이 모두 dev에 병합된 뒤 #313을 재개해 즐겨찾기부터 복기·완료까지 실제 API 전체 흐름과 서버 재기동 이어하기를 검증하고 `026/tasks.md`의 미완료 통합 항목을 닫는다.
- [ ] 각 controller 구현 커밋에서 030 계획 요청·응답·오류와 실제 코드를 대조하고, 구현된 라우트만 `ai/api-routes.md`의 실제 표로 옮기며 `docs/api-contracts.md`의 계획 상태를 제거한다. 계약 차이는 구현 전에 spec 변경으로 확정한다.
