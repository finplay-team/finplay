# Plan: 리뷰 게이트 자동 수정 라운드

## 관련 문서
- Spec: `./spec.md`
- GitHub 이슈: #292 (finplay-team/finplay), propose-directions 코멘트(1·2·3안 분석 + 공통 권고) — 채택안: 1안
- 관련 ADR: [ADR-0013](../../adr/0013-issue-triggered-agent-harness.md)(일부 대체 대상), 신규 [ADR-0016](../../adr/0016-review-gate-auto-fix-round.md), [ADR-0005](../../adr/0005-local-agent-orchestration.md)(자동 머지 금지 원칙 유지 확인)

이 spec은 API 엔드포인트나 엔티티가 아니라 `.github/workflows/agent.yml`의 `implement-and-open-pr` job을 바꾸는 CI 워크플로우 변경이다. spec 템플릿의 "API 설계·입력 명세·데이터 모델" 대신 아래처럼 스텝 시퀀스로 설계를 기술한다.

## 변경 전 스텝 시퀀스 (요약)
1. checkout → git 사용자 설정 → setup-java
2. 구현(claude-code-action)
3. 빌드 검증 (id: `build`)
4. 방금 연 PR 번호 조회 (id: `pr`)
5. 코드 리뷰 (id: `review`) — claude-code-action, `structured_output`(blocking_count·recommended_count·reference_count·report)
6. 리뷰 결과를 PR에 게시 — **버그**: `env.REVIEW_REPORT: ${{ fromJSON(steps.review.outputs.structured_output).report }}`가 `if: ... structured_output != ''` 가드와 무관하게 즉시 평가돼, `structured_output`이 빈 문자열이면 `fromJSON('')`이 표현식 평가 자체에서 실패해 스텝이 failure로 죽는다(2026-08-09 실행 실측).
7. 조건부 승인 — `if:`에서 `fromJSON(...).blocking_count == 0 && fromJSON(...).recommended_count == 0`

## 변경 후 스텝 시퀀스 (최종 구현 기준, PR #293 리뷰 4라운드 반영)

> 아래는 초기 설계가 아니라 최종 머지된 `agent.yml` 기준이다. 구현 중·리뷰 과정에서 초기 설계와 달라진 지점은 각 항목에 **[설계 대비 변경]**으로 표시했다. 번호는 위 "변경 전 스텝 시퀀스"와 같은 기준이다(1 = checkout 그룹, 2 = 구현, 3 = 빌드 검증, 4 = PR 번호 조회, …).

1. checkout 그룹(checkout → git 사용자 설정 → setup-java) — 변경 없음.

2. 구현(claude-code-action) — 변경 없음.

3. 빌드 검증 (id: `build`) — 변경 없음.

