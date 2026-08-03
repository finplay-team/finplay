// 인증 사용자의 거래 가능 종목 즐겨찾기 등록을 처리하는 서비스
package com.finplay.api.favorite.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.favorite.domain.Favorite;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.repository.FavoriteRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.InstrumentService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FavoriteService {

	private static final String UNIQUE_CONSTRAINT_NAME = "uk_favorites_user_instrument";

	private final FavoriteRepository favoriteRepository;
	private final InstrumentService instrumentService;
	private final UserQueryService userQueryService;
	private final Clock clock;

	@Transactional
	public FavoriteResponse createFavorite(Long userId, Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (!instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
		if (favoriteRepository.existsByUserIdAndInstrumentId(userId, instrumentId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		User user = userQueryService.getUser(userId);
		Favorite favorite = Favorite.create(user, instrument, LocalDateTime.now(clock));
		try {
			return FavoriteResponse.from(favoriteRepository.saveAndFlush(favorite));
		} catch (DataIntegrityViolationException exception) {
			if (isDuplicateFavorite(exception)) {
				throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
			}
			throw exception;
		}
	}

	private boolean isDuplicateFavorite(DataIntegrityViolationException exception) {
		String message = exception.getMostSpecificCause().getMessage();
		return message != null && message.contains(UNIQUE_CONSTRAINT_NAME);
	}
}
