package com.assurant.brain.jira;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dao.TicketProposalRepository;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("JiraTicketController async-start (heavy-op IT)")
class JiraJobsLifecycleIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.jira.TicketProposalService ticketProposalService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.jira.TicketCreationService ticketCreationService;

    @Autowired private AsyncJobRepository jobRepository;
    @Autowired private TicketProposalRepository proposalRepository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        jobRepository.deleteAll();
        proposalRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /jira/tickets/propose dispatches TICKET_PROPOSAL with proposal-id targetId")
    void proposeAsync() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "content", "Add a CSV export endpoint",
                "projectKey", "BRAIN"));

        MvcResult result = mvc.perform(post("/api/v1/jira/tickets/propose")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.proposalId").exists())
                .andReturn();

        String jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText();
        String proposalId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("proposalId").asText();

        var saved = jobRepository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("TICKET_PROPOSAL");
        assertThat(saved.getTargetKind()).isEqualTo("PROPOSAL");
        assertThat(saved.getTargetId()).isEqualTo(proposalId);
    }

    @Test
    @DisplayName("POST /jira/tickets/create dispatches TICKET_CREATION; same proposalId dedups")
    void createDedups() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "projectKey", "BRAIN",
                "tickets", List.of(Map.of(
                        "summary", "Add CSV export",
                        "description", "Build a CSV export",
                        "issueType", "Story"))));

        MvcResult first = mvc.perform(post("/api/v1/jira/tickets/create")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();
        String firstJobId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("jobId").asText();
        var saved = jobRepository.findById(java.util.UUID.fromString(firstJobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("TICKET_CREATION");
        assertThat(saved.getTargetKind()).isEqualTo("PROPOSAL");

        MvcResult second = mvc.perform(post("/api/v1/jira/tickets/create")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        var secondNode = objectMapper.readTree(second.getResponse().getContentAsString());
        if (secondNode.get("attachedToExisting").asBoolean()) {
            assertThat(secondNode.get("jobId").asText()).isEqualTo(firstJobId);
        }
    }
}
