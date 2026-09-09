package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import org.springframework.stereotype.Component;

@Component
public class NewsSearchQueryBuilder {

	private static final String QUERY_DELIMITER = " ";

	public String build(Instrument instrument) {
		if (instrument.getMarket() == Market.CRYPTO) {
			return instrument.getName() + QUERY_DELIMITER + instrument.getSymbol();
		}
		return instrument.getName();
	}
}
