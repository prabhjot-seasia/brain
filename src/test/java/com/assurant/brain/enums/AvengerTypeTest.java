package com.assurant.brain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AvengerType")
class AvengerTypeTest {

    @Test
    @DisplayName("has exactly 11 Avengers")
    void hasElevenAvengers() {
        assertThat(AvengerType.values()).hasSize(11);
    }

    @Test
    @DisplayName("role distribution: 6 workers, 3 service, 2 support")
    void roleDistribution() {
        long workers = Arrays.stream(AvengerType.values()).filter(t -> t.role() == AvengerRole.WORKER).count();
        long service = Arrays.stream(AvengerType.values()).filter(t -> t.role() == AvengerRole.SERVICE).count();
        long support = Arrays.stream(AvengerType.values()).filter(t -> t.role() == AvengerRole.SUPPORT).count();
        assertThat(workers).isEqualTo(6);
        assertThat(service).isEqualTo(3);
        assertThat(support).isEqualTo(2);
    }

    @Test
    @DisplayName("every Avenger has a distinct domain")
    void distinctDomains() {
        long distinct = Arrays.stream(AvengerType.values()).map(AvengerType::domain).distinct().count();
        assertThat(distinct).isEqualTo(11);
    }

    @Test
    @DisplayName("every Avenger has a persona prompt path")
    void personaPromptPath() {
        for (AvengerType type : AvengerType.values()) {
            assertThat(type.personaPromptPath()).isEqualTo("avengers/" + type.name() + ".md");
        }
    }
}
