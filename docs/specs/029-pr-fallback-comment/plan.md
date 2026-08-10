# Plan: PR 생성 전 정지 지점 이슈 알림

## 관련 문서
- Spec: `./spec.md`
- GitHub 이슈: #311 (finplay-team/finplay)
- 관련 ADR: [ADR-0016](../../adr/0016-review-gate-auto-fix-round.md)(이력 코멘트 always()/outcome != success 원칙의 출처, 이번에 적용 범위를 넓히는 갱신 대상), [ADR-0013](../../adr/0013-issue-triggered-agent-harness.md)(job 구조 원본)
- 선례: `docs/specs/025-review-gate-auto-fix/`(같은 파일을 다룬 직전 spec — plan.md의 "always()와 !cancelled()를 나누는 기준" 절이 이번 작업의 조건식 원칙 그대로다)

이 spec도 025와 같은 이유로 API 엔드포인트·엔티티가 아니라 `.github/workflows/agent.yml`의 `implement-and-open-pr` job을 바꾸는 CI 워크플로우 변경이다. "API 설계·입력 명세·데이터 모델" 대신 스텝 시퀀스로 설계를 기술한다.

## 현재 스텝 구조 (PR 생성 전 구간, `.github/workflows/agent.yml` L112-199 기준)

| 순서 | 스텝 이름 | 현재 id | 현재 `if:` |
|---|---|---|---|
| 1 | checkout | (없음) | (없음, 암묵적 success()) |
| 2 | git 사용자 설정 | (없음) | (없음) |
| 3 | setup-java | (없음) | (없음) |
| 4 | 구현(claude-code-action) | **없음 — 부여 필요** | (없음) |
| 5 | 빌드 검증 | `build` | `success()` |
| 6 | 방금 연 PR 번호 조회 | `pr` | `success() && steps.build.outcome == 'success'` |

4번 스텝은 현재 `id:`가 없어 뒤 스텝에서 `steps.implement.outcome`으로 참조할 수 없다 — 이번 변경의 전제 조건으로 `id: implement`를 먼저 부여한다.

## 변경 후 — 추가할 스텝 3개

세 스텝 모두 **PR이 아직 존재하지 않는 시점에만** 조건이 성립하도록 설계했다 — 그래서 게시 대상은 항상 이슈(`github.event.issue.number`)이고, `steps.pr.outputs.number`를 참조하거나 분기할 필요가 없다(spec.md 비즈니스 규칙 1번 — "PR 유무로 게시 위치를 가른다"는 원칙이 이 셋에서는 "PR이 없으므로 항상 이슈"로 단순화된다).

### 1. 구현 에이전트 호출 실패 이력 코멘트 (신규, id: `implement_failure_comment`)

배치 위치: 4번(구현) 스텝과 5번(빌드 검증) 스텝 사이.

```yaml
      - name: 구현 에이전트 호출 실패 이력 코멘트 — PR 생성 전
        id: implement_failure_comment
        # 이력 코멘트라 always() — 판정/승인이 아니다(ADR-0016 원칙).
        # outcome != 'success'로 취소(타임아웃)·실패·(앞선 checkout/setup-java 실패로 인한) 스킵을 모두 잡는다.
        # implement 앞에는 조건부 스텝이 없으므로(checkout·git 설정·setup-java 모두 암묵적 success() 의존)
        # 이 스텝이 always()로 도는 유일한 앞단 게이트다 — checkout이 실패해도 여기서 흔적이 남는다.
        if: always() && steps.implement.outcome != 'success'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          ISSUE_NUMBER: ${{ github.event.issue.number }}
          IMPLEMENT_OUTCOME: ${{ steps.implement.outcome }}
          RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
        run: |
          printf '%s\n' \
            "구현 에이전트 호출이 정상 종료되지 않았습니다 (outcome: $IMPLEMENT_OUTCOME) — PR이 열리지 않았습니다." \
            "cancelled은 job 타임아웃(timeout-minutes)일 수 있고, skipped는 이보다 앞선 준비 스텝(checkout 등)이 실패했다는 뜻일 수 있습니다." \
            "" \
            "실행 로그: $RUN_URL" > /tmp/implement-failure-body.md
          gh issue comment "$ISSUE_NUMBER" --body-file /tmp/implement-failure-body.md
```

### 2. 빌드 검증 실패 이력 코멘트 (신규, id: `build_failure_issue_comment`)

배치 위치: 5번(빌드 검증) 스텝과 6번(PR 번호 조회) 스텝 사이. 기존 자동 수정 라운드의 `autofix_build_failure_comment`(PR 대상)와 이름이 겹치지 않도록 `_issue_` 접미사로 구분한다.

