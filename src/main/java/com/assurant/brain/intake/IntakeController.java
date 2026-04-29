package com.assurant.brain.intake;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.IntakeRecordRepository;
import com.assurant.brain.domain.IntakeRecord;
import com.assurant.brain.enums.IntakeSourceType;
import com.assurant.brain.enums.IntakeStatus;
import com.assurant.brain.jira.JiraClient;
import com.assurant.brain.jira.JiraIssueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Log4j2
@RestController
@RequestMapping("/api/v1/intake")
@RequiredArgsConstructor
public class IntakeController {

    private static final String JIRA_KEY_PATTERN = "[A-Z]+-\\d+";

    private final DocumentExtractorService documentExtractorService;
    private final JiraClient jiraClient;
    private final JiraIssueMapper jiraIssueMapper;
    private final IntakeRecordRepository intakeRecordRepository;
    private final BrainProperties brainProperties;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "default") String userId) {

        long maxBytes = brainProperties.intake().maxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "File exceeds maximum size of " + brainProperties.intake().maxFileSizeMb() + " MB"));
        }

        String extractedText = documentExtractorService.extract(file);

        IntakeSourceType sourceType = isImage(file) ? IntakeSourceType.IMAGE_UPLOAD : IntakeSourceType.DOCUMENT_UPLOAD;
        IntakeRecord record = new IntakeRecord();
        record.setSourceType(sourceType);
        record.setSourceReference(file.getOriginalFilename());
        record.setExtractedText(extractedText);
        record.setStatus(IntakeStatus.EXTRACTED);
        intakeRecordRepository.save(record);

        log.info("Document extracted: file={}, type={}, chars={}",
                file.getOriginalFilename(), sourceType, extractedText.length());

        return ResponseEntity.ok(Map.of(
                "intakeId", record.getId().toString(),
                "sourceType", sourceType.name(),
                "fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown",
                "extractedText", extractedText,
                "charCount", extractedText.length()
        ));
    }

    @PostMapping("/adhoc")
    public ResponseEntity<Map<String, Object>> adhoc(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Text is required"));
        }

        IntakeRecord record = new IntakeRecord();
        record.setSourceType(IntakeSourceType.ADHOC_TEXT);
        record.setExtractedText(text);
        record.setStatus(IntakeStatus.EXTRACTED);
        intakeRecordRepository.save(record);

        return ResponseEntity.ok(Map.of(
                "intakeId", record.getId().toString(),
                "sourceType", IntakeSourceType.ADHOC_TEXT.name(),
                "extractedText", text,
                "charCount", text.length()
        ));
    }

    @PostMapping("/jira")
    public ResponseEntity<Map<String, Object>> fetchJiraTicket(@RequestBody Map<String, String> body) {
        String issueKey = body.get("issueKey");
        String userId = body.getOrDefault("userId", "default");

        if (issueKey == null || issueKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "issueKey is required"));
        }

        issueKey = issueKey.trim();
        if (issueKey.startsWith("http")) {
            issueKey = extractKeyFromUrl(issueKey);
        }

        Map<String, Object> issue = jiraClient.getIssue(userId, issueKey);
        String summary = jiraIssueMapper.extractSummary(issue);
        String key = jiraIssueMapper.extractIssueKey(issue);
        var analyzeRequest = jiraIssueMapper.toAnalyzeRequest(issue, null);

        IntakeRecord record = new IntakeRecord();
        record.setSourceType(IntakeSourceType.JIRA_TICKET);
        record.setSourceReference(key);
        record.setExtractedText(analyzeRequest.requirement());
        record.setStatus(IntakeStatus.EXTRACTED);
        intakeRecordRepository.save(record);

        return ResponseEntity.ok(Map.of(
                "intakeId", record.getId().toString(),
                "sourceType", IntakeSourceType.JIRA_TICKET.name(),
                "issueKey", key,
                "summary", summary,
                "extractedText", analyzeRequest.requirement(),
                "charCount", analyzeRequest.requirement().length()
        ));
    }

    private boolean isImage(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType != null && contentType.startsWith("image/");
    }

    private String extractKeyFromUrl(String url) {
        String[] parts = url.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches(JIRA_KEY_PATTERN)) {
                return parts[i];
            }
        }
        return url;
    }
}
