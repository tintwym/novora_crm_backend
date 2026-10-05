package dev.tintwym.novora.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.tintwym.novora.repositories.UserEntityRepository;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

	private final JwtService jwtService;
	private final UserEntityRepository userRepository;

	public JwtAuthFilter(JwtService jwtService, UserEntityRepository userRepository) {
		this.jwtService = jwtService;
		this.userRepository = userRepository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = header.substring(7);
		try {
			Claims claims = jwtService.parseAccess(token);
			if (!JwtService.TYPE_ACCESS.equals(claims.get("type", String.class))) {
				filterChain.doFilter(request, response);
				return;
			}
			String userId = claims.getSubject();
			userRepository.findById(userId).ifPresent(user -> {
				if (!user.isActive()) {
					return;
				}
				UserPrincipal principal = new UserPrincipal(
						user.getId(),
						user.getEmail(),
						user.getName(),
						user.getPasswordHash(),
						user.getRole(),
						user.getCompanyId(),
						user.isActive());
				var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
				auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				SecurityContextHolder.getContext().setAuthentication(auth);
			});
		} catch (Exception ignored) {
			SecurityContextHolder.clearContext();
		}

		filterChain.doFilter(request, response);
	}
}
