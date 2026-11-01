#!/bin/bash
set -e

cd "$(dirname "$0")"

ARGS="${@:-}"

mvn -q -DskipTests compile exec:java \
  -Dexec.mainClass="simulador.SimuladorPassageiros" \
  -Dexec.args="$ARGS"
