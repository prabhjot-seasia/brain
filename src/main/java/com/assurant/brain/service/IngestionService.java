package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ChunkRepository;
import com.assurant.brain.enums.IngestionStatus;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.Project;
import com.assurant.brain.dto.request.IngestManifest;
import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.enums.SourceType;
import com.assurant.brain.graph.CrossRepoEdgeBuilder;
import com.assurant.brain.graph.node.ClassNode;
import com.assurant.brain.graph.node.LibraryNode;
import com.assurant.brain.graph.node.ModuleNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.JavaAstContext;
import com.assurant.brain.ingest.JavaAstVisitor;
import com.assurant.brain.ingest.ManifestProcessor;
import com.assurant.brain.ingest.ParseResult;
import com.assurant.brain.ingest.RepoKind;
import com.assurant.brain.ingest.RepoKindClassifier;
import com.assurant.brain.jobs.AsyncJobService;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Log4j2
@Service("ingestionService")
@RequiredArgsConstructor
public class IngestionService {

    private double docTrustWeight()  { return brainProperties.rag() != null ? brainProperties.rag().docTrustWeight()  : 1.5; }
    private double codeTrustWeight() { return brainProperties.rag() != null ? brainProperties.rag().codeTrustWeight() : 1.0; }
    private static final Set<String> DOC_EXTENSIONS =
            Set.of(".md", ".txt", ".rst", ".yaml", ".yml", ".json", ".html");

    private final ProjectRepository    projectRepository;
    private final ChunkRepository      chunkRepository;
    private final ProjectNodeRepository projectNodeRepository;
    private final CrossRepoEdgeBuilder crossRepoEdgeBuilder;
    private final CallGraphExtractor   callGraphExtractor;
    private final VectorStore          vectorStore;
    private final BrainProperties      brainProperties;
    private final RepoKindClassifier   repoKindClassifier;
    private final List<ArtifactParser> artifactParsers;
    private final List<JavaAstVisitor> javaAstVisitors;
    private final ManifestProcessor    manifestProcessor;
    private final com.assurant.brain.retrieval.CommunitySummarizer communitySummarizer;
    private final AsyncJobService asyncJobService;
    private final GitCloneService gitCloneService;
    private final ProjectDetector projectDetector;

    @Async
    public void cloneAndIngestAsync(com.assurant.brain.dto.request.IngestRequest request, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId, "Cloning " + request.repoUrl());
            GitCloneService.CloneResult clone = gitCloneService.clone(
                    request.repoUrl(), request.effectiveBranch());
            asyncJobService.updateProgress(jobId, 5, "Detecting project metadata");
            ProjectDetector.DetectedMetadata detected = projectDetector.detect(clone.directory());

            Project project = projectRepository.findById(request.projectId()).orElseGet(Project::new);
            project.setId(request.projectId());
            project.setName(request.projectName());
            project.setRepoUrl(request.repoUrl());
            project.setBranch(request.effectiveBranch());
            project.setCommitSha(clone.commitSha());
            project.setLanguage(detected.language());
            project.setFramework(detected.framework());
            project.setBuildTool(detected.buildTool());
            project.setDescription(request.description());
            projectRepository.save(project);

