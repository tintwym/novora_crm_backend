package dev.tintwym.novora.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.QuoteLineItem;

public interface QuoteLineItemRepository extends JpaRepository<QuoteLineItem, String> {
	List<QuoteLineItem> findByQuoteIdOrderBySortOrderAsc(String quoteId);
	void deleteByQuoteId(String quoteId);
}
