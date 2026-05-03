package com.assurant.brain.confluence;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.confluence.dto.ConfluenceTarget;
import com.assurant.brain.confluence.dto.PublishResult;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.domain.GeneratedDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConfluencePublisherService")
class ConfluencePublisherServiceTest {

    private ConfluenceClient client;
    private GeneratedDocumentRepository docRepo;
    private ConfluencePublisherService publisher;
    private BrainProperties props;

    @BeforeEach
    void setup() {
        client = mock(ConfluenceClient.class);
        docRepo = mock(GeneratedDocumentRepository.class);
        when(docRepo.save(any(GeneratedDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        BrainProperties.Confluence conf = new BrainProperties.Confluence(
                true, "https://x.atlassian.net", "bot@x.com", "tok",
                null, null, true);
        props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, conf);

        publisher = new ConfluencePublisherService(props, client, new ConfluenceStorageRenderer(), docRepo);
    }

    @Test
    @DisplayName("publishOrUpdate without prior page → CREATE path stores pageId on doc")
    void createsNewPage() {
        GeneratedDocument doc = doc("ce-imei", null);
        when(client.createPage(eq("ENG"), eq("123"), anyString(), anyString()))
                .thenReturn(Map.of("id", "page-1", "_links", Map.of("webui", "/spaces/ENG/pages/page-1"),
                        "version", Map.of("number", 1)));
        when(client.updatePage(eq("page-1"), anyInt(), anyString(), anyString()))
                .thenReturn(Map.of("id", "page-1", "_links", Map.of("webui", "/spaces/ENG/pages/page-1"),
                        "version", Map.of("number", 2)));

        PublishResult result = publisher.publishOrUpdate(doc, new ConfluenceTarget("ENG", "123"));

        assertThat(result.status()).isEqualTo(PublishResult.Status.CREATED);
        assertThat(result.pageId()).isEqualTo("page-1");
        verify(client).createPage(eq("ENG"), eq("123"), anyString(), anyString());
        verify(client).updatePage(eq("page-1"), eq(2), anyString(), anyString());
        verify(docRepo, times(1)).save(any(GeneratedDocument.class));
    }

    @Test
    @DisplayName("publishOrUpdate with same target as stored → UPDATE path bumps version")
    void updateSameTarget() {
        GeneratedDocument doc = doc("ce-imei", "page-1");
        doc.setConfluenceSpaceKey("ENG");
        doc.setConfluenceParentPageId("123");
        when(client.getPage("page-1")).thenReturn(java.util.Optional.of(
                Map.of("id", "page-1", "version", Map.of("number", 5),
                        "_links", Map.of("webui", "/spaces/ENG/pages/page-1"))));
        when(client.updatePage(eq("page-1"), anyInt(), anyString(), anyString()))
                .thenReturn(Map.of("id", "page-1", "_links", Map.of("webui", "/spaces/ENG/pages/page-1"),
                        "version", Map.of("number", 6)));

        PublishResult result = publisher.publishOrUpdate(doc, new ConfluenceTarget("ENG", "123"));

        assertThat(result.status()).isEqualTo(PublishResult.Status.UPDATED);
        assertThat(result.versionNumber()).isEqualTo(6);
        verify(client, never()).createPage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("publishOrUpdate with different target than stored → CREATE fresh page")
    void differentTargetCreatesFresh() {
        GeneratedDocument doc = doc("ce-imei", "page-1");
        doc.setConfluenceSpaceKey("ENG");
        doc.setConfluenceParentPageId("123");
        when(client.createPage(eq("OTHER"), eq("999"), anyString(), anyString()))
                .thenReturn(Map.of("id", "page-2", "_links", Map.of("webui", "/spaces/OTHER/pages/page-2"),
                        "version", Map.of("number", 1)));
        when(client.updatePage(eq("page-2"), anyInt(), anyString(), anyString()))
                .thenReturn(Map.of("id", "page-2", "_links", Map.of("webui", "/spaces/OTHER/pages/page-2"),
                        "version", Map.of("number", 2)));

        PublishResult result = publisher.publishOrUpdate(doc, new ConfluenceTarget("OTHER", "999"));

        assertThat(result.status()).isEqualTo(PublishResult.Status.CREATED);
        assertThat(result.pageId()).isEqualTo("page-2");
    }

    @Test
    @DisplayName("publishOrUpdate when disabled → SKIPPED")
    void skipsWhenDisabled() {
        BrainProperties.Confluence disabled = new BrainProperties.Confluence(
                false, "", "", "", null, null, true);
        BrainProperties disabledProps = new BrainProperties(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, disabled);
        ConfluencePublisherService p = new ConfluencePublisherService(
                disabledProps, client, new ConfluenceStorageRenderer(), docRepo);
        PublishResult result = p.publishOrUpdate(doc("p", null), new ConfluenceTarget("S", "1"));
        assertThat(result.status()).isEqualTo(PublishResult.Status.SKIPPED);
    }

    @Test
    @DisplayName("updateExisting without a stored pageId → SKIPPED")
    void updateExistingNoPriorPage() {
        PublishResult result = publisher.updateExisting(doc("p", null));
        assertThat(result.status()).isEqualTo(PublishResult.Status.SKIPPED);
    }

    private GeneratedDocument doc(String projectId, String pageId) {
        GeneratedDocument d = new GeneratedDocument();
        d.setId(java.util.UUID.randomUUID());
        d.setProjectId(projectId);
        d.setTitle("Brain documentation");
        d.setContentMd("# Title\n\nHello world");
        d.setConfluencePageId(pageId);
        return d;
    }
}
