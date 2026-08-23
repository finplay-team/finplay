# 지정가 체결 청크 — 락 구간 vs 쓰기 구간 소요시간 실측

> 실행 기준: `src/main/java/com/finplay/api/domain/order/service/LimitOrderFillService.java`의
> `fillOnePending`에 임시로 추가한 `[PHASE-BENCHMARK]` 로그(`refactor/501-limit-order-fill-bulk-lock`
> 브랜치)와, 기존 `LimitOrderFillBatchInsertThroughputBenchmarkTest`를 그대로 재사용해 얻은 결과다.
> 목적은 [이슈 #501](https://github.com/finplay-team/finplay-backend/issues/501)(청크 내 벌크 락
> 최적화)에 실제로 들어가기 전에, 락 왕복이 전체 소요시간에서 차지하는 비중을 먼저 재서 우선순위를
> 판단하는 것이다.

## 측정 방법

`fillOnePending` 안에 `System.nanoTime()` 마커 2개를 추가했다.

- **락 구간(lockNanos)**: 메서드 시작 ~ 계좌 `FOR UPDATE` 락(`accountService.getAccountByIdForUpdate`)
  직후까지. 명시적인 order·account 락 왕복만 포함한다.
- **쓰기 구간(writeNanos)**: 그 이후 `fillBuy`/`fillSell` 호출 전체(수수료 계산 + Trade INSERT +
  holding 반영 + order 상태 UPDATE).

**주의 — BUY 경로는 이 구분이 락 비중을 과소평가한다.** BUY는 holding 락
(`findByAccountIdAndInstrumentIdForUpdate`)이 `PortfolioBuyService.applyBuyTrade` 내부에서 걸리는데,
이 호출이 "쓰기 구간"에 포함돼 있다. 즉 `writeNanos`에는 실제 INSERT/UPDATE뿐 아니라 락 왕복 하나가 더
섞여 있다 — 이 벤치마크는 BUY 주문만 만들기 때문에(`LimitOrderFillBatchInsertThroughputBenchmarkTest`),
아래 수치의 "락 비율"은 실제보다 낮게 잡힌 값이다.

## 결과 (1회 실행, 반복 없음)

| 청크 크기 | 락 구간 합계 | 쓰기 구간 합계 | 락 비율 |
|---|---|---|---|
| 5 (웜업, 참고용) | 43.8ms | 32.0ms | 57.8% |
| 10 | 56.9ms | 47.2ms | 54.7% |
| 50 | 217.6ms | 166.9ms | 56.6% |
| 100 | 301.4ms | 212.3ms | 58.7% |

같은 실행에서 `LimitOrderFillBatchInsertThroughputBenchmarkTest`가 남긴 `[BATCH-INSERT-BENCHMARK]` 총
소요시간(100건 528ms)과 위 표의 락+쓰기 합계(301.4+212.3=513.7ms)가 서로 근접해 측정 방식이 서로
모순되지 않음을 확인했다.

## 해석

청크 크기와 무관하게 락 구간이 전체 시간의 약 55~59%를 일관되게 차지한다. 위에 적은 대로 이 수치는
BUY의 holding 락이 "쓰기" 쪽으로 잘못 분류돼 있어 실제 락 비중은 이보다 더 높을 것으로 본다. 즉 #501을
먼저 처리하는 쪽이 성능 개선 폭도 더 크다는 근거가 된다 — 안전성 기준으로 이미 #501을 먼저 하기로
했던 결론과 방향이 같다.

절대적인 총 소요시간(예: 100건 528ms)은 이번 세션 안에서도 실행마다 달라졌다(같은 조건으로 이전에
돌렸을 때는 943ms였다) — JVM/커넥션 풀 예열 상태에 따라 흔들리는 값이라 절대치보다 "락 비율이 매
청크에서 55~59%대로 일관된다"는 상대적 결론에 무게를 둔다.

## 실행 환경

- `./gradlew test --tests "com.finplay.api.domain.order.service.LimitOrderFillBatchInsertThroughputBenchmarkTest" --rerun-tasks`
- Testcontainers(MySQL·Redis), 로컬 macOS, 2026-08-23 23:24 KST.

## 원본 로그 (일부)

```
[PHASE-BENCHMARK] orderId=1 lockNanos=16477125 writeNanos=10487709
[PHASE-BENCHMARK] orderId=2 lockNanos=7487250 writeNanos=5388208
[PHASE-BENCHMARK] orderId=3 lockNanos=6757708 writeNanos=5124667
...
[BATCH-INSERT-BENCHMARK] orderCount=100 totalMillis=528 perOrderMillis=5.28
```

## 참고

이 결과를 만든 `[PHASE-BENCHMARK]` 로그 자체는 `LimitOrderFillService.java`에 `TEMP(#501 사전 측정,
머지 금지)` 주석과 함께 남아 있는 임시 계측 코드다. #501을 실제로 구현하는 PR에는 포함하지 않는다.
