package dev.tintwym.novora.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.tintwym.novora.domain.entity.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
	Optional<RefreshToken> findByToken(String token);

	@Modifying
	@Query("update RefreshToken r set r.revokedAt = CURRENT_TIMESTAMP where r.token = :token and r.revokedAt is null")
	int revokeByToken(@Param("token") String token);
}
