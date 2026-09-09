package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDate;

public record TutorialPriceGenerationInput(
	short generatorVersion,
	long priceSeed,
	Long instrumentId,
	long runNumber,
	Market market,
	LocalDate tutorialDate) {
}
