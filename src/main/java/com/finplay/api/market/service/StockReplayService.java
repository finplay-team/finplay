// 재생세션(StockReplaySession)과 Clock을 조합해 주식 시장 개장 상태·현재가를 계산하는 읽기 전용 서비스
package com.finplay.api.market.service;

import com.finplay.api.market.domain.PreparationStatus;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockReplayService {

	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime FIRST_CANDLE_END_TIME = LocalTime.of(9, 1);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);

	// 집계 캔들(1d·1w·1M)의 from 생략 시 조회 하한과 결과 상한(spec 공통 계약 — 200개 캡, 이슈 #143)
	private static final int MAX_AGGREGATED_CANDLES = 200;
	private static final long LOOKBACK_FLOOR_DAYS = 400;
	private static final long LOOKBACK_FLOOR_WEEKS = 200;
	private static final long LOOKBACK_FLOOR_MONTHS = 200;

	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final StockCandleRepository stockCandleRepository;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;

	@Transactional(readOnly = true)
	public StockMarketStatus getMarketStatus() {
		LocalDateTime now = LocalDateTime.now(clock);
		boolean sessionReady = findReadySession(now.toLocalDate()).isPresent();
		return computeMarketStatus(sessionReady, now);
	}

	@Transactional(readOnly = true)
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return getCurrentPrices(List.of(instrumentId)).get(0);
	}

	// 계좌(=market) 단위로 여러 종목을 평가할 때 종목과 무관한 전역 상태(현재시각·재생세션·시장상태)를 요청당 1회만 계산하고,
	// 종목별로 실제로 달라지는 분봉 조회(findRevealedCandle)만 반복한다 (PR #97 리뷰 권장사항 — HoldingValuationService 등이
	// 보유 종목 N개를 평가할 때 이 전역 상태를 종목 수만큼 중복 조회하던 낭비를 없앤다). 반환 순서는 instrumentIds와 일치한다.
	//
	// marketStatus == CLOSED일 때만 폴백을 시도한다(QUOTE-HOLD-004 — 장중에는 어떤 경우에도 폴백하지 않는다. 특정 종목의
	// 분봉 결손은 수집 장애이며 옛 값으로 가리지 않는다). 폴백 대상은 오늘 세션이 아니라 서비스 날짜가 오늘보다 이전인
	// 마지막 READY 세션이다(QUOTE-HOLD-003) — 오늘 세션을 폴백에 쓰면 아직 재생되지 않은 오후가 개장 전에 새어 나간다.
	@Transactional(readOnly = true)
	public List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds) {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		Optional<StockReplaySession> readySession = findReadySession(today);
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		if (marketStatus == StockMarketStatus.OPEN) {
			// OPEN이면 오늘 세션이 반드시 준비되어 있다(computeMarketStatus 계약) — 폴백을 아예 시도하지 않는다.
			return buildTodaySessionPrices(instrumentIds, marketStatus, readySession.orElseThrow(), now.toLocalTime());
		}
		return buildClosedMarketPrices(instrumentIds, marketStatus, readySession, today, now.toLocalTime());
	}

	// marketStatus == OPEN 전용 — 오늘 재생세션 기준으로만 시세를 만든다(이 spec 도입 전 getCurrentPrices 본문과 동일).
	private List<StockReplayPriceDto> buildTodaySessionPrices(
		List<Long> instrumentIds, StockMarketStatus marketStatus, StockReplaySession replaySession, LocalTime nowTime) {
		LocalDate sourceTradingDate = replaySession.getSourceTradingDate();
		boolean isFirstCandleWindow = isWithinFirstCandleWindow(nowTime);
		return instrumentIds.stream().map(instrumentId -> {
			Optional<StockCandle> revealedCandle = findRevealedCandle(instrumentId, sourceTradingDate, nowTime);
			if (revealedCandle.isEmpty()) {
				return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, null, null, replaySession);
			}
			StockCandle candle = revealedCandle.get();
			var price = isFirstCandleWindow ? candle.getOpen() : candle.getClose();
			LocalDateTime sourceTime = LocalDateTime.of(sourceTradingDate, candle.getCandleTime());
			return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, price, sourceTime, replaySession);
		}).toList();
	}

	// marketStatus == CLOSED 전용(QUOTE-HOLD-001·003·005) — 오늘 세션 기준으로 공개할 분봉이 없는 종목만 폴백 후보다.
	// 오늘 세션이 아예 없거나(행 없음·FAILED·PREPARING) READY 세션이라도 공개된 분봉이 없는 경우 모두 여기로 들어온다.
	private List<StockReplayPriceDto> buildClosedMarketPrices(
		List<Long> instrumentIds, StockMarketStatus marketStatus, Optional<StockReplaySession> readySession,
		LocalDate today, LocalTime nowTime) {
		StockReplaySession todaySession = readySession.orElse(null);
		LocalDate todaySourceTradingDate = readySession.map(StockReplaySession::getSourceTradingDate).orElse(null);
		boolean isFirstCandleWindow = isWithinFirstCandleWindow(nowTime);

		List<Optional<StockCandle>> todayCandles = readySession.isEmpty()
			? instrumentIds.stream().<Optional<StockCandle>>map(id -> Optional.empty()).toList()
			: instrumentIds.stream().map(id -> findRevealedCandle(id, todaySourceTradingDate, nowTime)).toList();

		// 폴백 세션 조회는 요청당 최대 1회 — 폴백이 필요한 종목이 하나도 없으면(오늘 세션 기준으로 전부 정상 공개)
		// 아예 조회하지 않는다(plan.md "구성요소 변경" §2).
		boolean anyFallbackNeeded = todayCandles.stream().anyMatch(Optional::isEmpty);
		Optional<StockReplaySession> fallbackSession = anyFallbackNeeded
			? findFallbackSession(today)
			: Optional.empty();

		List<StockReplayPriceDto> results = new ArrayList<>(instrumentIds.size());
		for (int i = 0; i < instrumentIds.size(); i++) {
			Optional<StockCandle> todayCandle = todayCandles.get(i);
			if (todayCandle.isPresent()) {
				StockCandle candle = todayCandle.get();
				var price = isFirstCandleWindow ? candle.getOpen() : candle.getClose();
				LocalDateTime sourceTime = LocalDateTime.of(todaySourceTradingDate, candle.getCandleTime());
				results.add(new StockReplayPriceDto(
					true, marketStatus, todaySourceTradingDate, price, sourceTime, todaySession));
				continue;
			}
			Long instrumentId = instrumentIds.get(i);
			results.add(fallbackSession
				.flatMap(session -> buildFallbackPrice(instrumentId, marketStatus, session))
				.orElseGet(() -> new StockReplayPriceDto(
					readySession.isPresent(), marketStatus, todaySourceTradingDate, null, null, todaySession)));
		}
		return results;
	}

	// 폴백 세션의 원본 거래일 마지막 분봉으로 시세를 만든다(QUOTE-HOLD-001·005). sessionReady=false·replaySession=null을
	// 유지해 체결 경로에 구조적으로 도달하지 못하게 한다 — PriceQueryService.getOrderExecutionPrice가 marketStatus==CLOSED와
	// replaySession==null을 가격을 보기 전에 검사한다(plan.md "폴백 시세 DTO의 형태"). 그 거래일에 분봉이 하나도 없으면
	// (보관 정리 등) 폴백이 성립하지 않아 Optional.empty()를 반환하고, 호출부가 기존 UNAVAILABLE로 대체한다.
	private Optional<StockReplayPriceDto> buildFallbackPrice(
		Long instrumentId, StockMarketStatus marketStatus, StockReplaySession fallbackSession) {
		LocalDate fallbackTradingDate = fallbackSession.getSourceTradingDate();
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(instrumentId, fallbackTradingDate)
			.map(candle -> new StockReplayPriceDto(
				false, marketStatus, fallbackTradingDate, candle.getClose(),
				LocalDateTime.of(fallbackTradingDate, candle.getCandleTime()), null));
	}

	// 폴백 대상 세션 조회(QUOTE-HOLD-003·006) — 서비스 날짜가 오늘보다 이전인 READY 세션 중 가장 최근 것. 날짜 상한을
	// 두지 않는다. buildClosedMarketPrices가 실제로 필요할 때만(anyFallbackNeeded) 호출해 요청당 최대 1회로 제한한다.
	private Optional<StockReplaySession> findFallbackSession(LocalDate today) {
		return stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(today, PreparationStatus.READY);
	}

	// 캔들 API — 아직 마감하지 않은 분봉은 절대 응답에 포함하지 않는다. 09:00~09:00:59(첫 분봉 구간)은 그 첫 분봉조차 아직
	// 마감 전이므로 빈 배열을 반환한다 — 가격 API(getCurrentPrice)가 같은 구간에서 첫 분봉의 시가를 예외적으로 노출하는 것과는
	// 다른 계약이다(리뷰 확정: PRD·spec.md의 "아직 마감하지 않은 분봉은 노출하지 않는다"를 캔들 목록에는 예외 없이 적용).
	// 재생세션이 준비되지 않았거나 공개된 분봉이 없으면 예외 없이 빈 목록을 반환한다(가격 API의 PRICE_UNAVAILABLE과 다른 계약).
	//
	// marketStatus == CLOSED이고 오늘 세션 기준 결과가 비어 있으면(세션 없음·컷오프 없음(09:01 이전)·범위 역전·조회 결과 없음
	// 중 하나) 폴백 세션의 원본 거래일 하루치를 반환한다(QUOTE-HOLD-002). OPEN에는 어떤 경우에도 폴백하지 않는다
	// (QUOTE-HOLD-004) — getCurrentPrices와 같은 판정 순서다(63행 주석 참고). from·to의 LocalTime 성분은 폴백 조회에도
	// 그대로 적용한다(요청이 준 시간 범위는 폴백 거래일에도 유효하다).
	@Transactional(readOnly = true)
	public List<StockCandleDto> getRevealedCandles(Long instrumentId, LocalDateTime from, LocalDateTime to) {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		Optional<StockReplaySession> readySession = findReadySession(today);
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		LocalTime rangeStart = from != null ? from.toLocalTime() : LocalTime.MIN;
		LocalTime requestedEnd = to != null ? to.toLocalTime() : LocalTime.MAX;

		if (readySession.isPresent()) {
			LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
			Optional<LocalTime> cutoff = resolveRevealCutoff(now.toLocalTime());
			if (cutoff.isPresent()) {
				LocalTime rangeEnd = requestedEnd.isBefore(cutoff.get()) ? requestedEnd : cutoff.get();
				if (!rangeStart.isAfter(rangeEnd)) {
					List<StockCandleDto> todayCandles = queryRevealedCandles(instrumentId, sourceTradingDate,
						rangeStart, rangeEnd);
					if (!todayCandles.isEmpty() || marketStatus != StockMarketStatus.CLOSED) {
						return todayCandles;
					}
				}
			}
		}

		if (marketStatus != StockMarketStatus.CLOSED) {
			return List.of();
		}
		return findFallbackSession(today)
			.map(
				session -> queryRevealedCandles(instrumentId, session.getSourceTradingDate(), rangeStart, requestedEnd))
			.orElse(List.of());
	}

	private List<StockCandleDto> queryRevealedCandles(
		Long instrumentId, LocalDate tradingDate, LocalTime rangeStart, LocalTime rangeEnd) {
		return stockCandleRepository
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				instrumentId, tradingDate, rangeStart, rangeEnd)
			.stream()
			.map(StockCandleDto::from)
			.toList();
	}

	// 집계 캔들(1d·1w·1M) API — 공개 상한(reveal bound)을 지키며 1분봉을 조회해 StockCandleAggregator로 묶는다(이슈 #143).
	// getRevealedCandles(1m 경로)는 이 메서드가 손대지 않는다. 재생세션이 없으면 어떤 interval이든 빈 목록이다
	// (spec.md "공개 상한" — 재생 준비 전 거래일이 일봉으로 미리 새어 나가는 것을 막는다).
	//
	// marketStatus == CLOSED이고 오늘 세션 기준 결과가 비어 있으면 폴백 세션의 원본 거래일로 다시 집계한다(QUOTE-HOLD-002).
	// 폴백일 때는 그 거래일의 유효 컷오프를 LocalTime.MAX로 둬 하루치 전부를 집계 입력에 넣는다 — 이미 재생이 끝난 과거
	// 거래일이므로 컷오프로 가릴 이유가 없다(plan.md). OPEN에는 폴백하지 않는다(QUOTE-HOLD-004).
	@Transactional(readOnly = true)
	public List<StockCandleDto> getRevealedAggregatedCandles(
		Long instrumentId, CandleInterval interval, LocalDate fromDate, LocalDate toDate) {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		Optional<StockReplaySession> readySession = findReadySession(today);
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		if (readySession.isPresent()) {
			LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
			List<StockCandleDto> todayResult = buildAggregatedCandles(
				instrumentId, interval, fromDate, toDate, sourceTradingDate, resolveRevealCutoff(now.toLocalTime()));
			if (!todayResult.isEmpty() || marketStatus != StockMarketStatus.CLOSED) {
				return todayResult;
			}
		}

		if (marketStatus != StockMarketStatus.CLOSED) {
			return List.of();
		}
		return findFallbackSession(today)
			.map(session -> buildAggregatedCandles(
				instrumentId, interval, fromDate, toDate, session.getSourceTradingDate(), Optional.of(LocalTime.MAX)))
			.orElse(List.of());
	}

	// getRevealedAggregatedCandles 실행부 — 기준 거래일(sourceTradingDate)과 그날의 유효 컷오프를 매개변수로 받는다.
	// 오늘 세션 경로는 resolveRevealCutoff(현재 시각)를, 폴백 경로는 Optional.of(LocalTime.MAX)를 넘겨 같은 로직을
	// 재사용한다(200개 캡·선두 partial 버킷 필터·narrowRangeStart 포함, plan.md "getRevealedAggregatedCandles" 절).
	private List<StockCandleDto> buildAggregatedCandles(
		Long instrumentId, CandleInterval interval, LocalDate fromDate, LocalDate toDate,
		LocalDate sourceTradingDate, Optional<LocalTime> sourceTradingDateCutoff) {
		LocalDate requestedEnd = toDate != null ? toDate : sourceTradingDate;
		// 재생거래일을 절대 넘지 않는다 — "방어 규칙"이 아니라 실제로 도달 가능한 경로다. PRD MKT-005의 08:40 폴백
		// (직전 영업일 데이터가 아직 없으면 그 전 영업일로 폴백) 때문에, 08:10 수집이 거래일 D를 넣었지만 08:40 세션은
		// D-1로 확정되는 날이 생길 수 있다. 그런 날엔 D의 분봉이 DB에 이미 있어도 미공개이므로, to가 D 이후를
		// 가리켜도 이 클램프가 유일한 방어선이다(PR #151 리뷰 반영).
		LocalDate rangeEnd = requestedEnd.isBefore(sourceTradingDate) ? requestedEnd : sourceTradingDate;
		LocalDate rangeStart = fromDate != null ? fromDate : lookbackFloor(interval, rangeEnd);
		if (rangeStart.isAfter(rangeEnd)) {
			return List.of();
		}

		// 조회를 2회로 분리한다 — 미공개 분봉을 애초에 메모리에 올리지 않기 위해서다(한 번에 읽고 나중에 걸러내면
		// 필터를 빠뜨렸을 때 그대로 유출된다). (a) 과거 거래일(재생거래일 전부 공개)과 (b) 재생거래일 당일(컷오프까지만
		// 공개)을 별도 쿼리로 조회해 이어 붙인다 — 둘 다 tradingDate 오름차순이 유지되므로 (a) 다음 (b) 순서가 곧 전체
		// 오름차순이다.
		List<StockCandleDto> minuteCandles = new ArrayList<>();

		LocalDate sourceTradingDateMinusOne = sourceTradingDate.minusDays(1);
		LocalDate pastEnd = rangeEnd.isBefore(sourceTradingDateMinusOne) ? rangeEnd : sourceTradingDateMinusOne;
		if (!rangeStart.isAfter(pastEnd)) {
			LocalDate narrowedRangeStart = narrowRangeStart(instrumentId, interval, rangeStart, pastEnd);
			minuteCandles.addAll(
				stockCandleRepository
					.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
						instrumentId, narrowedRangeStart, pastEnd)
					.stream()
					.map(StockCandleDto::from)
					.toList());
		}

		boolean sourceTradingDateInRange = !rangeStart.isAfter(sourceTradingDate)
			&& !sourceTradingDate.isAfter(rangeEnd);
		if (sourceTradingDateInRange && sourceTradingDateCutoff.isPresent()) {
			minuteCandles.addAll(
				stockCandleRepository
					.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
						instrumentId, sourceTradingDate, LocalTime.MIN, sourceTradingDateCutoff.get())
					.stream()
					.map(StockCandleDto::from)
					.toList());
		}

		// 버킷 경계(월요일·1일)와 rangeStart가 정확히 일치하지 않으면, rangeStart보다 이른 시작일을 가진 "선두 partial
		// 버킷"(예: interval=1w, from=수요일이면 그 주 월요일 라벨의 버킷에 수~금 분봉만 모임)이 섞여 나갈 수 있다.
		// docs/api-contracts.md 계약은 "버킷 시작일이 [from의 날짜, to의 날짜] 안에 있으면 포함"이므로, 시작일이
		// rangeStart보다 이른 버킷은 반쪽짜리인 채로 완전한 캔들처럼 보이게 되어 제외해야 한다(PR #151 리뷰 차단 반영).
		// 200개 캡보다 먼저 걸러야 캡이 실제로 응답에 남을 버킷 수를 기준으로 동작한다.
		List<StockCandleDto> aggregated = StockCandleAggregator.aggregate(minuteCandles, interval).stream()
			.filter(candle -> !candle.tradingDate().isBefore(rangeStart))
			.toList();
		if (aggregated.size() <= MAX_AGGREGATED_CANDLES) {
			return aggregated;
		}
		return aggregated.subList(aggregated.size() - MAX_AGGREGATED_CANDLES, aggregated.size());
	}

	/**
	 * 현재 서비스 날짜의 재생세션이 준비됐는지와 그 원본 거래일을 함께 돌려준다 (spec 012 §C-6).
	 *
	 * <p>{@code getMarketStatus()}는 개장·폐장만 주고 원본 거래일을 알려 주지 않으며 {@code findReadySession}은
	 * private이라, 재생 중인 거래일 기준으로 콘텐츠를 만드는 호출부에는 조회 경로가 없었다. <b>노출 게이트를
	 * 우회하지 않는다</b> — 준비되지 않았으면 {@code ready=false}이고 거래일은 {@code null}이다.
	 */
	@Transactional(readOnly = true)
	public StockReplaySessionDto getCurrentReplaySession() {
		return findReadySession(LocalDate.now(clock))
			.map(session -> new StockReplaySessionDto(true, session.getSourceTradingDate()))
			.orElseGet(() -> new StockReplaySessionDto(false, null));
	}

	/**
	 * 서비스 날짜를 그날 재생한 원본 거래일로 바꾼다 — <b>과거 서비스 날짜도 조회할 수 있다</b> (spec 012 §C-6).
	 *
	 * <p>지난 체결·카드를 그때 재생 중이던 거래일 기준으로 다시 읽어야 하는 경로가 쓴다. {@code READY}로 확정된
	 * 세션만 본다 — {@code PREPARING}·{@code FAILED}의 {@code source_trading_date}는 후보 값이거나 준비하다
	 * 실패한 날짜라, 실제로 재생하지 않은 거래일을 그날의 정답처럼 돌려주게 된다.
	 *
	 * @return 그 서비스 날짜에 확정된 원본 거래일. 세션이 없거나 {@code READY}가 아니면 {@code Optional.empty()}
	 */
	@Transactional(readOnly = true)
	public Optional<LocalDate> getSourceTradingDate(LocalDate serviceDate) {
		return findReadySession(serviceDate).map(StockReplaySession::getSourceTradingDate);
	}

	/**
	 * 그 거래일의 <b>하루치 분봉 전부</b>를 돌려준다 — 현재 재생 시각과 무관하다.
	 *
	 * <p><b>재생 노출 게이트를 우회한다</b> (spec 012 §C-6). 개장 전 배치에서 호출하는 것이 기본이고, 조회 경로
	 * 에서는 §C-5의 게이트를 통과한 뒤에만 부른다 — <b>판정은 호출부 책임이며 이 메서드는 아무것도 감추지
	 * 않는다.</b> 사용자 응답에 그대로 실으면 그날 오후가 오전에 통째로 새어 나간다.
	 *
	 * <p>기존 {@code getRevealedCandles}로 대체할 수 없다. 그쪽은 {@code resolveRevealCutoff}로 현재 재생
	 * 시각까지만 주므로 08:45 배치가 부르면 <b>예외 없이 항상 빈 목록</b>이고, 그 상태는 정상 응답이라 카드가
	 * 매일 0건이 되어도 로그조차 남지 않는다.
	 */
	@Transactional(readOnly = true)
	public List<StockCandleDto> getFullDayCandles(Long instrumentId, LocalDate tradingDate) {
		return stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrumentId, tradingDate)
			.stream()
			.map(StockCandleDto::from)
			.toList();
	}

	/**
	 * 직전 거래일 <b>마지막 분봉</b>의 종가를 돌려준다 (spec 012 §C-6). 시가 갭 판정의 기준값이다.
	 *
	 * <p><b>이 메서드도 재생 노출 게이트를 우회한다</b> — 위 {@code getFullDayCandles}와 같은 호출 조건이다.
	 * 배치에서 부르는 것이 기본이고, 조회 경로에서는 §C-5의 게이트를 통과한 뒤에만 부른다.
	 *
	 * <p>직전 거래일 날짜는 {@link BusinessDayCalendar#previousBusinessDay}로 구한다 — 시장 단위 {@code D-1}을
	 * 얻는 유일한 경로다(§C-6). <b>"그 종목의 가장 최근 분봉"으로 대체하지 않는다</b> — 수집이 며칠 빈 종목에서
	 * 일주일 전 종가를 직전 종가로 읽어 <b>있지도 않은 큰 갭 카드</b>를 만들어 낸다. 값이 오래된 것보다 없는 쪽이
	 * 안전하며, 직전 거래일 분봉이 없으면 갭 카드를 만들지 않는 것이 spec의 정상 동작이다(§FEED-002, 오류 아님).
	 *
	 * <p>"마지막 분봉"을 리터럴 15:30으로 찾지 않는 이유는 §C-2-1에 있다 — 15:30 분봉은 보장되지 않아
	 * 리터럴로 찾으면 갭 카드가 매일 영구히 0건이 된다.
	 *
	 * @param tradingDate 기준 원본 거래일 {@code D}. 반환은 {@code D-1}의 마지막 분봉 종가다
	 * @return 직전 거래일 마지막 분봉의 종가. 그 거래일 분봉이 없으면 {@code Optional.empty()} (오류 아님)
	 */
	@Transactional(readOnly = true)
	public Optional<BigDecimal> getPreviousTradingDayClose(Long instrumentId, LocalDate tradingDate) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(tradingDate);
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(instrumentId, previousTradingDate)
			.map(StockCandle::getClose);
	}

	// getRevealedAggregatedCandles 전용 — from 생략 시 200개 버킷을 채우고도 남는 조회 하한(spec plan.md 확정값).
	private LocalDate lookbackFloor(CandleInterval interval, LocalDate rangeEnd) {
		return switch (interval) {
			case ONE_DAY -> rangeEnd.minusDays(LOOKBACK_FLOOR_DAYS);
			case ONE_WEEK -> rangeEnd.minusWeeks(LOOKBACK_FLOOR_WEEKS);
			case ONE_MONTH -> rangeEnd.minusMonths(LOOKBACK_FLOOR_MONTHS);
			case ONE_MINUTE -> throw new IllegalArgumentException("집계 캔들 전용 메서드입니다: " + interval);
		};
	}

	// getRevealedAggregatedCandles 전용(이슈 #155) — lookbackFloor의 고정값이나 호출자가 준 rangeStart를 그대로 쓰면
	// stock_candles가 쌓일수록(보관정리 배치 MVP 제외) 응답 200개 버킷에 필요한 것보다 훨씬 많은 1분봉을 매 요청마다
	// 읽게 된다. 여기서는 가벼운 DISTINCT 거래일 조회(하루 최대 391행이 아니라 최대 1행)로 실제 200번째 버킷의
	// 시작 거래일을 역산해 rangeStart를 그 시점까지만 좁힌다. 조회된 거래일이 200버킷을 채우기에 부족하면(데이터가
	// 아직 얕거나 fetchLimit 안에서 못 채우면) 원래 rangeStart를 그대로 쓴다 — 이 메서드는 범위를 넓히지 않고 좁히기만
	// 한다. 유일한 호출부(150행)가 이미 rangeStart<=queryEnd를 보장하므로 그 전제를 여기서 다시 검사하지 않는다.
	private LocalDate narrowRangeStart(
		Long instrumentId, CandleInterval interval, LocalDate rangeStart, LocalDate queryEnd) {
		int fetchLimit = MAX_AGGREGATED_CANDLES * maxTradingDaysPerBucket(interval);
		List<LocalDate> recentTradingDates = stockCandleRepository
			.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
				instrumentId, rangeStart, queryEnd, PageRequest.of(0, fetchLimit));
		if (recentTradingDates.isEmpty()) {
			return rangeStart;
		}

		// recentTradingDates는 최신→과거 순이다. 200번째로 새로 마주치는 버킷의 "시작일"(월요일·1일 등, 그 버킷의
		// 가장 이른 거래일이 아니라 버킷 경계 자체)을 narrowedFloor로 써야 한다 — 마주친 거래일 자체(예: 주봉이면
		// 금요일)를 쓰면 그 버킷의 앞쪽 거래일(월~목)이 뒤이은 1분봉 쿼리에서 통째로 빠져, 응답의 가장 오래된
		// 버킷 하나가 반쪽 데이터로 조용히 틀린 OHLC를 갖게 된다(PR #162 리뷰 차단 1 — 실제 재현·확정).
		Set<LocalDate> bucketsSeen = new HashSet<>();
		LocalDate narrowedFloor = rangeStart;
		for (LocalDate tradingDate : recentTradingDates) {
			LocalDate bucketStart = StockCandleAggregator.resolveBucketStart(tradingDate, interval);
			narrowedFloor = bucketStart;
			bucketsSeen.add(bucketStart);
			if (bucketsSeen.size() >= MAX_AGGREGATED_CANDLES) {
				break;
			}
		}
		// 버킷 시작일은 rangeStart보다 이를 수 있다(예: interval=1w, rangeStart가 그 주의 수요일이면 버킷
		// 시작일인 월요일은 rangeStart 이전이다) — 그 경우 rangeStart 밑으로 넓히지 않는다. 이렇게 만들어지는
		// 선두 partial 버킷은 원본 rangeStart 기준의 기존 필터(182행)가 그대로 걸러낸다.
		return narrowedFloor.isAfter(rangeStart) ? narrowedFloor : rangeStart;
	}

	// narrowRangeStart 전용 — DISTINCT 거래일 조회 자체를 과도하게 넓게 하지 않기 위한 버킷당 최대 거래일 수 상한.
	// 1w=최대 5거래일이지만 공휴일 배치 여유를 감안해 7, 1M=달력상 최대 31일.
	private static int maxTradingDaysPerBucket(CandleInterval interval) {
		return switch (interval) {
			case ONE_DAY -> 1;
			case ONE_WEEK -> 7;
			case ONE_MONTH -> 31;
			case ONE_MINUTE -> throw new IllegalArgumentException("집계 캔들 전용 메서드입니다: " + interval);
		};
	}

	private Optional<StockReplaySession> findReadySession(LocalDate serviceDate) {
		return stockReplaySessionRepository
			.findByServiceDate(serviceDate)
			.filter(session -> session.getPreparationStatus() == PreparationStatus.READY);
	}

	private StockMarketStatus computeMarketStatus(boolean sessionReady, LocalDateTime now) {
		if (!sessionReady) {
			return StockMarketStatus.CLOSED;
		}
		LocalDate today = now.toLocalDate();
		LocalTime time = now.toLocalTime();
		boolean withinTradingHours = !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(MARKET_CLOSE_TIME);
		return (businessDayCalendar.isBusinessDay(today) && withinTradingHours)
			? StockMarketStatus.OPEN
			: StockMarketStatus.CLOSED;
	}

	private boolean isWithinFirstCandleWindow(LocalTime time) {
		return !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(FIRST_CANDLE_END_TIME);
	}

	// 가격 API(getCurrentPrice) 전용 — 첫 분봉 구간(09:00~09:00:59)에는 그 첫 분봉 자체(재조회 없이 asc 쿼리 결과를 그대로,
	// 쿼리 1회, 이슈 #16 확정 계약)를 반환해 상위 getCurrentPrice가 시가만 골라 노출한다. 09:01부터는 마감 완료된 마지막
	// 분봉을 반환한다. 캔들 목록 API(getRevealedCandles)는 첫 분봉 구간에서도 반드시 빈 배열이어야 하므로(아직 마감 전인
	// 분봉은 노출 금지) 이 메서드를 재사용하지 않고 별도의 resolveRevealCutoff를 쓴다 — 두 메서드의 컷오프 계약은 다르다.
	private Optional<StockCandle> findRevealedCandle(Long instrumentId, LocalDate sourceTradingDate, LocalTime now) {
		if (isWithinFirstCandleWindow(now)) {
			return stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
				instrumentId, sourceTradingDate);
		}
		LocalTime currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
		if (currentMinute.isBefore(FIRST_CANDLE_END_TIME)) {
			// 09:00 이전(개장 전)이며 첫 분봉 구간도 아니므로 아직 공개된 분봉이 없다.
			return Optional.empty();
		}
		LocalTime cutoff = currentMinute.minusMinutes(1);
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				instrumentId, sourceTradingDate, cutoff);
	}

	// 캔들 API(getRevealedCandles) 전용 컷오프 계산 — 09:01 이전(09:00~09:00:59 첫 분봉 구간과 개장 전 모두 포함)에는
	// 어떤 분봉도 마감되지 않았으므로 Optional.empty()(빈 배열)를 반환한다. 09:01부터는 마감이 완료된 마지막 분봉의
	// 시각(currentMinute - 1분)을 컷오프로 반환한다. 리뷰 확정(PR #87 차단 1): 첫 분봉이라도 마감 전에는 노출하지 않는다 —
	// findRevealedCandle과 달리 이 메서드는 DB를 조회하지 않고 시각 계산만으로 판정한다.
	private Optional<LocalTime> resolveRevealCutoff(LocalTime now) {
		LocalTime currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
		if (currentMinute.isBefore(FIRST_CANDLE_END_TIME)) {
			return Optional.empty();
		}
		return Optional.of(currentMinute.minusMinutes(1));
	}
}
