package com.assurant.brain.bdd.http;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@TestConfiguration
public class BrainHttpClientConfig {

    @Value("${brain.bdd.target:}")
    private String bddTarget;

    @Bean
    public BrainHttpClient brainHttpClient(@Autowired(required = false) MockMvc mvc) {
        if (bddTarget != null && !bddTarget.isBlank()) {
            return new RestTemplateBrainHttpClient(bddTarget);
        }
        if (mvc == null) {
            throw new IllegalStateException(
                    "BrainHttpClient: no MockMvc bean is available in the test context. "
                  + "Either add @AutoConfigureMockMvc to your test, or set "
                  + "-Dbrain.bdd.target=<url> to use live mode.");
        }
        return new MockMvcBrainHttpClient(mvc);
    }
}
