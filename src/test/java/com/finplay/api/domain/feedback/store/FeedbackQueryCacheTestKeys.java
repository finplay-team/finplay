package com.finplay.api.domain.feedback.store;

import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class FeedbackQueryCacheTestKeys {

	public static final String PATTERN = "feedback:query-cache:*";

	private FeedbackQueryCacheTestKeys() {}

	public static void clear(StringRedisTemplate redisTemplate) {
		Set<String> keys = redisTemplate.keys(PATTERN);
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}
}
