# ADR-0007: PR 1차 자동 리뷰는 Codex API CI로 한다

- 상태: **폐기됨** (2026-07-23, 도입 당일 철회) — 리뷰가 3중(개발 중 항목별 code-reviewer + Codex CI + 로컬 /review-pr)이 되어 부담이 이익을 초과한다고 판단. 워크플로우와 프롬프트는 제거됨. Codex 키 활용은 필요해지면 재검토.
- 날짜: 2026-07-23
- 관계: [ADR-0005](0005-local-agent-orchestration.md)를 보완하려 했음 (철회로 ADR-0005 체제 유지)

## 맥락

ADR-0005는 Claude API 키 비용 때문에 "PR 생성 즉시 무인 자동 리뷰"를 포기하고 로컬 구독 세션 오케스트레이션을 택했다. 이후 상황이 바뀌었다.

- 팀에 OpenAI(Codex) API 키가 있다.
- Claude Code와 claude-code-action은 OpenAI 키를 지원하지 않으므로(공식 문서 확인) Claude 하네스를 CI로 옮기는 데는 쓸 수 없다.
- 하지만 공식 `openai/codex-action@v1`으로 별도 워크플로우를 만들면 PR마다 Codex가 자동 리뷰 코멘트를 달 수 있다.

## 결정

- **모든 PR에 Codex 1차 자동 리뷰**를 붙인다 (`.github/workflows/ai-review.yml`).
- 리뷰 기준은 로컬 code-reviewer와 **같은 정본 문서**를 쓴다 — 프롬프트(`.github/codex/prompts/review.md`)가 `docs/conventions.md`와 ADR들을 읽도록 지시하고, 출력 형식도 동일한 `[차단]/[권장]/[참고]`를 쓴다. 기준이 바뀌면 정본 문서만 고치면 양쪽에 반영된다.
- **역할 분담**: Codex CI = 모든 PR의 즉시 1차 게이트 (컨벤션·ADR·문서 동기화·명백한 버그). 로컬 `/review-pr` = 깊은 리뷰 + 빌드 실행 + 블랙박스 QA (Claude 구독, 사람이 트리거).
- **자동 승인 금지 유지** — Codex는 코멘트만 달고, 최종 승인 클릭은 사람이 한다 (ADR-0005 원칙).
- 인증: 레포 시크릿 `OPENAI_API_KEY`. 실행 샌드박스는 `read-only` (리뷰어는 파일을 수정하지 않는다).

## 한계 (알고 수용함)

- 실행마다 OpenAI API 비용이 발생한다 (docs-only PR은 paths-filter로 스킵해 절감).
- Codex는 빌드/테스트를 실행하지 않는다 — 그건 CI(ci.yml)와 로컬 test-runner의 몫이다.
- 튜터 제안의 "이슈 → 방향 제시 → 구현 → PR" 자동화는 여전히 미착수다 (`docs/harness-roadmap.md`). Codex 키로는 Claude 하네스를 러너에서 돌릴 수 없으므로, 그 단계는 Claude 구독 OAuth 토큰 또는 Anthropic API 키 확보 시 별도 ADR로 결정한다.

## 결과

- PR 오픈 즉시 사람 개입 없이 1차 리뷰가 달린다 — ADR-0005의 가장 큰 공백(무인 즉시 리뷰 불가)이 메워진다.
- 리뷰 실행 강제가 "사람의 명령 한 줄"에서 "PR 오픈"으로 앞당겨진다.
