package dev.tintwym.novora.modules.integrations.hrms;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies server-to-server requests between Novora products.
 *
 * <p>The signature covers the timestamp, HTTP method, path (with query string) and raw body, so a captured
 * request can't be replayed against a different endpoint, altered, or replayed after {@link #MAX_SKEW_SECONDS}.
 * Novora HRMS implements the same scheme; keep the two in sync.
 */
public final class RequestSigner {

	public static final String TIMESTAMP_HEADER = "X-Novora-Timestamp";
	public static final String SIGNATURE_HEADER = "X-Novora-Signature";
	public static final long MAX_SKEW_SECONDS = 300;

	private static final String VERSION = "v1=";

	private RequestSigner() {}

	public static String sign(String secret, long timestampSeconds, String method, String pathAndQuery, String body) {
		String canonical = timestampSeconds + "\n" + method.toUpperCase() + "\n" + pathAndQuery + "\n"
				+ (body == null ? "" : body);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return VERSION + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}

	public static boolean verify(String secret, String timestampHeader, String signatureHeader, String method,
			String pathAndQuery, String body, Instant now) {
		if (secret == null || secret.isBlank() || timestampHeader == null || signatureHeader == null) {
			return false;
		}
		long timestamp;
		try {
			timestamp = Long.parseLong(timestampHeader.trim());
		} catch (NumberFormatException e) {
			return false;
		}
		if (Math.abs(now.getEpochSecond() - timestamp) > MAX_SKEW_SECONDS) {
			return false;
		}
		String expected = sign(secret, timestamp, method, pathAndQuery, body);
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
	}
}
