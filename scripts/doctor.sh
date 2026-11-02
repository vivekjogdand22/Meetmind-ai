#!/bin/bash
# ============================================================
# MEETMIND DOCTOR
#
# Checks the things that most often go wrong when starting
# MeetMind and tells you how to fix each one. It only reads;
# it never starts, stops or changes anything.
#
# Run it from anywhere:   ./scripts/doctor.sh
# ============================================================

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PROBLEMS=0

if [ -t 1 ]; then
    GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
else
    GREEN=""; RED=""; YELLOW=""; BOLD=""; OFF=""
fi

ok()      { printf "  %s✓%s %s\n" "$GREEN" "$OFF" "$1"; }
note()    { printf "  %s-%s %s\n" "$YELLOW" "$OFF" "$1"; }
problem() { printf "  %s✗%s %s\n      fix: %s\n" "$RED" "$OFF" "$1" "$2"; PROBLEMS=$((PROBLEMS + 1)); }
section() { printf "\n%s%s%s\n" "$BOLD" "$1" "$OFF"; }

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

http_code() { curl -s -o /dev/null -m 4 -w '%{http_code}' "$1" 2>/dev/null; }


# ------------------------------------------------------------
section "Tools"
# ------------------------------------------------------------

if command -v java > /dev/null 2>&1; then
    JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
    if [ "${JAVA_MAJOR:-0}" -ge 21 ] 2>/dev/null; then
        ok "Java $JAVA_MAJOR"
    else
        problem "Java ${JAVA_MAJOR:-?} found, but the backend needs 21 or newer" "brew install openjdk@21"
    fi
else
    problem "Java is not installed" "brew install openjdk@21"
fi

if [ -x "$ROOT/backend/mvnw" ]; then
    ok "Maven wrapper (backend/mvnw)"
else
    problem "backend/mvnw is missing or not executable" "chmod +x backend/mvnw"
fi

if command -v node > /dev/null 2>&1; then
    ok "Node $(node --version)"
else
    problem "Node.js is not installed" "brew install node"
fi

if [ -d "$ROOT/frontend/node_modules" ]; then
    ok "Frontend packages installed"
else
    problem "Frontend packages are not installed" "cd frontend && npm install"
fi

VENV_PYTHON="$ROOT/ai-service/venv/bin/python"

if [ -x "$VENV_PYTHON" ]; then
    if "$VENV_PYTHON" -c "import fastapi, uvicorn, faster_whisper, requests" > /dev/null 2>&1; then
        ok "AI service Python environment (ai-service/venv)"
    else
        problem "The AI service Python packages are incomplete" "cd ai-service && source venv/bin/activate && pip install -r requirements.txt"
    fi
else
    problem "The AI service Python environment does not exist" "cd ai-service && python3 -m venv venv && source venv/bin/activate && pip install -r requirements.txt"
fi


# ------------------------------------------------------------
section "Database"
# ------------------------------------------------------------

if port_open 5432; then
    ok "PostgreSQL is accepting connections on port 5432"
else
    problem "PostgreSQL is not running (nothing listens on port 5432)" "brew services start postgresql@17"
fi

if [ -n "$DB_PASSWORD" ]; then
    ok "DB_PASSWORD is set in this terminal"
elif [ -f "$ROOT/backend/.env" ] && grep -q "^DB_PASSWORD=." "$ROOT/backend/.env"; then
    ok "DB_PASSWORD is set in backend/.env"
else
    note "No DB_PASSWORD found in this terminal or in backend/.env. That is fine if PostgreSQL does not ask for a password. If the backend is refused with \"password authentication failed\": cp backend/.env.example backend/.env and put your password in it"
fi


# ------------------------------------------------------------
section "AI model (Ollama)"
# ------------------------------------------------------------

MODEL="${OLLAMA_MODEL:-qwen3:8b}"

TAGS=$(curl -s -m 4 http://127.0.0.1:11434/api/tags 2>/dev/null)

if [ -z "$TAGS" ]; then
    problem "Ollama is not running. Without it the AI falls back to a much simpler analysis" "open the Ollama app, or run: ollama serve"
elif echo "$TAGS" | grep -q "\"name\":\"$MODEL\""; then
    ok "Ollama is running and has the model $MODEL"
else
    problem "Ollama is running, but the model $MODEL is not downloaded" "ollama pull $MODEL"
fi


# ------------------------------------------------------------
section "The three MeetMind programs"
# ------------------------------------------------------------

# check_service NAME PORT URL EXPECTED_HTTP_CODE START_COMMAND
check_service() {
    local name="$1" port="$2" url="$3" expected="$4" start="$5"

    if [ "$(http_code "$url")" = "$expected" ]; then
        ok "$name is running on port $port"
        return 0
    fi

    if port_open "$port"; then
        problem "Port $port is used by another program, so $name cannot use it" "find it with: lsof -i :$port   (then stop it)"
    else
        note "$name is not running. Start it with: $start"
    fi

    return 1
}

if check_service "Backend" 8080 "http://localhost:8080/api/health" 200 "cd backend && ./mvnw spring-boot:run"; then
    if [ "$(http_code "http://localhost:8080/api/meetings")" = "200" ]; then
        ok "The backend can read the database"
    else
        problem "The backend is running but cannot read the database" "look at the backend terminal for the first error line (wrong password, or PostgreSQL stopped)"
    fi
fi

check_service "AI service" 8000 "http://127.0.0.1:8000/health" 200 "cd ai-service && source venv/bin/activate && uvicorn main:app --host 127.0.0.1 --port 8000"

check_service "Frontend" 5173 "http://localhost:5173/" 200 "cd frontend && npm run dev"


# ------------------------------------------------------------
printf "\n"
if [ "$PROBLEMS" -eq 0 ]; then
    printf "%sAll checks passed.%s\n" "$GREEN" "$OFF"
    exit 0
fi

printf "%s%d problem(s) found.%s Fix them in the order shown above, then run this again.\n" "$RED" "$PROBLEMS" "$OFF"
exit 1
