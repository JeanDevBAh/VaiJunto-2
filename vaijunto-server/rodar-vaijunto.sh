#!/bin/bash
export EMPRESA_ID=VAIJUNTO
export PORTA_TCP=8080
export PORTA_HTTP=7070
export DATA_DIR=./data/vaijunto
export PEERS="VAIJUNTO=http://localhost:7070,EMPRESA_B=http://localhost:7071,EMPRESA_C=http://localhost:7072"
exec java -jar target/vaijunto-server-1.0-SNAPSHOT-jar-with-dependencies.jar
