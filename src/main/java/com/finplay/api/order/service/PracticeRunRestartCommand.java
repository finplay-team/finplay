// 교육 attempt 재시작 정리에 필요한 스칼라 값만 order 경계로 전달하는 명령 DTO
package com.finplay.api.order.service;

import com.finplay.api.market.domain.Market;
import java.time.LocalDateTime;

public record PracticeRunRestartCommand(
	Long attemptId,
	long runNumber,
	Long userId,
	Market market,
	Long instrumentId,
	LocalDateTime restartedAt) {
}
