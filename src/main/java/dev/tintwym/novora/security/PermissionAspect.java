package dev.tintwym.novora.security;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import dev.tintwym.novora.common.AppException;

@Aspect
@Component
public class PermissionAspect {

	@Before("@annotation(requiresPermission)")
	public void check(RequiresPermission requiresPermission) {
		UserPrincipal user = SecurityUtils.currentUser();
		if (!RolePermissions.has(user.getRole(), requiresPermission.value())) {
			throw new AppException("Insufficient permissions", HttpStatus.FORBIDDEN);
		}
	}
}
