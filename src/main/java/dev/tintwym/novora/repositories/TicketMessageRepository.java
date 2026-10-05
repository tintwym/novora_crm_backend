package dev.tintwym.novora.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.tintwym.novora.domain.entity.TicketMessage;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, String> {
	List<TicketMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);
	List<TicketMessage> findByTicketIdAndIsInternalFalseOrderByCreatedAtAsc(String ticketId);
	long countByTicketId(String ticketId);
}
