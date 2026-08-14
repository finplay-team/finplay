# Tasks: 튜토리얼 흐름 재설계 — 영속 attempt·자동 위험 스냅샷·단일 라이브 차트

- [x] **attempt·run 귀속 기반 데이터 모델** — 사용자·시장 유일 `practice_attempts`, 실행별 불변
  `practice_risk_snapshots`, nullable order attempt/run 귀속을 추가형 Flyway migration과 엔티티·Repository로
  구현한다. unique/check/nullable 구버전 호환과 실행 세대 격리를 MySQL `@DataJpaTest`로 검증한다.

- [x] **진입 ensure·선택·자동 위험 snapshot** — 기존 ACTIVE run을 바꾸지 않는
  `PUT /attempts/{market}` ensure/read와 instrument 선택 API, attempt 상태 전이, 샘플 주문의 현재 run 서버
  귀속, 최초 BUY fill의 entry/-3%/+5% snapshot 원자 저장을 구현한다. legacy intention API는 유지하되 새
  흐름의 전제에서 제거하고 reload가 restart를 일으키지 않는 STOCK·CRYPTO 단위·MVC 테스트를 작성한다.

- [ ] **명시적 원자 재시작·완료 replay** — `POST /attempts/{market}/restart`에서만 현재 run pending 주문
  취소/예약 반환, FILLED BUY-SELL 순수량 계산,
  canonical price 보상 SELL, run 증가와 선택 상태 초기화를 한 트랜잭션으로 구현한다. 다른 사용자·시장·run·
  일반 주문 격리, 부분 매도, 수량 불일치 rollback, 동시 restart, 완료 attempt 무변경 replay를 통합 검증한다.

- [ ] **결정적 canonical price와 29+1 차트** — attempt seed/version/anchor 기반 순수 생성기, 12:00 시작
  3초=1분 clock, 29개 완결 일봉+현재 일봉 API를 구현하고 샘플 quote·시장가/교육 지정가 체결·관찰·보상
  SELL이 같은 minute close를 쓰게 연결한다. golden vector, 시간 경계, reload·Spring Context 재생성 테스트를
  추가한다.

- [ ] **진행·완료 경로 통합과 회귀 테스트** — `GET /api/education/practice`를 attempt/risk/replay evidence로
  확장하고 두 시장의 선택→BUY→관찰→SELL→복기→completion/reward 전체 흐름을 Testcontainers로 검증한다.
  `026`·`030`·`031`·`033` 및 실제 종목 주문/가격 회귀를 함께 실행한다.

- [ ] **프론트엔드 companion·API/PRD 동기화** — 프론트에서 사전 의도 폼을 제거하고 서버 attempt 진입,
  restart 확인, 자동 risk 카드, 단일 29+1 polling chart, 완료 read-only replay를 연결한다. 실제 controller
  mapping에 맞춰 `docs/api-routes.md`·`docs/api-contracts.md`를 함께 갱신하고, 기능 완료 시 `docs/prd.md` §3에
  TUTORIAL-FLOW-001~012와 PR 번호를 반영한 뒤 대상 테스트와 전체 `gradlew build` 결과를 기록한다.
