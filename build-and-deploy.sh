#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

IMAGE_NAME="project-brain"
IMAGE_TAG="${IMAGE_TAG:-latest}"
COMPOSE_FILE="docker-compose.yml"

UI_DIR="ui"
UI_PORT="3000"
UI_PID_FILE=".ui.pid"
UI_LOG_FILE=".ui.log"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }
section() { echo -e "\n${CYAN}━━━ $* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"; }

env_val() { grep -E "^${1}=" .env 2>/dev/null | cut -d= -f2- | tr -d '"'"'" | xargs; }

set_env_val() {
  local key="$1" val="$2" file="$3"
  if grep -qE "^${key}=" "$file" 2>/dev/null; then
    sed -i '' "s|^${key}=.*|${key}=${val}|" "$file"
  else
    echo "${key}=${val}" >> "$file"
  fi
}

apply_provider() {
  [[ -z "${PROVIDER:-}" ]] && return 0

  if [[ ! -f ".env" ]]; then
    [[ -f ".env.example" ]] || error ".env.example not found — cannot bootstrap .env."
    cp .env.example .env
    info "Created .env from .env.example."
  fi
  if [[ ! -f "docker/runtime.env" ]]; then
    cp .env.example docker/runtime.env
    info "Created docker/runtime.env from .env.example."
  fi

  section "Switching LLM provider → ${PROVIDER}"

  case "$PROVIDER" in
    ollama)
      for f in .env docker/runtime.env; do [[ -f "$f" ]] || continue
        set_env_val BRAIN_LLM_PROVIDER      ollama   "$f"
        set_env_val BRAIN_LLM_PLAN_MODEL    llama3.1 "$f"
        set_env_val BRAIN_LLM_EXTRACT_MODEL llama3.1 "$f"
        set_env_val BRAIN_EMBED_PROVIDER    ollama   "$f"
        set_env_val BRAIN_EMBED_MODEL       bge-m3   "$f"
        set_env_val BRAIN_EMBED_DIMENSIONS  1024     "$f"
      done
      success "Provider set to Ollama (chat: llama3.1, embed: bge-m3). No API key required."
      ;;

    anthropic)
      local api_key; api_key=$(env_val SPRING_AI_ANTHROPIC_API_KEY)
      if [[ -z "$api_key" || "$api_key" == sk-ant-YOUR_KEY_HERE ]]; then
        error "SPRING_AI_ANTHROPIC_API_KEY is not set in .env. Add your key first."
      fi
      for f in .env docker/runtime.env; do [[ -f "$f" ]] || continue
        set_env_val BRAIN_LLM_PROVIDER      anthropic                 "$f"
        set_env_val BRAIN_LLM_PLAN_MODEL    claude-sonnet-4-5         "$f"
        set_env_val BRAIN_LLM_EXTRACT_MODEL claude-haiku-4-5-20251001 "$f"
      done
      success "Provider set to Anthropic (model: claude-sonnet-4-5)."
      ;;

    bedrock)
      local aws_key; aws_key=$(env_val AWS_ACCESS_KEY_ID)
      [[ -z "$aws_key" ]] && error "AWS_ACCESS_KEY_ID is not set in .env. Add AWS credentials first."
      for f in .env docker/runtime.env; do [[ -f "$f" ]] || continue
        set_env_val BRAIN_LLM_PROVIDER      bedrock                   "$f"
        set_env_val BRAIN_LLM_PLAN_MODEL    claude-sonnet-4-5         "$f"
        set_env_val BRAIN_LLM_EXTRACT_MODEL claude-haiku-4-5-20251001 "$f"
      done
      success "Provider set to AWS Bedrock (model: claude-sonnet-4-5)."
      ;;
  esac
}

check_deps() {
  command -v docker >/dev/null 2>&1 || error "Docker is not installed or not running."
  command -v java   >/dev/null 2>&1 || error "Java 21 is required. Install via: brew install temurin@21"

  local java_version
  java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
  [[ "$java_version" -ge 21 ]] || error "Java 21+ required (found $java_version)."

  [[ -f ".env"               ]] || error ".env not found. Run: cp .env.example .env  then edit it."
  [[ -f "docker/runtime.env" ]] || error "docker/runtime.env not found. Run: cp .env.example docker/runtime.env  then edit it."

  local llm_provider;   llm_provider=$(env_val BRAIN_LLM_PROVIDER);     llm_provider="${llm_provider:-ollama}"
  local embed_provider; embed_provider=$(env_val BRAIN_EMBED_PROVIDER); embed_provider="${embed_provider:-ollama}"
  if [[ "$llm_provider" == "ollama" || "$embed_provider" == "ollama" ]]; then
    command -v ollama >/dev/null 2>&1 || error "Ollama not installed. Download from: https://ollama.com"
  fi
}

