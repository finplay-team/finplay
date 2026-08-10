# Run Log: 026-market-order-practice-tutorial

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava` | plan.md "3단계 참조 가격선 계산"·"Evidence A"·"Evidence B" 절, 019 spec.md 계산 규칙 |
| - | reviewer(PR #298 리뷰, 독립 재검토) | `git diff origin/dev...HEAD -- src/main/java`, `git diff origin/dev...HEAD -- docs/...`, PowerShell `[decimal]` 산술로 `ReferencePriceCalculatorTest` 반올림 기대값(11933.62345660/13305.20370801 등) 재계산 검증, `gh pr view 298`·`gh issue view 297`·`gh pr checks 298` 대조 | docs/conventions.md, ADR-0002, ADR-0003, docs/specs/026-market-order-practice-tutorial/plan.md·spec.md, 019-exit-price-policy/spec.md |
| - | 오케스트레이터(PR #298 리뷰 참고사항 반영) | `EvidenceJudgmentService.judgeBoundaryEvidence`의 동률 tie-break를 임의 결정에서 명시 확정으로 전환(Javadoc·plan.md "Evidence A" 절에 근거 추가) | PR #298 리뷰 참고 2번 |

## 모니터링 (사람용 요약)
- 참조 가격선 계산(`ReferencePriceCalculator`)·evidence A/B 판정(`EvidenceJudgmentService`) 순수 서비스 추가, 컴파일 통과. `PracticeIntention`에 `exitPriceType`/rate 필드가 아직 없어(019 미착수) PERCENT 분기는 독립 메서드로만 구현.
- PR #298 독립 재리뷰(reviewer 세션, 이전 판정을 신뢰하지 않고 재검증) — 차단 0건, 권장 0건, 참고 2건. PowerShell decimal로 PERCENT 반올림 테스트 기대값을 직접 재계산해 정확함을 확인했고, tie-break 죽은 분기 주장도 대수적으로 재확인(entryPrice가 항상 두 경계 사이에 있다는 019 불변조건 하에 성립). 신규 API 없음 → api-routes/api-contracts/prd 갱신 불필요, ADR 위반 없음, 순수 서비스라 단위 테스트만으로 충분(ADR-0003). 머지 가능.
- **참고 1(DTO 네이밍) 처리**: `BoundaryEvidenceResult`/`ObservationEvidenceJudgment`/`ReferencePriceLines`가 `docs/conventions.md`의 `~Dto` 접미사 관례를 따르지 않는다는 지적. 기존 코드베이스에 `OAuthAuthorizationResult`(`~Result` 접미사) 선례가 있고 리뷰어가 명시적으로 "지적하지 않는다"고 판정해, **변경하지 않기로 결정**한다(개인 취향 수준, 일관된 다른 관례로도 커버됨).
- **참고 2(tie-break 임의 결정) 처리**: 동률이면 `STOP_LOSS` 우선이라는 규칙을 spec.md가 아니라 코드에만 있던 임의 결정에서, `plan.md` "Evidence A" 절과 Javadoc에 근거(019 불변조건 하 도달 불가능, 불변조건 깨질 경우를 대비한 명시적 확정)를 남겨 **문서화된 결정으로 전환**했다. 로직 자체는 변경하지 않았다(이미 "STOP_LOSS 우선"이었고 그 선택 자체를 바꿀 근거는 없었다 — 임의성만 해소).
