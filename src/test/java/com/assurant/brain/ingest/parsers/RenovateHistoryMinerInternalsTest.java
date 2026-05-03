package com.assurant.brain.ingest.parsers;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.ingest.IngestDocumentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("RenovateHistoryMiner internals")
class RenovateHistoryMinerInternalsTest {

    private RenovateHistoryMiner miner;

    @BeforeEach
    void setup() {
        var gh = new BrainProperties.GitHub("ghp_real", "https://api.github.com", 3);
        var props = new BrainProperties(null, null, null, null, gh, null, null, null, null, null, null, null, null, null, null, null, null, null, new BrainProperties.Docs("mmdc", 60, 0, 0, 5), null);
        miner = new RenovateHistoryMiner(props, mock(IngestDocumentFactory.class));
    }

    @Test
    @DisplayName("extractOwnerAndRepo handles HTTPS, SSH, .git suffix variants")
    void ownerRepoVariants() {
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo",
                "https://github.com/foo/bar")).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo",
                "https://github.com/foo/bar.git")).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo",
                "git@github.com:foo/bar.git")).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo",
                "https://gitlab.com/foo/bar")).isNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo",
                "")).isNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "extractOwnerAndRepo",
                (Object) null)).isNull();
    }

    @Test
    @DisplayName("parseDate parses ISO-8601 and returns null for malformed/blank input")
    void parseDateBranches() {
        Object good = ReflectionTestUtils.invokeMethod(miner, "parseDate", "2026-04-15T12:00:00Z");
        assertThat(good).isNotNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "parseDate", "")).isNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "parseDate", (Object) null)).isNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(miner, "parseDate", "garbage")).isNull();
    }
}
