#!/bin/bash
# ── Signal Engine Deployment Script ──────────────────────────────────────────
# Run this on any machine with Docker installed.
# Works on: local machine, Oracle Free VPS, any Ubuntu/Debian server.
#
# Usage:
#   chmod +x deploy.sh
#   ./deploy.sh           # first time
#   ./deploy.sh update    # pull latest code and restart

set -e

echo "🚀 Signal Engine Deployment"
echo "================================"

# Check Docker is installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker not found. Install from https://docs.docker.com/get-docker/"
    exit 1
fi

if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo "❌ Docker Compose not found."
    exit 1
fi

COMPOSE="docker compose"
command -v docker-compose &> /dev/null && COMPOSE="docker-compose"

# ── Project structure check ────────────────────────────────────────────────────
if [ ! -f "docker-compose.yml" ]; then
    echo "❌ Run this script from the project root (where docker-compose.yml is)"
    exit 1
fi

if [ ! -f "python-service/.env" ]; then
    echo "❌ Missing python-service/.env — copy env_reference.txt and fill in your API keys"
    exit 1
fi

# ── Pull latest code (if update mode) ─────────────────────────────────────────
if [ "$1" == "update" ]; then
    echo "📥 Pulling latest code..."
    git pull
fi

# ── Build and start ───────────────────────────────────────────────────────────
echo "🔨 Building Docker images..."
$COMPOSE build

echo "🟢 Starting services..."
$COMPOSE up -d

echo ""
echo "✅ Signal Engine is running!"
echo ""
echo "   Trade Tracker UI: http://localhost"
echo "   Java API:         http://localhost:8080"
echo "   Python service:   http://localhost:5000/health"
echo ""
echo "📋 Useful commands:"
echo "   $COMPOSE logs -f           # stream all logs"
echo "   $COMPOSE logs java-service # Java logs only"
echo "   $COMPOSE logs python-service # Python logs only"
echo "   $COMPOSE down              # stop everything"
echo "   $COMPOSE restart           # restart all services"
echo ""

# ── Health check ──────────────────────────────────────────────────────────────
echo "⏳ Waiting for services to start..."
sleep 10

if curl -sf http://localhost:5000/health > /dev/null 2>&1; then
    echo "✅ Python service: healthy"
else
    echo "⚠️  Python service: not ready yet (may still be starting)"
fi

if curl -sf http://localhost:8080/api/v1/trades > /dev/null 2>&1; then
    echo "✅ Java service: healthy"
else
    echo "⚠️  Java service: not ready yet (may still be starting — Spring Boot takes ~30s)"
fi
