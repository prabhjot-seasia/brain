package com.assurant.brain.bdd.http;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

public class MockMvcBrainHttpClient implements BrainHttpClient {

    private final MockMvc mvc;

    public MockMvcBrainHttpClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Override
    public BrainHttpResponse get(String path) {
        return get(path, Map.of());
    }

    @Override
    public BrainHttpResponse get(String path, Map<String, String> queryParams) {
        try {
            MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.get(path)
                    .accept(MediaType.APPLICATION_JSON);
            queryParams.forEach(builder::param);
            return wrap(mvc.perform(builder).andReturn());
        } catch (Exception e) {
            throw new RuntimeException("MockMvc GET " + path + " failed", e);
        }
    }

    @Override
    public BrainHttpResponse postJson(String path, String jsonBody) {
        try {
            return wrap(mvc.perform(MockMvcRequestBuilders.post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonBody)).andReturn());
        } catch (Exception e) {
            throw new RuntimeException("MockMvc POST " + path + " failed", e);
        }
    }

    private BrainHttpResponse wrap(MvcResult result) throws Exception {
        return new BrainHttpResponse(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }
}
