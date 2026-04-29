package com.assurant.brain.avenger;

import com.assurant.brain.enums.AvengerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AvengerPersonaLoader")
class AvengerPersonaLoaderTest {

    private AvengerPersonaLoader loader;

    @BeforeEach
    void setup() {
        loader = new AvengerPersonaLoader();
        loader.loadAll();
    }

    @Test
    @DisplayName("loads all 11 personas from classpath at startup")
    void loadsAllPersonas() {
        for (AvengerType type : AvengerType.values()) {
            assertThat(loader.load(type)).isNotBlank();
        }
    }

    @Test
    @DisplayName("loaded persona contains Output Contract section")
    void personaHasOutputContract() {
        for (AvengerType type : AvengerType.values()) {
            assertThat(loader.load(type)).contains("Output Contract");
        }
    }

    @Test
    @DisplayName("loaded persona starts with Avenger name header")
    void personaHasHeader() {
        String stark = loader.load(AvengerType.STARK);
        assertThat(stark).contains("STARK");
    }
}