ensure_ollama() {
  local llm_provider;   llm_provider=$(env_val BRAIN_LLM_PROVIDER)
  local embed_provider; embed_provider=$(env_val BRAIN_EMBED_PROVIDER)
  llm_provider="${llm_provider:-ollama}"
  embed_provider="${embed_provider:-ollama}"
  if [[ "$llm_provider" != "ollama" && "$embed_provider" != "ollama" ]]; then
    return 0
  fi

  section "Ollama Setup"

  local base_url; base_url=$(env_val SPRING_AI_OLLAMA_BASE_URL)
  base_url="${base_url:-http://localhost:11434}"

  local check_url="${base_url//host.docker.internal/localhost}"
  if ! curl -sf "${check_url}/api/tags" >/dev/null 2>&1; then
    error "Ollama is not running at ${check_url}. Start it with: ollama serve  (or open the Ollama app)"
  fi
  success "Ollama is running at ${base_url}."

  pull_model_if_missing() {
    local label="$1" model="$2"
    [[ -z "$model" ]] && return 0
    local model_base="${model%%:*}"
    if ollama list 2>/dev/null | awk 'NR>1 {print $1}' | grep -q "^${model_base}"; then
      success "${label} model '${model}' is already available."
    else
      info "Pulling ${label} model '${model}'... (first-run download — may take several minutes)"
      ollama pull "${model}" || error "Failed to pull '${model}'. Check your internet connection."
      success "${label} model '${model}' is ready."
    fi
  }

  if [[ "$llm_provider" == "ollama" ]]; then
    local chat_model; chat_model=$(env_val BRAIN_LLM_PLAN_MODEL); chat_model="${chat_model:-llama3.1}"
    pull_model_if_missing "chat" "${chat_model}"
  fi

  if [[ "$embed_provider" == "ollama" ]]; then
    local embed_model; embed_model=$(env_val BRAIN_EMBED_MODEL); embed_model="${embed_model:-bge-m3}"
    pull_model_if_missing "embedding" "${embed_model}"
  fi
}

build_jar() {
  section "Building Spring Boot JAR"
  info "Running Gradle bootJar..."
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" ./gradlew --no-daemon clean bootJar
  success "JAR built: build/libs/project-brain.jar"
}

build_image() {
  section "Building Docker image"
  info "Building image: ${IMAGE_NAME}:${IMAGE_TAG}"
  docker build \
    --build-arg BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --build-arg IMAGE_TAG="${IMAGE_TAG}" \
    -t "${IMAGE_NAME}:${IMAGE_TAG}" \
    -f Dockerfile \
    .
  success "Image built: ${IMAGE_NAME}:${IMAGE_TAG}"
}

start_infra() {
  section "Starting infrastructure"
  info "Starting PostgreSQL + pgvector and Neo4j..."
  docker compose -f "$COMPOSE_FILE" up -d db neo4j

  info "Waiting for PostgreSQL to be healthy..."
  local retries=20
  until docker compose -f "$COMPOSE_FILE" exec -T db pg_isready -U brain -d brain >/dev/null 2>&1; do
    retries=$((retries - 1))
    [[ $retries -le 0 ]] && error "PostgreSQL did not become healthy in time."
    echo -n "."; sleep 2
  done
  echo ""; success "PostgreSQL is healthy."

  info "Waiting for Neo4j to be healthy..."
  retries=30
  until docker compose -f "$COMPOSE_FILE" exec -T neo4j wget -q --spider http://localhost:7474 >/dev/null 2>&1; do
    retries=$((retries - 1))
    [[ $retries -le 0 ]] && error "Neo4j did not become healthy in time."
    echo -n "."; sleep 3
  done
  echo ""; success "Neo4j is healthy. Browser: http://localhost:7474"
}

start_app() {
  section "Starting Brain API"
  docker compose -f "$COMPOSE_FILE" up -d app
  success "Brain API starting at http://localhost:8080"
}

start_ui() {
  section "Starting Admin UI (Vite dev server)"

  if [[ ! -d "$UI_DIR" ]]; then
    warn "$UI_DIR directory not found — skipping UI startup."
    return 0
  fi

  command -v node >/dev/null 2>&1 || { warn "Node.js not installed — skipping UI. Install: brew install node"; return 0; }
  command -v npm  >/dev/null 2>&1 || { warn "npm not installed — skipping UI."; return 0; }

  if lsof -nP -iTCP:${UI_PORT} -sTCP:LISTEN >/dev/null 2>&1; then
    success "UI already running on http://localhost:${UI_PORT} (leaving as-is)"
    return 0
  fi

  if [[ ! -d "$UI_DIR/node_modules" ]]; then
    info "First run: installing UI dependencies (npm install)..."
    (cd "$UI_DIR" && npm install --no-audit --no-fund) || error "npm install failed"
  fi

  info "Spawning Vite (logs: ${UI_DIR}/${UI_LOG_FILE}, pid: ${UI_DIR}/${UI_PID_FILE})..."
  local abs_log="$SCRIPT_DIR/$UI_DIR/$UI_LOG_FILE"
  local abs_pid="$SCRIPT_DIR/$UI_DIR/$UI_PID_FILE"
  (
    cd "$UI_DIR"
    nohup npm run dev > "$abs_log" 2>&1 &
    echo $! > "$abs_pid"
  )

  local retries=20
  until curl -sf "http://localhost:${UI_PORT}/" >/dev/null 2>&1; do
    retries=$((retries - 1))
    if [[ $retries -le 0 ]]; then
      warn "UI did not bind port ${UI_PORT} within 20s. Check ${UI_DIR}/${UI_LOG_FILE}."
      return 0
    fi
    echo -n "."; sleep 1
  done
  echo ""
  success "Admin UI running at http://localhost:${UI_PORT}"
}

