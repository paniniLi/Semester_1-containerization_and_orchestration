#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

exec java \
  -jar "$SCRIPT_DIR/../lib/lab1-1.0.0.jar" \
  --portNumber=8080
