// 목록·프롬프트에 실을 기사를 상한만큼 고르는 절단 규칙 — 정렬과 "공시 우선"을 한 곳에 모은다. Spring 빈이 아니다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 규칙의 정본은 spec §뉴스 매칭 범위다(2026-08-04 확정). <b>정렬은 발행시각 내림차순, 동률은 {@code id}
 * 내림차순이고, 상한을 넘으면 공시를 먼저 채운 뒤 남은 자리를 뉴스 최신순으로 채운다.</b>
 *
 * <p><b>공시를 먼저 채우는 이유</b> — 공시는 {@code rcept_dt}만 있어 {@code published_at}이 그 날짜
 * {@code 00:00:00}이다(§C-3·§C-8). 같은 목록의 뉴스는 전부 {@code D-1 15:30} 이후이므로 공시는 내림차순
 * 목록에서 <b>예외 없이 최하위</b>이고, 상한을 넘는 순간 확률이 아니라 순서상 구조로 <b>항상 먼저 잘린다.</b>
 * 그대로 두면 노출 게이트 ⑧(상한·정렬)과 ⑫({@code D-1} 접수 공시가 브리핑·{@code PRE_MARKET} 요약·
 * {@code items} 셋 모두에 나온다)이 서로 모순된다. 특히 브리핑은 전 종목 합산 단일 목록이라 종목당 2건만
 * 쌓여도 상한을 넘어 <b>공시가 사실상 상시 전멸한다.</b>
 *
 * <p><b>정렬과 절단은 별개다.</b> 절단으로 살아남은 항목을 실을 때의 순서는 위 정렬 그대로이며, 공시가 목록
 * 아래쪽에 오는 것은 계약대로다 — "공시 우선"은 <b>누구를 남기느냐</b>의 규칙이지 순서의 규칙이 아니다.
 *
 * <p><b>세 자리가 같은 규칙을 쓴다</b> — Part C {@code items}·Part D {@code items}·요약과 브리핑의 LLM 입력.
 * 상한 값만 다르다(§C-7의 {@code max-items-*}). 그래서 호출부마다 다시 쓰지 않고 여기 하나로 둔다.
 *
 * <p><b>이 규칙을 "불필요한 복잡도"로 보고 되돌리지 않는다.</b> 되돌려도 테스트는 초록일 수 있다 —
 * 픽스처의 기사 수가 상한 아래이면 절단 자체가 일어나지 않아 규칙의 유무를 구분하지 못한다. 검증하려면
 * <b>픽스처를 상한 위로</b> 잡아야 한다.
 */
public final class NewsItemTruncator {

	// id가 null인 것은 아직 저장되지 않은 엔티티뿐이다. 실제 경로에서는 전부 조회 결과라 값이 있지만,
	// 여기서 NPE로 배치를 죽이는 것보다 순서만 뒤로 미는 편이 안전하다(reversed() 뒤에는 마지막으로 간다).
	private static final Comparator<MarketNewsItem> NEWEST_FIRST = Comparator
		.comparing(MarketNewsItem::getPublishedAt)
		.thenComparing(MarketNewsItem::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
		.reversed();

	private NewsItemTruncator() {}

	/**
	 * 상한만큼 고른 뒤 §뉴스 매칭 범위의 정렬로 돌려준다.
	 *
	 * @param limit §C-7의 {@code max-items-*} 중 그 자리에 해당하는 값. 1 이상인 것은
	 *     {@code FeedbackNewsProperties}가 기동 시점에 보장한다
	 * @return 발행시각 내림차순 + {@code id} 내림차순. 상한 이하이면 전부, 넘으면 공시를 먼저 채운 결과
	 */
	public static List<MarketNewsItem> truncateAndSort(List<MarketNewsItem> candidates, int limit) {
		List<MarketNewsItem> ordered = new ArrayList<>(candidates);
		ordered.sort(NEWEST_FIRST);
		if (ordered.size() <= limit) {
			return List.copyOf(ordered);
		}

		// 이미 정렬된 목록에서 걸러 내므로 두 부분목록도 각각 정렬 상태를 유지한다.
		List<MarketNewsItem> selected = new ArrayList<>(pick(ordered, MarketNewsItemType.DISCLOSURE, limit));
		selected.addAll(pick(ordered, MarketNewsItemType.NEWS, limit - selected.size()));
		selected.sort(NEWEST_FIRST);
		return List.copyOf(selected);
	}

	// 공시만으로 상한을 넘는 날도 있다 — 그때는 공시끼리도 최신순으로 잘리고 뉴스 자리가 0이 된다.
	private static List<MarketNewsItem> pick(List<MarketNewsItem> ordered, MarketNewsItemType type, int count) {
		return ordered.stream().filter(item -> item.getType() == type).limit(count).toList();
	}
}
