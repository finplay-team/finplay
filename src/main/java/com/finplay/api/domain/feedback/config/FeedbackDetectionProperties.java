// feedback.detection.* 설정값(변동 구간 탐지 임계치·구간)을 바인딩하는 프로퍼티 record — PriceMoveDetector가 사용한다.
package com.finplay.api.domain.feedback.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 ai/specs/012-ai-feedback/spec.md §C-7이다. 임계치를 코드 상수로 박지 않는 것은 §튜닝이
// "실데이터로 검증한 뒤 조정한다"로 정했기 때문이며, 조정은 application.yml만 고쳐서 끝나야 한다.
//
// yml과 @DefaultValue 양쪽에 값을 두는 것은 §C-7의 확정 방침이다(feedback.llm·feedback.news가 선례다) —
// 설정 없이도 기동하는 것을 실제로 보장하는 것은 record의 @DefaultValue이고, 운영 중 값을 바꿀 때는
// 항상 이기는 yml만 고친다. 두 곳이 갈리면 드리프트 테스트가 잡는다.
//
// 이 블록은 시크릿이 아니므로 naver-search·dart처럼 "빈 기본값 플레이스홀더만" 두는 예외에 해당하지 않는다.
@ConfigurationProperties(prefix = "feedback.detection")
public record FeedbackDetectionProperties(
	// 장중 후보 판정 계수 k. score = |cum| / (σ * √W)가 이 값 이상이면 후보다 (§탐지 알고리즘(주식)).
	@DefaultValue("2.5")
	double zScoreK,
	// 누적 수익률 구간 W(분). cum[t] = ln(close(t) / close(t - W분))이다.
	@DefaultValue("5")
	int windowMinutes,
	// 피크 병합 반경(분). 이미 채택한 피크와 이 반경 안이면 버린다 — 같은 급변이 인접 분마다 후보로 잡힌다.
	@DefaultValue("5")
	int mergeWindowMinutes,
	// 종목·거래일당 장중 카드 수 상한. 점수 내림차순으로 이만큼만 채택한다.
	@DefaultValue("2")
	int maxIntradayCards,
	// 시가 갭 카드를 만드는 |gap| 하한. detectionScore = |gap| / 이 값이라 분모이기도 하다.
	@DefaultValue("0.01")
	BigDecimal openingGapThreshold) {

	// 세 값만 막는 이유는 실패 모양이 조용하거나 치명적이기 때문이다. 나머지(k·병합 반경)가 이상하면
	// 카드가 과하게 생겨 눈에 띄지만, 아래 셋은 예외도 로그도 없이 결과가 사라지거나 배치를 죽인다.
	public FeedbackDetectionProperties {
		if (windowMinutes < 1) {
			// 0이면 cum[t] = ln(close(t)/close(t)) = 0이라 점수가 전부 0이 되어 장중 카드가 매일 0건이 된다.
			throw new IllegalArgumentException("feedback.detection.window-minutes는 1 이상이어야 합니다.");
		}
		if (maxIntradayCards < 1) {
			// 채택 단계에서 상위 0건을 고르므로 후보를 아무리 찾아도 카드가 하나도 남지 않는다.
			throw new IllegalArgumentException("feedback.detection.max-intraday-cards는 1 이상이어야 합니다.");
		}
		if (openingGapThreshold == null || openingGapThreshold.signum() <= 0) {
			// detectionScore = |gap| / threshold의 분모라 0이면 갭이 있는 첫 종목에서 ArithmeticException으로
			// 배치가 죽는다. 음수면 |gap| >= threshold가 항상 참이라 갭 카드가 매일 전 종목에 생긴다.
			throw new IllegalArgumentException("feedback.detection.opening-gap-threshold는 0보다 커야 합니다.");
		}
	}
}
