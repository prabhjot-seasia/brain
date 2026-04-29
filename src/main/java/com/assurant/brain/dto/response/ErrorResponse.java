package com.assurant.brain.dto.response;

import lombok.Getter;
import lombok.ToString;

import java.time.OffsetDateTime;

@Getter
@ToString
public class ErrorResponse {

    private final int code;
    private final String message;
    private final OffsetDateTime timestamp;

    private ErrorResponse(int code, String message) {
        this.code = code;
        this.message = message;
        this.timestamp = OffsetDateTime.now();
    }

    public static ErrorResponse of(int code, String message) {
        return new ErrorResponse(code, message);
    }
}
