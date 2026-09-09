package com.finplay.api.domain.education.priceruntime.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PracticePriceGeneratorV1 {

	public static final int VERSION = 1;

	private static final String DIGEST_ALGORITHM = "SHA-256";
	private static final int SEED_BYTES = Long.BYTES;
	private static final int TICK_BYTES = Integer.BYTES;
	private static final int UNSIGNED_LONG_BYTES = 8;
	private static final BigInteger MODULUS = BigInteger.valueOf(20_001);
	private static final BigInteger UNITS_OFFSET = BigInteger.valueOf(10_000);
	private static final BigDecimal RATE_DIVISOR = BigDecimal.valueOf(1_000_000);
	private static final BigDecimal MIN_PRICE_RATIO = BigDecimal.valueOf(0.5);
	private static final int PRICE_SCALE = 8;

	private PracticePriceGeneratorV1() {}

	public static BigDecimal nextPrice(long seed, int tick, BigDecimal previousPrice, BigDecimal startPrice) {
		BigInteger unsignedValue = digestToUnsignedLong(seed, tick);
		BigInteger units = unsignedValue.mod(MODULUS).subtract(UNITS_OFFSET);
		BigDecimal rate = new BigDecimal(units).divide(RATE_DIVISOR);
		BigDecimal candidate = previousPrice
			.multiply(BigDecimal.ONE.add(rate))
			.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		BigDecimal floor = startPrice.multiply(MIN_PRICE_RATIO).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		return candidate.compareTo(floor) < 0 ? floor : candidate;
	}

	private static BigInteger digestToUnsignedLong(long seed, int tick) {
		ByteBuffer buffer = ByteBuffer.allocate(SEED_BYTES + TICK_BYTES);
		buffer.putLong(seed);
		buffer.putInt(tick);
		byte[] hash = digest(buffer.array());
		byte[] firstEightBytes = new byte[UNSIGNED_LONG_BYTES];
		System.arraycopy(hash, 0, firstEightBytes, 0, UNSIGNED_LONG_BYTES);
		return new BigInteger(1, firstEightBytes);
	}

	private static byte[] digest(byte[] input) {
		try {
			return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(input);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(DIGEST_ALGORITHM + " 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
