// 매수 체결 1건에 작성된 투자일기를 영속하는 엔티티
package com.finplay.api.journal.domain;

import com.finplay.api.order.domain.Trade;
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

	private BuyTradeJournal(Trade buyTrade, String content, LocalDateTime createdAt) {
		this.buyTrade = buyTrade;
		this.content = content;
		this.createdAt = createdAt;
	}

	public static BuyTradeJournal of(Trade buyTrade, String content, LocalDateTime now) {
		return new BuyTradeJournal(buyTrade, content, now);
	}
}
