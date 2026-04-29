package com.assurant.brain.errorhandler;

import com.assurant.brain.exceptions.IngestionException;
import com.assurant.brain.exceptions.ProjectNotFoundException;
import com.assurant.brain.exceptions.SessionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("ProjectNotFoundException → 404")
    void projectNotFound() {
        var response = handler.handleProjectNotFound(new ProjectNotFoundException("proj-1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("SessionNotFoundException → 404")
    void sessionNotFound() {
        var response = handler.handleSessionNotFound(new SessionNotFoundException("sess-1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("IngestionException → 500")
    void ingestionError() {
        var response = handler.handleIngestionError(new IngestionException("clone failed"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("clone failed");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException → 400")
    void unreadableBody() {
        var ex = new HttpMessageNotReadableException("bad json",
                new MockHttpInputMessage(new byte[0]));
        var response = handler.handleUnreadable(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException → 415")
    void unsupportedMediaType() {
        var ex = new HttpMediaTypeNotSupportedException("text/plain not supported");
        var response = handler.handleMediaType(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("Generic Exception → 500")
    void genericException() {
        var response = handler.handleGeneral(new RuntimeException("unexpected"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
