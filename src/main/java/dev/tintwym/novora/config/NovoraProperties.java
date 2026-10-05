package dev.tintwym.novora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "novora")
public record NovoraProperties(
		String corsOrigin,
		String apiPrefix,
		Jwt jwt
) {
	public record Jwt(
			String accessSecret,
			String refreshSecret,
			String accessExpires,
			String refreshExpires
	) {}
}
