package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.graph.node.IncidentNode;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.guardrail.Rail;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Log4j2
@Component
@RequiredArgsConstructor
public class ServiceHealthRail implements Rail {

    public static final String METADATA_AFFECTED_SERVICES = "affectedServices";
    public static final Set<String> ACTIVE_STATUSES = Set.of("OPEN", "ACTIVE", "INVESTIGATING");
    private static final int PRIORITY = 5;
    private static final int MAX_INCIDENTS_LISTED = 5;

    private final IncidentNodeRepository incidentNodeRepository;

    @Override
    public RailResult apply(RailContext context) {
        if (context.projectId() == null || context.projectId().isBlank()) return RailResult.pass(type());

        Object meta = context.metadata() == null ? null : context.metadata().get(METADATA_AFFECTED_SERVICES);
        if (!(meta instanceof List<?> services) || services.isEmpty()) return RailResult.pass(type());

        List<IncidentNode> matches = findActiveIncidents(context.projectId(), services);
        if (matches.isEmpty()) return RailResult.pass(type());

        List<String> reasons = new ArrayList<>();
        reasons.add("Plan blocked — affected service(s) currently in active incident:");
        matches.stream().limit(MAX_INCIDENTS_LISTED).forEach(i ->
                reasons.add("- [" + (i.getSeverity() == null ? "INCIDENT" : i.getSeverity())
                        + " / " + (i.getStatus() == null ? "OPEN" : i.getStatus())
                        + "] " + i.getTitle()));
        reasons.add("Resolve the incident or explicitly override before generating changes.");
        return RailResult.block(type(), reasons);
    }

    private List<IncidentNode> findActiveIncidents(String projectId, List<?> services) {
        List<IncidentNode> all = new ArrayList<>();
        for (Object service : services) {
            if (service == null) continue;
            String name = service.toString();
            if (name.isBlank()) continue;
            try {
                incidentNodeRepository.findByProjectIdAndClassHintMatch(projectId, name).stream()
                        .filter(i -> i.getStatus() == null || ACTIVE_STATUSES.contains(i.getStatus().toUpperCase()))
                        .forEach(all::add);
            } catch (RuntimeException e) {
                log.debug("ServiceHealthRail incident lookup failed for project={} service={}: {}",
                        projectId, name, e.getMessage());
            }
        }
        return all;
    }

    @Override
    public RailPhase phase() { return RailPhase.PRE_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.SERVICE_HEALTH; }
}
