---
name: feature
description: spec 폴더 하나를 받아 구현→테스트 루프를 돌리고 빌드·리뷰로 마무리한다 (QA는 /review-pr에서). spec이 없으면 planner가 먼저 작성한다. 사용법: /feature ai/specs/001-foundation
---

# /feature — 기능 개발 루프

당신(메인 세션)은 오케스트레이터다. **직접 구현하지 않고** 서브에이전트에게 위임하며, 단계 사이의 판정만 담당한다.

## 서브에이전트 투입 packet (최소 전달 원칙)

서브에이전트에게 전달하는 것은 다음뿐이다.
- spec 폴더 경로 + 이번 작업 항목 1개
- 직전 단계가 보고한 변경 파일 목록
- (세션 재개 시) 마지막 실패 로그 1개 + 지난 턴 이후 메인 세션이 바꾼 파일 목록 — 이전 실패 이력 전체가 아니라 마지막 것만

전달하지 않는 것: 대화 히스토리 요약, 이전 항목들의 상세, 추측성 맥락. 에이전트가 더 필요한 정보는 `ai/context-router.md`를 따라 스스로 읽는다.

## 세션 생명주기 (ADR-0010)

- implementer/tester는 한 /feature 실행 동안 세션을 유지한다. 이 문서의 "재투입"은 신규 서브에이전트 생성이 아니라 **동일 세션 재개**를 뜻한다 — 새 Agent 호출 대신 해당 세션의 agentId로 SendMessage를 보낸다.
- production 버그·리뷰 차단은 코드를 작성한 동일 implementer 세션에 다시 전달한다.
- reviewer는 항상 새 Agent로 생성한다 — 구현자 전제 답습 방지.
- 다음이면 세션을 버리고 새로 만든다: 무관한 spec 전환, 컨텍스트 과다, 3회 실패 고착, 설계 대전환, /feature 종료.

## 사전 확인

1. 인자로 받은 spec 폴더(`ai/specs/NNN-*`)에 `spec.md`, `plan.md`, `tasks.md`가 있는지 확인. 없으면 **planner** 서브에이전트(계획 모드) 투입을 제안하고, planner가 작성한 spec을 사용자와 확인한 뒤 진행한다.
2. `git status`로 작업 트리가 깨끗한지 확인. 브랜치가 `main` 또는 `dev`면 새 작업 브랜치를 만든다 (베이스는 `dev`).
   - 이름은 `docs/conventions/git.md`의 브랜치 네이밍을 따른다 — `<타입>/<이슈번호>-<영문-요약>`. **이슈번호는 spec 번호가 아니라 GitHub 이슈 번호이며 0으로 채우지 않는다** (`feat/16-price-query`, `feat/016-...` 아님).
   - 이슈 번호를 모르면 `gh issue list`로 이번 작업에 대응하는 이슈를 확인한다.

## 작업 루프 — tasks.md의 미완료 체크박스마다 반복

1. **implementer** 서브에이전트 투입. 프롬프트에 spec 폴더 경로와 해당 작업 항목 1개를 명시.
2. **tester** 서브에이전트 투입. implementer가 보고한 변경 파일 목록을 전달. tester가 테스트 작성 + `--tests` 필터로 이번 항목 테스트만 실행·실패 분석한다 (전체 회귀는 마무리 `build` 1회가 잡는다).
3. tester 보고 판정.
   - 구현 버그로 실패 → 마지막 실패 로그를 첨부해 동일 implementer 세션 재개, 이어 동일 tester 세션 재개. **같은 항목에서 3회 실패하면 루프 중단**, 사용자에게 실패 내역 보고.
4. `.\gradlew.bat spotlessApply` 후 tasks.md 체크박스를 체크하고 시맨틱 커밋 (`feat: ...` — 항목 1개 = 커밋 1개).

## 마무리 — 모든 항목 완료 후

5. 검증 실행. **전체 `build`를 습관적으로 돌리지 않는다** — 이 머신은 완주시키지 못한다 (`ai/agent-mistakes.md` §공유 작업 폴더 규칙 5).
   - **항상**: `.\gradlew.bat compileJava compileTestJava spotlessJavaCheck spotbugsMain spotbugsTest` + 이번 spec에 영향받는 테스트를 `--tests` 필터로. `spotlessJavaCheck`·`spotbugs*`는 트리 전체를 보는 태스크라 **전체 게이트 3개 중 둘을 그대로 재현한다.**
   - **조건부로만** 전체 `.\gradlew.bat build`를 시도한다 — 경쟁하는 `java` 프로세스가 없고 `FreeVirtualMemory`에 여유가 있을 때. "돌려 보고 실패하면 CI" 방식은 쓰지 않는다(실패가 20~50분 지연·OOM이고 다른 세션 빌드까지 말린다).
   - 실패하면 원인 항목의 implementer 세션을 재개해 수정 후 재실행.
   - **전체 `build`를 통과시켰을 때만** 그 시점의 `git rev-parse HEAD`를 기록해 PR 본문의 **빌드 검증 SHA**에 적는다 — 그래야 `/review-pr`이 build를 재실행하지 않는다. **돌리지 않았으면 SHA를 적지 말고 실제로 돌린 태스크를 실측대로 쓴다.** 그 경우 커버리지 40%는 검증되지 않은 채 남고 **PR의 CI가 유일한 전체 게이트**가 된다 (아래 8번 보고에 포함).
6. **reviewer** 서브에이전트 투입 — **항상 새 세션** + **리뷰 모드** 명시 (범위: 이번 spec의 전체 diff, `git diff dev...HEAD`).
   - `RESULT: 차단 N건`에서 N > 0 → 차단 내역을 첨부해 해당 항목의 implementer 세션을 재개해 수정 후 5번부터 재실행. [권장]은 기록만 하고 진행.
7. **planner** 서브에이전트 투입 — **동기화 모드** 명시 → `ai/api-routes.md`(라우트 목록)·`docs/api/`의 해당 도메인 파일(계약 상세) 갱신분이 있으면 `docs: ...` 커밋.
8. **PR 생성** — `docs/conventions/git.md`의 PR 제목·본문 규칙을 그대로 따른다. 임의 형식으로 만들지 않는다.
   - 제목: `<타입>: <한국어 요약> (#이슈번호)` — 이슈 참조는 `(#16)` 형태 하나만 쓴다. 이슈 제목(`[MVP][...]`) 복사 금지.
   - 본문: **`.github/PULL_REQUEST_TEMPLATE.md`를 읽어 그 절 구조를 그대로 채운다** (관련 이슈 / 변경 내용 / 작업 종류 / 체크리스트 / 남은 위험 / 리뷰어에게). 새 형식을 만들지 않는다. `Closes #N`과 **체크리스트의 빌드 검증 SHA(5번에서 기록한 값)** 는 필수다. 실행하지 않은 검증에 체크하지 않는다.
   - `gh pr create --base dev --title "..." --body-file <파일>`로 올린다. 본문은 파일로 전달해 줄바꿈이 깨지지 않게 한다.
   - PR 링크를 사용자에게 보고한다. 리뷰는 `/review-pr <번호>`에서 수행한다.
9. 최종 보고: 완료 항목 / 커밋 목록 / **빌드 검증 SHA** / PR 링크 / [권장] 리뷰 잔여 사항.

QA(reviewer QA 모드)는 /feature에서 수행하지 않는다 — PR 단계의 `/review-pr`에서 1회 수행한다 (경량 시작, 지표 보고 재조정).
