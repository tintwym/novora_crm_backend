package dev.tintwym.novora.modules.integrations.hrms;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.tintwym.novora.common.AppException;
import tools.jackson.databind.json.JsonMapper;

/** Signed calls from the CRM to Novora HRMS. */
@Component
public class HrmsClient {

	static final String BASE_PATH = "/api/integrations/crm/v1";
	static final String CRM_COMPANY_HEADER = "X-Novora-Crm-Company";

	private static final Logger log = LoggerFactory.getLogger(HrmsClient.class);

	public record Organization(String organizationId, String organizationName) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RedeemResponse(String organizationId, String organizationName) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record EmployeeListResponse(String organizationId, List<HrmsEmployee> employees) {}

	private final HrmsSettings settings;
	private final JsonMapper jsonMapper;
	private final RestClient restClient;

	public HrmsClient(HrmsSettings settings, JsonMapper jsonMapper) {
		this.settings = settings;
		this.jsonMapper = jsonMapper;
		// A sleeping Render service can take about a minute to answer its first request.
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(10));
		requestFactory.setReadTimeout(Duration.ofSeconds(90));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/** Exchanges a one-time code from HRMS for the organization it belongs to, linking it to this company. */
	public Organization redeem(String code, String crmCompanyId) {
		String path = BASE_PATH + "/link/redeem";
		String body = jsonMapper.writeValueAsString(Map.of("code", code, "crmCompanyId", crmCompanyId));
		String response = send("POST", path, body, null);
		RedeemResponse parsed = parse(response, RedeemResponse.class);
		if (parsed.organizationId() == null) {
			throw new AppException("Novora HRMS sent an unexpected response", HttpStatus.BAD_GATEWAY);
		}
		return new Organization(parsed.organizationId(), parsed.organizationName());
	}

	/** Full roster of the linked organization, including employees who have left. */
	public List<HrmsEmployee> employees(String hrmsOrgId, String crmCompanyId) {
		String path = BASE_PATH + "/organizations/" + UUID.fromString(hrmsOrgId) + "/employees";
		EmployeeListResponse parsed = parse(send("GET", path, "", crmCompanyId), EmployeeListResponse.class);
		return parsed.employees() == null ? List.of() : parsed.employees();
	}

	private String send(String method, String path, String body, String crmCompanyId) {
		if (!settings.isReady()) {
			throw new AppException("The HRMS connection isn't set up on this server yet", HttpStatus.SERVICE_UNAVAILABLE);
		}
		long timestamp = Instant.now().getEpochSecond();
		String signature = RequestSigner.sign(settings.secret(), timestamp, method, path, body);
		try {
			RestClient.RequestBodySpec request = restClient.method(HttpMethod.valueOf(method))
					.uri(URI.create(settings.baseUrl() + path))
					.header(RequestSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
					.header(RequestSigner.SIGNATURE_HEADER, signature)
					.accept(MediaType.APPLICATION_JSON);
			if (crmCompanyId != null) request.header(CRM_COMPANY_HEADER, crmCompanyId);
			if (!body.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).body(body);
			String response = request.retrieve().body(String.class);
			return response == null ? "" : response;
		} catch (RestClientResponseException ex) {
			throw translate(ex);
		} catch (RuntimeException ex) {
			log.warn("Novora HRMS request {} {} failed: {}", method, path, ex.toString());
			throw new AppException("Couldn't reach Novora HRMS. It may be starting up; try again in a minute.",
					HttpStatus.BAD_GATEWAY);
		}
	}

	private AppException translate(RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		String message = hrmsMessage(ex.getResponseBodyAsString());
		log.warn("Novora HRMS answered {}: {}", status, message);
		return switch (status) {
			case 400 -> new AppException(message != null ? message : "Novora HRMS rejected the request",
					HttpStatus.BAD_REQUEST);
			case 401 -> new AppException(
					"Novora HRMS rejected this server's credentials. The integration secrets on the two servers must match.",
					HttpStatus.BAD_GATEWAY);
			case 403 -> new AppException(
					"Novora HRMS says this workspace is no longer linked. Ask your HR admin for a new code.",
					HttpStatus.CONFLICT);
			case 503 -> new AppException("The CRM connection isn't turned on in Novora HRMS yet",
					HttpStatus.BAD_GATEWAY);
			default -> new AppException("Novora HRMS had a problem (HTTP " + status + "). Try again later.",
					HttpStatus.BAD_GATEWAY);
		};
	}

	private String hrmsMessage(String body) {
		if (body == null || body.isBlank()) return null;
		try {
			Object message = jsonMapper.readValue(body, Map.class).get("message");
			return message == null ? null : String.valueOf(message);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private <T> T parse(String body, Class<T> type) {
		try {
			return jsonMapper.readValue(body, type);
		} catch (RuntimeException e) {
			throw new AppException("Novora HRMS sent an unexpected response", HttpStatus.BAD_GATEWAY);
		}
	}
}
