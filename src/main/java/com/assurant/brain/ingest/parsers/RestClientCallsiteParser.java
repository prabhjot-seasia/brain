package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.ServiceNode;
import com.assurant.brain.ingest.JavaAstContext;
import com.assurant.brain.ingest.JavaAstVisitor;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
public class RestClientCallsiteParser implements JavaAstVisitor {

    private static final List<String> REST_TEMPLATE_METHODS = List.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity",
            "exchange", "execute", "delete", "put");
    private static final List<String> WEBCLIENT_BUILDER_METHODS = List.of("baseUrl", "uri", "get", "post");

    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("\\$\\{([^.}]+)(?:\\.[^}]+)?}");
    private static final Pattern URL_LITERAL = Pattern.compile(
            "(?i)https?://([a-z0-9.-]+)(?::\\d+)?(/[^\"\\s]*)?");
    private static final Pattern HOSTNAME_SEGMENT = Pattern.compile("(?i)([a-z][a-z0-9-]+)");

    @Override
    public String name() {
        return "RestClientCallsiteParser";
    }

    @Override
    public void visit(JavaAstContext context) {
        Set<String> existing = new HashSet<>();
        context.projectNode().getCalledServices().forEach(s -> existing.add(s.getId()));

        context.cu().findAll(AnnotationExpr.class).forEach(a -> handleAnnotation(a, context, existing));
        context.cu().findAll(MethodCallExpr.class).forEach(m -> handleMethodCall(m, context, existing));
    }

    private void handleAnnotation(AnnotationExpr annotation, JavaAstContext context, Set<String> existing) {
        if (!"FeignClient".equals(annotation.getNameAsString())) return;
        String value = extractAnnotationValue(annotation, List.of("name", "value"));
        if (StringUtils.isBlank(value)) return;
        addService(context.projectNode(), context.projectId(), value, "FEIGN_CLIENT", null, existing);
    }

    private void handleMethodCall(MethodCallExpr call, JavaAstContext context, Set<String> existing) {
        String methodName = call.getNameAsString();
        if (REST_TEMPLATE_METHODS.contains(methodName) || WEBCLIENT_BUILDER_METHODS.contains(methodName)) {
            call.getArguments().stream()
                    .map(Object::toString)
                    .forEach(arg -> extractFromArgument(arg, context, existing));
        }
    }

    private void extractFromArgument(String argument, JavaAstContext context, Set<String> existing) {
        Matcher placeholder = PLACEHOLDER_NAME.matcher(argument);
        if (placeholder.find()) {
            String name = placeholder.group(1);
            addService(context.projectNode(), context.projectId(), name, "REST_PLACEHOLDER", argument, existing);
        }
        Matcher url = URL_LITERAL.matcher(argument);
        if (url.find()) {
            String host = url.group(1);
            String path = StringUtils.defaultString(url.group(2));
            String name = inferNameFromHostOrPath(host, path);
            if (StringUtils.isNotBlank(name)) {
                addService(context.projectNode(), context.projectId(), name, "REST_LITERAL",
                        url.group(0), existing);
            }
        }
    }

    private String inferNameFromHostOrPath(String host, String path) {
        if (StringUtils.isNotBlank(path) && path.length() > 1) {
            String[] segments = path.split("/");
            for (String segment : segments) {
                if (StringUtils.isBlank(segment)) continue;
                Matcher m = HOSTNAME_SEGMENT.matcher(segment);
                if (m.find()) return m.group(1);
            }
        }
        if (StringUtils.isNotBlank(host)) {
            String[] parts = host.split("\\.");
            if (parts.length > 0) return parts[0];
        }
        return "";
    }

    private String extractAnnotationValue(AnnotationExpr annotation, List<String> attrs) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return stripQuotes(single.getMemberValue().toString());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (attrs.contains(pair.getNameAsString())) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    private String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void addService(ProjectNode projectNode, String projectId, String rawName, String source,
                            String baseUrlTemplate, Set<String> existing) {
        String name = StringUtils.lowerCase(rawName).replaceAll("[^a-z0-9-]", "");
        if (name.isEmpty()) return;
        String id = projectId + ":service:" + name;
        if (existing.contains(id)) return;
        if (projectNode.getCalledServices().stream().anyMatch(s -> id.equals(s.getId()))) return;

        ServiceNode service = new ServiceNode();
        service.setId(id);
        service.setName(name);
        service.setBaseUrlTemplate(baseUrlTemplate);
        service.setSource(source);
        projectNode.getCalledServices().add(service);
        existing.add(id);
    }
}
