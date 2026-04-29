package com.assurant.brain.config;

import com.assurant.brain.enums.AvengerType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class AvengerTypeConverter implements Converter<String, AvengerType> {

    @Override
    public AvengerType convert(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("AvengerType cannot be blank");
        }
        return AvengerType.valueOf(source.toUpperCase());
    }
}
