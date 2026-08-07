// 기사 제목으로 그 종목의 기사인지 가리는 순수 계산 — 시장마다 규칙이 다르다 (코인은 자기 이름 필수).
package com.finplay.api.feedback.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 규칙의 정본은 spec §FEED-001이고 <b>시장마다 다르다</b> (2026-08-07 개정, 이슈 #179).
 *
 * <pre>
 * 코인  자기 이름이 독립적으로 등장하면 통과       — 다른 종목명이 함께 있어도 무관하다
 * 주식  다른 종목명이 독립적으로 등장하면 제외     — 자기 이름이 있는지는 보지 않는다 (개정 전 규칙)
 * </pre>
 *
 * <p><b>왜 코인만 바꿨나.</b> 개정 전 규칙은 자기 이름을 아예 보지 않아, 코인에서 <b>통과한 680건 중
 * 565건(83%)의 제목에 그 코인 이름이 없었다</b>(12종목 1,200건 실측). {@code 체인링크 코인} 질의에
 * {@code "두바이듀티프리 암호화폐 결제, 디르함 정산이 핵심"} 같은 기사가 근거로 붙었고,
 * {@code 비트코인캐시}·{@code 이더리움클래식}은 <b>각각</b> 필터를 통과한 51건(둘을 합쳐 102건) 중
 * 자기 이름이 든 제목이 <b>0건</b>이었다.
 * 주식은 <b>실측하지 않아 그대로 뒀다</b>(C-005) — 자세한 근거와 대가는 spec §FEED-001의 개정 문단에 있다.
 *
 * <p><b>판정은 같은 시장 안에서만 한다.</b> 시장을 섞으면 종목명이 겹치는 순간 정상 기사가 사라진다. 그래서
 * 대상 종목과 <b>같은 시장의</b> 종목명 목록만 받는다.
 *
 * <p><b>두 규칙이 같은 구간 대조를 방향만 뒤집어 쓴다.</b> 접두 관계라 단순 포함 검사로는 양방향 중 한쪽이
 * 반드시 틀리기 때문이다.
 *
 * <ul>
 *   <li>{@code 비트코인} 수집 중 제목 {@code "비트코인캐시 급등"} → {@code 비트코인캐시}가 자기 이름
 *       {@code 비트코인}보다 <b>길게 뻗어</b> 있으므로 다른 종목 기사다. <b>제외한다.</b></li>
 *   <li>{@code 비트코인캐시} 수집 중 같은 제목 → 거기 보이는 {@code 비트코인}은 자기 이름
 *       {@code 비트코인캐시} <b>안에</b> 통째로 들어 있다. 자기 기사다. <b>남긴다.</b></li>
 * </ul>
 *
 * <p>그래서 <b>등장 구간</b>끼리 대조하되, <b>어느 쪽 이름을 주어로 삼는지가 시장마다 다르다.</b>
 *
 * <ul>
 *   <li><b>코인</b> — 자기 이름의 등장 하나가 더 긴 다른 종목명에 통째로 삼켜지지 <b>않았다면</b> 통과다.
 *       모든 등장이 삼켜졌거나 자기 이름이 아예 없으면 제외한다. 다른 종목명이 함께 있는지는 보지 않는다.</li>
 *   <li><b>주식</b> — 다른 종목명의 등장이 자기 이름의 등장 구간에 완전히 <b>덮이지 않은 것</b>이 하나라도
 *       있으면 제외다. 자기 이름이 제목에 있는지는 보지 않는다.</li>
 * </ul>
 *
 * <p>자기 이름을 제목에서 지우고 검사하는 방식은 두 규칙 모두에서 첫 번째 예를 놓친다 —
 * {@code 비트코인}을 지우면 남는 {@code "캐시 급등"}에는 {@code 비트코인캐시}가 없다.
 *
 * <p><b>외부 의존이 없다.</b> 입력은 종목·같은 시장 종목명 목록·기사 제목뿐이라 고정 픽스처로 단정할 수
 * 있다 (spec §C-6). 발행일자로 거르는 일도 여기서 하지 않는다 — 수집 단계는 기사를 날짜로 걸러내지 않는다
 * (§FEED-001).
 *
 * <p>이 필터가 수집 단계의 유일한 오탐 방어선이다. 근거 0건은 카드를 막지만 오탐 근거는 아무것도 막지 않아
 * 무관 기사가 제목·URL로 노출되고 LLM 입력에도 들어간다.
 */
@Component
public class NewsTitleFilter {

	/**
	 * 이 기사를 대상 종목의 것으로 저장해도 되는지 판정한다.
	 *
	 * @param instrument 수집 대상 종목. <b>이 종목의 시장이 어느 규칙을 쓸지 가른다</b> — 코인과 주식의 판정이
	 *     서로 반대다 (클래스 javadoc의 표)
	 * @param sameMarketNames 대상과 <b>같은 시장</b> 종목명 목록. 대상 자신의 이름이 들어 있어도 되며 그 이름은
	 *     비교에서 제외된다 — 호출부가 목록에서 대상을 빼는 손질을 하지 않아도 결과가 같다
	 * @param title 기사 제목
	 * @return <b>코인</b>이면 자기 이름이 독립적으로 등장할 때 {@code true}(다른 종목명 유무는 무관),
	 *     <b>주식</b>이면 다른 종목명이 독립적으로 등장하지 않을 때 {@code true}(자기 이름 유무는 무관)
	 */
	public boolean isRelevant(Instrument instrument, List<String> sameMarketNames, String title) {
		if (instrument.getMarket() == Market.CRYPTO) {
			return appearsIndependently(title, instrument.getName(), sameMarketNames);
		}
		return noOtherNameAppears(title, instrument.getName(), sameMarketNames);
	}

	/**
	 * 코인 규칙 (2026-08-07 개정, 이슈 #179) — <b>자기 이름이 독립적으로 등장하면 통과</b>다. 다른 종목명이
	 * 함께 있어도 상관하지 않는다.
	 *
	 * <p>"독립적으로"가 접두 관계를 가른다. {@code 비트코인} 수집 중 제목 {@code "비트코인캐시 급등"}에서
	 * {@code 비트코인}의 등장은 {@code 비트코인캐시} 안에 통째로 갇혀 있으므로 <b>자기 이름을 읽은 것이
	 * 아니다.</b> 반대로 {@code 비트코인캐시} 수집 중 같은 제목에서는 자기 이름이 온전히 드러나 통과한다.
	 * 아래 {@link #noOtherNameAppears}가 쓰는 것과 <b>같은 구간 대조를 방향만 뒤집어</b> 쓴다.
	 */
	private static boolean appearsIndependently(String title, String selfName, List<String> sameMarketNames) {
		for (int selfStart : occurrenceStarts(title, selfName)) {
			if (!swallowedByLongerName(title, selfStart, selfName, sameMarketNames)) {
				return true;
			}
		}
		return false;
	}

	// 자기 이름보다 긴 종목명만 자기 등장을 삼킬 수 있다 — 같거나 짧으면 통째로 덮을 수 없다.
	private static boolean swallowedByLongerName(
		String title, int selfStart, String selfName, List<String> sameMarketNames) {
		for (String otherName : sameMarketNames) {
			if (otherName.length() <= selfName.length()) {
				continue;
			}
			for (int otherStart : occurrenceStarts(title, otherName)) {
				if (otherStart <= selfStart && selfStart + selfName.length() <= otherStart + otherName.length()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 주식 규칙 (개정 전 규칙 그대로) — <b>다른 종목명이 독립적으로 등장하지 않으면 통과</b>다. 자기 이름이
	 * 있는지는 보지 않는다.
	 *
	 * <p>코인과 갈라 둔 이유는 <b>주식을 실측하지 않았기 때문이다</b>(spec §FEED-001, C-005). 주식 시드에는
	 * 접두 관계 종목쌍이 0건이고 종목명이 일반명사와 충돌하지도 않아 현행으로 문제가 관측된 적이 없다.
	 * 주식도 같은 측정을 거치면 그때 규칙을 합칠 수 있다.
	 */
	private static boolean noOtherNameAppears(String title, String selfName, List<String> sameMarketNames) {
		List<Integer> selfStarts = occurrenceStarts(title, selfName);
		for (String otherName : sameMarketNames) {
			if (selfName.equals(otherName)) {
				continue;
			}
			for (int otherStart : occurrenceStarts(title, otherName)) {
				if (!coveredBySelfName(otherStart, otherName.length(), selfStarts, selfName.length())) {
					return false;
				}
			}
		}
		return true;
	}

	// 겹치는 등장도 놓치지 않도록 한 글자씩 밀며 찾는다.
	private static List<Integer> occurrenceStarts(String text, String keyword) {
		List<Integer> starts = new ArrayList<>();
		for (int index = text.indexOf(keyword); index >= 0; index = text.indexOf(keyword, index + 1)) {
			starts.add(index);
		}
		return starts;
	}

	// 다른 종목명의 등장 구간이 자기 이름의 등장 구간 하나에 통째로 들어가는지 본다. 들어가면 그 등장은
	// 자기 이름을 읽은 것일 뿐이라 다른 종목의 언급이 아니다.
	private static boolean coveredBySelfName(
		int otherStart, int otherLength, List<Integer> selfStarts, int selfLength) {
		for (int selfStart : selfStarts) {
			if (selfStart <= otherStart && otherStart + otherLength <= selfStart + selfLength) {
				return true;
			}
		}
		return false;
	}
}
