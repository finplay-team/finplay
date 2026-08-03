# Tasks: 3단계 투자 실습 튜토리얼

## 현재 이슈 — 문서 확정
- [x] PRD 2차 MVP의 3단계 실습과 3차 MVP의 투자 지식 교육·배지·RAG 코치를 분리하고 안전한 선행 API·OCO 계약을 확정한다.

아래 6개는 현재 이슈의 미완료 작업이 아닌 상위 구현 작업 그룹이다. 실제 이슈 생성 시 `plan.md`의 14개 후보처럼 API 하나 또는 트랜잭션 경계 하나로 나누며 production 구현은 현재 지시하지 않는다.

- [ ] 즐겨찾기 등록·순수 목록·해제 API와 `(user_id, instrument_id)` 유일 제약을 구현한다. GET은 write 없이 실제 favorite 포함 응답을 검증한다.
- [ ] 최초 intention의 공통 `practice_progresses` atomic insert-or-existing, 매수 전 손절·익절·수량 기록 API와 실제 시장가 매수 체결의 시각·종목·수량 연결 검증을 구현한다.
- [ ] 공통 예약 원장과 OCO exit plan 생성 트랜잭션을 구현한다: 서버 baseline 시세, 주식 OPEN replay session 귀속, 고정 잠금 순서, 진입가 범위, 동일 holding·수량, plan 단위 수량 1회 예약, 실패 시 무흔적. 실제 이슈는 plan의 OCO 생성·시장가 SELL·일반 지정가 SELL 후보로 각각 분리한다.
- [ ] OCO 순수 예약 목록·취소 API를 구현한다: GET 무쓰기와 실제 plan 포함 응답, 취소 시 두 조건 종결·예약 1회 반환.
- [ ] 유효 가격 이벤트 OCO 체결과 주식 15:30 자동 만료 트랜잭션을 각각 구현한다: 중복·역순 이벤트 최초 승자, 반대 조건 자동 취소, final observation, 코인 GTC, 가격 장애 중 PENDING, 생성·취소·만료 경합 원자성.
- [ ] 실습 순수 진행 조회와 관찰·복기 API를 구현한다: PENDING plan 전용 A·B 관찰, 서버 종결 전용 C, terminal plan 복기, progress → intention → plan 잠금, 서로 다른 eligible plan 동시 요청도 최초 reflection·completion·progress 완료만 201이고 나머지는 무저장 409, 정답·보상·LLM 없음.

후속 Controller가 실제 추가·변경되는 각 이슈에서만 `docs/api-routes.md`와 `docs/api-contracts.md`를 실제 매핑 기준으로 동기화한다.