```yaml
      - name: 빌드 검증 실패 이력 코멘트 — PR 생성 전
        id: build_failure_issue_comment
        # 구현이 성공했는데 빌드가 실패/취소된 경우만 잡는다 — 구현이 실패했으면 build 자체가 skipped라
        # steps.implement.outcome == 'success' 조건으로 위 1번과 상호 배타를 이룬다.
        if: always() && steps.implement.outcome == 'success' && steps.build.outcome != 'success'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          ISSUE_NUMBER: ${{ github.event.issue.number }}
          BUILD_OUTCOME: ${{ steps.build.outcome }}
          RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
        run: |
          printf '%s\n' \
            "빌드 검증이 정상 종료되지 않았습니다 (outcome: $BUILD_OUTCOME) — 구현은 끝났지만 PR이 열리지 않았습니다." \
            "cancelled은 job 타임아웃(timeout-minutes)일 수 있습니다." \
            "" \
            "실행 로그: $RUN_URL" > /tmp/build-failure-issue-body.md
          gh issue comment "$ISSUE_NUMBER" --body-file /tmp/build-failure-issue-body.md
```

### 3. PR 번호 조회 실패 이력 코멘트 (신규, id: `pr_lookup_failure_comment`)

배치 위치: 6번(PR 번호 조회) 스텝 바로 뒤, 기존 "코드 리뷰" 스텝 앞.

```yaml
      - name: PR 번호 조회 실패 이력 코멘트
        id: pr_lookup_failure_comment
        # 빌드가 성공했는데 PR 조회가 실패/취소된 경우만 잡는다 — 빌드가 실패했으면 pr 자체가 skipped라
        # steps.build.outcome == 'success' 조건으로 위 2번과 상호 배타를 이룬다.
        # pr 스텝은 NUMBER가 비면 이미 명시적으로 exit 1하므로(기존 로직, ADR-0016) 그 경우도 outcome='failure'로
        # 여기서 잡힌다 — 이슈 #311이 지목한 "steps.pr.outputs.number가 빈 값" 사례와 동일하다.
        if: always() && steps.build.outcome == 'success' && steps.pr.outcome != 'success'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          ISSUE_NUMBER: ${{ github.event.issue.number }}
          PR_LOOKUP_OUTCOME: ${{ steps.pr.outcome }}
          RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
        run: |
          printf '%s\n' \
            "PR 번호 조회가 정상 종료되지 않았습니다 (outcome: $PR_LOOKUP_OUTCOME) — 구현·빌드는 끝났지만 PR을 찾지 못했습니다." \
            "cancelled은 job 타임아웃(timeout-minutes)일 수 있습니다." \
            "" \
            "실행 로그: $RUN_URL" > /tmp/pr-lookup-failure-body.md
          gh issue comment "$ISSUE_NUMBER" --body-file /tmp/pr-lookup-failure-body.md
```

### 기존 스텝 변경 — id 부여만

```yaml
      - uses: anthropics/claude-code-action@v1
        id: implement    # 신규 — 나머지 with:/claude_args:는 변경 없음
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          ...
```

기존 `build`·`pr` 스텝의 `if:` 조건, 본문, 이후 PR 기반 이력 코멘트 스텝(`review_failure_comment` 등)은 이번 변경으로 손대지 않는다.

## 상호 배타성 근거 — 도달 가능한 outcome 조합 전수 검토

`implement`·`build`·`pr` 세 스텝의 `outcome`은 각각 `success`/`failure`/`cancelled`/`skipped` 중 하나다. 뒤 스텝은 앞 스텝이 실패하면 암묵적 `success()`에 의해 `skipped`가 되므로 실제 도달 가능한 조합은 아래 5가지뿐이다.

| # | implement.outcome | build.outcome | pr.outcome | 발생 코멘트 | 비고 |
|---|---|---|---|---|---|
| 1 | `success` \| `failure` \| `cancelled` \| `skipped`(단, `success` 아님) | `skipped` | `skipped` | `implement_failure_comment` **1개만** | build·pr는 `success()` 게이트로 자동 skip |
| 2 | `success` | `success` \| `failure` \| `cancelled`(단, `success` 아님) | `skipped` | `build_failure_issue_comment` **1개만** | pr는 `success() && build.outcome=='success'` 게이트로 자동 skip |
| 3 | `success` | `success` | `success` \| `failure` \| `cancelled`(단, `success` 아님) | `pr_lookup_failure_comment` **1개만** | `pr` 스텝 자체의 `exit 1`(빈 NUMBER)도 `failure`로 여기 포함 |
| 4 | `success` | `success` | `success` | (없음) | 기존 ADR-0016 PR 기반 이력 코멘트로 이어짐 — 이번 변경 대상 아님 |

각 행에서 세 조건식(`implement_failure_comment`/`build_failure_issue_comment`/`pr_lookup_failure_comment`의 `if:`)을 대입하면 정확히 하나만 참이 된다(1행: 1번 조건만 참, 2행: 2번만, 3행: 3번만, 4행: 셋 다 거짓). 완료 조건 "정확히 1개, 중복 0·누락 0"과 "정상 흐름에서 중복 코멘트가 늘지 않는다"를 이 표로 검증한다.

