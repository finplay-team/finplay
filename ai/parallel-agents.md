# 병렬 에이전트 가이드 — Claude Code · Codex/Orca

작업 속도를 올리고 싶을 때 어떤 병렬 수단을 쓰는지 정한다. 공식 문서: Claude Code [Agent View](https://code.claude.com/docs/en/agent-view.md)·[Agent Teams](https://code.claude.com/docs/en/agent-teams.md), Codex [Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents).

## 결정 트리

| 상황 | 수단 | 이유 |
|---|---|---|
| 독립적인 spec 2개 이상 동시 진행 | Claude **Agent View** 또는 Orca의 별도 worktree | 세션별 git worktree 격리로 파일 충돌 방지 |
| 경쟁 리뷰, 여러 가설 동시 탐색 | Claude **에이전트 팀** 또는 Codex **subagent** | 독립 컨텍스트의 결과를 메인 세션이 종합 |
| `feature` 루프 내부 (구현→테스트→빌드→리뷰) | **단계 순차 유지** | 각 단계가 이전 산출물에 의존 |

## Agent View — spec 단위 병렬 (기본 수단)

내장 기능이다. 만들 것 없음.

```
claude agents        # Agent View 실행
```

- 입력창에서 작업을 디스패치하면 백그라운드 세션이 생긴다. 각 세션은 `.claude/worktrees/`의 **격리된 worktree**에서 돌아 서로 파일을 건드리지 않는다.
- 세션 목록이 상태별(Needs input / Working / Completed / Failed)로 표시된다. `Space` 미리보기, `Enter` 전체 트랜스크립트 접속.
- 사용 예: `/feature ai/specs/003-market` 세션과 `/feature ai/specs/008-community` 세션을 동시에 디스패치.

### 1차 MVP 태스크 의존성 (PRD 8장 기준 — 병렬 가능 지점)

```
1 기반 → 2 인증·계좌 → 3 시세 → 4 매수 → 5 매도 → 6 조회 → 7 일기 → 9 통합
                    └→ 8 커뮤니티 (인증에만 의존 — 3~7과 병렬 가능)
```

- 8(커뮤니티)은 2(인증) 완료 후 언제든 병렬로 돌릴 수 있다.
- 나머지는 의존 사슬이라 순차가 맞다. 억지로 쪼개지 않는다.
- 병렬 세션 2개가 끝나면 각 브랜치를 순서대로 PR/머지한다 (동시 머지 금지 — 마이그레이션 번호 `V{N}` 충돌 확인 필수, ADR-0004).

## Codex/Orca — 역할 단위 서브에이전트

Codex는 `AGENTS.md`, `.agents/skills/`, `.codex/agents/`를 사용한다. `.codex/config.toml`에서 동시에 실행할 서브에이전트를 3개로 제한한다.

- spec 단위 병렬 구현은 같은 worktree의 서브에이전트로 처리하지 않는다. Orca에서 별도 worktree를 만들어 각 세션에 `feature` 스킬을 맡긴다.
- 한 spec의 `feature` 루프는 implementer → tester → build → reviewer 순서를 유지한다.
- `review-pr`에서는 reviewer 리뷰, tester 빌드 판독, reviewer QA를 최대 3개 인스턴스로 병렬 실행할 수 있다.
- Codex 서브에이전트는 같은 worktree를 공유하므로 production 코드는 작업 항목마다 implementer 한 명만 수정한다.
- 메인 에이전트가 역할별 결과, 실제 diff, 검증 명령을 다시 확인한다.

## 에이전트 팀 — 대화가 필요한 병렬 (실험 기능)

`.claude/settings.json`의 `env`로 활성화돼 있다 (`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`, 새 세션부터 적용).

- 팀원은 각자 독립 컨텍스트의 Claude Code 세션이며, **서로 직접 메시지**를 주고받고 **공유 태스크 리스트**에서 작업을 가져간다.
- 사용 예: PR 하나를 관점이 다른 리뷰어 2~3명이 동시에 보게 하고 서로 결과를 반박시키기, 버그 원인 가설 2개를 경쟁 조사.

### 규칙 (어기면 사고 난다)

1. **한 파일 = 한 팀원.** 팀에는 worktree 격리가 없다 — 같은 파일을 두 팀원이 수정하면 덮어쓴다 (공식 문서 경고). 코드 수정 작업이면 파일 소유권을 먼저 나눈다. 나눌 수 없으면 팀이 아니라 Agent View를 쓴다.
2. **다른 작업자의 변경을 되돌리지 않는다.** 팀원/병렬 세션에 투입하는 프롬프트에 "혼자 작업하는 것이 아니다"를 명시한다 — 내 변경과 충돌해 보여도 되돌리지 말고 보고한다.
3. **Windows는 인프로세스 모드만** — 분할 화면(tmux/iTerm2)은 미지원. 화살표 키로 팀원 전환.
4. 실험 기능 한계: `/resume`으로 팀원 미복원, 팀원이 태스크 완료 표시를 누락할 수 있음, 세션당 팀 1개.

## `feature`와의 관계

- Claude `/feature`와 Codex `feature`의 **최소 packet 원칙은 유지**한다 — 루프 안에서 에이전트끼리 자유 대화를 시키면 컨텍스트가 오염되고 판정이 흐려진다. 대화가 필요한 작업(경쟁 리뷰 등)만 팀 모드를 별도로 연다.
- 속도가 문제면 순서는 이렇다. ① 독립 spec을 Agent View 또는 Orca 별도 worktree로 병렬화 ② 그래도 느리면 spec의 tasks.md 항목을 더 작게 쪼개 커밋 주기를 단축 ③ 루프 내부 병렬화는 하지 않는다.
- 루프 안의 재시도·수정은 새 서브에이전트를 만들지 말고 동일 implementer/tester 세션을 재개한다. reviewer만 항상 새 세션이다 (ADR-0010).
