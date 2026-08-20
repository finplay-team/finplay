# ADR-0019: PR 생성 전 정지 지점의 이력 코멘트는 이슈에 남긴다

- 상태: 승인됨
- 날짜: 2026-08-10
- 관계: [ADR-0016](0016-review-gate-auto-fix-round.md)(PR 오픈 이후 구간의 "이력 코멘트는 always(), 판정·승인은 !cancelled()" 원칙을 그대로 물려받아 적용 범위만 PR 생성 전 구간으로 넓힌다 — ADR-0016 본문은 수정하지 않는다). GitHub 이슈 #311, `docs/specs/029-pr-fallback-comment/spec.md`·`plan.md`를 구체화한다.

## 맥락

ADR-0016은 `implement-and-open-pr` job이 PR을 연 뒤의 모든 정지 지점에 이력 코멘트를 남기도록 정비했다(PR #293). 그러나 이력 코멘트 스텝은 전부 `steps.pr.outputs.number != ''`를 요구하므로, **PR이 만들어지기 전에 흐름이 죽으면 아무 데도 알림이 가지 않는다.** 2026-08-10 실행(이슈 #276 처리)에서 실제로 발생했다 — 구현 에이전트가 100턴·$7.25를 쓰고도 브랜치·PR을 만들지 못했고, 이슈에는 지금도 아무 응답이 없다(GitHub 이슈 #311).

`github.event.issue.number`는 이 job의 트리거 시점(`issue_comment`)부터 항상 존재하므로, PR이 없어도 게시 대상은 확보돼 있다.

## 결정

- `implement-and-open-pr` job에 PR 생성 전 구간을 덮는 이력 코멘트 스텝 3개를 추가한다. 셋 다 PR이 존재하지 않는 시점에만 조건이 성립하므로 게시 대상은 항상 이슈(`github.event.issue.number`)다.
  - `implement_failure_comment` — 구현 에이전트 호출(`id: implement`)이 `outcome != 'success'`.
  - `build_failure_issue_comment` — 구현은 성공했지만 러너의 빌드 검증(`id: build`)이 `outcome != 'success'`.
  - `pr_lookup_failure_comment` — 빌드는 성공했지만 PR 번호 조회(`id: pr`)가 `outcome != 'success'`(빈 `NUMBER`로 인한 기존 `exit 1` 포함).
- 세 조건은 각각 바로 앞 스텝의 `outcome == 'success'`를 전제로 걸어 상호 배타를 구조적으로 보장한다 — 정상 흐름(구현 → 빌드 → PR 오픈 모두 성공)에서는 셋 다 스킵되고, 실패 흐름에서는 정확히 하나만 발화한다. 도달 가능한 outcome 조합을 전수 검토해 확인했다(`docs/specs/029-pr-fallback-comment/plan.md`).
- ADR-0016이 이미 세운 원칙을 그대로 따른다 — 이력 코멘트라 `always()`를 쓰고, 대상 판정은 `outcome == 'failure'`가 아니라 `outcome != 'success'`로 통일한다(취소된 스텝의 `outcome`은 `cancelled`이지 `failure`가 아니므로). 코멘트 본문은 "실패했습니다"로 단정하지 않고 실제 `outcome` 값을 함께 찍는다.
  - `implement_failure_comment`만 예외로 앞 스텝(`implement`)이 `skipped`인 경우(예: `checkout` 실패로 인한 연쇄 스킵)까지 잡는다 — `implement` 앞에는 조건부 스텝이 없어 이 스텝이 유일하게 항상 `always()`로 도는 앞단 게이트이기 때문이다.
- 코멘트 본문에 Actions 실행 링크(`${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}`)를 포함한다 — 이슈 #311이 지목한 실제 장애(이슈 #276)가 "Actions 탭을 직접 확인하지 않으면 알 방법이 없었다"는 것이었다.
- 기존 `steps.pr.outputs.number != ''` 게이트를 쓰는 PR 오픈 이후 구간의 이력 코멘트 스텝(`review_failure_comment` 등)은 변경하지 않는다 — 게시 대상(이슈 vs PR)을 고르는 공통 스텝으로 합치지 않고, ADR-0016이 세운 "정지 지점마다 스텝 하나" 구조를 유지해 회귀 위험을 낮춘다.
- ADR-0016 본문은 수정하지 않는다(ADR-0001의 "ADR은 한번 승인되면 수정하지 않는다" 원칙, CLAUDE.md 규칙 2). 대신 ADR-0016의 상태 줄에 이 ADR을 가리키는 포인터 한 줄만 추가한다 — ADR-0014 상태 줄이 ADR-0016(당시 번호)을 가리키는 형태로 이미 쓴 선례(PR #285, 커밋 `7ab60341`)를 그대로 따른다.

## 범위 밖

- `verify-actor` job 자체가 실패하는 경우의 알림 — 이 job은 `implement-and-open-pr`보다 앞서 실행되며, 실패하면 `implement-and-open-pr`가 아예 시작되지 않는다. 별도 job이라 이 ADR의 대상이 아니다.
- `propose-directions` job(라벨 트리거, 방향 제시 단계)의 실패 알림 — 별도 job이며 이 ADR의 범위(PR 오픈 흐름)가 아니다.
- ADR-0016이 이미 다루는 PR 오픈 이후 구간(자체 리뷰·자동 수정 라운드·최종 판정·조건부 승인)의 이력 코멘트 로직 변경 — 이 ADR은 그 앞 구간만 다룬다.
- 구현 에이전트가 턴 상한에 걸리는 문제(이슈 #276 실행의 `num_turns: 100`) — 별도 판단이 필요하다. 턴 상한 도달 시 `claude-code-action` 스텝의 `outcome`이 항상 `failure`/`cancelled`로 끝나는지는 확인되지 않았다 — `success`로 끝나는 경로가 있다면 이 ADR의 조건만으로는 모든 "결과물 없이 끝난 성공"까지 잡지 못할 수 있다.
- 사람의 최종 승인·머지 자동화(ADR-0005 유지).

## 결과

- PR이 만들어지지 않은 채 흐름이 끝난 실행에서도 이슈에 사유와 실행 로그 링크가 담긴 코멘트가 남는다 — 팀원이 Actions 탭을 직접 열어보지 않아도 어느 단계에서 왜 멈췄는지 알 수 있다.
- 정상 흐름에서는 추가 코멘트가 붙지 않는다 — 상호 배타 조건 덕분에 이 변경이 기존 동작에 영향을 주지 않는다.
- 이력 코멘트 스텝이 3개 늘어 job의 스텝 수가 늘지만, `always()`/`outcome != 'success'` 원칙이 ADR-0016과 완전히 일치해 향후 유지보수 시 두 구간(PR 오픈 전/후)을 같은 정신 모델로 읽을 수 있다.
