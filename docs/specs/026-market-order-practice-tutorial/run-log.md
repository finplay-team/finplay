# Run Log: 026-market-order-practice-tutorial

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava` | plan.md "3단계 참조 가격선 계산"·"Evidence A"·"Evidence B" 절, 019 spec.md 계산 규칙 |

## 모니터링 (사람용 요약)
- 참조 가격선 계산(`ReferencePriceCalculator`)·evidence A/B 판정(`EvidenceJudgmentService`) 순수 서비스 추가, 컴파일 통과. PracticeIntention에 exitPriceType/rate 필드가 아직 없어(019 미착수) PERCENT 분기는 독립 메서드로만 구현.