            ingestProject(project, clone.directory(), request.effectiveManifest(), jobId);
        } catch (Exception e) {
            log.error("Clone-and-ingest job={} failed before ingest started: {}", jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Async
    public void ingestProject(Project project, Path projectPath) {
        ingestProject(project, projectPath, IngestManifest.empty(), null);
    }

    @Async
    public void ingestProject(Project project, Path projectPath, IngestManifest manifest) {
        ingestProject(project, projectPath, manifest, null);
    }

    @Async
    public void ingestProject(Project project, Path projectPath, IngestManifest manifest, UUID jobId) {
        log.info("Starting ingestion for project={} jobId={} manifest={{environments={}, tenants={}, apiContractRegistry={}, cloudConfig={}}}",
                project.getId(),
                jobId,
                manifest.environments().size(),
                manifest.tenants().size(),
                manifest.apiContractRegistry() != null,
                manifest.cloudConfig() != null);
        if (jobId != null) {
            asyncJobService.markRunning(jobId, "Ingesting " + project.getId());
        }
        try {
            boolean hasExistingChunks = chunkRepository.countByProjectId(project.getId()) > 0;
            if (jobId != null) {
                asyncJobService.updateProgress(jobId, 10,
                        hasExistingChunks ? "Re-ingesting (incremental)" : "Ingesting (full)");
            }
            if (hasExistingChunks) {
                runIncrementalIngestion(project, projectPath, manifest, jobId);
            } else {
                runFullIngestion(project, projectPath, manifest, jobId);
            }
            if (jobId != null) {
                asyncJobService.markSucceeded(jobId, java.util.Map.of(
                        "projectId", project.getId(),
                        "commitSha", project.getCommitSha() == null ? "" : project.getCommitSha()));
            }
        } catch (Exception e) {
            handleIngestionFailure(project, e);
            if (jobId != null) {
                asyncJobService.markFailed(jobId, resolveErrorMessage(e));
            }
        } finally {
            deleteTempDir(projectPath);
        }
    }

    private void runFullIngestion(Project project, Path projectPath, IngestManifest manifest, UUID jobId) {
        project.setIngestionStatus(IngestionStatus.INGESTING);
        project.setIngestionError(null);
        projectRepository.save(project);
        chunkRepository.deleteByProjectId(project.getId());

        ProjectNode projectNode = buildProjectNode(project);
        List<Document> allDocuments = new ArrayList<>();
        Map<ClassNode, Set<String>> pendingCalls = new HashMap<>();

        RepoKind kind = classifyAndApply(project, projectPath, projectNode);
        walkAndParse(projectPath, project.getId(), projectNode, allDocuments, pendingCalls);
        callGraphExtractor.wireCalls(projectNode, pendingCalls);
        runArtifactParsers(project, projectPath, projectNode, kind, allDocuments, manifest);
        applyManifest(projectNode, manifest);

        if (!allDocuments.isEmpty()) {
            log.info("Embedding {} documents for project={}", allDocuments.size(), project.getId());
            vectorStore.add(allDocuments);
        }

        projectNodeRepository.save(projectNode);
        rebuildCrossRepoEdgesQuietly(project.getId(), jobId);
        project.setIngestionStatus(IngestionStatus.COMPLETE);
        project.setLastIngested(OffsetDateTime.now());
        projectRepository.save(project);

        log.info("Full ingestion complete for project={}, chunks={}",
                project.getId(), chunkRepository.countByProjectId(project.getId()));
    }

    private void runIncrementalIngestion(Project project, Path projectPath, IngestManifest manifest, UUID jobId) {
        project.setIngestionStatus(IngestionStatus.INGESTING);
        project.setIngestionError(null);
        projectRepository.save(project);

        Map<String, String> existingHashes = loadExistingHashes(project.getId());

        ProjectNode projectNode = buildProjectNode(project);
        List<Document> toEmbed = new ArrayList<>();
        Set<String> currentFilePaths = new HashSet<>();
        Map<ClassNode, Set<String>> pendingCalls = new HashMap<>();

        RepoKind kind = classifyAndApply(project, projectPath, projectNode);

        try (Stream<Path> files = Files.walk(projectPath)) {
            files.filter(Files::isRegularFile)
                 .filter(file -> !isSkippablePath(file))
                 .forEach(file -> {
                String relativePath = projectPath.relativize(file).toString();
                currentFilePaths.add(relativePath);
                String fileName = file.getFileName().toString();

                if (isPomFile(fileName)) {
                    parsePomFile(file, projectPath, project.getId(), projectNode);
                    return;
                }
                if (isPackageJsonFile(fileName)) {
                    parsePackageJsonFile(file, projectPath, project.getId(), projectNode);
                    return;
                }

                String fileHash = hashFileContent(file);
                if (fileHash.equals(existingHashes.get(relativePath))) {
                    return;
                }

                if (fileName.endsWith(".java")) {
                    parseJavaFile(file, project.getId(), projectNode, toEmbed, pendingCalls);
                } else if (isDocFile(fileName)) {
                    parseDocFile(file, project.getId(), toEmbed);
                }
            });
        } catch (Exception e) {
            log.error("Error walking project path for project={}", project.getId(), e);
        }

        callGraphExtractor.wireCalls(projectNode, pendingCalls);
        runArtifactParsers(project, projectPath, projectNode, kind, toEmbed, manifest);
        applyManifest(projectNode, manifest);

        List<String> deletedPaths = existingHashes.keySet().stream()
                .filter(path -> !currentFilePaths.contains(path))
                .toList();

        List<String> modifiedPaths = currentFilePaths.stream()
                .filter(path -> existingHashes.containsKey(path)
                        && !existingHashes.get(path).equals(hashFileContentByRelativePath(projectPath, path)))
                .toList();

        List<String> pathsToDelete = new ArrayList<>(deletedPaths);
        pathsToDelete.addAll(modifiedPaths);

        if (!pathsToDelete.isEmpty()) {
            chunkRepository.deleteByProjectIdAndFilePaths(project.getId(), pathsToDelete);
            log.info("Deleted chunks for {} files (project={})", pathsToDelete.size(), project.getId());
        }

        if (!toEmbed.isEmpty()) {
            log.info("Embedding {} changed documents for project={}", toEmbed.size(), project.getId());
            vectorStore.add(toEmbed);
        } else {
            log.info("No changes detected for project={}", project.getId());
        }

        projectNodeRepository.save(projectNode);
        rebuildCrossRepoEdgesQuietly(project.getId(), jobId);
        project.setIngestionStatus(IngestionStatus.COMPLETE);
        project.setLastIngested(OffsetDateTime.now());
        projectRepository.save(project);

        log.info("Incremental ingestion complete for project={}, embedded={}, deleted={}, unchanged={}",
                project.getId(), toEmbed.size(), pathsToDelete.size(),
                currentFilePaths.size() - toEmbed.size());
    }

    private Map<String, String> loadExistingHashes(String projectId) {
        Map<String, String> hashes = new HashMap<>();
        for (Object[] row : chunkRepository.findContentHashesByProjectId(projectId)) {
            if (row[0] != null && row[1] != null) {
                hashes.put(row[0].toString(), row[1].toString());
            }
        }
        return hashes;
    }

    private String hashFileContent(Path file) {
        try {
            return sha256(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    private String hashFileContentByRelativePath(Path root, String relativePath) {
        return hashFileContent(root.resolve(relativePath));
    }

    private void walkAndParse(Path projectPath, String projectId,
                               ProjectNode projectNode, List<Document> documents,
                               Map<ClassNode, Set<String>> pendingCalls) {
        try (Stream<Path> files = Files.walk(projectPath)) {
            files.filter(Files::isRegularFile)
                 .filter(file -> !isSkippablePath(file))
                 .forEach(file -> {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".java")) {
                    parseJavaFile(file, projectId, projectNode, documents, pendingCalls);
                } else if (isPomFile(fileName)) {
                    parsePomFile(file, projectPath, projectId, projectNode);
                } else if (isPackageJsonFile(fileName)) {
                    parsePackageJsonFile(file, projectPath, projectId, projectNode);
                } else if (isDocFile(fileName)) {
                    parseDocFile(file, projectId, documents);
                }
            });
        } catch (Exception e) {
            log.error("Error walking project path for project={}", projectId, e);
        }
    }

    private RepoKind classifyAndApply(Project project, Path projectPath, ProjectNode projectNode) {
        RepoKind kind = repoKindClassifier.classify(projectPath, project.getName());
        projectNode.setKind(kind.name());
        log.info("RepoKindClassifier classified project={} as kind={}", project.getId(), kind);
        return kind;
    }

    private void runArtifactParsers(Project project, Path projectPath, ProjectNode projectNode,
                                     RepoKind kind, List<Document> documents, IngestManifest manifest) {
        if (artifactParsers == null || artifactParsers.isEmpty()) return;
        IngestionContext context = new IngestionContext(
                project.getId(), projectPath, project.getRepoUrl(), projectNode, kind, documents, manifest);
        for (ArtifactParser parser : artifactParsers) {
            try {
                if (!parser.supports(context)) continue;
                ParseResult result = parser.parse(context);
                log.info("Parser {} completed for project={} stats={} gaps={}",
                        parser.name(), project.getId(), result.stats(), result.detectedGaps().size());
            } catch (RuntimeException e) {
                log.warn("Parser {} failed for project={}: {}",
                        parser.name(), project.getId(), e.getMessage());
            }
        }
    }

    private void applyManifest(ProjectNode projectNode, IngestManifest manifest) {
        if (manifest == null || manifest.isEmpty()) return;
        manifestProcessor.apply(projectNode, manifest);
    }

    private void rebuildCrossRepoEdgesQuietly(String projectId) {
        rebuildCrossRepoEdgesQuietly(projectId, null);
    }

    private void rebuildCrossRepoEdgesQuietly(String projectId, UUID jobId) {
        try {
            crossRepoEdgeBuilder.rebuildForProject(projectId);
        } catch (Exception e) {
            log.warn("Cross-repo DEPENDS_ON rebuild failed for project={} — ingestion will still " +
                    "complete; edges may be stale until the next ingest. Cause: {}",
                    projectId, e.getMessage());
        }
        try {
            crossRepoEdgeBuilder.rebuildServiceLinkages(projectId);
        } catch (Exception e) {
            log.warn("Service inference rebuild failed for project={} — services may stay unlinked " +
                    "until the next ingest. Cause: {}",
                    projectId, e.getMessage());
        }
        if (jobId != null) {
            asyncJobService.updateProgress(jobId, 80, "Summarizing communities");
        }
        rebuildCommunitySummariesQuietly(projectId, jobId);
    }

    private void rebuildCommunitySummariesQuietly(String projectId) {
        rebuildCommunitySummariesQuietly(projectId, null);
    }

    private void rebuildCommunitySummariesQuietly(String projectId, UUID jobId) {
        try {
            ProjectNode project = projectNodeRepository.findById(projectId).orElse(null);
            if (project == null) return;
            List<String> classFqns = new ArrayList<>();
            if (project.getModules() != null) {
                project.getModules().forEach(m -> {
                    if (m.getClasses() != null) m.getClasses().forEach(c -> {
                        if (c.getQualifiedName() != null) classFqns.add(c.getQualifiedName());
                    });
                });
            }
            if (classFqns.isEmpty()) return;
            var communities = communitySummarizer.detectCommunities(classFqns);
            log.info("CommunitySummarizer detected {} communities for project={}", communities.size(), projectId);
            int total = communities.size();
            int done = 0;
            for (var community : communities) {
                try {
                    communitySummarizer.summarize(projectId, community);
                } catch (Exception inner) {
                    log.debug("Skipped community {} for project={}: {}",
                            community.key(), projectId, inner.getMessage());
                }
                done++;
                if (jobId != null && total > 0) {
                    int pct = 80 + (int) Math.floor(15.0 * done / total);
                    asyncJobService.updateProgress(jobId, pct,
                            "Summarizing communities (" + done + "/" + total + ")");
                }
            }
        } catch (Exception e) {
            log.warn("CommunitySummarizer rebuild failed for project={}: {}", projectId, e.getMessage());
        }
    }

    private void handleIngestionFailure(Project project, Exception e) {
        String errorMessage = resolveErrorMessage(e);
        log.error("Ingestion FAILED for project={}: {} — rolling back all persisted state",
                project.getId(), errorMessage, e);

        try {
            chunkRepository.deleteByProjectId(project.getId());
        } catch (Exception cleanupEx) {
            log.error("Failed to delete partial chunks for project={}", project.getId(), cleanupEx);
        }
        try {
            projectNodeRepository.deleteById(project.getId());
        } catch (Exception cleanupEx) {
            log.error("Failed to delete Neo4j ProjectNode for project={}", project.getId(), cleanupEx);
        }
        try {
            projectRepository.deleteById(project.getId());
        } catch (Exception cleanupEx) {
            log.error("Failed to delete project row for project={}", project.getId(), cleanupEx);
        }
    }

    String resolveErrorMessage(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String cause = e.getCause() != null && e.getCause().getMessage() != null
                ? e.getCause().getMessage() : "";
        String combined = msg + " " + cause;
        String provider = brainProperties.embed() != null ? brainProperties.embed().provider() : "ollama";

        if ("ollama".equalsIgnoreCase(provider)) {
            if (combined.contains("Connection refused") || combined.contains("connect timed out")
                    || combined.contains("UnknownHostException") || combined.contains("Connection reset")) {
                String ollamaBaseUrl = brainProperties.embed().ollamaBaseUrl();
                return "Cannot reach Ollama at " + ollamaBaseUrl
                        + ". Start it with `ollama serve` (or open the Ollama app) and verify with `curl "
                        + ollamaBaseUrl + "/api/tags`.";
            }
            if ((combined.contains("model") && (combined.contains("not found") || combined.contains("does not exist")))
                    || combined.contains("\"error\":\"model")) {
                return "Embedding model not pulled. Run `ollama pull "
                        + brainProperties.embed().model() + "` (~2.2 GB for bge-m3) and retry ingestion.";
            }
            if (combined.contains("read timed out") || combined.contains("ReadTimeoutException")
                    || combined.contains("read timeout")) {
                return "Ollama embedding request timed out. The model may still be loading on first call. "
                        + "Wait ~30s and retry, or pre-warm with `ollama run "
                        + brainProperties.embed().model() + " 'hello'`.";
            }
            if (combined.contains("input length exceeds the context length")
                    || (combined.contains("context length") && combined.contains("exceeds"))) {
                return "An input chunk exceeded Ollama's effective context window. "
                        + "This usually means num_ctx is set too low (Ollama defaults to 2048 — see "
                        + "spring.ai.ollama.embedding.options.num-ctx in application.yml) or "
                        + "brain.chunk.max-chars is too high for the active model. For bge-m3, keep "
                        + "num-ctx=8192 and max-chars≤18000.";
            }
        }

        if (combined.contains("Incorrect API key") || combined.contains("invalid_api_key"))
            return "Invalid OpenAI API key. Check SPRING_AI_OPENAI_API_KEY in your environment.";

        if (combined.contains("insufficient_quota") || combined.contains("exceeded your current quota"))
            return "OpenAI API quota exceeded. Check your billing at platform.openai.com.";

        if (combined.contains("Rate limit") || combined.contains("rate_limit"))
            return "OpenAI rate limit hit. Wait a moment and retry ingestion.";

        if (combined.contains("model_not_found") || combined.contains("does not exist"))
            return "Embedding model not found. Check SPRING_AI_OPENAI_EMBEDDING_OPTIONS_MODEL.";

        if (combined.contains("Connection refused") || combined.contains("UnknownHostException"))
            return "Cannot reach embedding service. Check network and API URL configuration.";

        if (combined.contains("AccessDeniedException")
                || combined.contains("not authorized to perform: bedrock"))
            return "AWS Bedrock access denied. Enable model access for amazon.titan-embed-text-v2 "
                    + "in the Bedrock console (Model access → Edit → Save).";

        if (combined.contains("ThrottlingException"))
            return "AWS Bedrock throttled the request. Retry shortly or request a quota increase "
                    + "in the Service Quotas console.";

        if (combined.contains("ValidationException") && combined.contains("dimensions"))
            return "Bedrock returned a vector dimension that doesn't match brain.embed.dimensions. "
                    + "Check that the configured model produces 1024-dim vectors.";

        if (combined.contains("Tokens in a single document exceeds"))
            return "A document exceeded the embedding token limit. This should not happen — check chunk.max-chars configuration.";

        if (combined.contains("OutOfMemoryError") || combined.contains("Java heap space"))
            return "Server ran out of memory processing this project. Try a smaller ZIP or increase server memory.";

        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    private void parseJavaFile(Path file, String projectId, ProjectNode projectNode,
                                List<Document> documents,
                                Map<ClassNode, Set<String>> pendingCalls) {
        try {
            JavaParser parser = new JavaParser();
            Optional<CompilationUnit> result = parser.parse(file).getResult();
            if (result.isEmpty()) return;

            CompilationUnit cu = result.get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse(StringUtils.EMPTY);
            String header = buildCodeHeader(packageName, cu);

            for (TypeDeclaration<?> type : cu.getTypes()) {
                String className     = type.getNameAsString();
                String qualifiedName = StringUtils.isEmpty(packageName)
                        ? className : packageName + "." + className;
                ChunkType chunkType  = resolveClassChunkType(type);
                String content       = buildCodeContent(packageName, cu, type.toString());

                List<String> classChunks = splitIfOversized(content, header);
                for (int i = 0; i < classChunks.size(); i++) {
                    String chunkName = classChunks.size() == 1
                            ? qualifiedName
                            : qualifiedName + "#part" + (i + 1);
                    documents.add(buildVectorDocument(projectId, file.toString(),
                            SourceType.CODE, chunkType, chunkName, packageName,
                            classChunks.get(i), codeTrustWeight(), Map.of("parentClass", className)));
                }

                type.getMethods().forEach(method -> {
                    String methodContent = buildCodeContent(packageName, cu, method.toString());
                    String methodName = className + "." + method.getNameAsString();
                    List<String> methodChunks = splitIfOversized(methodContent, header);
                    for (int i = 0; i < methodChunks.size(); i++) {
                        String mChunkName = methodChunks.size() == 1
                                ? methodName
                                : methodName + "#part" + (i + 1);
                        documents.add(buildVectorDocument(projectId, file.toString(),
                                SourceType.CODE, ChunkType.METHOD, mChunkName, packageName,
                                methodChunks.get(i), codeTrustWeight(),
                                Map.of("parentClass", className)));
                    }
                });

                ClassNode classNode = new ClassNode();
                classNode.setName(className);
                classNode.setQualifiedName(qualifiedName);
                classNode.setFilePath(file.toString());
                classNode.setClassType(chunkType.name().toLowerCase());
                classNode.setProjectId(projectId);
                addToProjectGraph(projectNode, packageName, classNode);

                Set<String> targets = callGraphExtractor.collectCallTargets(type);
                if (!targets.isEmpty()) {
                    pendingCalls.put(classNode, targets);
                }
            }

            runJavaAstVisitors(cu, file, projectId, projectNode);
        } catch (Exception e) {
            log.warn("Could not parse Java file={}", file, e);
        }
    }

    private void runJavaAstVisitors(CompilationUnit cu, Path file, String projectId, ProjectNode projectNode) {
        if (javaAstVisitors == null || javaAstVisitors.isEmpty()) return;
        JavaAstContext ctx = new JavaAstContext(cu, file, projectId, projectNode);
        for (JavaAstVisitor visitor : javaAstVisitors) {
            try {
                visitor.visit(ctx);
            } catch (RuntimeException e) {
                log.warn("JavaAstVisitor {} failed for file={}: {}",
                        visitor.name(), file, e.getMessage());
            }
        }
    }

    private void parsePomFile(Path file, Path projectRoot, String projectId, ProjectNode projectNode) {
        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(new FileReader(file.toFile(), StandardCharsets.UTF_8));
            if (isRootBuildFile(file, projectRoot) && projectNode.getGroupId() == null) {
                String ownGroupId = model.getGroupId() != null
                        ? model.getGroupId()
                        : (model.getParent() != null ? model.getParent().getGroupId() : null);
                projectNode.setGroupId(ownGroupId);
                projectNode.setArtifactId(model.getArtifactId());
            }
            for (Dependency dep : model.getDependencies()) {
                LibraryNode lib = new LibraryNode();
                lib.setName(dep.getGroupId() + ":" + dep.getArtifactId());
                lib.setGroupId(dep.getGroupId());
                lib.setArtifactId(dep.getArtifactId());
                lib.setVersion(dep.getVersion());
                projectNode.getLibraries().add(lib);
            }
        } catch (Exception e) {
            log.warn("Could not parse pom.xml at={}", file, e);
        }
    }

    private void parsePackageJsonFile(Path file, Path projectRoot, String projectId,
                                       ProjectNode projectNode) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            if (isRootBuildFile(file, projectRoot) && projectNode.getNpmName() == null) {
                com.fasterxml.jackson.databind.JsonNode nameNode = root.get("name");
                if (nameNode != null && !nameNode.isNull()) {
                    projectNode.setNpmName(nameNode.asText());
                }
            }
            addNpmDependencies(root.get("dependencies"), projectNode);
            addNpmDependencies(root.get("devDependencies"), projectNode);
            addNpmDependencies(root.get("peerDependencies"), projectNode);
        } catch (Exception e) {
            log.warn("Could not parse package.json at={}", file, e);
        }
    }

    private void addNpmDependencies(com.fasterxml.jackson.databind.JsonNode deps,
                                     ProjectNode projectNode) {
        if (deps == null || !deps.isObject()) return;
        deps.fieldNames().forEachRemaining(pkgName -> {
            LibraryNode lib = new LibraryNode();
            lib.setName("npm:" + pkgName);
            lib.setArtifactId(pkgName);
            lib.setVersion(deps.get(pkgName).asText(""));
            lib.setPurpose("npm");
            projectNode.getLibraries().add(lib);
        });
    }

    private boolean isRootBuildFile(Path file, Path projectRoot) {
        try {
            return file.getParent() != null
                    && file.getParent().toAbsolutePath().normalize()
                            .equals(projectRoot.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private void parseDocFile(Path file, String projectId, List<Document> documents) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
            if (StringUtils.isBlank(content)) return;

            ChunkType docType = resolveDocChunkType(file.getFileName().toString(), file.toString());
            String baseName = file.getFileName().toString();

            List<String> docChunks = splitIfOversized(content, "");
            for (int i = 0; i < docChunks.size(); i++) {
                String chunkName = docChunks.size() == 1
                        ? baseName
                        : baseName + "#part" + (i + 1);
                documents.add(buildVectorDocument(projectId, file.toString(),
                        SourceType.DOC, docType, chunkName, null,
                        docChunks.get(i), docTrustWeight(), Map.of()));
            }
        } catch (Exception e) {
            log.warn("Could not parse doc file={}", file, e);
        }
    }

    private Document buildVectorDocument(String projectId, String filePath,
                                          SourceType sourceType, ChunkType chunkType,
                                          String chunkName, String packageName,
                                          String content, double trustWeight,
                                          Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new HashMap<>(extraMetadata);
        metadata.put("projectId",   projectId);
        metadata.put("filePath",    filePath);
        metadata.put("sourceType",  sourceType.name());
        metadata.put("chunkType",   chunkType != null ? chunkType.name() : StringUtils.EMPTY);
        metadata.put("chunkName",   StringUtils.defaultString(chunkName));
        metadata.put("packageName", StringUtils.defaultString(packageName));
        metadata.put("trustWeight", trustWeight);
        metadata.put("contentHash", sha256(content));
        return new Document(content, metadata);
    }

    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private ProjectNode buildProjectNode(Project project) {
        ProjectNode node = new ProjectNode();
        node.setId(project.getId());
        node.setName(project.getName());
        node.setLanguage(project.getLanguage());
        node.setFramework(project.getFramework());
        node.setBuildTool(project.getBuildTool());
        return node;
    }

    private void addToProjectGraph(ProjectNode projectNode, String packageName, ClassNode classNode) {
        String moduleName = StringUtils.substringBefore(packageName, ".");
        ModuleNode module = projectNode.getModules().stream()
                .filter(m -> m.getName().equals(moduleName))
                .findFirst()
                .orElseGet(() -> {
                    ModuleNode m = new ModuleNode();
                    m.setName(moduleName);
                    m.setPackagePrefix(packageName);
                    m.setProjectId(projectNode.getId());
                    projectNode.getModules().add(m);
                    return m;
                });
        module.getClasses().add(classNode);
    }

    private ChunkType resolveClassChunkType(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration c) {
            return c.isInterface() ? ChunkType.INTERFACE : ChunkType.CLASS;
        }
        if (type instanceof EnumDeclaration) return ChunkType.ENUM;
        return ChunkType.CLASS;
    }

    private ChunkType resolveDocChunkType(String fileName, String fullPath) {
        String lower = fileName.toLowerCase();
        if (lower.contains("adr") || fullPath.contains("/adr/")) return ChunkType.ADR;
        if (lower.contains("contributing") || lower.contains("code_style")) return ChunkType.CONVENTION;
        if (lower.endsWith(".yaml") || lower.endsWith(".json")) return ChunkType.API_SPEC;
        if (lower.endsWith(".html")) return ChunkType.CONFLUENCE;
        return ChunkType.README;
    }

    private boolean isSkippablePath(Path file) {
        String fullPath = file.toString();
        String fileName = file.getFileName().toString();
        return fullPath.contains("__MACOSX")
                || fileName.startsWith("._")
                || fileName.equals(".DS_Store")
                || fullPath.contains("/node_modules/")
                || fullPath.contains("/.git/")
                || fullPath.contains("/build/")
                || fullPath.contains("/target/");
    }

    private boolean isPomFile(String fileName) {
        return "pom.xml".equalsIgnoreCase(fileName);
    }

    private boolean isPackageJsonFile(String fileName) {
        return "package.json".equalsIgnoreCase(fileName);
    }

    private boolean isDocFile(String fileName) {
        String lower = fileName.toLowerCase();
        return DOC_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String buildCodeHeader(String packageName, CompilationUnit cu) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(packageName)) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        cu.getImports().stream().limit(15).forEach(imp -> sb.append(imp).append("\n"));
        sb.append("\n");
        return sb.toString();
    }

    private String buildCodeContent(String packageName, CompilationUnit cu, String body) {
        return buildCodeHeader(packageName, cu) + body;
    }

    private List<String> splitIfOversized(String content, String header) {
        int maxChars = brainProperties.chunk().maxChars();
        if (content.length() <= maxChars) {
            return List.of(content);
        }

        int overlapChars = brainProperties.chunk().overlapChars();
        int headerLen = header.length();
        int bodyBudget = maxChars - headerLen;
        if (bodyBudget <= 0) {
            log.warn("Header ({} chars) exceeds max-chars ({}) — returning content as-is", headerLen, maxChars);
            return List.of(content);
        }

        String body = content.startsWith(header) ? content.substring(headerLen) : content;
        String chunkPrefix = content.startsWith(header) ? header : "";

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int maxParts = (body.length() / Math.max(1, bodyBudget - overlapChars)) + 2;
        while (start < body.length() && chunks.size() < maxParts) {
            int end = Math.min(start + bodyBudget, body.length());
            if (end < body.length()) {
                int lastNewline = body.lastIndexOf('\n', end);
                if (lastNewline > start + (bodyBudget / 2)) {
                    end = lastNewline + 1;
                }
            }
            chunks.add(chunkPrefix + body.substring(start, end));
            int nextStart = end - overlapChars;
            if (nextStart <= start) nextStart = end;
            start = nextStart;
        }

        log.info("Split oversized chunk ({} chars) into {} sub-chunks (max-chars={}, overlap={})",
                content.length(), chunks.size(), maxChars, overlapChars);
        return chunks;
    }

    private void deleteTempDir(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception e) { log.trace("Failed to delete temp file: {}", p, e); }
                });
        } catch (Exception e) {
            log.warn("Could not delete temp directory={}", dir, e);
        }
    }
}
