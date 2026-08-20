# 지정가 체결 계좌 락 경합 및 holdings INSERT 데드락 실측 결과

> 실행 기준: `src/test/java/com/finplay/api/order/LimitOrderFillAccountLockContentionIntegrationTest.java`
> (`fix/513-holdings-insert-deadlock` 브랜치). `LimitOrderFillService.fillIfPending`을 실행기(ADR-0024·
> 0025)를 거치지 않고 직접 동시 호출해 락 경합만 격리해서 측정한다. 동시성 8(=`order.limit-fill-executor.
> partition-count` 기본값)로 5회 반복하고 중앙값을 사용한다.

## 시나리오

- **contended**: 계좌 1개에 서로 다른 종목 8개의 PENDING 지정가 매수(모두 그 계좌의 첫 매수)를 걸어두고
  8건을 동시에 체결한다 — 서로 다른 파티션(종목)이 같은 계좌 row를 동시에 잠그려는 최악의 경우를
  재현한다.
- **baseline**: 서로 다른 계좌 8개에 각각 종목 1개씩 PENDING 지정가 매수(각자 첫 매수)를 걸어두고 8건을
  동시에 체결한다 — 계좌 row는 겹치지 않는 기준선이다.

## 결과

| 시나리오 | 소요시간 median(raw) | 데드락 발생(5회 합산, 분모 40건) |
|---|---|---|
| contended (계좌 1개, 종목 8개 동시) | 45ms ([80, 50, 45, 41, 38]) | 0건 |
| baseline (계좌 8개, 종목 각 1개 동시) | 13ms ([21, 18, 13, 13, 12]) | **32건 (80%)** |

## 해석

**원래 확인하려던 계좌 락 경합은 사실이지만 위협적이지 않다.** contended가 baseline보다 3배 이상
느리지만(45ms vs 13ms), 데드락 없이 순서대로 대기하는 정상적인 지연이다. 8건 처리에 45ms면 절대값으로
문제 삼을 수준이 아니다.

**baseline에서 예상치 못한 데드락이 났고, 이게 더 심각하다.** 계좌가 겹치지 않는데도 40건 중 32건이
`insert into holdings` 실행 중 MySQL 데드락(`Deadlock found when trying to get lock; try restarting
transaction`)으로 실패했다. 원인은 `holdings.uk_holdings_account_instrument` 유니크 제약(account_id,
instrument_id)에 여러 트랜잭션이 동시에 INSERT를 시도하면, 이 앱이 격리수준을 낮추지 않아 기본값으로
쓰는 MySQL REPEATABLE READ 아래에서 실제 값이 겹치지 않아도 InnoDB 갭 락 경합으로 데드락이 날 수 있다는
잘 알려진 동작이다. 상세 원인·재현·해결 방향은 [이슈 #513](https://github.com/finplay-team/finplay-backend/issues/513)에
정리했다.

**아이러니하게도 지금 걱정하던 계좌 락이 이 데드락을 우연히 막고 있었다.** contended 시나리오는 계좌
락이 8건을 강제로 직렬화하기 때문에 holdings INSERT가 동시에 부딪힐 일이 없어 데드락이 0건이었다.
즉 계좌 락 자체를 없애거나 줄이는 방향(예: 이슈 #501의 벌크 락 최적화)을 검토할 때는, 이 계좌 락이
holdings INSERT 데드락을 부수적으로 막아주고 있었다는 사실을 함께 고려해야 한다.

## 원본 로그(수정 전)

```
계좌 락 경합 실측 — contended(계좌 1개·종목 8개 동시 체결) median=45ms raw=[80, 50, 45, 41, 38], deadlock=0, 기타실패=0
계좌 락 경합 실측 — baseline(계좌 8개·종목 각 1개 동시 체결) median=13ms raw=[21, 18, 13, 13, 12], deadlock=32, 기타실패=0
```

## 수정 후 재측정 (ADR-0028)

`OrderExecutionService.execute`·`LimitOrderFillService.fillIfPending`/`fillBatch`의 격리수준을 READ
COMMITTED로 좁혀 적용한 뒤(구체적인 결정은 [ADR-0028](../../ai/adr/0028-holdings-insert-deadlock-mitigation.md)
참고) 같은 조건(동시성 8, 5회 반복)으로 재측정했다.

| 시나리오 | 소요시간 median(raw) | 데드락 발생(5회 합산, 분모 40건) |
|---|---|---|
| contended (계좌 1개, 종목 8개 동시) | 68ms ([74, 64, 68, 269, 65]) | 0건 |
| baseline (계좌 8개, 종목 각 1개 동시) | 27ms ([27, 32, 37, 19, 17]) | **0건 (수정 전 32건)** |

baseline의 데드락이 32건에서 0건으로 사라졌다. 소요시간은 두 시나리오 모두 수정 전보다 다소 늘었는데
(contended 45ms→68ms, baseline 13ms→27ms), READ COMMITTED가 REPEATABLE READ보다 더 가볍다는 통념과
달리 이 케이스에서는 격리수준 자체의 오버헤드보다 갭 락 회피 경로가 다른 락 대기 방식을 타면서 생긴
차이로 보이며, 절대값 자체는 여전히 문제 삼을 수준이 아니다.

이 조건을 벗어난 동시성 30·1,000에서도 데드락 0건을 확인했으나(각각 90건, 2,000건 시도), 이 확인은
`CONCURRENCY` 상수를 임시로 바꿔 로컬에서만 실행하고 커밋하지 않았다 — 커밋된 테스트는 위 표의
동시성 8 조건 그대로다.

## 원본 로그(수정 후, 동시성 8)

```
계좌 락 경합 실측 — contended(계좌 1개·종목 8개 동시 체결) median=68ms raw=[74, 64, 68, 269, 65], deadlock=0, 기타실패=0
계좌 락 경합 실측 — baseline(계좌 8개·종목 각 1개 동시 체결) median=27ms raw=[27, 32, 37, 19, 17], deadlock=0, 기타실패=0
```
