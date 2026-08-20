// market.crypto.* 설정값(가격 스냅샷 크론·보관 기간)을 바인딩하는 프로퍼티 record — CryptoPriceSnapshotService가 사용한다.
package com.finplay.api.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 ai/specs/012-ai-feedback/spec.md §C-1(크론)·§코인 가격 스냅샷(보관 기간)이다. feedback.batch.*와 같은
// 방침 — yml과 @DefaultValue 양쪽에 값을 두고 드리프트 테스트로 대조한다(§C-7).
//
// zone은 여기 없다. @Scheduled 선언부가 zone = "Asia/Seoul"을 직접 붙인다(§C-1).
//
// sigmaLookbackHours는 이 스케줄의 가지치기 창(recordSnapshot의 retention)에만 쓴다. §코인 가격 스냅샷이 "보관
// sigma-lookback-hours를 넘은 원소는 기록할 때마다 제거"라고 정했고, feedback.crypto.sigma-lookback-hours(조회
// 창)와 반드시 같은 값이어야 한다 — 두 곳이 어긋나면 조회 창과 보관 창이 갈려 표본이 조용히 달라진다. 이 값을
// 바꿀 때는 feedback.crypto.sigma-lookback-hours도 함께 바꾼다.
@ConfigurationProperties(prefix = "market.crypto")
public record MarketCryptoProperties(
	// 코인 가격 스냅샷 기록 (매 분 정각).
	@DefaultValue("0 * * * * *")
	String priceSnapshotCron,
	// σ 계산에 쓰는 조회 창이자 스냅샷 보관 기간. feedback.crypto.sigma-lookback-hours와 같은 값을 유지한다.
	@DefaultValue("24")
	int sigmaLookbackHours) {

	// FeedbackCryptoProperties.sigmaLookbackHours와 대칭으로 막는다 — 여기서 막지 않으면 두 값이 함께
	// 잘못 설정됐을 때(예: 둘 다 0) 드리프트 테스트는 통과한 채 스냅샷 보관창이 사실상 사라진다.
	public MarketCryptoProperties {
		if (sigmaLookbackHours < 1) {
			// 0이면 가지치기 창이 사라져 기록 직후 스냅샷이 스스로 제거된다.
			throw new IllegalArgumentException("market.crypto.sigma-lookback-hours는 1 이상이어야 합니다.");
		}
	}
}
