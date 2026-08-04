// 제목에 같은 시장의 다른 종목명이 든 기사를 걸러내는 순수 계산 — 접두 관계 종목쌍의 교차 오염을 막는다.
package com.finplay.api.feedback.service;

import com.finplay.api.market.domain.Instrument;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 규칙의 정본은 spec §FEED-001이다 — <b>제목에 같은 시장의 다른 {@code instruments.name}이 포함된 기사는
 * 제외한다.</b> {@code 이더리움}·{@code 비트코인} 질의가 {@code 이더리움클래식}·{@code 비트코인캐시} 기사를
 * 함께 끌어오기 때문이다.
 *
 * <p><b>판정은 같은 시장 안에서만 한다.</b> 시장을 섞으면 종목명이 겹치는 순간 정상 기사가 사라진다. 그래서
 * 대상 종목과 <b>같은 시장의</b> 종목명 목록만 받는다.
 *
 * <p><b>자기 종목명 안에 든 다른 종목명은 세지 않는다.</b> 이 구분이 이 클래스의 전부다. 접두 관계라
 * 단순 포함 검사로는 양방향 중 한쪽이 반드시 틀린다.
 *
 * <ul>
 *   <li>{@code 비트코인} 수집 중 제목 {@code "비트코인캐시 급등"} → {@code 비트코인캐시}가 자기 이름
 *       {@code 비트코인}보다 <b>길게 뻗어</b> 있으므로 다른 종목 기사다. <b>제외한다.</b></li>
 *   <li>{@code 비트코인캐시} 수집 중 같은 제목 → 거기 보이는 {@code 비트코인}은 자기 이름
 *       {@code 비트코인캐시} <b>안에</b> 통째로 들어 있다. 자기 기사다. <b>남긴다.</b></li>
 * </ul>
 *
 * <p>그래서 다른 종목명의 <b>등장 구간</b>을 자기 이름의 등장 구간과 대조한다. 자기 이름 등장 구간에 완전히
 * 덮이지 않은 등장이 하나라도 있으면 제외다. 자기 이름을 제목에서 지우고 검사하는 방식은 첫 번째 예를
 * 놓친다 — {@code 비트코인}을 지우면 남는 {@code "캐시 급등"}에는 {@code 비트코인캐시}가 없다.
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
	 * @param instrument 수집 대상 종목
	 * @param sameMarketNames 대상과 <b>같은 시장</b> 종목명 목록. 대상 자신의 이름이 들어 있어도 되며 그 이름은
	 *     비교에서 제외된다 — 호출부가 목록에서 대상을 빼는 손질을 하지 않아도 결과가 같다
	 * @param title 기사 제목
	 * @return 다른 종목명이 독립적으로 등장하지 않으면 {@code true}
	 */
	public boolean isRelevant(Instrument instrument, List<String> sameMarketNames, String title) {
		String selfName = instrument.getName();
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
