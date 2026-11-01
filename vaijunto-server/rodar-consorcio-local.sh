#!/bin/bash
set -e

cd "$(dirname "$0")"
mkdir -p ./data/empresa_a ./data/empresa_b ./data/empresa_c

PORTA_TCP_A=8080
PORTA_HTTP_A=7070
PORTA_TCP_B=8081
PORTA_HTTP_B=7071
PORTA_TCP_C=8082
PORTA_HTTP_C=7072

export EMPRESA_ID=EMPRESA_A PORTA_TCP=$PORTA_TCP_A PORTA_HTTP=$PORTA_HTTP_A DATA_DIR=./data/empresa_a PEERS="EMPRESA_B=http://localhost:7071,EMPRESA_C=http://localhost:7072"
java -jar target/vaijunto-server-1.0-SNAPSHOT-jar-with-dependencies.jar > /tmp/vaijunto_empresa_a.log 2>&1 &
PID_A=$!

export EMPRESA_ID=EMPRESA_B PORTA_TCP=$PORTA_TCP_B PORTA_HTTP=$PORTA_HTTP_B DATA_DIR=./data/empresa_b PEERS="EMPRESA_A=http://localhost:7070,EMPRESA_C=http://localhost:7072"
java -jar target/vaijunto-server-1.0-SNAPSHOT-jar-with-dependencies.jar > /tmp/vaijunto_empresa_b.log 2>&1 &
PID_B=$!

export EMPRESA_ID=EMPRESA_C PORTA_TCP=$PORTA_TCP_C PORTA_HTTP=$PORTA_HTTP_C DATA_DIR=./data/empresa_c PEERS="EMPRESA_A=http://localhost:7070,EMPRESA_B=http://localhost:7071"
java -jar target/vaijunto-server-1.0-SNAPSHOT-jar-with-dependencies.jar > /tmp/vaijunto_empresa_c.log 2>&1 &
PID_C=$!

echo "Servidores iniciados:"
for PID in "$PID_A" "$PID_B" "$PID_C"; do echo "  PID $PID"; done

echo "
URLs:
  EMPRESA_A: http://localhost:$PORTA_HTTP_A/api/v1/saude
  EMPRESA_B: http://localhost:$PORTA_HTTP_B/api/v1/saude
  EMPRESA_C: http://localhost:$PORTA_HTTP_C/api/v1/saude
"

wait "$PID_A" "$PID_B" "$PID_C"
