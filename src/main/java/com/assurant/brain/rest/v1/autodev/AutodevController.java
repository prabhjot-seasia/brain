package com.assurant.brain.rest.v1.autodev;

import com.assurant.brain.dto.request.AutodevClarifyRequest;
import com.assurant.brain.dto.request.AutodevCreatePrsRequest;
import com.assurant.brain.dto.request.AutodevExecuteRequest;
import com.assurant.brain.dto.request.AutodevPlanRequest;
import com.assurant.brain.dto.request.AutodevStartRequest;
import com.assurant.brain.dto.response.AutodevExecuteResponse;
import com.assurant.brain.dto.response.AutodevPlanResponse;
import com.assurant.brain.dto.response.AutodevSessionResponse;
import com.assurant.brain.dao.PrBatchRepository;
import com.assurant.brain.domain.PrBatch;
import com.assurant.brain.facade.autodev.AutodevFacade;
import com.assurant.brain.facade.autodev.MultiRepoPrResult;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.jobs.dto.JobStartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/autodev")
@RequiredArgsConstructor
public class AutodevController {

    private final AutodevFacade autodevFacade;
    private final PrBatchRepository prBatchRepository;
    private final AsyncJobService asyncJobService;

    @PostMapping("/start")
    public ResponseEntity<AutodevSessionResponse> start(@RequestBody AutodevStartRequest req) {
        return ResponseEntity.ok(autodevFacade.start(
                req.source(), req.payload(), req.intakeId(), req.seedProjectId()));
    }

    @PostMapping("/clarify")
    public ResponseEntity<AutodevSessionResponse> clarify(@RequestBody AutodevClarifyRequest req) {
        return ResponseEntity.ok(autodevFacade.clarify(req.sessionId(), req.answers()));
    }

    @PostMapping("/plan")
    public ResponseEntity<AutodevPlanResponse> plan(@RequestBody AutodevPlanRequest req) {
        return ResponseEntity.ok(autodevFacade.plan(req.sessionId()));
    }

    @PostMapping("/execute")
    public ResponseEntity<AutodevExecuteResponse> execute(@RequestBody AutodevExecuteRequest req) {
        return ResponseEntity.ok(autodevFacade.execute(req.sessionId(), req.approvedProjectIds()));
    }

    @PostMapping("/execute/start")
    public ResponseEntity<JobStartResponse> executeAsync(@RequestBody AutodevExecuteRequest req) {
        AsyncJob job = asyncJobService.startOrAttach(
                "AUTODEV_PIPELINE", "SESSION", req.sessionId(), null);
        if (!job.attachedToExisting()) {
            autodevFacade.executeAsync(req.sessionId(), req.approvedProjectIds(), job.id());
        }
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobStartResponse.from(job));
    }

    @PostMapping("/create-prs")
    public ResponseEntity<MultiRepoPrResult> createPrs(@RequestBody AutodevCreatePrsRequest req) {
        return ResponseEntity.ok(autodevFacade.createPrs(req.sessionId(), req.repos()));
    }

    @PostMapping("/create-prs/start")
    public ResponseEntity<JobStartResponse> createPrsAsync(@RequestBody AutodevCreatePrsRequest req) {
        AsyncJob job = asyncJobService.startOrAttach(
                "MULTI_REPO_PR", "SESSION", req.sessionId(), null);
        if (!job.attachedToExisting()) {
            autodevFacade.createPrsAsync(req.sessionId(), req.repos(), job.id());
        }
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobStartResponse.from(job));
    }

    @GetMapping("/batch/{batchId}")
    public ResponseEntity<PrBatch> getBatch(@PathVariable String batchId) {
        return prBatchRepository.findById(UUID.fromString(batchId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
