# 지정가 체결 청크 벌크 락 적용 전후 처리 시간 실측 비교

> 실행 기준: `src/test/java/com/finplay/api/domain/order/service/LimitOrderFillBulkLockAccountPoolBenchmarkTest.java`
> (`refactor/501-limit-order-fill-bulk-lock` 브랜치, 커밋 `c0064f91`). `ai/adr/0024-limit-order-fill-executor.md`가
> 원래 재현했던 조건과 같은 규모("계좌 15개 풀 조건에서 가격 하나에 지정가 500건")로, `order.limit-fill-executor.
> batch-size` 기본값(50)만큼 청크를 나눠 `LimitOrderFillService.fillBatch`를 순차 호출한 총 소요시간을 잰다.
> 목적은 [이슈 #501](https://github.com/finplay-team/finplay-backend/issues/501)(청크 내 벌크 락 최적화)이
> `docs/loadtest/limit-order-fill-lock-vs-write-phase-benchmark-result.md`가 사전에 추정한 "락 구간이 청크
> 처리 시간의 55~59%"라는 근거대로 실제 처리 시간을 줄였는지 확인하는 것이다.

## 측정 방법

- **적용 후(벌크 락)**: 현재 브랜치(`refactor/501-limit-order-fill-bulk-lock`, 커밋 `c0064f91`)에서 그대로
  실행. `fillBatch`가 order→account→holding을 청크당 3번의 벌크 `FOR UPDATE` 조회로 묶는다.
- **적용 전(개별 순차 왕복)**: `git worktree add /tmp/limit-order-fill-baseline dev`로 `dev`(커밋 `453c609c`,
  이 spec이 아직 반영되지 않은 상태)의 별도 워킹트리를 만들고, 같은 벤치마크 테스트 파일을 그 워킹트리에
  복사해 실행했다. `dev`의 `fillBatch`는 청크 안에서 주문마다 `fillOnePending`을 호출해 order→account→
  holding을 매번 개별 조회한다. 측정 후 `git worktree remove`로 정리했고, 현재 작업 브랜치의 커밋 이력·
  워킹트리는 건드리지 않았다.
- 시나리오는 두 조건에서 동일: 계좌 15개 풀, 단일 종목(`BULKLOCK`), 단일 가격(100,000), 지정가 매수 500건을
  계좌 15개에 라운드로빈으로 분배해 생성한 뒤, 500건을 50건씩 10개 청크로 나눠 `fillBatch`를 순차 호출하고
  전체 소요시간을 잰다.
- 각 조건 3회 반복 실행(JVM 재기동 포함, Testcontainers MySQL·Redis)했고 중앙값을 사용한다. CI 게이트가
  아니라 수동 실측이라 성능 임계값을 assert하지 않고 결과를 로그로만 남긴다.

## 결과 (3회 반복)

| 조건 | 총 소요시간 median(raw) | 건당 소요시간 median |
|---|---|---|
| 적용 전 (dev, 개별 순차 왕복) | 2,099ms ([2099, 2018, 2108]) | 4.20ms |
| 적용 후 (054, 벌크 락) | 1,434ms ([1421, 1434, 1601]) | 2.87ms |

**500건 처리 시간이 중앙값 기준 2,099ms → 1,434ms로 약 31.7% 줄었다.**

## 해석

이번 재현은 단일 스레드·단일 청크 순차 실행 조건이라 파티션 병렬 실행이나 계좌 락 경합은 재현하지 않는다
(그건 `docs/loadtest/holdings-insert-deadlock-result.md`·`LimitOrderFillAccountLockContentionIntegrationTest`
영역이다). 이 실측이 보여주는 것은 순수하게 "청크 하나 안에서 order→account→holding을 몇 번 왕복하는가"가
전체 처리 시간에 미치는 영향이다.

`docs/loadtest/limit-order-fill-lock-vs-write-phase-benchmark-result.md`는 청크 크기와 무관하게 락 구간이
전체 시간의 55~59%를 차지한다고 추정했다(BUY의 holding 락이 "쓰기 구간"으로 잘못 집계돼 실제 비중은 더
높을 것이라는 단서와 함께). 이번 벌크 락은 락 구간 자체를 없애는 것이 아니라 청크당 왕복 횟수를 최대
`batchSize`(50)회에서 3회(order·account·holding 벌크 조회 각 1회)로 줄이는 것이므로, 락 구간이 사라지지
않고 크게 압축된다. 실측된 약 31.7% 개선은 이 압축 효과와 방향이 일치한다 — 락 구간을 완전히 제거하지
않았으므로 55~59%에 못 미치지만, 왕복 횟수 자체가 크게 줄어드는 것을 감안하면 상당한 개선폭이다.

절대적인 총 소요시간(적용 전 2018~2108ms, 적용 후 1421~1601ms)은 실행마다 다소 흔들렸다(JVM/커넥션 풀
예열 상태에 따른 변동, 별도 웜업 단계 없이 콜드 스타트부터 측정). 상대적 개선 방향(적용 후가 매 회 더
빠름)은 3회 모두 일관됐다.

## 실행 환경

- 적용 후: `./gradlew test --tests "com.finplay.api.domain.order.service.LimitOrderFillBulkLockAccountPoolBenchmarkTest" --rerun-tasks` (커밋 `c0064f91`, 브랜치 `refactor/501-limit-order-fill-bulk-lock`)
- 적용 전: 위와 동일 명령을 `/tmp/limit-order-fill-baseline`(`dev` 커밋 `453c609c`) 워킹트리에서 실행
- Testcontainers(MySQL·Redis), 로컬 macOS, 2026-08-24 00:44~00:52 KST(3회 반복 각각).

## 원본 로그

```
# 적용 후 (054, 벌크 락) — 3회
[BULK-LOCK-BENCHMARK] accountPoolSize=15 orderCount=500 chunkSize=50 totalMillis=1421 perOrderMillis=2.84
[BULK-LOCK-BENCHMARK] accountPoolSize=15 orderCount=500 chunkSize=50 totalMillis=1434 perOrderMillis=2.87
[BULK-LOCK-BENCHMARK] accountPoolSize=15 orderCount=500 chunkSize=50 totalMillis=1601 perOrderMillis=3.20

# 적용 전 (dev, 개별 순차 왕복) — 3회
[BULK-LOCK-BENCHMARK] accountPoolSize=15 orderCount=500 chunkSize=50 totalMillis=2099 perOrderMillis=4.20
[BULK-LOCK-BENCHMARK] accountPoolSize=15 orderCount=500 chunkSize=50 totalMillis=2018 perOrderMillis=4.04
[BULK-LOCK-BENCHMARK] accountPoolSize=15 orderCount=500 chunkSize=50 totalMillis=2108 perOrderMillis=4.22
```

## 참고

이 결과를 만든 `LimitOrderFillBulkLockAccountPoolBenchmarkTest`는 이번 spec 커밋에 그대로 남겨둔다 —
`order.limit-fill-executor.batch-size`나 잠금 전략을 다시 건드릴 때 회귀를 감시할 수 있는 재현 가능한
벤치마크로 유지하는 편이 일회성 계측 코드([이전 세션에서 되돌린 `[PHASE-BENCHMARK]` 임시 로그](limit-order-fill-lock-vs-write-phase-benchmark-result.md)와
달리)보다 낫다고 판단했다. CI 게이트에 포함되지 않고 성능 임계값을 assert하지 않으므로 유지 비용은
낮다.
