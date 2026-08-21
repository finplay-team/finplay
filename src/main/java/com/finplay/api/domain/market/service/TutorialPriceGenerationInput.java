// attempt 실행 세대의 결정적 튜토리얼 가격 생성에 필요한 불변 입력
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
