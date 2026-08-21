// 외부 호출 없이 항상 빈 목록을 돌려주는 로컬·테스트용 NewsCollector 구현.
package com.finplay.api.domain.feedback.collector;

import com.finplay.api.domain.market.entity.Instrument;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code @Profile("!prod & !news-real")}로 로컬·테스트에서만 등록된다 ({@code FakeCryptoCandleProvider}의
 * {@code !prod & !crypto-real} 선례). 이 빈이 있어서 <b>네이버 검색 키가 없어도 기동과
 * {@code ./gradlew build}가 통과한다</b> (ADR-0011, spec §실패 처리의 "네이버·DART 키 없음 →
 * {@code Fake*Collector}가 빈 목록 반환. 기동·테스트 정상").
 *
 * <p><b>{@code news-real}을 켠 로컬에서는 이 빈이 빠진다</b> (이슈 #273). 그 전에는 로컬에서 실제 기사를
 * 받을 방법이 아예 없어, 키를 {@code .env}에 채워 두고도 뉴스가 0건인 것을 <b>수집 결함으로 오인</b>했다 —
 * {@code EMPTY}는 계약대로의 응답이라 겉으로는 아무 신호가 없다.
 *
 * <p><b>가짜 기사를 지어내지 않는다.</b> 빈 목록이 정상 동작이며 그 뒤에 이어지는 것도 정상 경로다 —
 * 근거 0건이면 카드를 만들지 않고(FEED-003), 요약·브리핑은 {@code EMPTY}가 된다(§C-4). 여기서 그럴듯한
 * 더미 기사를 반환하면 로컬 화면에 <b>실제로 없었던 사건</b>이 근거로 붙고, 코인 더미 시세가 실제 종가로
 * 오해를 샀던 것과 같은 사고가 된다(.env.example의 코인 더미 경고).
 *
 * <p>테스트가 기사를 필요로 하면 이 클래스에 데이터를 넣지 말고 저장 경로에 직접 픽스처를 넣는다 — 그래야
 * 수집기 계약과 저장 계약이 섞이지 않는다.
 */
@Slf4j
@Component
@Profile("!prod & !news-real")
public class FakeNewsCollector implements NewsCollector {

	@Override
	public List<CollectedNewsDto> collect(Instrument instrument, List<String> sameMarketNames) {
		log.debug("[FakeNewsCollector] 뉴스 수집 건너뜀 (외부 호출 안 함) symbol={}", instrument.getSymbol());
		return List.of();
	}
}
