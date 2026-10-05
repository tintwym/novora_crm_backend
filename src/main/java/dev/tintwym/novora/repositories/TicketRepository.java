package dev.tintwym.novora.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.tintwym.novora.domain.entity.Ticket;
import dev.tintwym.novora.domain.enums.TicketPriority;
import dev.tintwym.novora.domain.enums.TicketStatus;

public interface TicketRepository extends JpaRepository<Ticket, String> {
	Optional<Ticket> findByIdAndCompanyId(String id, String companyId);
	List<Ticket> findByCompanyIdOrderByPriorityDescUpdatedAtDesc(String companyId);
	List<Ticket> findByCompanyIdAndStatusOrderByPriorityDescUpdatedAtDesc(String companyId, TicketStatus status);
	List<Ticket> findByCompanyIdAndPriorityOrderByUpdatedAtDesc(String companyId, TicketPriority priority);
	long countByCompanyId(String companyId);

	@Query(value = """
			select coalesce(max(cast(substring(ticket_number from 3) as integer)), 0)
			from tickets where company_id = :companyId and ticket_number ~ '^T-[0-9]+$'
			""", nativeQuery = true)
	int maxTicketSequence(@Param("companyId") String companyId);
	Optional<Ticket> findByCompanyIdAndTicketNumber(String companyId, String ticketNumber);
}
