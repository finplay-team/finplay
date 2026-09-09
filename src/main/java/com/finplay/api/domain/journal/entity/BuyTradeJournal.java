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
@Table(name = "buy_trade_journals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyTradeJournal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buy_trade_id", nullable = false)
	private Trade buyTrade;

	@Column(nullable = false, length = 5000)
	private String content;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private BuyTradeJournal(Trade buyTrade, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {
		this.buyTrade = buyTrade;
		this.content = content;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static BuyTradeJournal of(Trade buyTrade, String content, LocalDateTime now) {
		return new BuyTradeJournal(buyTrade, content, now, now);
	}

	public void updateContent(String content, LocalDateTime updatedAt) {
		this.content = content;
		this.updatedAt = updatedAt;
	}
}
