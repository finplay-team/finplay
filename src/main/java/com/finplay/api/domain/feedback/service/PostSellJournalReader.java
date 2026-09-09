package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackJournalProperties;
import com.finplay.api.domain.journal.service.JournalContentDto;
import com.finplay.api.domain.journal.service.JournalService;
import com.finplay.api.domain.portfolio.service.AllocatedBuyTradeDto;
import com.finplay.api.domain.portfolio.service.SellAllocationQueryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PostSellJournalReader {

	private static final String DIGEST_ALGORITHM = "SHA-256";

	private static final String BUY_JOURNAL_KIND = "BUY";

	private static final String SELL_JOURNAL_KIND = "SELL";

	private static final DateTimeFormatter FINGERPRINT_TIME = DateTimeFormatter
		.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

	private static final String FINGERPRINT_FIELD_SEPARATOR = ":";

	private static final String FINGERPRINT_ENTRY_SEPARATOR = "\n";

	private final JournalService journalService;

	private final SellAllocationQueryService sellAllocationQueryService;

	private final FeedbackJournalProperties properties;

	JournalDigestDto read(Long sellTradeId) {
		Optional<JournalContentDto> sellJournal = journalService.findSellJournalContent(sellTradeId);
		Map<Long, LocalDateTime> buyAtByTradeId = buyAtByTradeId(sellTradeId);
		List<JournalContentDto> buyJournals = selectBuyJournals(buyAtByTradeId.keySet());
		if (sellJournal.isEmpty() && buyJournals.isEmpty()) {
			return JournalDigestDto.empty();
		}

		List<JournalDigestDto.BuyJournalLine> buyLines = buyJournals.stream()
			.map(journal -> new JournalDigestDto.BuyJournalLine(journal.tradeId(),
				buyAtByTradeId.get(journal.tradeId()), truncate(journal.content())))
			.toList();
		String sellContent = sellJournal.map(journal -> truncate(journal.content())).orElse(null);

		return new JournalDigestDto(sellContent, buyLines, fingerprintOf(sellJournal.orElse(null), buyJournals));
	}

	private List<JournalContentDto> selectBuyJournals(Collection<Long> buyTradeIds) {
		Map<Long, JournalContentDto> byTradeId = new LinkedHashMap<>();
		for (JournalContentDto journal : journalService.findBuyJournalContents(buyTradeIds)) {
			byTradeId.put(journal.tradeId(), journal);
		}

		List<JournalContentDto> selected = new ArrayList<>();
		for (Long buyTradeId : buyTradeIds) {
			JournalContentDto journal = byTradeId.get(buyTradeId);
			if (journal != null) {
				selected.add(journal);
				if (selected.size() >= properties.maxBuyJournals()) {
					break;
				}
			}
		}
		return selected;
	}

	private Map<Long, LocalDateTime> buyAtByTradeId(Long sellTradeId) {
		Map<Long, LocalDateTime> buyAt = new LinkedHashMap<>();
		for (AllocatedBuyTradeDto allocated : sellAllocationQueryService.getAllocatedBuyTrades(sellTradeId)) {
			buyAt.put(allocated.buyTradeId(), allocated.executedAt());
		}
		return buyAt;
	}

	private String truncate(String content) {
		String folded = content.replaceAll("\\s*\\R\\s*", " ");
		int limit = properties.maxJournalChars();
		return folded.length() <= limit ? folded : folded.substring(0, limit);
	}

	private String fingerprintOf(JournalContentDto sellJournal, List<JournalContentDto> buyJournals) {
		List<String> entries = new ArrayList<>();
		if (sellJournal != null) {
			entries.add(fingerprintEntry(SELL_JOURNAL_KIND, sellJournal));
		}
		for (JournalContentDto journal : buyJournals) {
			entries.add(fingerprintEntry(BUY_JOURNAL_KIND, journal));
		}
		entries.sort(null);

		return sha256Hex(String.join(FINGERPRINT_ENTRY_SEPARATOR, entries));
	}

	private String fingerprintEntry(String kind, JournalContentDto journal) {
		return kind + FINGERPRINT_FIELD_SEPARATOR + journal.tradeId() + FINGERPRINT_FIELD_SEPARATOR
			+ FINGERPRINT_TIME.format(journal.updatedAt());
	}

	private String sha256Hex(String source) {
		try {
			byte[] digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
				.digest(source.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("투자일기 지문 SHA-256 계산에 실패했습니다.", unavailable);
		}
	}
}
