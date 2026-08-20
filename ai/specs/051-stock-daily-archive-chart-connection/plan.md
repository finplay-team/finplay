# Plan: 주식 일봉 3년 아카이브를 캔들 조회 API에 연결

## 관련 문서

- Spec: `./spec.md`
- 선행 spec: `050-stock-daily-archive`(수집·저장, PR #508)
- 이슈: #506(결정 1·2, 완료 조건 6·7), #495(1m 시간축, 이 spec의 범위 밖)

## 변경 지점 — `StockReplayService`만 건드린다

새 API·새 테이블·새 배치를 만들지 않는다. `buildAggregatedCandles`(집계 캔들의 "과거" 구간을 만드는 부분)만 바꾼다.

### 변경 전

```java
if (!rangeStart.isAfter(pastEnd)) {
    LocalDate narrowedRangeStart = narrowRangeStart(instrumentId, interval, rangeStart, pastEnd);
    minuteCandles.addAll(
        stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
            instrumentId, narrowedRangeStart, pastEnd)
            .stream().map(StockCandleDto::from).toList());
}
```

### 변경 후

`pastCandlesPreferringArchive(instrumentId, narrowedRangeStart, pastEnd)`로 대체 — 아카이브를 우선 조회하고, 아카이브가 커버 못한 날짜만 1분봉으로 채운다.

```java
private List<StockCandleDto> pastCandlesPreferringArchive(Long instrumentId, LocalDate rangeStart, LocalDate rangeEnd) {
    List<StockDailyCandle> archiveRows = stockDailyCandleRepository
        .findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(instrumentId, rangeStart, rangeEnd);
    Set<LocalDate> archiveDates = new HashSet<>();
    List<StockCandleDto> merged = new ArrayList<>();
    for (StockDailyCandle c : archiveRows) {
        archiveDates.add(c.getTradingDate());
        merged.add(toArchiveDto(c));
    }
    stockCandleRepository
        .findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(instrumentId, rangeStart, rangeEnd)
        .stream()
        .filter(candle -> !archiveDates.contains(candle.getTradingDate()))
        .map(StockCandleDto::from)
        .forEach(merged::add);
    merged.sort(Comparator.comparing(StockCandleDto::tradingDate).thenComparing(StockCandleDto::candleTime));
    return merged;
}
```

`toArchiveDto`는 `StockDailyCandle`을 `StockCandleDto(tradingDate, LocalTime.MIDNIGHT, open, high, low, close, volume)`로 변환한다 — `candleTime`은 아카이브에 없는 개념이라 자정을 쓰는데, 이건 `StockCandleAggregator`가 확정 버킷을 만들 때 이미 쓰는 값과 같아서(그 클래스의 `BucketAccumulator.toDto`) 소스가 달라져도 출력 형태가 흔들리지 않는다.

**`StockCandleAggregator`는 건드리지 않는다.** 이 클래스는 입력이 1분봉이든 하루 단위로 이미 완성된 "봉"이든 구분하지 않는다 — `tradingDate`로만 버킷을 나누고 그 버킷 안에서 open=최초/close=최종/high·low=최대·최소/volume=합계를 계산하므로, 거래일당 정확히 하나의 완성된 OHLCV를 넣으면 그 자체로 정확한 1d 버킷이 되고, 여러 날을 묶으면 1w·1M도 그대로 옳게 나온다. 새 집계 로직이 필요 없다 — 기존 것을 그대로 재사용한다.

## 결정 1(시간축) 구현 — 새 게이트를 만들지 않는다

`pastCandlesPreferringArchive`는 호출부가 이미 `pastEnd = min(rangeEnd, sourceTradingDate - 1일)`로 계산해 넘겨준 범위만 본다(기존 `buildAggregatedCandles` 로직 무변경). 즉 아카이브 쿼리 자체가 **물리적으로** `sourceTradingDate` 이후를 요청할 수 없다 — 별도의 "공개 상한 검사"를 아카이브 경로에 새로 넣지 않아도 기존 클램프가 그대로 방어선이 된다. 재생 중인 당일은 여전히 별도 쿼리(컷오프 적용, 변경 없음)로만 채워진다.

## 결정 2(이중 소스) 구현

날짜별 우선순위: 아카이브에 있으면 아카이브, 없으면 1분봉. `archiveDates`로 필터링해 같은 날짜가 두 번 들어가는 것 자체를 원천 차단한다(합치거나 평균내지 않는다).

## 부수 수정 — `narrowRangeStart`

기존 구현은 `stock_candles`의 distinct 거래일만으로 "200개 버킷에 필요한 만큼만" 조회 범위를 좁혔다. 아카이브 도입 전에는 과거 데이터의 유일한 소스가 1분봉이라 안전한 최적화였지만, 지금은 **아카이브에만 있고 1분봉엔 없는 과거 거래일**이 존재할 수 있어 이 최적화가 그 구간을 조회 자체에서 잘라버린다(실제로 통합 테스트에서 재현·확인).

고친 방식: 1분봉 distinct 거래일과 아카이브 거래일을 합쳐(`HashSet`으로 중복 제거) 최신순 정렬 후 상한만큼 자른 뒤, 기존 200-버킷 계산 루프에 그대로 넣는다. 아카이브 쿼리에 `PageRequest` 상한을 따로 두지 않았다 — 3년 최대치가 이미 수백 행이라 1분봉의 "하루 최대 391행" 문제와 규모가 다르다.

## 테스트 계획

- **통합(`CandleQueryServiceIntegrationTest`, Testcontainers)**: `aggregatedDailyIntervalPrefersArchiveOverOneMinuteAggregationPerTradingDate` 신설 — 아카이브 전용 거래일·1분봉 전용 거래일·재생거래일(아카이브에 같은 날짜의 다른 값이 있어도 무시돼야 함)을 한 응답에서 함께 검증한다.
- 기존 회귀: `aggregatedIntervalsSpanMultipleTradingDaysWhileOneMinuteRegressionHolds`, `aggregatedDailyIntervalReturnsCorrectLatestTwoHundredBucketsWhenDataSpansMoreThanTwoHundredTradingDays`, 커서 페이지네이션 테스트 3종 전부 무변경 통과 확인.
- `StockReplayServiceTest`(단위, mock) — `stockDailyCandleRepository`를 mock 처리, 언스텁 시 빈 리스트라 기존 1분봉 전용 시나리오가 그대로 유지됨을 확인.
