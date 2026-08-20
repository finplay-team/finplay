# Tasks: 코인 투자 실습 튜토리얼 정책

## 문서 (이 이슈, #222)

- [x] **spec 확정** — 코인 실습의 tutorial identity, 3단계 코인 정의, GTC 수명, 관찰 A·B·C, 소수 수량 비교, 빗썸 가격 이벤트, 오류 계약을 `spec.md`에 확정한다.
- [x] **계약 delta 확정** — market → tutorial key 매핑, 엔드포인트별 코인 delta, 잠금 순서 표, 테스트 계획과 트레이드오프를 `plan.md`에 확정한다.
- [x] **`016` 상호 참조** — 코인 경로 정본이 이 spec임을 `016/spec.md`에 한 줄로 연결한다. 주식 규칙은 수정하지 않는다.

## 후속 production (착수 시 한 건씩 이슈 생성)

- [x] **tutorial key market 분기** — `PracticeIntentionService`의 단일 상수를 종목 market 해석으로 바꾸고 `COIN_PRACTICE_V1` progress 확보·잠금을 구현한다. migration 없음. 기존 행 백필 없음. 두 key가 사용자당 독립 행으로 공존함을 `@DataJpaTest`로 검증한다. (이슈 #226)
- [ ] **코인 favorite chain 검증** — 코인 실습 chain의 market 일관성(favorite·intention·buyTrade·holding·exitPlan 전부 `CRYPTO` 동일 종목)을 검증하고 불일치를 정의된 409로 무흔적 거부한다.
- [ ] **소수 수량 비교 정책** — 3자 snapshot equality를 `compareTo` 기반으로 구현하고 멱등 fingerprint 수량 정규화를 함께 적용한다. 단위 테스트로 `0.1` vs `0.10000000` 경계를 고정한다.
- [ ] **코인 OCO 생성·취소 분기** — 세션 검증·15:30 조건 없이 `holding → plan` 잠금으로 생성·취소하고 `replaySessionId` null을 저장한다. 코인 상태 머신에 만료 전이를 만들지 않는다. `016` candidate 7과 같은 이슈에서 처리할 수 있다.
- [ ] **코인 GTC 회귀 검증** — 만료 scan 실행 후에도 코인 plan이 `PENDING`·예약 수량 불변임을 통합 테스트로 고정하고, 취소 ↔ 트리거 경합의 정확히 한 번 규칙을 검증한다.
- [ ] **코인 관찰·복기** — A·B는 그대로, C는 체결 트랜잭션만 `FINAL_EVENT`를 생성하도록 구현하고 `COIN_PRACTICE_V1` completion 최초 1회 생성과 완료 후 비회귀를 검증한다.
- [ ] **두 튜토리얼 독립 완료 검증** — 주식 실습 완료 사용자가 코인 실습을 처음부터 진행·완료하고 두 progress·completion이 서로 간섭·회귀하지 않음을 통합 테스트로 고정한다.
