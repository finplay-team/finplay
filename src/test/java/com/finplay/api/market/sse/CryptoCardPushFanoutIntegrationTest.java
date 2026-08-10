// SseEmitterRegistry+CryptoCardPushSubscriber+RedisMessageListenerContainer 조합을 (스프링 빈이 아니라) 테스트
// 코드에서 두 벌 직접 조립해, 같은 Testcontainers Redis에 한쪽이 발행한 카드 확정 메시지를 양쪽 모두 수신하는지
// (다중 인스턴스 팬아웃) 검증한다. (docs/specs/028-crypto-card-sse-push/tasks.md 항목 4)
package com.finplay.api.market.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.PriceMoveCardConfirmedEvent;
import com.finplay.api.market.service.CryptoPriceMoveCardPublisher;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;
import tools.jackson.databind.ObjectMapper;

// ADR-0018 §결정 4의 핵심 주장(인스턴스 경계를 넘는 팬아웃)의 직접 증거다. 전체 Spring Context를 두 개 띄우는
// 대신, 한 Context 안에서 SseEmitterRegistry·CryptoCardPushSubscriber·RedisMessageListenerContainer를 new로 두
// 벌 조립해 "서버 인스턴스 두 대"를 흉내낸다 — 실제 프로덕션 빈 배선(RedisPubSubConfig)은 건드리지 않는다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CryptoCardPushFanoutIntegrationTest {

	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	// "한쪽 인스턴스가 발행"의 발행자 — 실제 프로덕션 빈을 그대로 쓴다(발행 쪽은 이 테스트의 검증 대상이 아니다,
	// 3번 항목이 이미 검증했다).
	@Autowired
	private CryptoPriceMoveCardPublisher cryptoPriceMoveCardPublisher;

	private final ObjectMapper objectMapper = new ObjectMapper();

	// "인스턴스 A" 조합.
	private SseEmitterRegistry registryA;
	private RedisMessageListenerContainer containerA;
	private SseEmitterTestHandler handlerA;

	// "인스턴스 B" 조합 — A와 완전히 별개의 SseEmitterRegistry·구독자·컨테이너다. 같은 Redis만 공유한다.
	private SseEmitterRegistry registryB;
	private RedisMessageListenerContainer containerB;
	private SseEmitterTestHandler handlerB;

	@BeforeEach
	void setUp() throws Exception {
		registryA = new SseEmitterRegistry();
		containerA = startCombo(registryA);
		handlerA = registerFakeEmitter(registryA);
		// registerFakeEmitter()가 이미 retry 힌트 1건을 emitter에 flush해 sentEvents가 비어있지 않다 — 워밍업
		// 이벤트가 "실제로 도착"했는지는 이 기준선 대비 증가로만 판단할 수 있다(그렇지 않으면 retry 힌트만으로도
		// 워밍업 대기가 즉시 통과해버려 구독이 실제로 걸리기 전에 본 시나리오가 시작될 수 있다).
		int beforeWarmupA = handlerA.getSentEvents().size();

		registryB = new SseEmitterRegistry();
		containerB = startCombo(registryB);
		handlerB = registerFakeEmitter(registryB);
		int beforeWarmupB = handlerB.getSentEvents().size();

		warmUp(beforeWarmupA, beforeWarmupB);
	}

	@AfterEach
	void tearDown() {
		containerA.stop();
		containerB.stop();
	}

	private RedisMessageListenerContainer startCombo(SseEmitterRegistry registry) {
		CryptoCardPushSubscriber subscriber = new CryptoCardPushSubscriber(registry, objectMapper);
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redisConnectionFactory);
		container.addMessageListener(subscriber, new ChannelTopic(CryptoPriceMoveCardPublisher.CHANNEL));
		container.afterPropertiesSet();
		container.start();
		return container;
	}

	private static SseEmitterTestHandler registerFakeEmitter(SseEmitterRegistry registry) throws Exception {
		SseEmitter emitter = registry.createEmitter(Market.CRYPTO);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		registry.activate(Market.CRYPTO, emitter);
		return handler;
	}

	// 구독 등록이 Redis 서버에 실제로 반영되기까지 지연이 있다 — 두 조합 모두 워밍업 이벤트를 실제로 받는 것을
	// 확인한 뒤에야 본 시나리오를 실행한다 (CryptoPriceMoveCardPushIntegrationTest와 같은 이유).
	private void warmUp(int beforeWarmupA, int beforeWarmupB) {
		cryptoPriceMoveCardPublisher.publish(-1L, -1L);
		awaitUntil(
			() -> handlerA.getSentEvents().size() > beforeWarmupA && handlerB.getSentEvents().size() > beforeWarmupB,
			Duration.ofSeconds(5), "워밍업 이벤트를 두 조합 모두 받지 못했다 — 이후 단정이 신뢰할 수 없다");
	}

	private static void awaitUntil(BooleanSupplier condition, Duration timeout, String failureMessage) {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failureMessage, e);
			}
		}
		throw new AssertionError(failureMessage);
	}

	// SseEventBuilder.build()가 만드는 DataWithMediaType을 벗겨 실제 데이터 객체를 꺼낸다
	// (CryptoCardPushSubscriberTest와 동일 관례).
	private static Object unwrapData(Object sentEvent) {
		if (sentEvent instanceof ResponseBodyEmitter.DataWithMediaType dataWithMediaType) {
			return dataWithMediaType.getData();
		}
		return sentEvent;
	}

	private static PriceMoveCardConfirmedEvent latestPayload(SseEmitterTestHandler handler) {
		List<Object> sentEvents = handler.getSentEvents();
		for (int i = sentEvents.size() - 1; i >= 0; i--) {
			Object unwrapped = unwrapData(sentEvents.get(i));
			if (unwrapped instanceof PriceMoveCardConfirmedEvent event) {
				return event;
			}
		}
		throw new AssertionError("priceMoveCardConfirmed 데이터 페이로드가 전송되지 않았다");
	}

	@Test
	@DisplayName("한쪽 인스턴스가 발행한 카드 확정 메시지를 두 인스턴스의 emitter가 모두 수신한다(다중 인스턴스 팬아웃)")
	void bothCombosReceiveTheMessagePublishedByOneInstance() {
		int sentBeforeA = handlerA.getSentEvents().size();
		int sentBeforeB = handlerB.getSentEvents().size();

		// "한쪽"만 발행한다 — B쪽은 발행을 전혀 하지 않는다. 그런데도 B의 emitter가 받는다면, 그건 로컬
		// CopyOnWriteArrayList가 아니라 Redis를 통해 팬아웃됐다는 뜻이다(ADR-0018 §맥락 "왜 Redis pub/sub인가").
		cryptoPriceMoveCardPublisher.publish(42L, 777L);

		awaitUntil(() -> handlerA.getSentEvents().size() > sentBeforeA && handlerB.getSentEvents().size() > sentBeforeB,
			Duration.ofSeconds(5), "두 조합 중 최소 한쪽이 새 메시지를 받지 못했다");

		PriceMoveCardConfirmedEvent payloadA = latestPayload(handlerA);
		assertThat(payloadA.market()).isEqualTo(Market.CRYPTO);
		assertThat(payloadA.instrumentId()).isEqualTo(42L);
		assertThat(payloadA.priceMoveEventId()).isEqualTo(777L);

		PriceMoveCardConfirmedEvent payloadB = latestPayload(handlerB);
		assertThat(payloadB.market()).isEqualTo(Market.CRYPTO);
		assertThat(payloadB.instrumentId()).isEqualTo(42L);
		assertThat(payloadB.priceMoveEventId()).isEqualTo(777L);
	}
}
