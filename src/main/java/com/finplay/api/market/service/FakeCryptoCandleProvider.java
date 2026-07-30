// 실제 빗썸 REST 연결 없이 심볼별 코인 1분봉을 미리 채워 반환하거나 장애를 시뮬레이션하는 테스트·로컬용 CryptoCandleProvider 구현
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & !crypto-real")
public class FakeCryptoCandleProvider implements CryptoCandleProvider {

	private final Map<String, List<CryptoCandleDto>> candlesBySymbol = new HashMap<>();

	private volatile boolean failing;

	@Override
	public List<CryptoCandleDto> getCandles(String symbol, LocalDateTime from, LocalDateTime to) {
		if (failing) {
			throw new BusinessException(ErrorCode.MARKET_DATA_PROVIDER_ERROR);
		}
		List<CryptoCandleDto> candles = candlesBySymbol.getOrDefault(symbol, List.of());
		return candles.stream()
			.filter(candle -> from == null || !candle.sourceTime().isBefore(from))
			.filter(candle -> to == null || !candle.sourceTime().isAfter(to))
			.toList();
	}

	// 테스트 전용: 심볼의 캔들 목록(시각 오름차순, 진행 중 분봉 포함 여부는 호출자가 결정)을 미리 채운다.
	public void setCandles(String symbol, List<CryptoCandleDto> candles) {
		candlesBySymbol.put(symbol, new ArrayList<>(candles));
	}

	// 테스트 전용: 이후 조회를 MARKET_DATA_PROVIDER_ERROR로 실패시켜 빗썸 REST 장애를 시뮬레이션한다.
	public void simulateFailure() {
		failing = true;
	}

	// 테스트 전용: 장애 시뮬레이션을 해제한다.
	public void reset() {
		failing = false;
	}
}
