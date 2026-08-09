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

## 변경 후 스텝 시퀀스

1~4. 변경 없음.

5. 코드 리뷰 — 라운드 1 (id: `review`) — 내용 변경 없음.

6. 리뷰 결과를 PR에 게시 — 라운드 1 — **버그 수정** (아래 "버그 수정 상세" 참고). 게시하는 내용·조건은 기존과 동일, 파싱 방식만 바꾼다.

7. **[신규]** 자동 수정 — 차단 사항 반영 (id: `autofix`)
   - `if: success() && steps.pr.outputs.number != '' && steps.review.outputs.structured_output != '' && fromJSON(steps.review.outputs.structured_output).blocking_count > 0`
   - claude-code-action 별도 호출(구현 스텝과 같은 checkout·브랜치 위에서 이어서 실행). 프롬프트에 라운드 1 리뷰 report를 전달하고 "차단 항목만 반영해라, 권장 항목은 건드리지 마라"를 명시한다.
   - 허용 툴은 구현 스텝과 동일 수준(`Read,Grep,Glob,Edit,Write,Bash`). 커밋 후 push까지 이 스텝 안에서 수행한다.

8. **[신규]** 빌드 재검증 — 자동 수정 후 (id: `build_autofix`)
   - `if: steps.autofix.outcome == 'success'`
   - `./gradlew build`를 러너가 직접 실행(에이전트 자기 보고에 의존하지 않는 기존 원칙 유지).

9. **[신규]** 재리뷰 — 자동 수정 라운드 (id: `review_autofix`)
   - `if: steps.build_autofix.outcome == 'success'`
   - claude-code-action 별도 호출. 리뷰 기준·프롬프트·허용 툴·`json-schema`는 라운드 1 리뷰 스텝(5번)과 동일하다 — 자기가 고친 걸 자기가 통과시키지 않도록 `autofix` 스텝과는 다른 호출.

10. **[신규]** 재리뷰 결과를 PR에 게시 — 자동 수정 1회차
    - `if: steps.review_autofix.outcome == 'success' && steps.review_autofix.outputs.structured_output != ''`
    - 6번과 동일하게 수정된 파싱 패턴을 쓰되, 코멘트 본문 맨 앞에 `## 자동 수정 1회차` 헤더를 붙이고 맨 끝에 종료 사유 줄을 추가한다.
      - `blocking_count == 0` → "차단 해소"
      - 그 외(여전히 blocking > 0) → "라운드 소진(1회 한도 도달) — 사람이 직접 확인해야 합니다"

11. **[신규]** 자동 수정 라운드 — 빌드 실패 이력 코멘트
    - `if: steps.autofix.outcome == 'success' && steps.build_autofix.outcome == 'failure'`
    - PR에 `## 자동 수정 1회차\n빌드 실패로 종료 — 사람이 직접 확인해야 합니다.`만 게시한다(재리뷰는 돌리지 않는다).

12. **[신규]** 최종 판정 병합 (id: `judge`)
    - `if: always() && steps.pr.outputs.number != ''`
    - 셸 스크립트: `steps.review_autofix.outputs.structured_output`이 비어 있지 않으면(자동 수정 라운드가 재리뷰까지 실행됨) 그 값에서 `blocking_count`·`recommended_count`를 뽑아 최종값으로 쓴다. 비어 있으면(자동 수정 라운드 미실행 — 라운드 1이 이미 깨끗했거나, 빌드 실패로 재리뷰까지 못 갔거나) 라운드 1(`steps.review.outputs.structured_output`) 값을 최종값으로 쓴다. `final_blocking`·`final_recommended`를 `GITHUB_OUTPUT`에 쓴다.

13. 조건부 승인 (기존 7번 위치에서 이 자리로 이동)
    - `if: success() && steps.pr.outputs.number != '' && steps.judge.outputs.final_blocking == '0' && steps.judge.outputs.final_recommended == '0'`
    - 승인 판정 기준(차단 0·권장 0) 자체는 ADR-0013 그대로 — 판정 대상만 "라운드 1 또는 자동 수정 라운드의 최종 결과"로 바뀐다.

