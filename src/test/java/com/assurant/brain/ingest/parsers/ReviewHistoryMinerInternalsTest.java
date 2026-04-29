package com.assurant.brain.ingest.parsers;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.ReviewPatternNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReviewHistoryMiner internals")
class ReviewHistoryMinerInternalsTest {

    private ReviewHistoryMiner miner;

    @BeforeEach
    void setup() {
        var gh = new BrainProperties.GitHub("ghp_real", "https://api.github.com", 3);
        var props = new BrainProperties(null, null, null, null, gh, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new BrainProperties.Docs("mmdc", 60, 0, 0, 5));
        miner = new ReviewHistoryMiner(props);
    }

    @Test
    @DisplayName("normalizePhrase strips backticks, redacts @mentions and emails, collapses whitespace")
    void normalizePhraseRedacts() {
        String out = ReflectionTestUtils.invokeMethod(miner, "normalizePhrase",
                "@alice please   replace `Foo` with `Bar` cc bob@example.com\nsecond line ignored");
        assertThat(out).contains("@user")
                .contains("[redacted-email]")
                .contains("X")
                .doesNotContain("\n")
                .doesNotContain("Foo")
                .doesNotContain("alice");
    }

    @Test
    @DisplayName("normalizePhrase truncates at MAX_PHRASE_LENGTH")
    void normalizePhraseTruncates() {
        String huge = "x".repeat(1000);
        String out = ReflectionTestUtils.invokeMethod(miner, "normalizePhrase", huge);
        assertThat(out).hasSize(200);
    }

    @Test
    @DisplayName("normalizePhrase caps incoming body bytes before splitting on newline")
    void normalizePhraseCaps() {
        String huge = "a".repeat(10_000);
        String out = ReflectionTestUtils.invokeMethod(miner, "normalizePhrase", huge);
        assertThat(out).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("extractOwnerAndRepo handles HTTPS, SSH, and trailing-slash variants")
    void extractOwnerAndRepoMatchesAllVariants() {
        Object o1 = ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo", "https://github.com/foo/bar");
        Object o2 = ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo", "https://github.com/foo/bar.git");
        Object o3 = ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo", "git@github.com:foo/bar.git");
        Object o4 = ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo", "https://github.com/foo/bar/");
        assertThat(o1).isNotNull();
        assertThat(o2).isNotNull();
        assertThat(o3).isNotNull();
        assertThat(o4).isNotNull();

        Object none = ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo", "https://gitlab.com/foo/bar");
        assertThat(none).isNull();
    }

    @Test
    @DisplayName("attachPatterns adds new patterns and ignores duplicates by id")
    void attachPatternsDeduplicates() {
        ProjectNode pn = new ProjectNode();
        pn.setReviewPatterns(new ArrayList<>());

        ReviewPatternNode existing = new ReviewPatternNode();
        existing.setId("ce-imei:reviewPattern:abc");
        pn.getReviewPatterns().add(existing);

        ReviewPatternNode dup = new ReviewPatternNode();
        dup.setId("ce-imei:reviewPattern:abc");
        ReviewPatternNode novel = new ReviewPatternNode();
        novel.setId("ce-imei:reviewPattern:xyz");

        ReflectionTestUtils.invokeMethod(miner, "attachPatterns", pn, List.of(dup, novel));

        assertThat(pn.getReviewPatterns()).hasSize(2);
        assertThat(pn.getReviewPatterns()).extracting(ReviewPatternNode::getId)
                .containsExactlyInAnyOrder("ce-imei:reviewPattern:abc", "ce-imei:reviewPattern:xyz");
    }

    @Test
    @DisplayName("hashReviewer returns deterministic 'reviewer:<hex>' shape")
    void hashReviewerStable() {
        Object h1 = ReflectionTestUtils.invokeMethod(miner, "hashReviewer", "alice");
        Object h2 = ReflectionTestUtils.invokeMethod(miner, "hashReviewer", "alice");
        Object h3 = ReflectionTestUtils.invokeMethod(miner, "hashReviewer", "bob");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
        assertThat((String) h1).startsWith("reviewer:");
    }
}
