// 외부 호출 없이 항상 빈 목록을 돌려주는 로컬·테스트용 DisclosureCollector 구현.
package com.finplay.api.feedback.collector;

import com.finplay.api.market.domain.Instrument;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code @Profile("!prod")}로 로컬·테스트에서만 등록된다 ({@code FakeNewsCollector}와 같은 기준). 이 빈이 있어서
 * <b>DART 키가 없어도 기동과 {@code ./gradlew build}가 통과한다</b> (ADR-0011, spec §실패 처리).
 *
 * <p><b>가짜 공시를 지어내지 않는다.</b> 빈 목록이 정상 동작이며 근거 0건이면 카드를 만들지 않는다(FEED-003).
 * 여기서 그럴듯한 더미 공시를 반환하면 로컬 화면에 <b>실제로 접수되지 않은 공시</b>가 근거로 붙는다.
 */
@Slf4j
@Component
@Profile("!prod")
public class FakeDisclosureCollector implements DisclosureCollector {

	@Override
	public List<CollectedNewsDto> collect(Instrument instrument, LocalDate collectionDate) {
		log.debug("[FakeDisclosureCollector] 공시 수집 건너뜀 (외부 호출 안 함) symbol={}", instrument.getSymbol());
		return List.of();
	}
}