### `timeout-minutes`
- `implement-and-open-pr` job에 `timeout-minutes: 60`을 명시한다.
- 근거: 기존 흐름(구현 + 빌드 ~7분 + 리뷰) 소요에 자동 수정 라운드(수정 + 빌드 ~7분 + 재리뷰) 추가분을 더한 보수적 추정 — 이슈 #292 propose-directions의 비용 추정("라운드 1회당 claude-code-action 호출 2건과 전체 빌드")과 `docs/agent-mistakes.md`의 실측 빌드 시간(7분 내외)을 근거로 삼았다. 정확한 값 자체는 이번 spec의 핵심 결정이 아니며, 실제 실행 시간을 관찰한 뒤 조정 가능하다.

## 버그 수정 상세 — env 표현식 즉시 평가
- **기존**: `env: REVIEW_REPORT: ${{ fromJSON(steps.review.outputs.structured_output).report }}` + `if: ... && steps.review.outputs.structured_output != ''`
- **문제**: GitHub Actions는 스텝의 `env` 컨텍스트를 `if:` 판정과 독립적으로(그리고 먼저) 평가한다. `structured_output`이 빈 문자열이면 `fromJSON('')`이 표현식 평가 자체에서 에러를 던지고, `if:`가 스텝을 건너뛰기도 전에 스텝이 failure로 기록된다. `if:`가 걸어주는 것처럼 보이지만 실제로는 막아주지 못한다(2026-08-09 실행 https://github.com/finplay-team/finplay/actions/runs/31333724172/job/93295980910에서 "The template is not valid ... Error reading JToken from JsonReader"로 실측).
- **수정**: `env`에는 원문 문자열만 넘긴다.
  ```yaml
  env:
    STRUCTURED_OUTPUT: ${{ steps.review.outputs.structured_output }}
  run: |
    if [ -z "$STRUCTURED_OUTPUT" ]; then
      echo "structured_output이 비어 있어 PR 코멘트를 건너뜁니다."
      exit 0
    fi
    REVIEW_REPORT=$(printf '%s' "$STRUCTURED_OUTPUT" | jq -r '.report')
    printf '%s\n' "$REVIEW_REPORT" > /tmp/review-body.md
    gh pr comment "${{ steps.pr.outputs.number }}" --body-file /tmp/review-body.md
  ```
  문자열 대입(env)은 빈 값이어도 실패하지 않는다 — JSON 파싱(`jq`)은 값이 있는지 셸에서 확인한 **뒤**, `run:` 스크립트 안에서만 수행한다. `jq`는 `ubuntu-latest`에 기본 설치돼 있다.
- 이 패턴을 라운드 1 게시 스텝(6번)과 자동 수정 라운드 재리뷰 게시 스텝(10번) 양쪽에 동일하게 적용해, 새로 추가하는 스텝이 같은 버그를 복제하지 않게 한다.
- 조건부 승인 스텝(13번, 기존 7번)의 `if:`는 `env`가 아니라 `if:` 표현식 자체 안에서 좌에서 우로 단락 평가되므로(기존에도 `structured_output != ''`를 `fromJSON(...)` 호출보다 먼저 검사) 이 버그의 대상이 아니다 — 그대로 둔다. 다만 이번 변경으로 판정 대상이 `judge` 스텝의 출력을 보도록 바뀐다(12·13번 참고).

## 테스트 계획
- 단위/슬라이스/통합 테스트: 해당 없음(`src/` 무변경, ADR-0003 테스트 전략의 대상이 아니다).
- 검증 방법
  1. YAML 문법 확인(`actionlint` 또는 편집기 구문 하이라이팅) — 로컬에 GitHub Actions 실행 환경이 없으므로 실제 동작 검증은 실제 이슈로 한다.
  2. 차단 사항이 있는 테스트 이슈 1건을 만들어 `@claude N안으로 구현해줘`를 남기고, 자동 수정 커밋 추가 → 재빌드 → 재리뷰 → PR 코멘트("자동 수정 1회차" 헤더 확인)까지 실제로 도는지 확인한다(spec의 완료 조건과 동일).
  3. `structured_output`이 빈 문자열인 경우(리뷰 에이전트가 스키마 도구를 호출하지 않고 끝나는 경우)는 실제 이슈로 재현하기 어려우므로, `STRUCTURED_OUTPUT=""`로 같은 `run:` 스크립트를 로컬 셸에서 직접 실행해 스텝이 죽지 않고 스킵 로그만 남기는지 확인한다.
