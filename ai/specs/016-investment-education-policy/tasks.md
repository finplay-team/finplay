# Tasks: 3단계 투자 실습 튜토리얼

## 현재 이슈 — 문서 확정
- [x] #158: PRD 단계 분리와 10개 API 상세 계약, canonical UUID 멱등·새 transaction reconciliation, decimal·답변·evidence·진행 상태, intention/reflection과 favorite DELETE 경합 잠금, 실제 Spring transaction 경계, FK 기준 DDL, 15개 후보의 DAG·migration·transaction·test 소유권을 확정한다. `docs/api-routes.md`와 `docs/api-contracts.md`에는 구현 예정 계약으로 분리 등록하고 production은 변경하지 않는다.
- [x] #163 (candidate 1): 거래 가능한 종목의 즐겨찾기 등록 API `POST /api/favorites`와 `(user_id, instrument_id)` 유일 제약을 구현한다.
- [x] #168 (candidate 2): 본인 즐겨찾기를 등록 최신순으로 순수 조회하는 `GET /api/favorites`를 구현한다.
- [x] #172 (candidate 3): 본인 즐겨찾기를 해제하는 `DELETE /api/favorites/{instrumentId}`를 구현한다.
- [x] #175 (candidate 4): 공통 `practice_progresses`를 atomic insert-or-existing하고 진행 상태·favorite를 잠가 검증한 뒤, 매수 전 수량·손절가·익절가를 새 intention으로 기록하는 `POST /api/education/practice/intentions`를 구현한다.
- [x] #186 (candidate 5): nullable `trades.stock_replay_session_id` FK를 추가하고 주식 체결에는 현재 replay session id를 기록하며 코인 체결은 null을 유지한다.
- [x] #199 (후속 문서): 기존 절대 가격 intention 호환을 유지하면서 PRICE/PERCENT 입력, 실제 BUY 체결가 기준 퍼센트 계산과 OCO 실행 가격선 snapshot 계약을 `docs/specs/019-exit-price-policy`로 확정한다. production 확장은 019 tasks의 후속 이슈다.

아래 7개는 현재 이슈의 미완료 작업이 아닌 상위 구현 작업 그룹이다. 실제 이슈 생성 시 `plan.md`의 15개 후보처럼 API 하나 또는 트랜잭션 경계 하나로 나누며 production 구현은 현재 지시하지 않는다.

- [x] 즐겨찾기 등록·순수 목록·해제 API와 `(user_id, instrument_id)` 유일 제약을 구현한다. GET은 write 없이 실제 favorite 포함 응답을 검증한다.
- [x] holding 기반 최초 intention의 공통 `practice_progresses` atomic insert-or-existing, step 1 favorite와 같은 instrument 검증, 매수 전 손절·익절·수량 기록 API와 실제 매수 체결 chain을 구현한다. candidate 4/#175와 026 경로 PR #295에서 완료했으며 OCO 전용 chain은 아래 별도 작업이 소유한다.
- [ ] OCO 전용 `POST /api/education/practice/oco/intentions`를 구현한다. 종목 market별 OCO progress 생성·잠금, API 비노출 `tutorialKey` record 필드 귀속, holding/OCO intention 상호 사용 거부와 두 경로의 완료 독립성을 단위·WebMvc·통합 테스트로 검증한다.
- [ ] nullable `trades.stock_replay_session_id`와 주식 fill session 기록, 공통 reservation ledger, 기존 MARKET SELL `availableQuantity` 변경을 OCO보다 먼저 또는 같은 atomic release로 배포한 뒤 tutorial-only OCO 생성 orchestration을 활성화한다. (candidate 5·6 완료, OCO 생성 orchestration은 미완료) 일반 LIMIT SELL도 ledger 선행 전 활성화하지 않으며 order는 education repository에 직접 의존하지 않는다.
- [ ] OCO 순수 예약 목록·취소 API를 구현한다: GET 무쓰기와 실제 plan 포함 응답, 취소 시 두 조건 종결·예약 1회 반환.
- [ ] 유효 가격 이벤트 OCO 체결과 주식 15:30 자동 만료 트랜잭션을 각각 구현한다: 중복·역순 이벤트 최초 승자, 반대 조건 자동 취소, final observation, 코인 GTC, 가격 장애 중 PENDING, 생성·취소·만료 경합 원자성.
- [ ] 실습 순수 진행 조회와 관찰·복기 API를 구현한다: owner·instrument chain, intention·trade·plan snapshot quantity equality와 저장된 holdingId 검증(현재 holding quantity 제외), PENDING plan 전용 A·B, 서버 종결 전용 C, 최초 완료만 201이고 나머지는 무저장 409.

후속 Controller가 실제 추가·변경되는 각 이슈에서 해당 계획 계약을 실제 매핑 기준으로 전환하고 두 전역 API 문서를 함께 동기화한다.

