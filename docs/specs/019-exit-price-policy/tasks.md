# Tasks: OCO 손절·익절 가격·퍼센트 입력 정책

- [ ] **intention 인메모리 tagged union** — ADR-0012의 record/repository에 내부 UUID instance key와 PRICE/PERCENT 배타 필드를 추가하고 불변식·동시성 단위 테스트를 작성한다. DB 테이블·migration은 만들지 않는다.
- [ ] **intention API 호환 확장** — 구형 PRICE 요청을 유지하면서 명시 PRICE/PERCENT 입력 검증과 모드별 응답을 구현하고 두 전역 API 문서를 actual로 동기화한다.
- [ ] **OCO 가격선 계산 정책** — PRICE 복사, PERCENT entry price 계산, scale 8 HALF_UP과 범위 오류를 순수 정책·단위 테스트로 구현한다.
- [ ] **OCO 생성 계약 반영** — 4필드 요청 fingerprint, intention instance key·snapshot 전달, plan 실행 가격선·원본 rate 저장과 예약 원자성을 candidate 7 구현에 반영한다.
- [ ] **통합·회귀 검증** — 두 입력 방식의 intention → BUY → OCO 흐름, 멱등성, 실패 무흔적, 기존 PRICE 및 시장가 주문 회귀를 전체 build로 검증한다.
