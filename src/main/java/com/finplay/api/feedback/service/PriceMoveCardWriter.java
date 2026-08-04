// 카드와 그 근거 연결의 저장만을 담당하는 트랜잭션 경계 전용 컴포넌트 — LLM 호출을 경계 밖에 두기 위해 분리했다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// PriceMoveCardService는 중복 확인·근거 매칭·서술 생성(외부 LLM 호출, 건당 최대 20초)을 모두 끝낸 뒤에만 이
// 컴포넌트를 호출한다 — 저장 둘만 트랜잭션으로 감싸 외부 호출 대기 중에 DB 커넥션을 점유하지 않는다
// (market의 KisHistoricalCandleImportWriter와 같은 형태·같은 이유). 개장 전 배치는 종목 수만큼 이 호출을
// 반복하고, 그 시간대는 SSE heartbeat와 매분 가격 push가 같은 풀을 쓰는 때다.
//
// 그렇다고 트랜잭션을 아예 없애면 카드와 근거가 따로 커밋돼 근거 0건 카드가 남는데, 다음 실행은 중복 판정에
// 걸려 그 카드를 영영 고치지 않는다 (PriceMoveEventSource가 "이 행이 0건인 카드는 존재하지 않아야 한다"로
// 못박은 상태다). 그래서 경계를 없애는 것이 아니라 쓰기까지 좁힌다.
//
// 별도 클래스인 이유는 자기호출이다 — 같은 클래스의 private 메서드에 @Transactional을 붙이면 프록시를 타지
// 않아 애노테이션이 무효가 되고, 정확히 막으려던 "따로 커밋" 상태가 조용히 된다.
@Component
@RequiredArgsConstructor
class PriceMoveCardWriter {

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	/**
	 * 카드 1건과 그 근거 연결을 <b>한 트랜잭션에</b> 저장한다.
	 *
	 * @param card 아직 저장되지 않은 카드
	 * @param sources 근거 기사. 비어 있을 수 없다 — 근거 0건이면 호출부가 카드 생성 자체를 접는다(FEED-003)
	 */
	@Transactional
	PriceMoveEvent persist(PriceMoveEvent card, List<MarketNewsItem> sources) {
		PriceMoveEvent saved = priceMoveEventRepository.save(card);
		priceMoveEventSourceRepository.saveAll(
			sources.stream().map(source -> PriceMoveEventSource.of(saved, source)).toList());
		return saved;
	}
}
