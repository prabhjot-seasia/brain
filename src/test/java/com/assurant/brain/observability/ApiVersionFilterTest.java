package com.assurant.brain.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("ApiVersionFilter")
class ApiVersionFilterTest {

    private final ApiVersionFilter filter = new ApiVersionFilter();

    @Test
    @DisplayName("adds X-API-Version header to every response")
    void addsVersionHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader(ApiVersionFilter.HEADER_NAME)).isEqualTo("v1");
        verify(chain).doFilter(request, response);
    }
}
