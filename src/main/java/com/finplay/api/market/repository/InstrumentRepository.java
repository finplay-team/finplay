// 종목의 영속화와 시장별 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

	List<Instrument> findAllByOrderByIdAsc();

	List<Instrument> findByMarketOrderByIdAsc(Market market);

	List<Instrument> findByMarketAndTradableTrueOrderByIdAsc(Market market);

	// 샌드박스 튜토리얼 종목을 배치 수집 대상에서 제외한다 (035-stock-collector-reliability COLLECT-STAB-002)
	List<Instrument> findByMarketAndTutorialSampleFalseOrderByIdAsc(Market market);

	// 거래 가능하면서 샌드박스가 아닌 종목 — 합성 틱 발행 대상이다 (이슈 #490).
	// **위 두 조회를 그대로 쓸 수 없어서 따로 둔다.** findByMarketAndTradableTrue...는 샌드박스를 걸러내지
	// 않고(이 이슈가 고치려는 바로 그 구멍), findByMarketAndTutorialSampleFalse...는 tradable을 보지 않아
	// 거래 불가 종목까지 틱을 받게 된다. 두 조건이 동시에 필요한 곳은 여기뿐이다.
	List<Instrument> findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market market);

	// 지정가 체결 리스너의 가격 갱신 이벤트에서 심볼로 종목을 조회한다(015-limit-order LMT-002)
	Optional<Instrument> findByMarketAndSymbol(Market market, String symbol);
}
