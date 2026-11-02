package dev.tintwym.novora.modules.integrations.hrms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class RequestSignerTest {

	private static final String SECRET = "test-secret-that-is-at-least-32-characters";
	private static final String PATH = "/api/v1/integrations/hrms/events";

	@Test
	void acceptsAnUntouchedRequest() {
		Instant now = Instant.parse("2026-10-05T10:00:00Z");
		String sig = RequestSigner.sign(SECRET, now.getEpochSecond(), "POST", PATH, "{\"a\":1}");
		assertTrue(RequestSigner.verify(SECRET, Long.toString(now.getEpochSecond()), sig, "POST", PATH, "{\"a\":1}", now));
	}

	@Test
	void rejectsChangedBodyPathMethodOrSecret() {
		Instant now = Instant.parse("2026-10-05T10:00:00Z");
		String ts = Long.toString(now.getEpochSecond());
		String sig = RequestSigner.sign(SECRET, now.getEpochSecond(), "POST", PATH, "{\"a\":1}");
		assertFalse(RequestSigner.verify(SECRET, ts, sig, "POST", PATH, "{\"a\":2}", now));
		assertFalse(RequestSigner.verify(SECRET, ts, sig, "POST", PATH + "x", "{\"a\":1}", now));
		assertFalse(RequestSigner.verify(SECRET, ts, sig, "PUT", PATH, "{\"a\":1}", now));
		assertFalse(RequestSigner.verify(SECRET + "!", ts, sig, "POST", PATH, "{\"a\":1}", now));
		assertFalse(RequestSigner.verify(SECRET, null, sig, "POST", PATH, "{\"a\":1}", now));
		assertFalse(RequestSigner.verify(SECRET, "not-a-number", sig, "POST", PATH, "{\"a\":1}", now));
	}

	@Test
	void rejectsOldOrFutureTimestamps() {
		Instant signedAt = Instant.parse("2026-10-05T10:00:00Z");
		String ts = Long.toString(signedAt.getEpochSecond());
		String sig = RequestSigner.sign(SECRET, signedAt.getEpochSecond(), "POST", PATH, "");
		assertTrue(RequestSigner.verify(SECRET, ts, sig, "POST", PATH, "", signedAt.plusSeconds(299)));
		assertFalse(RequestSigner.verify(SECRET, ts, sig, "POST", PATH, "", signedAt.plusSeconds(301)));
		assertFalse(RequestSigner.verify(SECRET, ts, sig, "POST", PATH, "", signedAt.minusSeconds(301)));
	}

	/** Known answer shared with the HRMS RequestSignerTest, so the two implementations can't drift apart. */
	@Test
	void matchesTheSharedKnownAnswer() {
		String sig = RequestSigner.sign("0123456789abcdef0123456789abcdef", 1_700_000_000L, "post",
				"/api/v1/integrations/hrms/events", "{\"hello\":\"world\"}");
		assertEquals("v1=a1bc6140cafdc910ecf5e40b0efd02b9c2448ed81bf3282116ed29fbd6faa8e9", sig);
	}
}
