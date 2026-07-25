---
name: feature
description: Use when implementing one finplay-api spec folder through the planner, implementer, tester, build, review, and documentation gates.
---

# Feature

spec 폴더 하나를 품질 게이트 순서대로 구현한다. 메인 에이전트는 오케스트레이터이며 production 코드를 직접 작성하지 않는다.

## 사전 확인

1. `AGENTS.md`와 `docs/context-router.md`를 읽는다.
2. 인자로 받은 `docs/specs/NNN-*`에 `spec.md`, `plan.md`, `tasks.md`가 있는지 확인한다.
3. 문서가 없으면 planner를 계획 모드로 투입하고 사용자의 spec 확인을 받은 뒤 구현한다.
4. `git status --short --branch`로 기존 변경과 브랜치를 확인한다. 사용자 변경을 커밋에 섞거나 되돌리지 않는다.
5. 현재 브랜치가 `main` 또는 `dev`면 사용자 작업과 충돌하지 않는지 확인한 뒤 `dev` 기반 `feat/NNN-요약` 브랜치를 만든다.

## 전달 계약

서브에이전트에는 spec 경로, 현재 작업 항목 하나, 직전 변경 파일, 재시도 시 마지막 실패 로그만 전달한다. 역할은 `.codex/agents/`에서 선택한다.

## 작업 항목 루프

tasks.md의 미완료 항목마다 순차 실행한다.

1. implementer 한 명에게 해당 항목의 production 구현을 맡긴다.
2. 메인 에이전트가 실제 diff가 항목 범위와 보고 파일에 일치하는지 확인한다.
3. tester에게 변경 파일과 완료 조건을 전달해 테스트 작성과 대상 테스트 실행을 맡긴다.
4. 메인 에이전트가 테스트 명령과 결과를 확인한다.
5. production 버그면 마지막 실패 로그와 함께 같은 implementer를 재투입하고 tester를 다시 실행한다. 같은 항목에서 세 번 실패하면 중단해 사용자에게 보고한다.
6. OS에 맞는 wrapper로 `spotlessApply`를 실행하고 tasks.md를 체크한 뒤 해당 항목만 시맨틱 커밋한다.

## 마무리

1. OS에 맞는 wrapper로 `build`를 실행한다. 통과한 HEAD SHA를 기록한다.
2. reviewer를 리뷰 모드로 투입해 `git diff dev...HEAD`를 검토시킨다.
3. 차단 지적은 해당 implementer가 수정하고 대상 테스트와 build를 다시 실행한다. 권장·참고는 잔여 사항으로 기록한다.
4. planner를 동기화 모드로 투입한다. 문서 변경이 있으면 별도 `docs:` 커밋으로 분리한다.
5. 메인 에이전트가 최종 diff와 현재 검증 결과를 재확인한다.
6. 완료 항목, 커밋, 빌드 검증 SHA, 실행 명령, 잔여 위험을 보고한다.

QA는 이 스킬에서 실행하지 않는다. API 블랙박스 QA는 PR 단계의 `review-pr`에서 한 번 수행한다.
