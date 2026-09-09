package com.finplay.api.domain.feedback.collector;

import com.finplay.api.domain.market.entity.Instrument;
import java.util.List;

public interface NewsCollector {

	List<CollectedNewsDto> collect(Instrument instrument, List<String> sameMarketNames);
}
