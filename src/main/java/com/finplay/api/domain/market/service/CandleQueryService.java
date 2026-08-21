// 주식·코인 종목의 캔들(1m·1d·1w·1M) 조회 요청을 검증하고 시장에 따라 StockPriceProvider·CryptoCandleProvider에 위임하는 서비스
package com.finplay.api.domain.market.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.dto.response.CandleResponse;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandleQueryService {

	// 정상 페이지의 봉 개수 — provider들이 이미 이 상한을 지키고 있으므로 여기서는 개수만 읽는다(plan §6-4).
	private static final int PAGE_SIZE = 200;

	private final InstrumentRepository instrumentRepository;
	private final StockPriceProvider stockPriceProvider;
	private final CryptoCandleProvider cryptoCandleProvider;

	@Transactional(readOnly = true)
	public CandleListResponse getCandles(
		Long instrumentId, String interval, LocalDateTime from, LocalDateTime to, String cursor) {
		// ① interval 파싱 — 커서가 유효해도 이 판정이 먼저다(CANDLE-PAGE-010, plan §7).
		CandleInterval candleInterval = CandleInterval.from(interval);

		// ② 종목 존재 확인
		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		// ③ 시장 판정
		boolean isCrypto = instrument.getMarket() == Market.CRYPTO;
		boolean isStockOneMinute = !isCrypto && candleInterval == CandleInterval.ONE_MINUTE;

		// ④ 커서 파싱(형식 오류는 400) — 주식 1m에서도 형식 검증은 그대로 한다(무시되는 것은 효과일 뿐이다).
		LocalDateTime parsedCursor = CandleCursor.parse(cursor);

		// ⑤ 커서 적용 여부 — 주식 1m은 제외한다(plan §10, CANDLE-PAGE-025·026).
		boolean cursorApplies = parsedCursor != null && !isStockOneMinute;

		// ⑥ 상한 확정 — 적용되면 원래 to는 값·검증 모두에서 버리고 cursor-1분으로 덮어쓴다(§5 D-1).
		LocalDateTime effectiveTo = to;
		boolean lowerBoundReversed = false;

		// 코인(MKT-008, 이슈 #20): sourceTradingDate 개념이 없는 실제 달력 시각의 봉이므로 from>to 판정도
		// 날짜를 포함한 전체 시각으로 비교한다 — interval(1m/1d/1w/1M)에 관계없이 동일하다(spec "공통 계약").
		if (isCrypto) {
			if (cursorApplies) {
				effectiveTo = parsedCursor.minusMinutes(1);
				lowerBoundReversed = from != null && from.isAfter(effectiveTo);
			} else if (from != null && to != null && from.isAfter(to)) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		} else if (candleInterval.isAggregated()) {
			// 주식 집계(1d·1w·1M, 이슈 #143): 집계 봉은 여러 거래일에 걸치므로 from·to의 날짜 성분만 비교한다
			// (spec "공통 계약" — 1m과 다른 이유: 1m은 항상 재생 중인 단일 거래일 안에서만 조회되지만 집계 봉은 아니다).
			if (cursorApplies) {
				effectiveTo = parsedCursor.minusMinutes(1);
				lowerBoundReversed = from != null && from.toLocalDate().isAfter(effectiveTo.toLocalDate());
			} else if (from != null && to != null && from.toLocalDate().isAfter(to.toLocalDate())) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		} else {
			// 리뷰 확정(PR #87 QA FAIL, 옵션 c): 주식 1분봉은 항상 재생 중인 단일 거래일(source_trading_date) 안에서만
			// 조회되므로 from·to의 날짜 성분은 무시하고 시각(LocalTime)만 쓴다. from>to 판정도 같은 기준(시각)으로
			// 통일한다 — 그렇지 않으면 날짜 기준 판정과 실제 조회에 쓰이는 시각 기준 필터링이 서로 어긋나 검증을
			// 통과한 요청이 조용히 빈 배열을 반환하거나, 시각상 유효한 요청이 날짜 때문에 400으로 거부되는 문제가 있었다.
			// 주식 1m은 cursorApplies가 항상 false이므로 원래 to·검증 로직이 그대로 유지된다(CANDLE-PAGE-025).
			if (from != null && to != null && from.toLocalTime().isAfter(to.toLocalTime())) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		}

		// ⑦ D-1: 커서 적용 중 하한이 역전되면 provider를 부르지 않고 빈 봉투를 즉시 반환한다(plan §5).
		if (cursorApplies && lowerBoundReversed) {
			return CandleListResponse.of(List.of(), null, false);
		}

		// ⑧ provider 호출 (기존 그대로 — 5개 인자짜리 시그니처로 바꾸지 않는다, plan §6-7)
		List<CandleResponse> content;
		if (isCrypto) {
			content = cryptoCandleProvider.getCandles(instrument.getSymbol(), candleInterval, from, effectiveTo)
				.stream()
				.map(CandleResponse::from)
				.toList();
		} else {
			content = stockPriceProvider.getCandles(instrumentId, candleInterval, from, effectiveTo).stream()
				.map(CandleResponse::from)
				.toList();
		}

		// ⑨ 봉투 조립 — 주식 1m은 200개여도 hasNext를 강제로 false로 고정한다(CANDLE-PAGE-026).
		boolean hasNext = !isStockOneMinute && content.size() == PAGE_SIZE;
		String nextCursor = hasNext ? CandleCursor.encode(content.get(0).sourceTime()) : null;

		return CandleListResponse.of(content, nextCursor, hasNext);
	}

	/**
	 * 코인 캔들을 <b>도메인 값 그대로</b> 돌려준다 — 매도 회고(spec 012 §FEED-012)가 쓴다.
	 *
	 * <p><b>{@link #getCandles}와 따로 있는 이유는 반환 타입이다.</b> 그쪽은 화면용 {@code CandleResponse}로 옮겨
	 * 담는데, {@code feedback}은 {@code close}로 극값·반사실을 계산해야 하므로 응답 DTO를 거칠 이유가 없다.
	 * <b>{@code feedback}이 {@code CryptoCandleProvider}를 직접 주입하지 않는 것이 요점이다</b>(§C-6 — market은
	 * 전부 서비스를 경유한다). 주식 쪽 대응물은 {@code StockReplayService.getFullDayCandles}다.
	 *
	 * <p><b>노출 게이트가 없다.</b> 코인은 실시간이라 재생 스포일러가 성립하지 않으므로 요청한 구간을 그대로
	 * 준다 — 어디까지 잘라 쓸지는 호출부 책임이다.
	 *
	 * <p><b>공급자의 200봉 상한이 그대로 적용된다</b>({@code 013-candle-interval}). 구간이 그보다 넓으면 조용히
	 * {@code to} 기준 최신 200개로 잘리므로, <b>호출부가 구간 길이를 먼저 판정해야 한다</b> — 잘린 목록으로 극값을
	 * 구하면 예외 없이 "보유 구간 앞부분을 안 본" 값이 나간다.
	 */
	public List<CryptoCandleDto> getCryptoCandles(
		String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		return cryptoCandleProvider.getCandles(symbol, interval, from, to);
	}
}