4. 방금 연 PR 번호 조회 (id: `pr`)
   - **[설계 대비 변경, PR #293 3차 리뷰 권장 3]** PR을 못 찾으면(`NUMBER`가 빈 문자열) `[ -n "$NUMBER" ] || { echo "::error::..."; exit 1; }`로 job을 명시적으로 실패시키는 가드가 추가됐다 — 이전엔 이후 스텝이 전부 조용히 스킵돼 job이 우연히 초록으로 끝났다(이슈 #292의 실제 실패 사례).

5. 코드 리뷰 — 라운드 1 (id: `review`) — 내용 변경 없음.

6. 리뷰 결과를 PR에 게시 — 라운드 1 — 버그 수정(아래 "버그 수정 상세" 참고).
   - **[설계 대비 변경, PR #293 3차 리뷰 권장 2]** `structured_output`이 빈 값이어도 조용히 건너뛰지 않고 "리뷰가 구조화된 결과를 반환하지 않았습니다 — 사람이 직접 확인해야 합니다."를 PR에 게시한다. 이건 라운드가 아니므로 "자동 수정 N회차" 헤더는 붙이지 않는다.
   - **[설계 대비 변경]** `if:`에서 `steps.review.outputs.structured_output != ''` 조건이 빠졌다 — 빈 값 판단을 이제 `run:` 안에서 위 방식으로 직접 처리하므로 `if:`에서 걸러낼 필요가 없어졌다.

7. **[신규, PR #293 4차 리뷰 참고 1]** 리뷰 호출 실패 이력 코멘트 — 라운드 1 (id: `review_failure_comment`)
   - `if: always() && steps.pr.outputs.number != '' && steps.review.outcome != 'success'`
   - 6번은 `review`가 success로 끝났지만 구조화 출력이 빈 경우(침묵 실패)를 흡수한다. 여기는 `review` 호출 자체가 실패로 끝나는 경우다 — 자동 수정 라운드 쪽 15번(`autofix_review_failure_comment`)과 같은 패턴이며, 라운드 1만 비대칭으로 흔적이 안 남던 것을 맞췄다. 라운드가 아니므로 "자동 수정 N회차" 헤더는 없다.

8. **[신규, 최초 설계에는 없었음]** 자동 수정 준비 — 라운드 1 report 파일 저장 (id: `prep_autofix`)
   - `if: success() && steps.pr.outputs.number != '' && steps.review.outputs.structured_output != '' && fromJSON(steps.review.outputs.structured_output).blocking_count > 0`
   - 구현 중 드러난 사실: claude-code-action의 `prompt:`(= `with:`)도 `env:`와 마찬가지로 `if:` 가드와 무관하게 먼저 평가된다. 그래서 report 본문을 다음 스텝의 prompt에 `fromJSON(...)`으로 직접 넣지 않고, `/tmp/review-report-round1.md` 파일에 적어 자동 수정 스텝이 파일을 읽게 했다.

9. 자동 수정 — 차단 사항 반영 (id: `autofix`) — `if: steps.prep_autofix.outcome == 'success'`. 나머지 내용(프롬프트·허용 툴)은 최초 설계와 동일.

10. **[신규, PR #293 3차 리뷰 권장 1]** 자동 수정 라운드 — 자동 수정 호출 실패 이력 코멘트 (id: `autofix_call_failure_comment`)
    - `if: always() && steps.prep_autofix.outcome == 'success' && steps.autofix.outcome != 'success'`
    - `autofix` 스텝 자체가 실패/취소로 끝나면(커밋·push까지 못 갔을 수 있음) 이후 아무 흔적도 안 남던 사각지대를 메운다. `autofix_build_failure_comment`와 같은 패턴.

11. 빌드 재검증 — 자동 수정 후 (id: `build_autofix`) — `if: steps.autofix.outcome == 'success'`. 변경 없음.

12. 재리뷰 — 자동 수정 라운드 (id: `review_autofix`) — `if: steps.build_autofix.outcome == 'success'`. 변경 없음.

13. 재리뷰 결과를 PR에 게시 — 자동 수정 1회차 (id: `post_review_autofix`)
    - **[설계 대비 변경]** `if: steps.review_autofix.outcome == 'success'`만 남았다(`&& steps.review_autofix.outputs.structured_output != ''` 조건은 빠졌다 — 빈 값도 이 스텝 안에서 직접 처리하기 때문).
    - `STRUCTURED_OUTPUT`이 비어 있으면(PR #293 2차 리뷰 — 침묵 실패) → "## 자동 수정 1회차 / 재리뷰가 구조화된 결과를 반환하지 않았습니다 — 사람이 직접 확인해야 합니다."
    - `blocking_count == 0` → "차단 해소"
    - 그 외(여전히 blocking > 0) → "라운드 소진(1회 한도 도달) — 사람이 직접 확인해야 합니다"
    - **[설계 대비 변경, PR #293 리뷰]** 최초 설계엔 "라운드 소진"을 별도 스텝(`judge` 뒤)에서 게시하려 했으나, `post_review_autofix`와 동시에 "## 자동 수정 1회차" 코멘트가 2건 게시되는 중복 문제가 나와 별도 스텝을 없애고 이 스텝 하나로 세 분기(침묵 실패/차단 해소/라운드 소진)를 모두 흡수했다.

14. 자동 수정 라운드 — 빌드 실패 이력 코멘트 (id: `autofix_build_failure_comment`) — `if: always() && steps.autofix.outcome == 'success' && steps.build_autofix.outcome != 'success'`. 내용은 최초 설계와 동일.

15. **[신규, PR #293 2차 리뷰]** 자동 수정 라운드 — 재리뷰 호출 실패 이력 코멘트 (id: `autofix_review_failure_comment`)
    - `if: always() && steps.autofix.outcome == 'success' && steps.build_autofix.outcome == 'success' && steps.review_autofix.outcome != 'success'`
    - `review_autofix` 호출 자체가 실패로 끝나는 경우(13번의 "구조화 출력 없이 성공 종료"와 다름)를 흡수한다. `post_review_autofix`의 if와 상호 배타적이라 동시 실행은 없다.

16. 최종 판정 병합 (id: `judge`)
    - `if: !cancelled() && steps.pr.outputs.number != ''`
    - **[설계 대비 변경]** 최초 설계는 "`review_autofix`의 structured_output 유무"로 분기하려 했으나, 최종 구현은 **`steps.autofix.outcome`**을 1차 분기 기준으로 쓴다. `AUTOFIX_OUTCOME == 'success'`(자동 수정 라운드가 시도됨)면 `BUILD_AUTOFIX_OUTCOME`(빌드 실패 시 `final_blocking=1`로 승인 보류)과 `REVIEW_AUTOFIX_STRUCTURED_OUTPUT`(비어 있어도 `final_blocking=1`)을 차례로 확인한 뒤에만 실제 `blocking_count`·`recommended_count`를 최종값으로 쓴다. 자동 수정 라운드가 안 돌았으면 라운드 1 결과를 쓰되, 그마저 비어 있으면 역시 `final_blocking=1`이다. **판정 불가한 모든 경우가 "모르면 승인 안 함"으로 수렴**한다.
    - **[설계 대비 변경, PR #293 4차 리뷰 참고 3]** `final_reason`은 "자동 수정이 아예 안 돌았다"(`round1_only`)와 "돌다가 실패했다"(`autofix_failed`)를 구분한다. `final_reason`은 어느 `if:`에서도 참조되지 않는 **로그 전용 값**이라(라운드 소진 코멘트 스텝을 제거한 뒤로 소비처가 없다 — `steps.judge.outputs` 소비처는 `final_blocking`·`final_recommended`뿐) 판정에 영향이 없다.

17. 조건부 승인 (기존 7번 위치에서 16번 뒤로 이동)
    - `if: !cancelled() && steps.judge.outcome == 'success' && steps.pr.outputs.number != '' && steps.judge.outputs.final_blocking == '0' && steps.judge.outputs.final_recommended == '0'`
    - **[설계 대비 변경]** `success()`가 아니라 `!cancelled() && steps.judge.outcome == 'success'`다 — `judge`가 `!cancelled()`로 도는 스텝이라 이 승인 스텝도 암묵적 `success()`에 기대지 않고 `judge`가 실제로 성공했는지부터 명시적으로 확인한다.
    - 승인 판정 기준(차단 0·권장 0) 자체는 ADR-0013 그대로 — 판정 대상만 "라운드 1 또는 자동 수정 라운드의 최종 결과"로 바뀐다.

### `always()`와 `!cancelled()`를 나누는 기준, 그리고 대상 판정을 `!= 'success'`로 두는 이유 (PR #293 3차 리뷰 참고 1 → 4차 리뷰 권장 1 → 5차 리뷰 권장 1로 정정)
- **이력 코멘트 스텝 4개는 `always()`** — `review_failure_comment`·`autofix_call_failure_comment`·`autofix_build_failure_comment`·`autofix_review_failure_comment`.
- **판정·승인 스텝 2개는 `!cancelled()`** — `judge`·조건부 승인.
- 근거: `timeout-minutes: 90` 초과 시 GitHub은 job을 **취소**로 처리한다. 가장 오래 도는 스텝이 `autofix`라 타임아웃이 걸릴 확률이 제일 높은 지점이 바로 거기인데, 이력 코멘트까지 `!cancelled()`면 그 경우 PR에 아무 흔적도 안 남아 spec.md의 "코멘트 이력만 보고 왜 멈췄는지 알 수 있다"를 못 지킨다. 정보성 코멘트는 취소된 런에 붙어도 무해하지만 **승인**은 아니므로, 막아야 하는 것은 판정·승인뿐이다.
- 3차 리뷰에서 이력 코멘트까지 일괄 `!cancelled()`로 바꿨던 것을 4차 리뷰에서 위 기준으로 되돌렸다.
- **이력 코멘트 스텝의 대상 판정은 `outcome == 'failure'`가 아니라 `outcome != 'success'`다** (5차 리뷰 권장 1). 취소된 스텝의 `outcome`은 `failure`가 아니라 `cancelled`라, `== 'failure'`로 두면 위에서 `always()`를 붙여 취소 경로를 살려둔 의미가 그대로 사라진다. 이력 코멘트 4개가 모두 이 규칙을 따른다. 각 스텝이 바로 앞 스텝의 `success`를 함께 요구하므로 대상 스텝이 `skipped`가 될 수 없어 `!= 'success'`가 과하게 잡히지도 않는다. 같은 이유로 코멘트 본문은 "실패했습니다"로 단정하지 않고 실제 `outcome` 값을 `*_OUTCOME` env로 받아 출력한다 — 조건이 `failure`와 `cancelled`를 모두 잡으므로 단정하면 타임아웃으로 멈춘 런에서 원인을 잘못 지목하게 된다.
- 도달 가능한 outcome 조합 10개를 전수 시뮬레이션해 라운드 1 코멘트 1개 + (자동 수정 라운드 진입 시) 라운드 코멘트 1개로 중복·누락이 0건임을 확인했다 (5차 리뷰 답신에 표로 기록).

### PR 번호를 `run:` 스크립트에 전달하는 방식 (PR #293 3차 리뷰 참고 4)
- `gh pr comment`/`gh pr review` 호출 전부 `run:` 스크립트에 `${{ steps.pr.outputs.number }}`를 직접 보간하지 않는다. 각 스텝의 `env:`에 `PR_NUMBER: ${{ steps.pr.outputs.number }}`를 추가하고, `run:`에서는 `"$PR_NUMBER"`로 참조한다 — 파일 상단 L79 규칙("run 스크립트에는 env로만 전달")과의 일관성.

### `timeout-minutes`
- `implement-and-open-pr` job에 `timeout-minutes: 90`을 명시한다(PR #293 3차 리뷰 권장 4 — 최초 60분 추정에서 상향).
- 산정 근거의 정본은 [ADR-0016](../../adr/0016-review-gate-auto-fix-round.md)에 둔다. `agent.yml`에는 ADR 참조만 짧게 남긴다.

## 버그 수정 상세 — env 표현식 즉시 평가
- **기존**: `env: REVIEW_REPORT: ${{ fromJSON(steps.review.outputs.structured_output).report }}` + `if: ... && steps.review.outputs.structured_output != ''`
- **문제**: GitHub Actions는 스텝의 `env` 컨텍스트를 `if:` 판정과 독립적으로(그리고 먼저) 평가한다. `structured_output`이 빈 문자열이면 `fromJSON('')`이 표현식 평가 자체에서 에러를 던지고, `if:`가 스텝을 건너뛰기도 전에 스텝이 failure로 기록된다. `if:`가 걸어주는 것처럼 보이지만 실제로는 막아주지 못한다(2026-08-09 실행 https://github.com/finplay-team/finplay/actions/runs/31333724172/job/93295980910에서 "The template is not valid ... Error reading JToken from JsonReader"로 실측).
- **수정**: `env`에는 원문 문자열만 넘긴다.
  ```yaml
  env:
    PR_NUMBER: ${{ steps.pr.outputs.number }}
    STRUCTURED_OUTPUT: ${{ steps.review.outputs.structured_output }}
  run: |
    if [ -z "$STRUCTURED_OUTPUT" ]; then
      printf '%s\n' "리뷰가 구조화된 결과를 반환하지 않았습니다 — 사람이 직접 확인해야 합니다." > /tmp/review-body.md
      gh pr comment "$PR_NUMBER" --body-file /tmp/review-body.md
      exit 0
    fi
    REVIEW_REPORT=$(printf '%s' "$STRUCTURED_OUTPUT" | jq -r '.report')
    printf '%s\n' "$REVIEW_REPORT" > /tmp/review-body.md
    gh pr comment "$PR_NUMBER" --body-file /tmp/review-body.md
  ```
  문자열 대입(env)은 빈 값이어도 실패하지 않는다 — JSON 파싱(`jq`)은 값이 있는지 셸에서 확인한 **뒤**, `run:` 스크립트 안에서만 수행한다. `jq`는 `ubuntu-latest`에 기본 설치돼 있다. `PR_NUMBER`도 같은 이유로 `env`를 거친다(위 "PR 번호를 run: 스크립트에 전달하는 방식" 참고).
- 이 패턴을 리뷰 결과 게시 스텝(6번)과 자동 수정 라운드 재리뷰 게시 스텝(13번, `post_review_autofix`) 양쪽에 동일하게 적용해, 새로 추가하는 스텝이 같은 버그를 복제하지 않게 한다.
- 조건부 승인 스텝(17번, 기존 7번)의 `if:`는 `env`가 아니라 `if:` 표현식 자체 안에서 좌에서 우로 단락 평가되므로(기존에도 `structured_output != ''`를 `fromJSON(...)` 호출보다 먼저 검사) 이 버그의 대상이 아니다 — 그대로 둔다. 다만 이번 변경으로 판정 대상이 `judge` 스텝의 출력을 보도록 바뀐다(16·17번 참고).

## 테스트 계획
- 단위/슬라이스/통합 테스트: 해당 없음(`src/` 무변경, ADR-0003 테스트 전략의 대상이 아니다).
- 검증 방법
  1. YAML 문법 확인(`actionlint` 또는 편집기 구문 하이라이팅) — 로컬에 GitHub Actions 실행 환경이 없으므로 실제 동작 검증은 실제 이슈로 한다.
  2. 차단 사항이 있는 테스트 이슈 1건을 만들어 `@claude N안으로 구현해줘`를 남기고, 자동 수정 커밋 추가 → 재빌드 → 재리뷰 → PR 코멘트("자동 수정 1회차" 헤더 확인)까지 실제로 도는지 확인한다(spec의 완료 조건과 동일).
  3. `structured_output`이 빈 문자열인 경우(리뷰 에이전트가 스키마 도구를 호출하지 않고 끝나는 경우)는 실제 이슈로 재현하기 어려우므로, `STRUCTURED_OUTPUT=""`로 같은 `run:` 스크립트를 로컬 셸에서 직접 실행해 스텝이 죽지 않고 스킵 로그만 남기는지 확인한다.
