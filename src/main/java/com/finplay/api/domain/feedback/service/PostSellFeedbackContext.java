package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;

record PostSellFeedbackContext(Trade trade, SellAllocationSummaryDto allocation) {
}