## ADR-0016 갱신 — 종료 사유 열거 확장

CLAUDE.md 규칙 2에 따라 ADR을 새 번호로 대체하지 않고 **같은 ADR을 직접 갱신**한다(오케스트레이터 지시 근거: 이슈 #311 완료 조건 "ADR-0016의 종료 사유 열거를 갱신한다"는 기존 결정을 뒤집는 게 아니라 같은 원칙의 적용 범위를 PR 생성 전 구간으로 넓히는 것이다). 갱신 이력을 명확히 남긴다.

### 1) 메타데이터 절에 갱신 이력 한 줄 추가

`docs/adr/0016-review-gate-auto-fix-round.md`의 "관계" 줄(L5) 바로 아래에 추가:

```markdown
- 갱신: 2026-08-10(이슈 #311, `docs/specs/029-pr-fallback-comment/`) — "이력 코멘트 스텝은 always()" 목록에 PR 생성 전 구간 스텝 3개를 추가했다. 기존 결정을 뒤집지 않고 적용 범위를 넓히는 갱신이라 새 ADR 번호를 만들지 않았다(CLAUDE.md 규칙 2).
```

### 2) "이력 코멘트 스텝은 always()" 목록 확장 (L32)

현재:
```markdown
  - **이력 코멘트 스텝은 `always()`** — `review_failure_comment`·`autofix_call_failure_comment`·`autofix_build_failure_comment`·`autofix_review_failure_comment`.
```

갱신 후:
```markdown
  - **이력 코멘트 스텝은 `always()`** — `review_failure_comment`·`autofix_call_failure_comment`·`autofix_build_failure_comment`·`autofix_review_failure_comment`.
  - **PR 생성 전 구간도 같은 원칙을 따른다**(이슈 #311) — `implement_failure_comment`·`build_failure_issue_comment`·`pr_lookup_failure_comment`. 이 셋은 정의상 PR이 아직 없는 시점에만 조건이 성립하므로 `steps.pr.outputs.number != ''` 게이트가 아니라 앞 스텝의 `outcome`으로 상호 배타를 걸고, 게시 대상도 PR이 아니라 이슈(`github.event.issue.number`)다. 대상 판정(`outcome != 'success'`)과 코멘트 본문에 실제 outcome을 찍는 원칙은 동일하다.
```

원본 문장("현재 열거는 PR이 존재하는 구간만 다룬다", 이슈 #311)이 가리키는 열거가 바로 이 목록이다 — `implement-and-open-pr` job의 다른 이력 코멘트류 텍스트(예: L18-24의 "자동 수정 라운드의 종료 사유 6가지")는 자동 수정 라운드 자체에 국한된 별도 열거이므로 손대지 않는다.

## 로컬 검증 절차 (CI 없음 — `./gradlew build`는 이 변경의 검증 대상 아님, `src/` 무변경)

1. **YAML 문법**: `python -c "import yaml; yaml.safe_load(open('.github/workflows/agent.yml', encoding='utf-8'))"` — 예외 없이 종료해야 한다.
2. **셸 문법**: 새로 추가한 `run:` 블록 3개 각각을 `.sh` 파일로 뽑아 `bash -n`으로 검증한다. **LF(`\n`)로 저장** — Windows 환경에서 CRLF로 저장하면 `bash -n`이 거짓 실패를 낸다(작업 지시 원문 경고, `docs/agent-mistakes.md`의 개행 관련 유사 사례와 같은 종류의 함정).
3. **조건식 대조**: 위 "도달 가능한 outcome 조합 전수 검토" 표의 `if:` 문자열을, 실제로 커밋된 `agent.yml`의 세 스텝 `if:` 줄과 한 글자씩 대조한다(복붙 오타로 상호 배타가 깨지는 것을 막기 위함 — 025의 5차 리뷰에서 실제로 조건식 오타가 지적된 선례가 있다).
4. **실제 이슈 검증**: 머지 후 테스트 이슈로 세 정지 지점 중 최소 1곳(가장 재현하기 쉬운 것은 빈 `PR_NUMBER`를 유발하는 시나리오)을 실제로 발생시켜 이슈 코멘트가 남는지 확인한다 — `issue_comment` 트리거는 항상 `dev` 기준 워크플로우 버전으로 실행되므로 PR 머지 전에는 불가능하다(025의 tasks.md와 동일한 제약).

## 테스트 계획
- 단위/슬라이스/통합 테스트: 해당 없음(`src/` 무변경, ADR-0003 테스트 전략의 대상이 아니다).
- 위 "로컬 검증 절차" 1~3번이 이번 PR 안에서 가능한 전부이고, 4번은 머지 후 별도 검증이다.