stop_ui() {
  if [[ -f "$UI_DIR/$UI_PID_FILE" ]]; then
    local pid; pid=$(cat "$UI_DIR/$UI_PID_FILE" 2>/dev/null)
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      info "Stopping Admin UI (pid $pid)..."
      kill "$pid" 2>/dev/null || true
      pkill -P "$pid" 2>/dev/null || true
    fi
    rm -f "$UI_DIR/$UI_PID_FILE"
  fi
  local port_pid; port_pid=$(lsof -nP -iTCP:${UI_PORT} -sTCP:LISTEN -t 2>/dev/null | head -1)
  if [[ -n "$port_pid" ]]; then
    info "Stopping orphan UI process on port ${UI_PORT} (pid $port_pid)..."
    kill "$port_pid" 2>/dev/null || true
  fi
}

print_provider_info() {
  local llm_provider;   llm_provider=$(env_val BRAIN_LLM_PROVIDER);     llm_provider="${llm_provider:-ollama}"
  local llm_model;      llm_model=$(env_val BRAIN_LLM_PLAN_MODEL);      llm_model="${llm_model:-llama3.1}"
  local embed_provider; embed_provider=$(env_val BRAIN_EMBED_PROVIDER); embed_provider="${embed_provider:-ollama}"
  local embed_model;    embed_model=$(env_val BRAIN_EMBED_MODEL);       embed_model="${embed_model:-bge-m3}"
  local embed_dims;     embed_dims=$(env_val BRAIN_EMBED_DIMENSIONS);   embed_dims="${embed_dims:-1024}"

  echo -e "  ${CYAN}LLM provider:${NC}       ${llm_provider}  (model: ${llm_model})"
  echo -e "  ${CYAN}Embed provider:${NC}     ${embed_provider}  (model: ${embed_model}, dims: ${embed_dims})"
  if [[ "$llm_provider" == "ollama" || "$embed_provider" == "ollama" ]]; then
    local base_url; base_url=$(env_val SPRING_AI_OLLAMA_BASE_URL)
    echo -e "  ${CYAN}Ollama base URL:${NC}    ${base_url:-http://localhost:11434}"
  fi
}

cmd_build() {
  check_deps
  build_jar
  build_image
}

cmd_up() {
  check_deps
  ensure_ollama
  start_infra
  start_app
  start_ui
  echo ""
  success "All services running."
  echo -e "  ${CYAN}Brain API:${NC}   http://localhost:8080"
  echo -e "  ${CYAN}Admin UI:${NC}    http://localhost:${UI_PORT}"
  echo -e "  ${CYAN}Neo4j UI:${NC}    http://localhost:7474"
  echo -e "  ${CYAN}MCP (dev):${NC}   Run 'cd mcp && npm install && npm run dev'"
  print_provider_info
}

cmd_restart() {
  check_deps
  ensure_ollama
  section "Rebuilding and restarting Brain API"
  build_jar
  build_image
  docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate app
  start_ui
  success "Brain API restarted."
  echo -e "  ${CYAN}Brain API:${NC}   http://localhost:8080"
  echo -e "  ${CYAN}Admin UI:${NC}    http://localhost:${UI_PORT}"
  print_provider_info
}

cmd_down() {
  section "Stopping all services"
  stop_ui
  docker compose -f "$COMPOSE_FILE" down
  success "All containers stopped. Data volumes are preserved."
  warn "To also remove data: docker compose down -v"
}

cmd_logs()   { docker compose -f "$COMPOSE_FILE" logs -f --tail=100; }
cmd_status() { section "Container status"; docker compose -f "$COMPOSE_FILE" ps; }

COMMAND="all"
PROVIDER=""

for arg in "$@"; do
  case "$arg" in
    --ollama)    PROVIDER="ollama"    ;;
    --anthropic) PROVIDER="anthropic" ;;
    --bedrock)   PROVIDER="bedrock"   ;;
    build|up|restart|down|logs|status|all) COMMAND="$arg" ;;
    *)
      echo -e "${RED}Unknown argument:${NC} $arg"
      echo "Usage: $0 [build|up|restart|down|logs|status] [--ollama|--anthropic|--bedrock]"
      exit 1
      ;;
  esac
done

apply_provider

case "$COMMAND" in
  build)   cmd_build   ;;
  up)      cmd_up      ;;
  restart) cmd_restart ;;
  down)    cmd_down    ;;
  logs)    cmd_logs    ;;
  status)  cmd_status  ;;
  all)
    cmd_build
    cmd_up
    ;;
esac
