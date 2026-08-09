# Tasks: 리뷰 게이트 자동 수정 라운드

- [x] ADR-0016 초안 작성 — ADR-0013의 "조건부 승인" 결정 절을 대체(superseded)로 표시한다. ADR-0013 상태 줄에 "일부 대체됨 — '조건부 승인' 절은 ADR-0016이 대체"를 추가한다(ADR-0005가 ADR-0008에 의해 "일부 대체됨"으로 표시된 것과 같은 형식).
- [x] `.github/workflows/agent.yml` — "리뷰 결과를 PR에 게시" 스텝의 env 즉시 평가 버그 수정. `fromJSON`을 env에서 빼고 raw `structured_output` 문자열만 env로 넘긴 뒤, `run:` 스크립트 안에서 빈 값 체크 후 `jq -r '.report'`로 파싱하도록 바꾼다. 게시되는 내용·조건(빈 값이면 스킵)은 기존과 동일하게 유지한다.
- [x] `.github/workflows/agent.yml` — `implement-and-open-pr` job에 자동 수정 라운드 스텝(차단 사항 자동 수정 → 러너 빌드 재검증 → 독립 재리뷰 → "자동 수정 1회차" 헤더 + 종료 사유로 재리뷰 결과 게시, 빌드 실패 시 별도 이력 코멘트)을 추가한다. 재리뷰 결과 게시 스텝은 앞 항목에서 고친 파싱 패턴을 그대로 재사용해 같은 버그를 복제하지 않는다.
- [x] `.github/workflows/agent.yml` — 최종 판정 병합 스텝(라운드 2 결과 우선, 없으면 라운드 1 결과)을 추가하고 조건부 승인 스텝을 그 결과를 보도록 재배치한다. `implement-and-open-pr` job에 `timeout-minutes`를 명시한다.
- [x] `docs/harness-roadmap.md` 관련 항목 갱신 — 자동 수정 라운드 도입을 구현 단계 체크리스트에 반영한다.
- [ ] 테스트 이슈 1건으로 전체 흐름 검증 — 차단 있는 self-review → 자동 수정 커밋 → 재빌드 → 재리뷰 → PR 코멘트 이력까지 실제로 도는지 확인한다(spec 완료 조건, GitHub 이슈 #292 완료 조건과 동일). **PR 머지 후에만 가능** — GitHub Actions는 issue/issue_comment 트리거 워크플로우를 항상 기본 브랜치(dev) 버전으로 실행하므로, 이 PR이 머지되기 전까지는 실제 흐름 검증이 불가능하다.
