import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { AVENGER_NAMES, AVENGER_DESCRIPTIONS, AvengerName } from "./avengers.js";

const BRAIN_API_URL = process.env.BRAIN_API_URL ?? "http://localhost:8080";

const server = new McpServer({
  name: "project-brain",
  version: "0.0.1",
});

async function callBrainApi(path: string, method = "GET", body?: unknown) {
  const res = await fetch(`${BRAIN_API_URL}${path}`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Brain API error ${res.status}: ${text}`);
  }
  return res.json();
}

server.tool(
  "analyze_requirement",
  "Analyze a development requirement or Jira ticket. The Brain will ask clarifying questions until it has 100% confidence, then return a convention-aware implementation plan.",
  {
    projectId: z.string().describe("The project ID registered in the Brain"),
    requirement: z.string().describe("The requirement, ticket description, or free-form task"),
    sessionId: z.string().optional().describe("Session ID from a previous round (omit for first call)"),
    answers: z.string().optional().describe("Your answers to the Brain's previous questions"),
  },
  async ({ projectId, requirement, sessionId, answers }) => {
    const result = await callBrainApi("/api/v1/analyze", "POST", {
      projectId,
      requirement,
      sessionId,
      answers,
    });

    if (result.planReady) {
      return {
        content: [{ type: "text", text: `## Plan Ready\n\n${result.plan}` }],
      };
    }

    const rawQuestions = result.questions as Array<{ text: string; options: string[] } | string>;
    const questions = rawQuestions.map((q, i: number) => {
      if (typeof q === "string") return `${i + 1}. ${q}`;
      const opts = q.options?.length
        ? q.options.map((o: string, j: number) => `   ${String.fromCharCode(97 + j)}) ${o}`).join("\n")
        : "";
      return `${i + 1}. ${q.text}${opts ? "\n" + opts : ""}`;
    }).join("\n\n");

    const unknowns = result.unknownReferences?.length
      ? `\n\n⚠️ **Unknown references** (must resolve before proceeding):\n${(result.unknownReferences as string[]).join(", ")}`
      : "";

    const dims = result.dimensions
      ? Object.entries(result.dimensions as Record<string, { score: number; summary: string }>)
          .map(([k, v]) => `- **${k.toUpperCase()}** (${Math.round(v.score * 100)}%): ${v.summary}`)
          .join("\n")
      : "";

    return {
      content: [{
        type: "text",
        text: `## Brain needs clarification\n\nSession: \`${result.sessionId}\`\n\n${unknowns}\n\n**Dimensions understood so far:**\n${dims}\n\n**Questions:**\n${questions}\n\nFor each question, respond with the option letter (a, b, c, d) or provide a custom answer.\nFormat: \`Q1: a\` or \`Q1: [Custom] your answer\`\n\nCall \`analyze_requirement\` again with \`sessionId="${result.sessionId}"\` and your \`answers\`.`,
      }],
    };
  }
);

server.tool(
  "get_conventions",
  "Get the coding conventions the Brain knows for a project, optionally filtered by category (e.g. string-handling, db-migration, http-client, logging).",
  {
    projectId: z.string().describe("The project ID"),
    category: z.string().optional().describe("Convention category filter (optional)"),
  },
  async ({ projectId, category }) => {
    const path = `/api/v1/projects/${projectId}/conventions${category ? `?category=${category}` : ""}`;
    const conventions = await callBrainApi(path);

    if (!Array.isArray(conventions) || conventions.length === 0) {
      return { content: [{ type: "text", text: `No conventions found for project ${projectId}${category ? ` / category ${category}` : ""}.` }] };
    }

    const text = (conventions as Array<{ rule: string; category: string; sourceFile: string; trustWeight: number }>)
      .map(c => `- **[${c.category}]** ${c.rule} *(source: ${c.sourceFile}, trust: ${c.trustWeight})*`)
      .join("\n");

    return { content: [{ type: "text", text: `## Conventions for ${projectId}\n\n${text}` }] };
  }
);

server.tool(
  "list_projects",
  "List all projects indexed in the Brain.",
  {},
  async () => {
    const projects = await callBrainApi("/api/v1/projects");
    if (!Array.isArray(projects) || projects.length === 0) {
      return { content: [{ type: "text", text: "No projects indexed yet. Use the Admin UI or CLI to ingest a project." }] };
    }

    const text = (projects as Array<{ id: string; name: string; language: string; framework: string; lastIngested: string }>)
      .map(p => `- **${p.id}** — ${p.name} (${p.language}/${p.framework}) — last indexed: ${p.lastIngested ?? "never"}`)
      .join("\n");

    return { content: [{ type: "text", text: `## Indexed Projects\n\n${text}` }] };
  }
);

