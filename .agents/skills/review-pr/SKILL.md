---
name: review-pr
description: Use when reviewing one finplay-api pull request or branch diff with code review, build evidence, optional API QA, and a GitHub review comment.
---

# Review PR

PR 하나를 독립 검증한 뒤 근거가 있는 종합 리뷰를 게시한다. 자동 승인과 자동 머지는 하지 않는다.

## 사전 확인

1. `AGENTS.md`와 코드 리뷰에 해당하는 `ai/context-router.md` 문서를 읽는다.
2. 현재 브랜치와 변경을 기록한다.
3. PR 번호가 있으면 `gh pr view <번호>`로 제목·본문·대상 브랜치를 확인하고 `gh pr checkout <번호>`를 실행한다. 번호가 없거나 원격을 사용할 수 없으면 현재 브랜치와 `dev`의 로컬 diff를 검토한다.
4. 관련 spec 경로를 식별한다. 특정할 수 없으면 QA를 실행하지 않고 그 제한을 보고한다.

## 병렬 검증

가능하면 다음을 서로 다른 서브에이전트로 병렬 실행한다. packet에는 PR 번호, `git diff dev...HEAD`, 관련 spec 경로만 전달한다.

1. reviewer 리뷰 모드: 컨벤션, ADR, 정확성, 문서·테스트 누락을 검토한다. 파일을 수정하지 않는다.
2. tester 빌드 검증 모드:
   - 변경이 `docs/`, Markdown, `.claude/`, `.codex/`, `.agents/`뿐이면 build를 생략하고 문서 전용이라고 기록한다.
   - CI가 있으면 `gh pr checks <번호>` 결과와 실패 로그를 판독한다. 로컬 build를 중복 실행하지 않는다.
   - CI가 없으면 PR 본문의 빌드 검증 SHA, 현재 HEAD, `dev` merge-base가 모두 동일하고 통과 근거가 있을 때만 기존 결과를 인용한다.
   - 위 조건을 만족하지 않으면 OS에 맞는 wrapper로 `build`를 실행한다.
3. controller/API 변경이 있고 관련 spec이 명확하면 reviewer의 별도 인스턴스를 QA 모드로 투입한다. 리뷰 모드와 QA 모드를 한 인스턴스에 맡기지 않는다.

메인 에이전트는 각 보고의 diff 범위, 실제 명령, SHA, CI 링크, PASS/FAIL을 다시 확인한다.

## 판정과 게시

- 리뷰 차단 0건, 빌드 통과 또는 정당한 생략, QA FAIL 0건이면 승인 의견이다.
- 그 외에는 수정 요청 의견이다.
- 빌드를 실행하지 않았다면 문서 전용, CI 통과 링크, 기존 build SHA 중 실제 근거를 명시한다.
- QA를 실행하지 않았으면 통과로 표현하지 않는다.
- 본문은 판정, 차단, 권장, 빌드 근거, QA 결과, 남은 위험 순서로 작성한다.
- PR 번호가 있으면 `gh pr review <번호> --comment --body "<종합 리뷰>"`로 게시한다. `--approve`는 사용하지 않는다.
- 체크아웃했다면 작업 시작 시 기록한 원래 브랜치로 돌아간다. 사용자의 미커밋 변경을 덮어쓰지 않는다.

리뷰 지적에 따른 수정 구현은 이 스킬의 범위가 아니다. 사용자가 수정을 요청하면 같은 PR 범위의 별도 작업으로 처리한다.
