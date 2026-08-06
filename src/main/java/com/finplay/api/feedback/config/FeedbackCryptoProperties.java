// feedback.crypto.* 설정값(코인 변동 감시 임계치·구간·근거창)을 바인딩하는 프로퍼티 record — CryptoPriceMoveWatcher·NewsMatcher가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/specs/012-ai-feedback/spec.md §C-7이다. FeedbackDetectionProperties·FeedbackNewsProperties와
// 같은 방침으로 yml과 @DefaultValue 양쪽에 값을 둔다 — 설정 없이도 기동하는 것을 보장하는 것은 @DefaultValue이고,
// 운영 중 값을 바꿀 때는 항상 이기는 yml만 고친다. 두 곳이 갈리면 드리프트 테스트가 잡는다.
//
// z-score-k는 이 블록에 없다 — §탐지 알고리즘(코인)의 |r5| / σ24 < k는 feedback.detection.z-score-k를 그대로
// 쓴다(§C-7 확정 방침, 주식·코인이 같은 계수를 공유). 여기서 새로 만들지 않는다.
@ConfigurationProperties(prefix = "feedback.crypto")
public record FeedbackCryptoProperties(
	// 종목당 카드 생성 후 다음 카드까지의 최소 간격(분). CryptoPriceMoveWatcher의 쿨다운 판정이 쓴다.
	@DefaultValue("30")
	int cooldownMinutes,
	// 종목·거래일(KST)당 카드 수 상한. 쿨다운만으로는 상한이 보장되지 않는다(spec §완료 조건).
	@DefaultValue("6")
	int dailyLimit,
	// σ 표본 구간 폭(분). rolling-window-minutes 간격으로 스냅샷을 자른 로그수익률 집합이 σ 표본이며,
	// 겹치지 않는 구간으로만 만든다(§탐지 알고리즘(코인)).
	@DefaultValue("5")
	int rollingWindowMinutes,
	// σ 계산 조회 창(시간). market.crypto.sigma-lookback-hours(CryptoPriceSnapshotService의 Redis 보관 기간)와
	// 항상 같은 값을 유지해야 한다 — 어긋나면 조회 창과 보관 창이 갈려 표본이 조용히 달라진다.
	@DefaultValue("24")
	int sigmaLookbackHours,
	// σ 계산 최소 표본 수. 미만이면 예외 없이 카드를 만들지 않는다(§탐지 알고리즘(코인)).
	@DefaultValue("100")
	int minSampleCount,
	// 코인 카드 근거 탐색(이전) 폭(분). 이후 방향은 0이다 — 탐지가 occurredAt 시점에 실시간으로 돌아
	// 그 이후 기사는 존재할 수 없다(§뉴스 매칭 범위).
	@DefaultValue("35")
	int matchBeforeMinutes,
	// 종목 단위 Redis 락(CryptoWatchLock)의 TTL(초). 다중 인스턴스 중복 감시 방어선이다(ADR-0014).
	@DefaultValue("30")
	int watchLockTtlSeconds) {

	// 여기 있는 것만 막는다 — 나머지(쿨다운·일일 상한)가 이상하면 카드가 과하게 생겨 눈에 띄지만, 아래 다섯은
	// 예외도 로그도 없이 카드가 조용히 사라진다(FeedbackDetectionProperties·FeedbackNewsProperties와 같은 이유).
	public FeedbackCryptoProperties {
		if (rollingWindowMinutes < 1) {
			// 0이면 겹치지 않는 구간 하나의 폭이 0이 되어 σ 표본을 하나도 만들 수 없다.
			throw new IllegalArgumentException("feedback.crypto.rolling-window-minutes는 1 이상이어야 합니다.");
		}
		if (sigmaLookbackHours < 1) {
			// 0이면 조회 창이 사라져 σ 표본이 항상 0건이다.
			throw new IllegalArgumentException("feedback.crypto.sigma-lookback-hours는 1 이상이어야 합니다.");
		}
		if (minSampleCount < 1) {
			// 0이면 표본 부족 판정이 항상 통과해 σ=0 나눗셈으로 이어질 수 있다.
			throw new IllegalArgumentException("feedback.crypto.min-sample-count는 1 이상이어야 합니다.");
		}
		if (matchBeforeMinutes < 0) {
			// 음수면 근거창의 시작이 끝(occurredAt)보다 늦어 BETWEEN이 항상 빈 결과다 — 카드가 매일 0건이 된다.
			throw new IllegalArgumentException("feedback.crypto.match-before-minutes는 0 이상이어야 합니다.");
		}
		if (watchLockTtlSeconds < 1) {
			// 0 이하면 Duration.ofSeconds(0/음수)가 Redis 명령 오류를 유발하고, CryptoWatchLock.tryLock의
			// catch(RuntimeException)이 이를 삼켜 항상 Optional.empty()를 반환한다 — 모든 코인 카드가 DEBUG
			// 로그 한 줄만 남기고 영구 0건이 된다(이슈 #244 2차 리뷰 [권장 2]).
			throw new IllegalArgumentException("feedback.crypto.watch-lock-ttl-seconds는 1 이상이어야 합니다.");
		}
	}
}