## Issue #193 — 즐겨찾기·사전 의도 인메모리 전환 + 튜토리얼 합성 시세

`plan.md`의 "Issue #193 설계 결정"(트랜잭션과 경합 섹션 상단, 데이터 모델, 합성 시세 생성 규칙)과 ADR-0012를 따른다. 커밋 단위는 아래 항목 굵기를 기준으로 하되, 2·3번은 서로 강하게 얽혀 있어 한 커밋으로 묶어도 된다.

- [x] 1. `V19__drop_favorites_and_practice_intentions.sql` migration을 추가해 `favorites`, `practice_intentions` 테이블을 DROP한다. `practice_progresses`는 건드리지 않는다. V14·V16 파일은 수정하지 않는다(ADR-0004).
- [x] 2. `Favorite`(`@Entity`)·`FavoriteRepository`(JPA)를 제거하고 `FavoriteService`를 `ConcurrentHashMap` 기반 인메모리 저장 + 사용자 단위 `ReentrantLock`으로 재작성한다. `AtomicLong`으로 `favoriteId`를 채번한다. `PracticeIntentionService`가 사용할 `withFavoriteLock(userId, instrumentId, action)` 같은 락 대여 메서드를 함께 제공한다. `FavoriteController`의 요청/응답 계약(경로·DTO·오류 코드)은 바꾸지 않는다.
- [x] 3. `PracticeIntention`(`@Entity`)·`PracticeIntentionRepository`(JPA)를 제거하고 `PracticeIntentionService`를 인메모리 저장(사용자별 리스트)으로 재작성한다. `practice_progresses`의 기존 `INSERT ... ON DUPLICATE KEY UPDATE` upsert + `SELECT ... FOR UPDATE` 잠금은 그대로 유지하고, 그 안에서 `FavoriteService`가 제공하는 in-memory 락을 사용하도록 잠금 순서를 `progress(DB) → favorite 락(in-memory)`로 맞춘다. `PracticeIntentionController`의 계약은 바꾸지 않는다.
- [x] 4. 튜토리얼 전용 합성 시세 서비스+컨트롤러를 `com.finplay.api.education` 아래 신규 패키지로 추가한다: `GET /api/education/practice/synthetic-prices/{instrumentId}` → `SyntheticPriceSeriesResponse(title, tickSeconds, prices)`. `plan.md`의 생성 규칙(랜덤워크 파라미터, 틱 수, clamp)을 따르고 저장소는 두지 않는다.
- [x] 5. 기존 DB/Testcontainers 기반 테스트를 재작성·삭제한다: `FavoriteRepositoryTest`(삭제 — JPA repository 자체가 없어짐), `FavoriteConcurrencyIntegrationTest`(인메모리 동시성 단위 테스트로 재작성), `FavoriteServiceTest`·`FavoriteControllerTest`(인메모리 저장소 기준으로 갱신), `PracticeRepositoryTest`의 practice_intentions 관련 부분(삭제 또는 재작성), `PracticeIntentionServiceTest`·`PracticeIntentionControllerTest`·`PracticeIntentionConcurrencyIntegrationTest`(인메모리 favorite 락 기준으로 갱신). 새 합성 시세 컨트롤러의 `@WebMvcTest`·서비스 단위 테스트를 추가한다.
- [x] 6. 문서 동기화: 이 spec의 candidate 1~4 관련 서술과 실제 코드 일치 확인, `docs/api-routes.md`·`docs/api-contracts.md`에 신규 합성 시세 라우트·계약 추가(FavoriteController·PracticeIntentionController 자체의 경로는 안 바뀌므로 그 두 문서의 기존 favorite/intention 행은 변경 불필요 — 신규 합성 시세 행만 추가), `docs/agent-mistakes.md`에 인메모리 전환 중 실제로 재현된 실수가 있으면 기록한다.

### 미확정 사항 — 최종 확정값
- 인메모리 상태 스코프: `@Service` 싱글턴 빈 내부 필드로 확정(별도 컴포넌트로 분리하지 않음). 근거는 ADR-0012. `FavoriteService`·`PracticeIntentionService` 구현이 이 결정대로다.
- 합성 시세 틱 수는 100(시작가를 첫 틱으로 포함, 101이 아님)으로 확정했다. 시작가는 `PriceQueryService.getPriceQuote`로 조회한 실제 유효 현재가를 쓰고, 조회 실패(`PriceStatus.AVAILABLE`이 아님)일 때만 고정 fallback `10,000`을 쓴다(임의 난수 시작가 아님). `SyntheticPriceService`, `docs/specs/016-investment-education-policy/plan.md`의 "합성 시세 생성 규칙(#193, 확정)" 절에 반영했다. PRD에는 이 기능 자체가 없어(합성 시세는 이번 확정 배경에서 처음 도입) 상위 요구사항 ID가 없다는 점도 참고한다.
