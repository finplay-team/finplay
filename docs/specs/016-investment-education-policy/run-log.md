# Run Log: 016-investment-education-policy

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 02:19 | reviewer(리뷰) | `git diff dev...HEAD` (PR #173, feat/172-favorite-delete) | conventions.md, ADR-0002, ADR-0003, spec.md candidate 3 |
| 03:40 | reviewer(리뷰) | `git diff 5d6cb00863f18d00b60982b476b60e96b6bd0b02...HEAD` (PR #176, feat/175-practice-intention, candidate 4 고유분만) | conventions.md, ADR-0002, ADR-0003, ADR-0004, spec.md candidate 4 |
| 04:10 | reviewer(리뷰, 후속) | `git diff HEAD` (작업트리, PR #176 후속 수정) | conventions.md, ADR-0002, ADR-0003, ADR-0004 — 03:40 권장 1건 재검증 |

## 모니터링 (사람용 요약)
- 02:19 — PR #173(candidate 3 즐겨찾기 해제) 리뷰 완료, 차단 0건 — 권장 2건, 머지 가능.
- 03:40 — PR #176(candidate 4 투자 실습 사전 의도 기록) 리뷰 완료, 차단 0건 — 권장 1건 / 참고 1건, 머지 가능.
- 04:10 — PR #176 후속: IllegalStateException을 BusinessException(INTERNAL_ERROR)로 교체하고 도달 불가 근거 주석 추가, 단위 테스트(progress row 누락 시 INTERNAL_ERROR·잠금/저장 미호출) 신규 추가 확인. GlobalExceptionHandler가 BusinessException을 ErrorCode.getHttpStatus()(500)와 공통 ErrorResponse 포맷으로 정상 매핑함을 확인. 회귀 없음, 차단 0건, 머지 가능.
