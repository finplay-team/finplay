// 매도 체결 1건에 작성된 매도 회고를 영속하는 엔티티
package com.finplay.api.domain.journal.entity;

import com.finplay.api.domain.order.entity.Trade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sell_trade_journals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellTradeJournal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sell_trade_id", nullable = false)
	private Trade sellTrade;

	@Column(nullable = false, length = 5000)
	private String content;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private SellTradeJournal(Trade sellTrade, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {
		this.sellTrade = sellTrade;
		this.content = content;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static SellTradeJournal of(Trade sellTrade, String content, LocalDateTime now) {
		return new SellTradeJournal(sellTrade, content, now, now);
	}

	public void updateContent(String content, LocalDateTime updatedAt) {
		this.content = content;
		this.updatedAt = updatedAt;
	}
}
