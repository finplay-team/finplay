// 매수 체결에 대한 투자일기 작성 유스케이스를 처리하는 서비스
package com.finplay.api.domain.journal.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.journal.dto.response.BuyJournalResponse;
import com.finplay.api.domain.journal.dto.response.BuyJournalUpdateResponse;
import com.finplay.api.domain.journal.dto.response.JournalListItemResponse;
import com.finplay.api.domain.journal.dto.response.JournalListResponse;
import com.finplay.api.domain.journal.dto.response.SellJournalResponse;
import com.finplay.api.domain.journal.dto.response.SellJournalUpdateResponse;
import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import com.finplay.api.domain.journal.entity.SellTradeJournal;
import com.finplay.api.domain.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.domain.journal.repository.SellTradeJournalRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JournalService {

	private final TradeService tradeService;
	private final AccountService accountService;
	private final BuyTradeJournalRepository buyTradeJournalRepository;
	private final SellTradeJournalRepository sellTradeJournalRepository;
	private final Clock clock;

	@Transactional
	public BuyJournalResponse createBuyJournal(Long userId, Long buyTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, buyTradeId);
		if (trade.getSide() != OrderSide.BUY) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (buyTradeJournalRepository.existsByBuyTradeId(buyTradeId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		BuyTradeJournal journal = BuyTradeJournal.of(trade, content, LocalDateTime.now(clock));
		try {
			return BuyJournalResponse.from(buyTradeJournalRepository.saveAndFlush(journal));
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	@Transactional
	public SellJournalResponse createSellJournal(Long userId, Long sellTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, sellTradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (sellTradeJournalRepository.existsBySellTradeId(sellTradeId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		SellTradeJournal journal = SellTradeJournal.of(trade, content, LocalDateTime.now(clock));
		try {
			return SellJournalResponse.from(sellTradeJournalRepository.saveAndFlush(journal));
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	@Transactional
	public SellJournalUpdateResponse updateSellJournal(Long userId, Long sellTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, sellTradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		SellTradeJournal journal = sellTradeJournalRepository
			.findBySellTradeId(sellTradeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		journal.updateContent(content, LocalDateTime.now(clock));
		return SellJournalUpdateResponse.from(journal);
	}

	@Transactional
	public BuyJournalUpdateResponse updateBuyJournal(Long userId, Long buyTradeId, String content) {
		Trade trade = tradeService.getOwnedTrade(userId, buyTradeId);
		if (trade.getSide() != OrderSide.BUY) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		BuyTradeJournal journal = buyTradeJournalRepository
			.findByBuyTradeId(buyTradeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		journal.updateContent(content, LocalDateTime.now(clock));
		return BuyJournalUpdateResponse.from(journal);
	}

	/**
	 * 매도 체결 1건의 매도 회고를 읽는다 — 없으면 {@link Optional#empty()}다 (spec 012 §C-6, 4차 §FEED-013).
	 *
	 * <p><b>일기가 없는 것을 예외로 다루지 않는다.</b> 매도 회고 서술 경로에서는 <b>일기가 없는 것이 정상 상태</b>이며,
	 * 소유권 검증({@code getOwnedTrade})도 호출하지 않는다.
	 *
	 * <p><b>회원 id를 인자로 받지 않는다</b>(§C-6). 호출부({@code PostSellFeedbackService} 경로)가
	 * {@code getOwnedTrade}로 이미 확인한 체결의 id만 넘긴다 — 여기서 회원 id를 받으면 검증하는 것처럼 보이는데
	 * 실제로는 아무것도 막지 않는 인자가 된다.
	 *
	 * <p><b>읽기만 한다.</b> {@code updated_at}을 건드리면 지문이 스스로 바뀌어 재생성이 무한히 열린다
	 * (§FEED-013 결정 3).
	 */
	@Transactional(readOnly = true)
	public Optional<JournalContentDto> findSellJournalContent(Long sellTradeId) {
		return sellTradeJournalRepository
			.findBySellTradeId(sellTradeId)
			.map(journal -> new JournalContentDto(sellTradeId, journal.getContent(), journal.getUpdatedAt()));
	}

	/**
	 * 매수 체결 id 목록으로 매수 회고를 <b>한 번에</b> 읽는다 (spec 012 §C-6, 4차 §FEED-013).
	 *
	 * <p><b>빈 목록이면 쿼리 없이 빈 결과를 돌려준다.</b> 배분된 매수 체결이 없는 경우가 정상 입력이다.
	 *
	 * <p><b>일기가 있는 체결만 담긴다.</b> 입력 id 수와 결과 수가 다른 것이 정상이며, 프롬프트에 실을 순서
	 * (매수 시각 오름차순)와 건수 상한은 호출부가 정한다(§FEED-013 결정 4).
	 *
	 * <p>회원 id를 받지 않는 근거는 {@link #findSellJournalContent}와 같다 — 매수 체결 id는 이미 소유권을 확인한
	 * 매도 체결의 배분에서 나온 값이라 같은 회원의 것임이 구조적으로 보장된다(§C-6).
	 */
	@Transactional(readOnly = true)
	public List<JournalContentDto> findBuyJournalContents(Collection<Long> buyTradeIds) {
		if (buyTradeIds.isEmpty()) {
			return List.of();
		}
		return buyTradeJournalRepository
			.findAllByBuyTradeIdIn(buyTradeIds)
			.stream()
			.map(journal -> new JournalContentDto(journal.getBuyTrade().getId(), journal.getContent(),
				journal.getUpdatedAt()))
			.toList();
	}

	@Transactional(readOnly = true)
	public JournalListResponse getMyJournalEntries(Long userId, Market market, String cursor, int limit) {
		Account account = accountService.getAccountFor(userId, market);
		JournalCursor parsedCursor = JournalCursor.parse(cursor);
		LocalDateTime cursorCreatedAt = parsedCursor == null ? null : parsedCursor.createdAt();
		Long cursorTradeId = parsedCursor == null ? null : parsedCursor.tradeId();

		List<BuyTradeJournal> buyPage = buyTradeJournalRepository.findByAccountIdWithCursor(
			account.getId(), cursorCreatedAt, cursorTradeId, limit + 1);
		List<SellTradeJournal> sellPage = sellTradeJournalRepository.findByAccountIdWithCursor(
			account.getId(), cursorCreatedAt, cursorTradeId, limit + 1);

		List<JournalListItemResponse> merged = Stream
			.concat(buyPage.stream().map(JournalListItemResponse::from),
				sellPage.stream().map(JournalListItemResponse::from))
			.sorted(Comparator.comparing(JournalListItemResponse::createdAt)
				.thenComparing(JournalService::tradeIdOf)
				.reversed())
			.limit(limit + 1)
			.toList();

		boolean hasNext = merged.size() > limit;
		List<JournalListItemResponse> content = hasNext ? merged.subList(0, limit) : merged;
		String nextCursor = hasNext
			? JournalCursor.encode(content.get(content.size() - 1).createdAt(),
				tradeIdOf(content.get(content.size() - 1)))
			: null;

		return JournalListResponse.of(content, nextCursor, hasNext);
	}

	private static Long tradeIdOf(JournalListItemResponse item) {
		return item.buyTradeId() != null ? item.buyTradeId() : item.sellTradeId();
	}
}
