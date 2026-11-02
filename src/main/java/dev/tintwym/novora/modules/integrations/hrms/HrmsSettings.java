package dev.tintwym.novora.modules.integrations.hrms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-wide switch and credentials for talking to Novora HRMS. Off unless every value is set. */
@Component
public class HrmsSettings {

	static final int MIN_SECRET_LENGTH = 32;

	private final boolean enabled;
	private final String baseUrl;
	private final String secret;

	public HrmsSettings(
			@Value("${novora.hrms.enabled:false}") boolean enabled,
			@Value("${novora.hrms.base-url:}") String baseUrl,
			@Value("${novora.hrms.integration-secret:}") String secret) {
		this.enabled = enabled;
		this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
		this.secret = secret == null ? "" : secret.trim();
	}

	public boolean isReady() {
		return enabled && !baseUrl.isEmpty() && secret.length() >= MIN_SECRET_LENGTH;
	}

	public String baseUrl() {
		return baseUrl;
	}

	public String secret() {
		return secret;
	}
}
