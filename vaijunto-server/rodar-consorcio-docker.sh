#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p data-lab/empresa_a data-lab/empresa_b data-lab/empresa_c

docker compose -f docker-compose.lab.yml up --build -d

echo "Consórcio VaiJunto levantado."
echo "" 
echo "Health checks:"
echo "  http://localhost:7070/api/v1/saude"
echo "  http://localhost:7071/api/v1/saude"
echo "  http://localhost:7072/api/v1/saude"
echo "" 
echo "TCPs:"
echo "  empresa-a: 127.0.0.1:8080"
echo "  empresa-b: 127.0.0.1:8081"
echo "  empresa-c: 127.0.0.1:8082"
