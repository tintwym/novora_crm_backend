package dev.tintwym.novora.config;

import java.net.URI;
import java.net.URISyntaxException;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(NovoraProperties.class)
public class DataSourceConfig {

	@Bean
	@Primary
	public DataSource dataSource(DataSourceProperties properties) {
		String url = properties.getUrl();
		String username = properties.getUsername();
		String password = properties.getPassword();
		String driver;

		// pgjdbc rejects user:password@ in a jdbc: URL, so treat that form like a plain postgresql:// URI
		if (url != null && url.startsWith("jdbc:postgresql://") && hasUserInfo(url.substring("jdbc:postgresql://".length()))) {
			url = url.substring("jdbc:".length());
		}

		if (url != null && (url.startsWith("postgresql://") || url.startsWith("postgres://"))) {
			driver = "org.postgresql.Driver";
			ParsedJdbc parsed = parsePostgresUri(url);
			url = parsed.jdbcUrl();
			// Credentials inside the URL (Render, Neon) win over DATABASE_USER / DATABASE_PASSWORD defaults
			if (StringUtils.hasText(parsed.username())) {
				username = parsed.username();
			}
			if (StringUtils.hasText(parsed.password())) {
				password = parsed.password();
			}
		} else {
			driver = properties.determineDriverClassName();
		}

		return DataSourceBuilder.create()
				.driverClassName(driver)
				.url(url)
				.username(username)
				.password(password)
				.build();
	}

	private static boolean hasUserInfo(String afterScheme) {
		int slash = afterScheme.indexOf('/');
		String authority = slash < 0 ? afterScheme : afterScheme.substring(0, slash);
		return authority.contains("@");
	}

	static ParsedJdbc parsePostgresUri(String uriString) {
		try {
			String normalized = uriString.replaceFirst("^postgres(ql)?://", "http://");
			URI uri = new URI(normalized);
			String userInfo = uri.getUserInfo();
			String user = null;
			String pass = null;
			if (userInfo != null) {
				int idx = userInfo.indexOf(':');
				if (idx >= 0) {
					user = userInfo.substring(0, idx);
					pass = userInfo.substring(idx + 1);
				} else {
					user = userInfo;
				}
			}
			String host = uri.getHost();
			int port = uri.getPort() > 0 ? uri.getPort() : 5432;
			String path = uri.getPath() != null ? uri.getPath() : "";
			String query = uri.getRawQuery();
			String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path
					+ (query != null ? "?" + query : "");
			return new ParsedJdbc(jdbcUrl, user, pass);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid DATABASE_URL: " + uriString, e);
		}
	}

	record ParsedJdbc(String jdbcUrl, String username, String password) {}
}
