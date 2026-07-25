# ADR-0009: Codex도 로컬 에이전트 오케스트레이션을 지원한다

- 상태: 승인됨
- 날짜: 2026-07-25
- 관계: ADR-0005의 로컬 오케스트레이션 대상을 Claude Code에서 Claude Code와 Codex로 확장한다. ADR-0008의 4개 역할은 유지한다. 폐기된 ADR-0007의 Codex CI 자동 리뷰를 재도입하지 않는다.

## 맥락

저장소에는 `CLAUDE.md`, `.claude/agents/`, `.claude/skills/`로 Claude Code용 기능 개발·PR 리뷰 하네스가 정의돼 있다. Codex 팀원도 같은 저장소를 사용하지만 Codex가 자동으로 읽는 `AGENTS.md`, `.agents/skills/`, `.codex/agents/`가 없어 동일한 역할 분리와 검증 루프가 새 세션에 적용되지 않는다.

## 결정

- 공통 Codex 프로젝트 규칙은 루트 `AGENTS.md`에 둔다.
- Codex의 진입점은 저장소 스킬 두 개다.
  - `feature` — spec 단위 구현 → 대상 테스트 → 전체 build → 리뷰 → 문서 동기화
  - `review-pr` — 리뷰·빌드 판독·조건부 블랙박스 QA → GitHub 리뷰 코멘트
- Codex 역할은 `.codex/agents/`의 planner, implementer, tester, reviewer 네 개로 정의한다.
- `.codex/config.toml`에서 멀티에이전트를 활성화하고 동시 서브에이전트를 3개로 제한한다.
- 작업 항목 하나의 production 코드 작성자는 implementer 한 명으로 제한한다. tester와 reviewer는 production 코드를 수정하지 않는다.
- 메인 에이전트는 서브에이전트의 완료 보고만 신뢰하지 않고 diff와 실제 검증 결과를 다시 확인한다.
- Claude와 Codex의 도구별 설정 파일은 각각 유지하되 역할, 품질 게이트, 자동 승인 금지 정책은 동일하게 유지한다.

## 범위 밖

- MCP 서버 추가
- GitHub Actions에서 실행되는 무인 에이전트
- Codex API PR 자동 리뷰 재도입
- 자동 승인·자동 머지
- 모델명과 개인 인증·권한 설정의 저장소 고정

## 결과

- Claude Code와 Codex 팀원이 같은 로컬 개발·리뷰 절차를 재현할 수 있다.
- Codex 스킬이나 적용 가능한 `AGENTS.md` 지시가 역할 위임을 요구할 때만 서브에이전트를 사용한다.
- CI 하네스 전환은 계속 `docs/harness-roadmap.md`의 후속 ADR 범위로 남는다.
