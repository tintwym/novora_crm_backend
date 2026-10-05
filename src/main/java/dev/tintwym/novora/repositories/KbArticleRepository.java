package dev.tintwym.novora.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.KbArticle;

public interface KbArticleRepository extends JpaRepository<KbArticle, String> {
	List<KbArticle> findByCompanyIdOrderByUpdatedAtDesc(String companyId);
	List<KbArticle> findByCompanyIdAndPublishedTrueOrderByTitleAsc(String companyId);
}
