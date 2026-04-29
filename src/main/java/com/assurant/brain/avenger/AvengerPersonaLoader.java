package com.assurant.brain.avenger;

import com.assurant.brain.enums.AvengerType;
import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

@Log4j2
@Component
public class AvengerPersonaLoader {

    private final Map<AvengerType, String> personaCache = new EnumMap<>(AvengerType.class);

    @PostConstruct
    void loadAll() {
        for (AvengerType type : AvengerType.values()) {
            personaCache.put(type, loadPersona(type));
        }
        log.info("Loaded {} Avenger personas", personaCache.size());
    }

    public String load(AvengerType type) {
        String cached = personaCache.get(type);
        if (cached == null) {
            throw new IllegalStateException("Persona not loaded for " + type);
        }
        return cached;
    }

    private String loadPersona(AvengerType type) {
        ClassPathResource resource = new ClassPathResource(type.personaPromptPath());
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load persona for " + type + " from " + type.personaPromptPath(), e);
        }
    }
}
