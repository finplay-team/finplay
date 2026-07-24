---
name: feature
description: spec 폴더 하나를 받아 구현→테스트 루프를 돌리고 빌드·리뷰로 마무리한다 (QA는 /review-pr에서). spec이 없으면 planner가 먼저 작성한다. 사용법: /feature docs/specs/001-foundation
---

# /feature — 기능 개발 루프

당신(메인 세션)은 오케스트레이터다. **직접 구현하지 않고** 서브에이전트에게 위임하며, 단계 사이의 판정만 담당한다.

## 서브에이전트 투입 packet (최소 전달 원칙)

서브에이전트에게 전달하는 것은 다음뿐이다.
- spec 폴더 경로 + 이번 작업 항목 1개
- 직전 단계가 보고한 변경 파일 목록
- (재투입 시) 마지막 실패 로그 1개 — 이전 실패 이력 전체가 아니라 마지막 것만

전달하지 않는 것: 대화 히스토리 요약, 이전 항목들의 상세, 추측성 맥락. 에이전트가 더 필요한 정보는 `docs/context-router.md`를 따라 스스로 읽는다.

## 사전 확인

1. 인자로 받은 spec 폴더(`docs/specs/NNN-*`)에 `spec.md`, `plan.md`, `tasks.md`가 있는지 확인. 없으면 **planner** 서브에이전트(계획 모드) 투입을 제안하고, planner가 작성한 spec을 사용자와 확인한 뒤 진행한다.
2. `git status`로 작업 트리가 깨끗한지 확인. 브랜치가 `main` 또는 `dev`면 `feat/NNN-요약` 브랜치를 만든다 (베이스는 `dev`).

## 작업 루프 — tasks.md의 미완료 체크박스마다 반복

1. **implementer** 서브에이전트 투입. 프롬프트에 spec 폴더 경로와 해당 작업 항목 1개를 명시.
2. **tester** 서브에이전트 투입. implementer가 보고한 변경 파일 목록을 전달. tester가 테스트 작성 + `--tests` 필터로 이번 항목 테스트만 실행·실패 분석한다 (전체 회귀는 마무리 `build` 1회가 잡는다).
3. tester 보고 판정.
   - 구현 버그로 실패 → 마지막 실패 로그를 첨부해 implementer 재투입 후 tester 재투입. **같은 항목에서 3회 실패하면 루프 중단**, 사용자에게 실패 내역 보고.
4. `.\gradlew.bat spotlessApply` 후 tasks.md 체크박스를 체크하고 시맨틱 커밋 (`feat: ...` — 항목 1개 = 커밋 1개).

## 마무리 — 모든 항목 완료 후

5. `.\gradlew.bat build` 실행 (전체 게이트: Spotless + SpotBugs + 커버리지 40%). 실패하면 원인 항목에 implementer 재투입 후 재실행.
   - 통과하면 그 시점의 `git rev-parse HEAD`를 기록해 둔다. PR 본문의 **빌드 검증 SHA**에 적어야 `/review-pr`이 build를 재실행하지 않는다 (아래 8번 보고에 포함).
6. **reviewer** 서브에이전트 투입 — **리뷰 모드** 명시 (범위: 이번 spec의 전체 diff, `git diff dev...HEAD`).
   - `RESULT: 차단 N건`에서 N > 0 → 차단 내역을 첨부해 해당 항목에 implementer 재투입 후 5번부터 재실행. [권장]은 기록만 하고 진행.
7. **planner** 서브에이전트 투입 — **동기화 모드** 명시 → api-routes.md 갱신분이 있으면 `docs: ...` 커밋.
8. 최종 보고: 완료 항목 / 커밋 목록 / **빌드 검증 SHA** / [권장] 리뷰 잔여 사항.

QA(reviewer QA 모드)는 /feature에서 수행하지 않는다 — PR 단계의 `/review-pr`에서 1회 수행한다 (경량 시작, 지표 보고 재조정).
