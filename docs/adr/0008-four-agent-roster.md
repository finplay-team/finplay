# ADR-0008: 서브에이전트를 4개 체제로 통합한다 (planner 신설)

- 상태: 승인됨
- 날짜: 2026-07-23

## 맥락

기존 서브에이전트는 6개였다 (implementer, test-writer, code-reviewer, qa-verifier, doc-syncer, test-runner). 튜터 피드백 두 가지를 받았다.

1. 에이전트 수가 너무 많다 — 4개로 줄일 것.
2. 계획과 문서 작성을 전담하는 에이전트가 없다 — 신설할 것.

## 결정

기능은 모두 유지하면서 병합해 **4개 체제**로 전환한다. 팀장(오케스트레이터)은 별도 에이전트가 아니라 **메인 세션**이다 — Claude Code 구조상 서브에이전트는 다른 서브에이전트를 투입할 수 없으므로, 팀장의 행동 정의는 `/feature`·`/review-pr` 스킬에 둔다.

```
오케스트레이터 (메인 세션 — /feature, /review-pr 스킬이 행동 정의)
 ├─ planner     계획·문서 (신설 + doc-syncer 흡수)
 ├─ implementer 구현 (유지)
 ├─ tester      테스트 작성·실행 (test-writer + test-runner 병합)
 └─ reviewer    코드 리뷰·블랙박스 QA (code-reviewer + qa-verifier 병합)
```

- **planner** — 계획 모드(spec/plan/tasks 작성)와 동기화 모드(api-routes.md 갱신)를 오케스트레이터가 지정한다.
- **tester** — 테스트 작성 후 실행·실패 분석까지 한 투입에서 수행한다. src/main 수정 금지는 유지.
- **reviewer** — 리뷰 모드와 QA 모드를 분리해 투입한다. **한 투입에서 두 모드를 겸하지 않는다** — QA 모드의 "구현 코드를 읽지 않는다" 블랙박스 독립성(구 qa-verifier 원칙)을 모드 단위로 유지하기 위함이다.

ADR-0005의 오케스트레이션 방식(로컬 세션 오케스트레이터 + 스킬 진입점)은 그대로 유효하며, 에이전트 로스터 부분만 이 ADR이 대체한다.

## 결과

- 에이전트 정의 파일이 6개 → 4개로 줄어 유지보수 대상이 감소한다.
- spec 작성이 에이전트 워크플로우에 편입된다 — spec 없는 기능 요청 시 /feature가 planner 투입을 제안한다.
- 트레이드오프: tester·reviewer가 역할 2개를 겸하므로 투입 시 오케스트레이터가 모드/범위를 명시해야 한다 (스킬에 명문화함).
