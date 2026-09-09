package com.finplay.api.domain.feedback.collector;

import com.finplay.api.domain.market.entity.Instrument;
import java.time.LocalDate;
import java.util.List;

public interface DisclosureCollector {

	List<CollectedNewsDto> collect(Instrument instrument, LocalDate collectionDate);
}
