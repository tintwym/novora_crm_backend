package dev.tintwym.novora.modules.integrations.hrms;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.tintwym.novora.domain.enums.Role;

/**
 * Decides which CRM role an employee gets from their HRMS department and position level. People outside the
 * workspace's sales and support departments don't get a CRM account.
 */
public final class HrmsRoleMapper {

	static final Set<String> MANAGER_LEVELS = Set.of("manager", "director", "c_level");
	static final int MAX_CODES = 20;
	static final int MAX_CODE_LENGTH = 20;

	private HrmsRoleMapper() {}

	public static Optional<Role> roleFor(HrmsEmployee employee, Set<String> salesCodes, Set<String> supportCodes) {
		String code = normalize(employee.departmentCode());
		if (code == null) return Optional.empty();
		if (salesCodes.contains(code)) {
			String level = employee.positionLevel() == null ? "" : employee.positionLevel().trim().toLowerCase(Locale.ROOT);
			return Optional.of(MANAGER_LEVELS.contains(level) ? Role.SALES_MANAGER : Role.SALES_REP);
		}
		if (supportCodes.contains(code)) return Optional.of(Role.SUPPORT_AGENT);
		return Optional.empty();
	}

	/** Splits a stored comma-separated list such as "SLS,SALES" into normalized codes. */
	public static Set<String> parseCodes(String csv) {
		if (csv == null || csv.isBlank()) return Set.of();
		return Arrays.stream(csv.split(","))
				.map(HrmsRoleMapper::normalize)
				.filter(c -> c != null)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/** Valid department code or null; codes are compared case-insensitively. */
	static String normalize(String raw) {
		if (raw == null) return null;
		String code = raw.trim().toUpperCase(Locale.ROOT);
		if (code.isEmpty() || code.length() > MAX_CODE_LENGTH || !code.matches("[A-Z0-9_-]+")) return null;
		return code;
	}
}
