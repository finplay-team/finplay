package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PostSellPromptDto(
	String instrumentName,
	LocalDateTime buyAt,
	BigDecimal buyPrice,
	LocalDateTime sellAt,
	BigDecimal sellPrice,
	BigDecimal quantity,
	BigDecimal returnRate,
	long realizedPnl,
	BigDecimal holdHighPrice,
	LocalDateTime holdHighAt,
	BigDecimal sellVsHighRate,
	BigDecimal holdLowPrice,
	LocalDateTime holdLowAt,
	BigDecimal sellVsLowRate,
	Integer buyToNewsMinutes,
	LocalDateTime firstNewsAt,
	List<HeldPriceMoveDto> priceMoves,
	BigDecimal closePrice,
	BigDecimal sellToCloseRate,
	Integer holderCount,
	BigDecimal soldWithin30MinRate,
	Integer medianMinutesToSell,
	Integer yourMinutesToSell,
	boolean multiDayHold,
	HoldHighBasis holdHighBasis,
	List<BuyJournalLineDto> buyJournals,
	String sellJournalContent) {

	public PostSellPromptDto {
		priceMoves = List.copyOf(priceMoves);
		buyJournals = List.copyOf(buyJournals);
	}
}
