#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NODE_22_BIN="/opt/homebrew/opt/node@22/bin"
JAVA_17_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
PYTHON_311_BIN="/opt/homebrew/opt/python@3.11/libexec/bin"
PREVIEW_DATA="${LOGSEQ_ALFRED_PREVIEW_DATA:-${HOME}/Library/Application Support/Logseq Alfred Preview}"

export PATH="${NODE_22_BIN}:${JAVA_17_HOME}/bin:${PYTHON_311_BIN}:/opt/homebrew/bin:${PATH}"
export JAVA_HOME="${JAVA_17_HOME}"
export LOGSEQ_ALFRED_PREVIEW=1
export LOGSEQ_ALFRED_PREVIEW_DATA="${PREVIEW_DATA}"

cd "${REPO_ROOT}"

if [[ "${1:-}" != "--skip-build" ]]; then
  scripts/validate-alfred-local.sh
fi

mkdir -p "${PREVIEW_DATA}"

echo "Abriendo Alfred local con un perfil aislado…"
echo "Los cambios sin commit están incluidos. Cierra la ventana para terminar la prueba."
static/node_modules/.bin/electron static