server.tool(
  "get_project",
  "Get details about a specific project in the Brain.",
  {
    projectId: z.string().describe("The project ID"),
  },
  async ({ projectId }) => {
    const project = await callBrainApi(`/api/v1/projects/${projectId}`);
    return { content: [{ type: "text", text: JSON.stringify(project, null, 2) }] };
  }
);

function registerAvenger(name: AvengerName) {
  const toolName = `review_with_${name.toLowerCase()}`;
  server.tool(
    toolName,
    AVENGER_DESCRIPTIONS[name],
    {
      projectId: z.string().describe("The project ID for context scoping"),
      code: z.string().describe("The code, config, or artifact to review"),
      context: z.string().optional().describe("Additional context (plan, requirement, etc.)"),
    },
    async ({ projectId, code, context }) => {
      const result = await callBrainApi(`/api/v1/avengers/${name}/review`, "POST", {
        projectId,
        code,
        context,
      });

      const issues = Array.isArray(result.issues) && result.issues.length
        ? `\n\n**Issues:**\n${(result.issues as string[]).map((i, idx) => `${idx + 1}. ${i}`).join("\n")}`
        : "";

      return {
        content: [{
          type: "text",
          text: `## ${name} — ${result.verdict}\n\n${result.summary ?? ""}${issues}\n\n*Latency: ${result.latencyMs}ms*`,
        }],
      };
    }
  );
}

for (const name of AVENGER_NAMES) {
  registerAvenger(name);
}

server.tool(
  "run_full_avengers_review",
  "Run all 11 Avengers in parallel for a comprehensive review. Returns aggregated verdict (APPROVED/CHANGES_REQUESTED/BLOCKED) plus individual Avenger verdicts.",
  {
    projectId: z.string().describe("The project ID for context scoping"),
    code: z.string().describe("The code, config, or artifact to review"),
    context: z.string().optional().describe("Additional context (plan, requirement, etc.)"),
  },
  async ({ projectId, code, context }) => {
    const result = await callBrainApi("/api/v1/avengers/full-review", "POST", { projectId, code, context });

    const perAvenger = (result.reviews as Array<{ avenger: string; verdict: string; summary: string }>)
      .map(r => `- **${r.avenger}**: ${r.verdict} — ${r.summary ?? ""}`)
      .join("\n");

    return {
      content: [{
        type: "text",
        text: `## Full Avengers Review\n\n**Overall Verdict:** ${result.overallVerdict}\n\n- Approved: ${result.approved}\n- Changes Requested: ${result.changesRequested}\n- Blocked: ${result.blocked}\n- Total latency: ${result.totalLatencyMs}ms\n\n### Per-Avenger Verdicts\n\n${perAvenger}`,
      }],
    };
  }
);

server.tool(
  "start_autonomous_development",
  "Begin an autonomous multi-repo development flow. Brain ingests the requirement (raw text or an IntakeRecord id), detects affected repos, and runs the first clarification round.",
  {
    payload: z.string().optional().describe("Raw requirement text (pasted content, markdown, etc.)"),
    intakeId: z.string().optional().describe("Existing IntakeRecord UUID from /api/v1/intake/* endpoints"),
    source: z.string().optional().describe("Source label: FREE_FORM, JIRA_TICKET, PDF_UPLOAD, DOCX, IMAGE, URL"),
    seedProjectId: z.string().optional().describe("Optional seed project if one is clearly known"),
  },
  async ({ payload, intakeId, source, seedProjectId }) => {
    const result = await callBrainApi("/api/v1/autodev/start", "POST", {
      payload, intakeId, source: source ?? "FREE_FORM", seedProjectId,
    });
    const affected = Array.isArray(result.proposedAffectedProjects) && result.proposedAffectedProjects.length
      ? `\n\n**Proposed affected projects:**\n${(result.proposedAffectedProjects as Array<{projectId:string;confidence:number;rationale:string}>).map(p => `- \`${p.projectId}\` (${Math.round(p.confidence*100)}%) — ${p.rationale}`).join("\n")}`
      : "";
    const rawQ = result.clarificationQuestions as Array<{ text: string; options: string[] } | string>;
    const questions = Array.isArray(rawQ) && rawQ.length
      ? `\n\n**Clarification questions:**\n${rawQ.map((q, i) => {
          if (typeof q === "string") return `${i + 1}. ${q}`;
          const opts = q.options?.length ? q.options.map((o, j) => `   ${String.fromCharCode(97 + j)}) ${o}`).join("\n") : "";
          return `${i + 1}. ${q.text}${opts ? "\n" + opts : ""}`;
        }).join("\n\n")}\n\nReply with \`clarify_autonomous_development\` sessionId=\`${result.sessionId}\`.`
      : "\n\nBrain is confident — call \`plan_autonomous_development\` to generate the multi-repo plan.";
    return { content: [{ type: "text", text: `## Autodev session \`${result.sessionId}\`${affected}${questions}` }] };
  }
);

