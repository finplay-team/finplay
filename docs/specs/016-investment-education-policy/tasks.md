# Tasks: 3단계 투자 실습 튜토리얼

## 현재 이슈 — 문서 확정
- [x] #158: PRD 단계 분리와 10개 API 상세 계약, canonical UUID 멱등·새 transaction reconciliation, decimal·답변·evidence·진행 상태, intention/reflection과 favorite DELETE 경합 잠금, 실제 Spring transaction 경계, FK 기준 DDL, 15개 후보의 DAG·migration·transaction·test 소유권을 확정한다. `docs/api-routes.md`와 `docs/api-contracts.md`에는 구현 예정 계약으로 분리 등록하고 production은 변경하지 않는다.
- [x] #163 (candidate 1): 거래 가능한 종목의 즐겨찾기 등록 API `POST /api/favorites`와 `(user_id, instrument_id)` 유일 제약을 구현한다.
- [x] #168 (candidate 2): 본인 즐겨찾기를 등록 최신순으로 순수 조회하는 `GET /api/favorites`를 구현한다.
- [x] #172 (candidate 3): 본인 즐겨찾기를 해제하는 `DELETE /api/favorites/{instrumentId}`를 구현한다.
- [x] #175 (candidate 4): 공통 `practice_progresses`를 atomic insert-or-existing하고 진행 상태·favorite를 잠가 검증한 뒤, 매수 전 수량·손절가·익절가를 새 intention으로 기록하는 `POST /api/education/practice/intentions`를 구현한다.

아래 6개는 현재 이슈의 미완료 작업이 아닌 상위 구현 작업 그룹이다. 실제 이슈 생성 시 `plan.md`의 15개 후보처럼 API 하나 또는 트랜잭션 경계 하나로 나누며 production 구현은 현재 지시하지 않는다.

- [x] 즐겨찾기 등록·순수 목록·해제 API와 `(user_id, instrument_id)` 유일 제약을 구현한다. GET은 write 없이 실제 favorite 포함 응답을 검증한다.
- [ ] 최초 intention의 공통 `practice_progresses` atomic insert-or-existing, step 1 favorite와 같은 instrument 검증, 매수 전 손절·익절·수량 기록 API와 실제 시장가 매수 체결 chain을 구현한다. (candidate 4/#175의 intention 기록까지 완료했으며 실제 시장가 매수 체결 chain은 미완료)
- [ ] nullable `trades.stock_replay_session_id`와 주식 fill session 기록, 공통 reservation ledger, 기존 MARKET SELL `availableQuantity` 변경을 OCO보다 먼저 또는 같은 atomic release로 배포한 뒤 tutorial-only OCO 생성 orchestration을 활성화한다. 일반 LIMIT SELL도 ledger 선행 전 활성화하지 않으며 order는 education repository에 직접 의존하지 않는다.
- [ ] OCO 순수 예약 목록·취소 API를 구현한다: GET 무쓰기와 실제 plan 포함 응답, 취소 시 두 조건 종결·예약 1회 반환.
- [ ] 유효 가격 이벤트 OCO 체결과 주식 15:30 자동 만료 트랜잭션을 각각 구현한다: 중복·역순 이벤트 최초 승자, 반대 조건 자동 취소, final observation, 코인 GTC, 가격 장애 중 PENDING, 생성·취소·만료 경합 원자성.
- [ ] 실습 순수 진행 조회와 관찰·복기 API를 구현한다: owner·instrument chain, intention·trade·plan snapshot quantity equality와 저장된 holdingId 검증(현재 holding quantity 제외), PENDING plan 전용 A·B, 서버 종결 전용 C, 최초 완료만 201이고 나머지는 무저장 409.

후속 Controller가 실제 추가·변경되는 각 이슈에서 해당 계획 계약을 실제 매핑 기준으로 전환하고 두 전역 API 문서를 함께 동기화한다.
