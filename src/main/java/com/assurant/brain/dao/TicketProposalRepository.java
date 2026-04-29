package com.assurant.brain.dao;

import com.assurant.brain.domain.TicketProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketProposalRepository extends JpaRepository<TicketProposal, UUID> {

    List<TicketProposal> findByUserIdOrderByCreatedAtDesc(String userId);
}
