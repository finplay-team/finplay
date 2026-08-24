# 지정가 청크 벌크 락과 동시에 도는 시장가 주문의 완료 소요시간 실측

> 실행 기준: `src/test/java/com/finplay/api/domain/order/LimitOrderFillMarketOrderContentionBenchmarkTest.java`
> (`refactor/501-limit-order-fill-bulk-lock` 브랜치, 커밋 `7785fead`). [이슈 #501](https://github.com/finplay-team/finplay-backend/issues/501)(청크 내 벌크 락)이
> 계좌 락을 청크 시작 시 한꺼번에(벌크 `SELECT ... FOR UPDATE`) 거는 방식으로 바뀌면서, 그 계좌를 동시에
> 건드리려는 시장가 주문이 대기하기 시작하는 시점이 앞당겨질 수 있다는 이론적 우려가 있었다. 이 실측은
> "얼마나 더 기다리게 되는지"를 지정가 청크(벌크 락)와 시장가 주문이 같은 계좌를 동시에 건드리는 조건에서
> 직접 잰 것이다. `docs/loadtest/limit-order-fill-batch-bulk-lock-benchmark-result.md`(청크 자체의 처리
> 시간 개선)와는 별개로, 이번 spec의 `tasks.md`에는 없던 추가 실측이다.

## 측정 방법

- **적용 후(벌크 락)**: 현재 브랜치(`refactor/501-limit-order-fill-bulk-lock`, 커밋 `7785fead`)에서 그대로
  실행. `fillBatch`가 청크 시작 시 order→account→holding을 각각 벌크 `FOR UPDATE`로 한 번씩 잠근다 — 계좌
  벌크 락은 청크 안의 계좌 전부를 order 벌크 락 직후 한 번에 잠근다.
- **적용 전(개별 순차 락)**: `git worktree add /tmp/limit-order-market-contention-baseline dev`로 `dev`
  (커밋 `453c609c`, 이 spec이 아직 반영되지 않은 상태)의 별도 워킹트리를 만들고, 같은 벤치마크 테스트
  파일을 그 워킹트리에 복사해 실행했다. `dev`의 `fillBatch`는 청크 안에서 주문마다 order→account→holding을
  개별 `FOR UPDATE`로 순차 왕복하며, 계좌 락은 그 주문 차례가 됐을 때만 걸린다. 측정 후
  `git worktree remove --force`로 정리했고, 현재 작업 브랜치의 커밋 이력·워킹트리는 건드리지 않았다.
- 시나리오는 두 조건에서 동일: 계좌 15개(`LimitOrderFillBulkLockAccountPoolBenchmarkTest`의 계좌풀 규모
  참고), 단일 종목 A(`MCTA`류)에 단일 가격(1,000,000)으로 지정가 매수를 계좌마다 1건씩(총 15건) 만들어
  **원래 처리 순서(requestedAt asc, id asc)상 맨 마지막에 오는 주문의 계좌**를 고정한다. 그 계좌만 대상으로,
  별도 종목 B(`MCTB`류)에 시장가 매수 1건을 낸다.
- `CountDownLatch`로 "지정가 청크 `fillBatch` 시작"과 "그 계좌를 대상으로 한 시장가 주문 생성"을 정확히
  동시에 출발시킨다(`LimitOrderFillBatchMarketOrderDeadlockIntegrationTest`의 `CountDownLatch`/
  `ExecutorService` 구조를 그대로 따름). **시장가 주문 스레드의 시작~완료까지 걸린 시간**(`System.nanoTime()`)
  을 핵심 측정값으로 기록하고, 지정가 청크(`fillBatch`) 자체의 총 소요시간도 참고용으로 함께 기록한다.
- 각 조건 한 번의 JVM 기동(Testcontainers MySQL·Redis) 안에서 5회 반복(`REPEAT=5`)하고 중앙값을 쓴다.
  CI 게이트가 아니라 수동 실측이라 성능 임계값을 assert하지 않고 결과를 로그로만 남긴다.

## 결과 (5회 반복)

| 조건 | 시장가 주문 소요시간 median(raw, ms) | 지정가 청크 총 소요시간 median(raw, ms) |
|---|---|---|
| 적용 전 (dev, 개별 순차 락) | 14ms ([38, 15, 14, 13, 11]) | 124ms ([195, 151, 124, 104, 96]) |
| 적용 후 (054, 벌크 락) | 18ms ([243, 17, 19, 18, 15]) | 111ms ([222, 104, 111, 111, 83]) |

**시장가 주문 완료 소요시간의 중앙값은 14ms → 18ms로 4ms 늘었다.** 방향은 이론(벌크 락이 시장가를 더
오래 기다리게 한다)과 일치하지만, 폭은 청크 처리 시간(약 100ms 안팎)에 훨씬 못 미친다.

## 해석

이론상 최악의 경우는 "벌크 락은 청크 시작과 동시에 대상 계좌를 잠그므로 시장가 주문이 청크 전체 시간만큼
기다려야 하고, 순차 락은 시장가가 그 계좌 차례가 오기 전에 락을 선점해 거의 안 기다린다"는 것이었다.
5회 반복 raw 값을 나눠보면 이 이론이 **가끔은 정확히 재현되지만, 항상 재현되지는 않는다.**

- **적용 후(벌크 락) rep 0**: `chunkTotalMillis=222`, `marketOrderMillis=243` — 시장가 주문이 청크 총
  소요시간과 거의 같거나 더 길게 걸렸다. 시장가 주문이 계좌 락을 얻으려다 청크의 벌크 계좌 락에 막혀
  청크가 끝날 때까지(커밋 시점) 대기한 뒤에야 자신의 처리(약 20ms)를 마친 것과 정확히 부합하는 패턴이다
  — 이론이 예측한 최악의 경우가 실제로 관측됐다.
- **적용 전(dev) rep 0**: `chunkTotalMillis=195`, `marketOrderMillis=38` — 시장가 주문이 청크보다 훨씬
  먼저(약 5배) 끝났다. 순차 락이 그 계좌까지 도달하기 전에 시장가 주문이 락을 선점해 거의 기다리지 않은
  것과 부합한다 — 이 역시 이론이 예측한 그대로다.
- **나머지 4회(양쪽 조건 공통)**: 시장가 주문이 11~19ms 안에 끝났다. 벌크 락 조건에서도 시장가 주문의
  자체 처리 경로(주문 검증·가격 조회·계좌 락)가 청크의 "order 벌크 락 → account 벌크 락" 경로보다 먼저
  대상 계좌의 `FOR UPDATE`에 도달하는 경우가 더 흔했다는 뜻이다. 이 경우 대기 비용은 시장가 주문이 아니라
  청크 쪽(벌크 계좌 락 쿼리가 시장가 주문의 짧은 트랜잭션이 커밋할 때까지 대기)으로 넘어가는데, 시장가
  주문 자체가 몇 십 ms 안에 끝나는 짧은 트랜잭션이라 청크 총 소요시간에 끼치는 영향도 크지 않았다.

**정직한 결론**: 계좌 15개·단일 청크 규모에서는 벌크 락이 이론상 최악의 경우(청크 전체 시간만큼 시장가를
기다리게 함)를 만들어내긴 하지만, 그 최악의 경우가 매번 일어나지는 않는다 — 어느 쪽이 대상 계좌의
`FOR UPDATE`를 먼저 신청하는지는 두 트랜잭션의 상대적인 진행 속도에 달린 경쟁(race)이고, 이번 재현
규모(order 락 + account 벌크 락까지 가는 경로가 짧다)에서는 시장가 주문의 자체 처리 경로도 충분히 빨라
경쟁에서 이기는 경우가 더 많았다. 중앙값만 보면 영향이 미미해 보이지만(+4ms), 5회 중 1회(rep 0, 양쪽
조건 모두)는 이론이 예측한 양극단의 패턴을 정확히 보여준다 — **표본이 5회로 작아 "보통은 이 정도"라고
일반화하기보다 "이런 양상이 둘 다 나타날 수 있다"는 정도로 읽는 것이 안전하다.** 청크 크기(계좌 수)가
커져 청크 총 소요시간 자체가 길어지면, 시장가 주문이 경쟁에서 이길 확률(=벌크 계좌 락 쿼리가 실행되기
전에 시장가 주문이 먼저 락을 신청할 확률)은 상대적으로 줄어들 것으로 추정되지만, 이는 이번 실측 범위
밖이다(별도 재현 필요).

## 실행 환경

- 적용 후: `./gradlew test --tests "com.finplay.api.domain.order.LimitOrderFillMarketOrderContentionBenchmarkTest" --rerun-tasks` (커밋 `7785fead`, 브랜치 `refactor/501-limit-order-fill-bulk-lock`)
- 적용 전: 위와 동일 명령을 `/tmp/limit-order-market-contention-baseline`(`dev` 커밋 `453c609c`) 워킹트리에서 실행
- Testcontainers(MySQL·Redis), 로컬 macOS, 2026-08-24 02:19~02:22 KST.

## 원본 로그

```
# 적용 후 (054, 벌크 락)
[MARKET-CONTENTION-BENCHMARK] rep=0 accountPoolSize=15 chunkTotalMillis=222 marketOrderMillis=243
[MARKET-CONTENTION-BENCHMARK] rep=1 accountPoolSize=15 chunkTotalMillis=104 marketOrderMillis=17
[MARKET-CONTENTION-BENCHMARK] rep=2 accountPoolSize=15 chunkTotalMillis=111 marketOrderMillis=19
[MARKET-CONTENTION-BENCHMARK] rep=3 accountPoolSize=15 chunkTotalMillis=111 marketOrderMillis=18
[MARKET-CONTENTION-BENCHMARK] rep=4 accountPoolSize=15 chunkTotalMillis=83 marketOrderMillis=15
[MARKET-CONTENTION-BENCHMARK-SUMMARY] medianChunkTotalMillis=111 medianMarketOrderMillis=18

# 적용 전 (dev, 개별 순차 락)
[MARKET-CONTENTION-BENCHMARK] rep=0 accountPoolSize=15 chunkTotalMillis=195 marketOrderMillis=38
[MARKET-CONTENTION-BENCHMARK] rep=1 accountPoolSize=15 chunkTotalMillis=151 marketOrderMillis=15
[MARKET-CONTENTION-BENCHMARK] rep=2 accountPoolSize=15 chunkTotalMillis=124 marketOrderMillis=14
[MARKET-CONTENTION-BENCHMARK] rep=3 accountPoolSize=15 chunkTotalMillis=104 marketOrderMillis=13
[MARKET-CONTENTION-BENCHMARK] rep=4 accountPoolSize=15 chunkTotalMillis=96 marketOrderMillis=11
[MARKET-CONTENTION-BENCHMARK-SUMMARY] medianChunkTotalMillis=124 medianMarketOrderMillis=14
```

## 참고

이 결과를 만든 `LimitOrderFillMarketOrderContentionBenchmarkTest`는 이 커밋에 그대로 남겨둔다 —
`LimitOrderFillBulkLockAccountPoolBenchmarkTest`와 같은 이유로, 청크 크기·계좌 락 전략을 다시 건드릴 때
회귀를 감시할 수 있는 재현 가능한 벤치마크로 유지하는 편이 낫다고 판단했다. `LimitOrderFillBatchMarket
OrderDeadlockIntegrationTest`(데드락 유무만 검증)와는 목적이 달라 — 이 테스트는 시장가 주문의 실제 소요
시간을 측정한다 — 별도로 유지한다. CI 게이트에 포함되지 않고 성능 임계값을 assert하지 않으므로 유지
비용은 낮다.
