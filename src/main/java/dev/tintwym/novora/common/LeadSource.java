package dev.tintwym.novora.common;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;

/** Where a contact or deal came from. Stored as the upper-case code. */
public final class LeadSource {

	public static final List<String> CODES = List.of(
			"WEBSITE", "REFERRAL", "CAMPAIGN", "EVENT", "COLD_OUTREACH", "SOCIAL", "PARTNER", "OTHER");

	private LeadSource() {
	}

	/**
	 * Accepts a code or a human label ("Cold outreach", "social media") and returns the code,
	 * or null when the value is empty.
	 */
	public static String normalize(Object raw) {
		if (raw == null) {
			return null;
		}
		String text = String.valueOf(raw).trim();
		if (text.isEmpty() || "null".equals(text)) {
			return null;
		}
		String code = text.toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
		if (code.equals("SOCIAL_MEDIA")) {
			code = "SOCIAL";
		}
		if (!CODES.contains(code)) {
			throw new AppException("Source must be one of: Website, Referral, Campaign, Event, Cold outreach, "
					+ "Social media, Partner, Other", HttpStatus.BAD_REQUEST);
		}
		return code;
	}
}
