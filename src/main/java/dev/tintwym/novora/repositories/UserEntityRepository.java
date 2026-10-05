package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.UserEntity;

public interface UserEntityRepository extends JpaRepository<UserEntity, String> {
	Optional<UserEntity> findByEmailIgnoreCase(String email);
	boolean existsByEmailIgnoreCase(String email);
	Optional<UserEntity> findByIdAndCompanyId(String id, String companyId);
	List<UserEntity> findByCompanyId(String companyId);
}
