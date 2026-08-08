// 주식·코인 종목의 캔들(1m·1d·1w·1M) 조회 요청을 검증하고 시장에 따라 StockPriceProvider·CryptoCandleProvider에 위임하는 서비스
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.CandleResponse;
import com.finplay.api.market.repository.InstrumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandleQueryService {

	private final InstrumentRepository instrumentRepository;
	private final StockPriceProvider stockPriceProvider;
	private final CryptoCandleProvider cryptoCandleProvider;

	@Transactional(readOnly = true)
	public List<CandleResponse> getCandles(Long instrumentId, String interval, LocalDateTime from, LocalDateTime to) {
		// 이슈 #143(013): 파싱한 interval을 더 이상 버리지 않고 provider까지 그대로 전달한다.
		CandleInterval candleInterval = CandleInterval.from(interval);

		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		// 코인(MKT-008, 이슈 #20): sourceTradingDate 개념이 없는 실제 달력 시각의 봉이므로 from>to 판정도
		// 날짜를 포함한 전체 시각으로 비교한다 — interval(1m/1d/1w/1M)에 관계없이 동일하다(spec "공통 계약").
		if (instrument.getMarket() == Market.CRYPTO) {
			if (from != null && to != null && from.isAfter(to)) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
			return cryptoCandleProvider.getCandles(instrument.getSymbol(), candleInterval, from, to).stream()
				.map(CandleResponse::from)
				.toList();
		}

		if (candleInterval.isAggregated()) {
			// 주식 집계(1d·1w·1M, 이슈 #143): 집계 봉은 여러 거래일에 걸치므로 from·to의 날짜 성분만 비교한다
			// (spec "공통 계약" — 1m과 다른 이유: 1m은 항상 재생 중인 단일 거래일 안에서만 조회되지만 집계 봉은 아니다).
			if (from != null && to != null && from.toLocalDate().isAfter(to.toLocalDate())) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		} else {
			// 리뷰 확정(PR #87 QA FAIL, 옵션 c): 주식 1분봉은 항상 재생 중인 단일 거래일(source_trading_date) 안에서만
			// 조회되므로 from·to의 날짜 성분은 무시하고 시각(LocalTime)만 쓴다. from>to 판정도 같은 기준(시각)으로
			// 통일한다 — 그렇지 않으면 날짜 기준 판정과 실제 조회에 쓰이는 시각 기준 필터링이 서로 어긋나 검증을
			// 통과한 요청이 조용히 빈 배열을 반환하거나, 시각상 유효한 요청이 날짜 때문에 400으로 거부되는 문제가 있었다.
			if (from != null && to != null && from.toLocalTime().isAfter(to.toLocalTime())) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		}

		return stockPriceProvider.getCandles(instrumentId, candleInterval, from, to).stream()
			.map(CandleResponse::from)
			.toList();
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
