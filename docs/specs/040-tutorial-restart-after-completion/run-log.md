# Run Log: 040-tutorial-restart-after-completion

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md "소스 변경 대상과 근거" #1, spec.md TUTORIAL-RESTART-001 |
| implementer | `.\gradlew.bat compileJava` | plan.md "소스 변경 대상과 근거" #3, spec.md TUTORIAL-RESTART-003 |
| implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md "소스 변경 대상과 근거" #4·"동시성 설계", spec.md TUTORIAL-RESTART-004~007 |

## 모니터링 (사람용 요약)
- `PracticeAttempt.restart()`의 COMPLETED 가드와 `PracticeAttemptRestartService.restart()`의 완료 단락 분기 제거, 단위/슬라이스 테스트 추가·갱신, 컴파일 통과.
- `PracticeAttemptService.ensureAttempt()`에서 completion 존재+attempt 비완료+기존 행 조합의 `PRACTICE_EVIDENCE_MISSING` 오판 제거(현재 상태 반환), 컴파일 통과. 테스트는 이번 항목 범위 아님(오케스트레이터 지시).
- `PracticeHoldingReflectionService.createAttemptReflection()`에 completion 존재 여부(기존 락 재사용) 기반 최초/재완료 분기 추가, `PracticeHoldingReflectionResponse`에 `rewardGranted` 필드 추가, 단위·슬라이스 테스트 추가·갱신, 컴파일 통과.
| reviewer(리뷰) | `git diff origin/dev...HEAD` | conventions.md, ADR-0002/0003/0004, spec.md TUTORIAL-RESTART-001~007 |
| implementer | Gradle 미실행(오케스트레이터가 순차 단독 실행) | 이슈 #426(PR #427) — spec.md 비즈니스 규칙 "attempt별 완료 여부는 `practice_attempts.status`로만 표현", 026 spec "완료 evidence 불변", ADR-0003 |

## 모니터링 (사람용 요약)
- 리뷰 완료, 차단 0건 — 보상 중복 지급 방지·소급 판정·동시성 잠금 순서 확인, 머지 가능.
- 이슈 #426(PR #427): `GET /api/education/practice`(진행 조회)가 "완료 기록 + 비완료 attempt"를 예전 완료 응답으로 덮어써, 이 spec이 연 재시작 흐름에서 매수 evidence·매도 기한·risk snapshot이 프론트에 전달되지 않던 문제를 수정. attempt가 있으면 attempt를 먼저 보고 attempt가 없을 때만 완료 기록으로 폴백하며, 재시작 중에도 최초 완료의 `completedAt`·`rewardAmount`는 유지한다(재완료 보상 미지급 규칙은 그대로).
