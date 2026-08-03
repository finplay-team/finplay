// 인증 사용자가 관심 종목으로 등록한 즐겨찾기를 표현하는 엔티티
package com.finplay.api.favorite.domain;

import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "favorites", uniqueConstraints = @UniqueConstraint(name = "uk_favorites_user_instrument", columnNames = {
	"user_id", "instrument_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Favorite {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private Favorite(User user, Instrument instrument, LocalDateTime createdAt) {
		this.user = user;
		this.instrument = instrument;
		this.createdAt = createdAt;
	}

	public static Favorite create(User user, Instrument instrument, LocalDateTime createdAt) {
		return new Favorite(user, instrument, createdAt);
	}
}
