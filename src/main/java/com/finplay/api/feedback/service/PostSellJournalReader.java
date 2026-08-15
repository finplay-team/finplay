// 매도 회고 프롬프트에 실을 투자일기를 모아 정렬·절단하고 지문을 계산하는 읽기 전용 컴포넌트.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackJournalProperties;
import com.finplay.api.journal.service.JournalContentDto;
import com.finplay.api.journal.service.JournalService;
import com.finplay.api.portfolio.service.AllocatedBuyTradeDto;
import com.finplay.api.portfolio.service.SellAllocationQueryService;
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

/**
 * 규칙의 정본은 spec §FEED-013 결정 3·4이고 신설 근거는 §C-6이다(4차). {@code PostSellFeedbackService}가 조회마다
 * 한 번 불러 프롬프트 재료와 재생성 판정용 지문을 함께 얻는다.
 *
 * <p><b>{@code journal}·{@code portfolio} 서비스만 주입한다</b>(ADR-0002·{@code docs/conventions.md}).
 * {@code BuyTradeJournalRepository}·{@code TradeAllocationRepository}가 여기 들어오면 이 클래스를 둔 이유가
 * 사라진다 — 다른 도메인의 테이블을 {@code feedback}이 직접 보게 된다.
 *
 * <p><b>소유권을 다시 검증하지 않고 회원 id를 받지도 않는다</b>(§C-6). 호출부가 {@code getOwnedTrade}로 이미
 * 확인한 매도 체결의 id만 넘기고, 매수 체결 id는 그 체결의 배분에서 나온 값이라 같은 회원의 것임이 구조적으로
 * 보장된다.
 *
 * <p><b>읽기만 한다.</b> {@code buy_trade_journals}·{@code sell_trade_journals}에 쓰지 않으며 특히
 * {@code updated_at}을 건드리지 않는다 — 건드리면 지문이 스스로 바뀌어 재생성이 무한히 열린다.
 *
 * <p><b>트랜잭션을 갖지 않는다.</b> 경계는 두 협력 서비스가 각자 {@code readOnly}로 갖는다
 * ({@link PostSellFeedbackReader}와 같은 형태다). 여기에 {@code @Transactional}을 붙이면 호출부의 LLM 호출이
 * 그 트랜잭션 안으로 들어올 수 있다.
 */
@Component
@RequiredArgsConstructor
class PostSellJournalReader {

	private static final String DIGEST_ALGORITHM = "SHA-256";

	private static final String BUY_JOURNAL_KIND = "BUY";

	private static final String SELL_JOURNAL_KIND = "SELL";

	// 지문 입력의 시각 표기. 자릿수를 고정하는 것은 LocalDateTime.toString()이 뒷자리 0을 생략해
	// 10:00:00.000000과 10:00이 같은 값의 다른 표기가 되기 때문이다 — 값이 같으면 문자열도 같아야 한다.
	// 컬럼이 DATETIME(6)이라 마이크로초까지 적는다.
	private static final DateTimeFormatter FINGERPRINT_TIME = DateTimeFormatter
		.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

	private static final String FINGERPRINT_FIELD_SEPARATOR = ":";

	private static final String FINGERPRINT_ENTRY_SEPARATOR = "\n";

	private final JournalService journalService;

	private final SellAllocationQueryService sellAllocationQueryService;

	private final FeedbackJournalProperties properties;

	/**
	 * 매도 체결 1건의 프롬프트용 일기 묶음과 지문을 읽는다.
	 *
	 * <p>순서는 매도 회고 1건 조회 → 배분된 매수 체결 id 조회(매수 시각 오름차순) → 매수 회고 일괄 조회 → 그 id
	 * 순서대로 정렬 → 상한 적용 → 본문 절단 → 지문 계산이다.
	 *
	 * @param sellTradeId 매도 체결 id. 소유권 검증은 호출부가 이미 끝냈다고 본다
	 * @return 일기가 하나도 없으면 {@link JournalDigestDto#empty()} — 예외가 아니라 정상 상태다
	 */
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

