package com.assurant.brain.confluence;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.confluence.dto.ConfluenceTarget;
import com.assurant.brain.confluence.dto.PublishResult;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.domain.GeneratedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
public class ConfluencePublisherService {

    private final BrainProperties brainProperties;
    private final ConfluenceClient client;
    private final ConfluenceStorageRenderer renderer;
    private final GeneratedDocumentRepository documentRepository;

    public PublishResult publishOrUpdate(GeneratedDocument doc, ConfluenceTarget target) {
        if (!enabled()) return PublishResult.skipped("brain.confluence.enabled=false");
        if (target == null || !target.isComplete()) {
            return PublishResult.skipped("Confluence target missing spaceKey or parentPageId");
        }
        if (doc == null) return PublishResult.skipped("no document supplied");

        boolean sameTarget = doc.getConfluencePageId() != null
                && target.spaceKey().equals(doc.getConfluenceSpaceKey())
                && target.parentPageId().equals(doc.getConfluenceParentPageId());

        if (sameTarget) {
            return updatePage(doc);
        }
        return createPage(doc, target);
    }

    public PublishResult updateExisting(GeneratedDocument doc) {
        if (!enabled()) return PublishResult.skipped("brain.confluence.enabled=false");
        if (doc == null || doc.getConfluencePageId() == null) {
            return PublishResult.skipped("no prior published Confluence page for this doc");
        }
        return updatePage(doc);
    }

    private PublishResult createPage(GeneratedDocument doc, ConfluenceTarget target) {
        try {
            ConfluenceStorageRenderer.RenderedPage rendered = renderer.render(doc.getContentMd());
            String title = effectiveTitle(doc);
            Map<String, Object> seed = client.createPage(target.spaceKey(), target.parentPageId(),
                    title, "<p>Brain documentation — uploading content…</p>");
            String pageId = String.valueOf(seed.get("id"));

            int attachmentsUploaded = uploadAttachments(pageId, rendered.attachments());

            int newVersion = currentVersion(seed) + 1;
            Map<String, Object> updated = client.updatePage(pageId, newVersion, title,
                    rendered.storageBody());

            persist(doc, target, pageId, urlOf(updated));
            return new PublishResult(PublishResult.Status.CREATED, pageId, urlOf(updated),
                    newVersion, attachmentsUploaded, "Confluence page created");
        } catch (RuntimeException e) {
            log.warn("Confluence create failed for doc={}: {}", doc.getId(), e.getMessage());
            return PublishResult.failed("create-page failed: " + e.getMessage());
        }
    }

    private PublishResult updatePage(GeneratedDocument doc) {
        String pageId = doc.getConfluencePageId();
        try {
            ConfluenceStorageRenderer.RenderedPage rendered = renderer.render(doc.getContentMd());
            Optional<Map<String, Object>> current = client.getPage(pageId);
            if (current.isEmpty()) {
                return PublishResult.failed("page not found: " + pageId);
            }
            int newVersion = currentVersion(current.get()) + 1;
            int attachmentsUploaded = uploadAttachments(pageId, rendered.attachments());

            Map<String, Object> updated = client.updatePage(pageId, newVersion,
                    effectiveTitle(doc), rendered.storageBody());

            doc.setConfluencePublishedAt(OffsetDateTime.now());
            doc.setConfluenceUrl(urlOf(updated));
            documentRepository.save(doc);
            return new PublishResult(PublishResult.Status.UPDATED, pageId, urlOf(updated),
                    newVersion, attachmentsUploaded, "Confluence page updated");
        } catch (RuntimeException e) {
            log.warn("Confluence update failed for doc={} page={}: {}",
                    doc.getId(), pageId, e.getMessage());
            return PublishResult.failed("update-page failed: " + e.getMessage());
        }
    }

    private int uploadAttachments(String pageId, Map<String, byte[]> attachments) {
        int count = 0;
        if (attachments == null) return 0;
        for (Map.Entry<String, byte[]> e : attachments.entrySet()) {
            try {
                client.uploadAttachment(pageId, e.getKey(), e.getValue(),
                        contentTypeFor(e.getKey()), "Brain diagram update");
                count++;
            } catch (RuntimeException ex) {
                log.warn("Confluence attachment upload failed for page={} file={}: {}",
                        pageId, e.getKey(), ex.getMessage());
            }
        }
        return count;
    }

    private String contentTypeFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    @SuppressWarnings("unchecked")
    private int currentVersion(Map<String, Object> page) {
        Object versionObj = page.get("version");
        if (versionObj instanceof Map<?, ?> map) {
            Object n = ((Map<String, Object>) map).get("number");
            if (n instanceof Number num) return num.intValue();
        }
        return 1;
    }

    @SuppressWarnings("unchecked")
    private String urlOf(Map<String, Object> page) {
        if (page == null) return null;
        Object id = page.get("id");
        Object links = page.get("_links");
        String base = brainProperties.confluence() == null ? "" : brainProperties.confluence().host();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (links instanceof Map<?, ?> linksMap) {
            Object webui = ((Map<String, Object>) linksMap).get("webui");
            if (webui instanceof String s) return base + "/wiki" + s;
        }
        return id == null ? null : base + "/wiki/spaces/.../pages/" + id;
    }

    private String effectiveTitle(GeneratedDocument doc) {
        String stamp = OffsetDateTime.now().toLocalDate().toString();
        String base = doc.getTitle() == null || doc.getTitle().isBlank()
                ? "Brain documentation" : doc.getTitle();
        return base + " (" + stamp + ")";
    }

    @Transactional
    public void persist(GeneratedDocument doc, ConfluenceTarget target,
                          String pageId, String url) {
        doc.setConfluencePageId(pageId);
        doc.setConfluenceUrl(url);
        doc.setConfluenceSpaceKey(target.spaceKey());
        doc.setConfluenceParentPageId(target.parentPageId());
        doc.setConfluencePublishedAt(OffsetDateTime.now());
        documentRepository.save(doc);
    }

    private boolean enabled() {
        BrainProperties.Confluence c = brainProperties.confluence();
        return c != null && c.enabled();
    }
}
