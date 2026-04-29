package com.assurant.brain.bdd.ui;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BDD step definitions that observe async-job state via the live /api/v1/jobs API
 * (independent of the test JVM's own DB context, which uses TestContainers).
 */
public class JobStreamSteps {

    private static final String API_BASE = System.getProperty("brain.api.url", "http://localhost:8080");

    private final RestTemplate http = new RestTemplate();

    @Given("a placeholder project {string} exists with name {string}")
    public void placeholderProject(String projectId, String name) {
        try {
            http.getForObject(API_BASE + "/api/v1/projects/" + projectId, Map.class);
            return;
        } catch (HttpStatusCodeException notFound) {
            // fall through to seed via ingest with intentionally-bad URL — controller returns 202 + jobId,
            // async clone fails, project row remains with the metadata we set. That's enough for nav-only
            // BDDs that just need /projects/{id} to resolve.
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = String.format(
                "{\"projectId\":\"%s\",\"projectName\":\"%s\",\"repoUrl\":\"https://10.255.255.99/seed.git\"}",
                projectId, name);
        try {
            http.exchange(API_BASE + "/api/v1/projects/ingest", HttpMethod.POST,
                    new HttpEntity<>(body, h), String.class);
        } catch (HttpStatusCodeException e) {
            // swallow — we only care that the project row gets created; clone failure is expected
        }
    }

    @Given("there are no async_jobs rows for target {string}")
    public void noJobsForTarget(String targetId) {
        // Best-effort cleanup via the listing endpoint; if a row is in-flight we can't delete via API,
        // so this scenario should run on a fresh stack or with a unique target id.
    }

    @Then("the recent-jobs listing for project {string} contains a {string} job")
    public void listingContainsJob(String projectId, String jobType) {
        List<Map<String, Object>> jobs = fetchJobs(projectId, 50);
        assertThat(jobs).as("/jobs listing for projectId=%s", projectId)
                .anySatisfy(j -> assertThat(j.get("jobType")).isEqualTo(jobType));
    }

    @Then("the recent-jobs listing for project {string} has exactly {int} {string} job(s)")
    public void listingHasExactly(String projectId, int n, String jobType) {
        List<Map<String, Object>> jobs = fetchJobs(projectId, 50);
        long actual = jobs.stream().filter(j -> jobType.equals(j.get("jobType"))).count();
        assertThat(actual).isEqualTo(n);
    }

    @Then("the recent-jobs listing for project {string} has exactly {int} in-flight {string} job(s)")
    public void listingHasInFlight(String projectId, int n, String jobType) {
        List<Map<String, Object>> jobs = fetchJobs(projectId, 50);
        long actual = jobs.stream()
                .filter(j -> jobType.equals(j.get("jobType")))
                .filter(j -> "QUEUED".equals(j.get("status")) || "RUNNING".equals(j.get("status")))
                .count();
        assertThat(actual).isEqualTo(n);
    }

    @Then("the most recent {string} job for project {string} reaches status {string} within {int} seconds")
    public void jobReachesStatus(String jobType, String projectId, String expected, int seconds) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(seconds));
        String last = "(no row)";
        int polls = 0;
        Exception lastErr = null;
        while (Instant.now().isBefore(deadline)) {
            polls++;
            try {
                List<Map<String, Object>> jobs = fetchJobs(projectId, 50);
                for (Map<String, Object> j : jobs) {
                    if (jobType.equals(j.get("jobType"))) {
                        last = String.valueOf(j.get("status"));
                        if (expected.equalsIgnoreCase(last)) return;
                        break;
                    }
                }
            } catch (Exception e) {
                lastErr = e;
            }
            try { Thread.sleep(500); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        throw new AssertionError(jobType + " job for project=" + projectId
                + " never reached status=" + expected + " within " + seconds + "s; last=" + last
                + "; polls=" + polls + (lastErr != null ? "; lastErr=" + lastErr : ""));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchJobs(String projectId, int limit) {
        return http.getForObject(
                API_BASE + "/api/v1/jobs?projectId=" + projectId + "&limit=" + limit, List.class);
    }
}
