package com.finplay.api.domain.portfolio.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HoldingService {

	private final AccountService accountService;
	private final HoldingRepository holdingRepository;
	private final HoldingValuationService holdingValuationService;

	@Transactional(readOnly = true)
	public List<HoldingListItemResponse> getHoldings(Long userId, Market market) {
		Account account = accountService.getAccountFor(userId, market);
		List<Holding> holdings = holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId());
		List<HoldingValuationDto> valuations = holdingValuationService.evaluateHoldings(holdings);
		return IntStream.range(0, holdings.size())
			.mapToObj(i -> HoldingListItemResponse.of(holdings.get(i), valuations.get(i)))
			.toList();
	}

	@Transactional(readOnly = true)
	public Optional<Long> findHoldingId(
		Long userId, com.finplay.api.domain.market.entity.Market market, Long instrumentId) {
		Market accountMarket = Market.valueOf(market.name());
		Account account = accountService.getAccountFor(userId, accountMarket);
		return holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrumentId).map(Holding::getId);
	}

	@Transactional(readOnly = true)
	public Optional<Holding> findHoldingForOwner(Long userId, Long holdingId) {
		return holdingRepository.findByIdFetchingInstrument(holdingId)
			.filter(holding -> holding.getAccount().getUser().getId().equals(userId));
	}
}
