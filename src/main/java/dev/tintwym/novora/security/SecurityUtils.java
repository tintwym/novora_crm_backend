package dev.tintwym.novora.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import dev.tintwym.novora.common.AppException;

public final class SecurityUtils {

	private SecurityUtils() {}

	public static UserPrincipal currentUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
			throw new AppException("Unauthorized", HttpStatus.UNAUTHORIZED);
		}
		return principal;
	}

	public static boolean canViewAllDeals(UserPrincipal user) {
		return RolePermissions.has(user.getRole(), Permission.VIEW_ALL_DEALS);
	}
}