	/**
	 * 배분된 매수 체결의 회고를 매수 시각 오름차순으로 고른다.
	 *
	 * <p><b>상한({@code max-buy-journals})은 "일기가 실제로 있는 것"에만 적용한다</b>(결정 4). 매수 체결 id를 먼저
	 * 자르면 lot 4건 중 앞 3건에 일기가 없을 때 실을 수 있는 일기가 <b>0건</b>이 된다 — 상한이 막으려던 것은
	 * 프롬프트 길이이지 lot 수가 아니다.
	 *
	 * <p>정렬 기준은 <b>매수 시각</b>이지 일기의 {@code createdAt}이 아니다. 배분 조회가 이미 그 순서로 id를
	 * 주므로 일괄 조회 결과를 그 순서에 되꽂는다 — 일괄 조회는 순서를 보장하지 않는다.
	 *
	 * @param buyTradeIds 배분 조회가 준 매수 체결 id, <b>매수 시각 오름차순</b>({@code LinkedHashMap}의 키라 그
	 *     순서가 유지된다)
	 */
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
				if (selected.size() == properties.maxBuyJournals()) {
					break;
				}
			}
		}
		return selected;
	}

	/**
	 * 배분된 매수 체결의 체결시각을 <b>매수 시각 오름차순</b>으로 담는다 — 순서와 시각을 한 자료구조가 함께
	 * 들고 있어야 둘이 어긋나지 않는다. 키 순회 순서가 곧 프롬프트에 실릴 순서다.
	 */
	private Map<Long, LocalDateTime> buyAtByTradeId(Long sellTradeId) {
		Map<Long, LocalDateTime> buyAt = new LinkedHashMap<>();
		for (AllocatedBuyTradeDto allocated : sellAllocationQueryService.getAllocatedBuyTrades(sellTradeId)) {
			buyAt.put(allocated.buyTradeId(), allocated.executedAt());
		}
		return buyAt;
	}

	/**
	 * 본문을 {@code max-journal-chars}자에서 자른다. 원본 상한이 5000자라 절단이 없으면 프롬프트가 기사 목록보다
	 * 커진다(결정 4).
	 *
	 * <p><b>잘렸다는 표시를 붙이지 않는다</b> — spec이 절단만 정했고, 표시를 붙이면 그 문자열이 프롬프트에 그대로
	 * 실려 모델이 회고의 일부로 읽는다. 절단은 지문에 영향을 주지 않는다(지문은 본문이 아니라 {@code updated_at}을
	 * 해싱한다).
	 */
	private String truncate(String content) {
		int limit = properties.maxJournalChars();
		return content.length() <= limit ? content : content.substring(0, limit);
	}

	/**
	 * 실린 일기의 {@code (종류, 체결 ID, updated_at)}을 정렬해 이어 붙인 문자열의 SHA-256 hex다(결정 3).
	 *
	 * <p><b>본문을 해싱하지 않는다.</b> 5000자 × N건을 매 조회마다 해싱할 이유가 없고, {@code updated_at}이 수정
	 * 때마다 갱신되므로(JOUR-002·004) 본문 변경을 그대로 따라온다. <b>절단 전 원본을 기준으로 삼는 문제도 함께
	 * 사라진다.</b>
	 *
	 * <p><b>상한에 걸려 빠진 일기는 넣지 않는다</b> — 인자로 이미 선별된 목록만 받는 것이 그 보장이다. 넣으면 그
	 * 일기를 고칠 때마다 <b>출력이 달라지지 않는데 재생성만 일어나</b> 카운터를 태운다.
	 *
	 * <p><b>같은 입력이면 JVM·실행 회차와 무관하게 같은 값이 나온다.</b> {@code Set}의 순회 순서나
	 * {@code hashCode}에 기대지 않고 문자열 자연 정렬만 쓴다.
	 */
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

	// SHA-256은 모든 JRE가 제공하도록 표준이 요구하는 알고리즘이라 여기 걸리면 런타임이 깨진 것이다
	// (auth/crypto/Sha256BcryptPasswordEncoder와 같은 처리다).
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