server.tool(
  "clarify_autonomous_development",
  "Provide answers to the Brain's clarification questions for an autonomous development session.",
  {
    sessionId: z.string().describe("Autodev session ID from start_autonomous_development"),
    answers: z.string().describe("Your answers to the previous round of questions"),
  },
  async ({ sessionId, answers }) => {
    const result = await callBrainApi("/api/v1/autodev/clarify", "POST", { sessionId, answers });
    const rawQ = result.clarificationQuestions as Array<{ text: string; options: string[] } | string>;
    const questions = Array.isArray(rawQ) && rawQ.length
      ? `\n\n**Still needs clarification:**\n${rawQ.map((q, i) => {
          if (typeof q === "string") return `${i + 1}. ${q}`;
          const opts = q.options?.length ? q.options.map((o, j) => `   ${String.fromCharCode(97 + j)}) ${o}`).join("\n") : "";
          return `${i + 1}. ${q.text}${opts ? "\n" + opts : ""}`;
        }).join("\n\n")}`
      : "\n\nBrain is confident — call \`plan_autonomous_development\` next.";
    return { content: [{ type: "text", text: `## Session \`${result.sessionId}\`${questions}` }] };
  }
);

server.tool(
  "plan_autonomous_development",
  "Generate the multi-repo implementation plan (umbrella JSON: {requirement, multiRepo, projects[]}) once clarification is complete. Human approval expected before execute.",
  {
    sessionId: z.string().describe("Autodev session ID"),
  },
  async ({ sessionId }) => {
    const result = await callBrainApi("/api/v1/autodev/plan", "POST", { sessionId });
    return { content: [{ type: "text", text: `## Multi-repo plan for session \`${result.sessionId}\`\n\n\`\`\`json\n${result.multiRepoPlan}\n\`\`\`` }] };
  }
);

server.tool(
  "execute_autonomous_development",
  "Walk the approved plan graph: generate per-project code fragments via EditOrchestrator, annotate seams, self-review. Does NOT open PRs yet.",
  {
    sessionId: z.string().describe("Autodev session ID"),
    approvedProjectIds: z.array(z.string()).optional().describe("Project IDs the user approved (omit to use all affected)"),
  },
  async ({ sessionId, approvedProjectIds }) => {
    const result = await callBrainApi("/api/v1/autodev/execute", "POST", { sessionId, approvedProjectIds });
    const per = Array.isArray(result.perProject) && result.perProject.length
      ? (result.perProject as Array<{projectId:string;fileCount:number;nodeCount:number;nodeErrors:string[]}>)
          .map(p => `- \`${p.projectId}\` — ${p.fileCount} file(s), ${p.nodeCount} plan node(s)${p.nodeErrors?.length ? `, ${p.nodeErrors.length} node error(s)` : ""}`)
          .join("\n")
      : "(no projects)";
    return { content: [{ type: "text", text: `## Execution complete — session \`${result.sessionId}\`\n\n${per}\n\nCall \`create_autonomous_prs\` to fan out PRs.` }] };
  }
);

server.tool(
  "create_autonomous_prs",
  "Fan out GitHub Draft PRs, one per approved repo. Best-effort: failed repos are reported without blocking successful ones.",
  {
    sessionId: z.string().describe("Autodev session ID"),
    repos: z.array(z.object({
      projectId: z.string(),
      repoUrl: z.string(),
      baseBranch: z.string(),
    })).describe("Per-project repo target: {projectId, repoUrl, baseBranch}"),
  },
  async ({ sessionId, repos }) => {
    const result = await callBrainApi("/api/v1/autodev/create-prs", "POST", { sessionId, repos });
    const perRepo = (result.perRepo as Array<{projectId:string;repoUrl:string;outcome:string;prUrl?:string;errorMessage?:string;failureStage?:string}>)
      .map(r => r.outcome === "SUCCESS"
        ? `- ✅ \`${r.projectId}\` → ${r.prUrl}`
        : `- ❌ \`${r.projectId}\` (${r.repoUrl}) — ${r.failureStage}: ${r.errorMessage}`)
      .join("\n");
    return { content: [{ type: "text", text: `## Batch \`${result.batchId}\` — ${result.overallStatus}\n\nSuccess: ${result.succeeded}, Failed: ${result.failed}, Skipped: ${result.skipped}\n\n${perRepo}` }] };
  }
);

server.tool(
  "get_autonomous_batch_status",
  "Poll the status of an in-flight or completed autonomous PR batch.",
  {
    batchId: z.string().describe("Batch UUID returned by create_autonomous_prs"),
  },
  async ({ batchId }) => {
    const result = await callBrainApi(`/api/v1/autodev/batch/${batchId}`);
    return { content: [{ type: "text", text: `## Batch \`${batchId}\`\n\n\`\`\`json\n${JSON.stringify(result, null, 2)}\n\`\`\`` }] };
  }
);

const transport = new StdioServerTransport();
await server.connect(transport);
