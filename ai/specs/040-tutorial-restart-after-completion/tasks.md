# Tasks: 완료 튜토리얼 attempt 재시작 허용 — 보상은 최초 완료 1회만

- [x] `PracticeAttempt.restart()`의 `COMPLETED` 가드 제거 + `PracticeAttemptRestartService.restart()`의
  완료 단락 분기 제거(정리·재시작을 실제로 수행하도록 변경) + 단위/슬라이스 테스트
- [x] `PracticeAttemptService.ensureAttempt()`의 "completion 존재 + attempt 비완료 + 기존 행" 조합을
  오류로 오판하던 분기 수정 + 단위/슬라이스 테스트
- [x] `PracticeHoldingReflectionService.createAttemptReflection()`에 최초 완료/재완료 분기 추가(재완료 시
  `practice_completions`·`practice_market_reflections`·`practice_progresses` 쓰기·보상 지급 생략, attempt만
  완료 처리) + 단위 테스트
- [x] `PracticeHoldingReflectionResponse`에 `rewardGranted` 필드 추가(2026-08-16 사용자 확인 — 채택) +
  관련 슬라이스 테스트 갱신
- [x] Testcontainers 통합 테스트: 최초 완료→보상 지급→재시작→재완료→보상 미지급·evidence 행 수 불변,
  배포 전 완료 데이터 seed 기반 소급 판정, 동시 재완료 요청 경합
- [x] 문서 동기화: `docs/api-contracts.md`의 restart/ensure/holding-reflections 3개 계약 갱신,
  `docs/specs/039-tutorial-flow-redesign/spec.md`에 TUTORIAL-FLOW-005 대체 표기 추가, `docs/prd.md` §3
  구현 현황 갱신
