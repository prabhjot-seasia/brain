package com.assurant.brain.rest.v1.avengers.controller;

import com.assurant.brain.avenger.hawkeye.AsffMapper;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.AvengerReviewRepository;
import com.assurant.brain.domain.AvengerReview;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HawkeyeAsffController")
class HawkeyeAsffControllerTest {

    private AvengerReviewRepository repository;
    private AsffMapper asffMapper;
    private HawkeyeAsffController controller;

    @BeforeEach
    void setup() {
        repository = mock(AvengerReviewRepository.class);
        asffMapper = mock(AsffMapper.class);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, new BrainProperties.Hawkeye("arn:aws:test", "111122223333", "us-west-2"), null, null);
        controller = new HawkeyeAsffController(repository, asffMapper, props);
    }

    @Test
    @DisplayName("findings clamps limit to 200 max")
    void clampsLimitTo200() {
        when(repository.findByAvengerAndProjectIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(asffMapper.toFindingsBatch(any(), any())).thenReturn(mock(JsonNode.class));

        controller.findings("proj-1", 5000);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(repository).findByAvengerAndProjectIdOrderByCreatedAtDesc(eq(AvengerType.HAWKEYE), eq("proj-1"), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("findings uses default limit 50 when not provided")
    void defaultLimit() {
        when(repository.findByAvengerAndProjectIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(asffMapper.toFindingsBatch(any(), any())).thenReturn(mock(JsonNode.class));

        controller.findings("proj-1", null);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(repository).findByAvengerAndProjectIdOrderByCreatedAtDesc(any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("findings forwards reviews into AsffMapper using configured productArn")
    void forwardsToAsffMapper() {
        AvengerReview review = sampleReview();
        when(repository.findByAvengerAndProjectIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of(review));
        JsonNode batch = new ObjectMapper().createObjectNode();
        when(asffMapper.toFindingsBatch(any(), eq("arn:aws:test"))).thenReturn(batch);

        ResponseEntity<JsonNode> response = controller.findings("proj-1", 25);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(asffMapper).toFindingsBatch(eq(List.of(review)), eq("arn:aws:test"));
    }

    @Test
    @DisplayName("findings falls back to default ProductArn when config is missing")
    void defaultProductArnWhenConfigMissing() {
        var propsNoHawkeye = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);        ReflectionTestUtils.setField(controller, "brainProperties", propsNoHawkeye);
        when(repository.findByAvengerAndProjectIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        when(asffMapper.toFindingsBatch(any(), any())).thenReturn(mock(JsonNode.class));

        controller.findings("proj-1", 10);

        verify(asffMapper).toFindingsBatch(any(), eq("arn:aws:securityhub:::product/project-brain/hawkeye"));
    }

    private AvengerReview sampleReview() {
        AvengerReview r = new AvengerReview();
        r.setId(UUID.randomUUID());
        r.setAvenger(AvengerType.HAWKEYE);
        r.setProjectId("proj-1");
        r.setRequestHash("abc");
        r.setVerdict(AvengerVerdict.BLOCKED);
        r.setIssues(List.of("hardcoded secret"));
        r.setSummary("review summary");
        r.setTokensIn(100);
        r.setTokensOut(50);
        r.setLatencyMs(123);
        r.setCreatedAt(OffsetDateTime.of(2026, 4, 27, 12, 0, 0, 0, ZoneOffset.UTC));
        return r;
    }
}
