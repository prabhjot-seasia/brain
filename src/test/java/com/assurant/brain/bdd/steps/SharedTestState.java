package com.assurant.brain.bdd.steps;

import org.springframework.stereotype.Component;

@Component
public class SharedTestState {

    private String capturedSessionId;
    private int responseStatus;
    private String responseBody;

    public String getCapturedSessionId() { return capturedSessionId; }
    public void setCapturedSessionId(String capturedSessionId) { this.capturedSessionId = capturedSessionId; }

    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public void reset() {
        capturedSessionId = null;
        responseStatus = 0;
        responseBody = null;
    }
}
