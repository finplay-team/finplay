# Run Log: 041-tutorial-market-scenario

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 16:25 | reviewer(리뷰) | `git diff origin/dev...HEAD` (041 1~3번) | conventions.md, ADR-0002·0003·0004·0021, 041 spec·plan·tasks |
| 22:10 | 메인 세션 | `./gradlew test --tests "*Practice*" --tests "*Tutorial*" --tests "*Scenario*"` (041 4~5번) | 경량 경로 — 전체 빌드는 PR 직전 1회 |

## 모니터링 (사람용 요약)
- 16:25 — 041 1~3번 리뷰: 차단 1건(대본 로더가 Jackson 2 `ObjectMapper`를 주입받아 Boot 4.1 컨텍스트에 후보 빈이 없음), 권장 2건. 대본 값·문안·도달 부등식·V50 마이그레이션은 문서와 일치.

## 041 4~5번 판정 기록 (이슈 #472, 2026-08-19)

plan이 "구현 착수 시 판정하고 run-log에 남긴다"고 지시한 두 항목이다.

### `progress_updated_at` — 새 컬럼 `scenario_progress_updated_at`(V52)

`updated_at` 재사용은 택하지 않았다. 그 컬럼은 재시작·완료·프리셋 선택(042 3번)·완료 replay 조정처럼
**대본 진행과 무관한 경로가 전부 갱신**하므로, 그런 요청 한 번이 사용자가 실제로 기다린 시간을 0으로
만들고 대본이 그만큼 뒤로 밀린다. 041 3번 세션도 별도 컬럼을 권했다(context-notes.md). 추가형 nullable
이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니며, 대본 위치 다섯 컬럼과 함께 종목 선택·재시작에서
지워진다. 머지 직전 `git ls-tree origin/dev -- src/main/resources/db/migration`로 최고 번호가 V51임을
확인해 V52를 부여했다.

### `order` 인터페이스 — `canonicalPrice` 커서화 (오버로드 아님)

`LimitOrderFillService`에 가격을 받는 오버로드를 더하는 대안은 **문제를 옮기기만 한다.** 체인이
`fillIfPending(orderId, pricedAt)` → `lockForFill(attribution, pricedAt)` →
`canonicalPrice(attempt, pricedAt)`라서, 오버로드를 더해도 `lockForFill`이 여전히 시각에서 가격을
파생한다. 그 경로까지 함께 고쳐야 하고, 그러면 `order`의 공개 계약만 넓어지고 남는 이득이 없다.
커서화는 `PracticeAttemptCanonicalPriceService.canonicalPrice(attempt, observedAt)`의 시그니처를
유지한 채 내부 파생만 바꾸므로 `order`의 공개 계약이 그대로다 — 버전 2에서 `observedAt`이 무시될 뿐이다.

**대가는 순회 도중의 중간 커서가 보인다는 것**이고, tick이 attempt를 비관 잠금하고 있어 같은 attempt에
대한 다른 요청이 직렬화되므로 받아들였다. 오히려 이 성질이 SCENARIO-013을 가능하게 한다 — 커서를 밀고
정산을 부르면 체결이 그 분의 가격을 그대로 본다.

### plan과 다르게 구현한 것

- **`GENERATOR_VERSION`을 시장 조건부로 올렸다.** plan·tasks는 "2로 올린다"라고만 적었는데, 저작된
  대본이 `scenario-crypto-v1.json` 하나뿐이라 STOCK까지 2를 주면 모든 가격 조회가
  `IllegalArgumentException("대본이 저작되지 않은 시장입니다: STOCK")`으로 터진다 —
  `PracticeAttemptCompletionFlowIntegrationTest`의 STOCK 파라미터에서 실제로 재현했다.
  `TutorialScenarioScriptLoader.hasScript(market)`로 판정하므로 STOCK 대본(SCENARIO-024)이 들어오면
  자동으로 따라간다.
- **완료 replay attempt는 버전 1로 남겼다.** `initializeCompletedReplay`는 legacy completion만 있는
  사용자에게 만들어 주는 읽기 전용 실행이라 커서가 없고 tick도 돌지 않는다. 버전 2를 주면 대본 첫 구간
  0분에 고정된 평평한 차트가 되므로 기존 재현을 유지했다.
- **`virtualDateTime`은 두 버전 모두 `anchorAt` 기준 벽시계 파생을 유지했다.** plan은 이 값의 버전 2
  정의를 정하지 않았다. 커서에서 파생하면 대기 루프에서 시계가 되감기고, 별도 누적 컬럼을 두는 것은
  이 항목의 범위를 넘는다. 결과적으로 clamp된 만큼 표시 시계가 커서보다 앞설 수 있으며
  `docs/api-contracts.md`에 적었다. 사건의 `revealedAtVirtualMinute`을 내리는 6번이 이 값을 다시 볼 수
  있다.
- **버전 2 차트의 과거 29봉은 버전 1과 같은 seed 생성을 쓴다.** plan §대본 설계가 "과거 29개 완결
  일봉은 대본 대상이 아니다"라고 적은 것을 그대로 따랐고, `TutorialPriceGenerator.generateHistory`를
  두 버전이 공유하도록 뽑아 V1 golden vector가 무변경으로 통과한다.

### 042 6번에 남긴 자리

진행 계산은 가상 분마다 `PracticeOrderSettlementService.settleCurrentRun(attemptId, runNumber,
pricedAt)` **하나만** 부른다. 042의 OCO 정산 루프를 그 메서드 안에 얹으면 지정가와 OCO가 같은 가상 분에
같은 순서로 판정된다 — 진행 계산 쪽은 손대지 않아도 된다.
