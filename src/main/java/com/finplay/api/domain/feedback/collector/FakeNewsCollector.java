package com.finplay.api.domain.feedback.collector;

import com.finplay.api.domain.market.entity.Instrument;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!prod & !news-real")
public class FakeNewsCollector implements NewsCollector {

	@Override
	public List<CollectedNewsDto> collect(Instrument instrument, List<String> sameMarketNames) {
		log.debug("[FakeNewsCollector] 뉴스 수집 건너뜀 (외부 호출 안 함) symbol={}", instrument.getSymbol());
		return List.of();
	}
}
