// 계좌의 실현손익이 갱신됐음을 알리는 이벤트 (동시성 설계상 손익값을 싣지 않고 accountId만 전달)
package com.finplay.api.domain.account.event;

public record RealizedPnlUpdatedEvent(Long accountId) {
}
