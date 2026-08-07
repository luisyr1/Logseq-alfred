#!/usr/bin/env bash

set -euo pipefail

NODE_22_BIN="/opt/homebrew/opt/node@22/bin"
JAVA_17_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
PYTHON_311_BIN="/opt/homebrew/opt/python@3.11/libexec/bin"

export PATH="${NODE_22_BIN}:${JAVA_17_HOME}/bin:${PYTHON_311_BIN}:/opt/homebrew/bin:${PATH}"
export JAVA_HOME="${JAVA_17_HOME}"

if [[ ! -x "${NODE_22_BIN}/node" || ! -x "${JAVA_17_HOME}/bin/java" ]]; then
  echo "Falta el entorno local de Alfred. Instala node@22 y openjdk@17 con Homebrew."
  exit 1
fi

echo "Entorno Alfred: Node $(node --version), Yarn $(yarn --version), Java $(java -version 2>&1 | head -n 1), $(python3 --version)"
echo "Instalando dependencias reproducibles…"
yarn install --frozen-lockfile

echo "Compilando recursos de Logseq…"
yarn gulp:build

echo "Compilando la aplicación Electron…"
yarn cljs:release-electron

echo "Validación local completada. Ya se puede publicar el cambio."
