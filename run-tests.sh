#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# --- JDK 21 required (JaCoCo 0.8.13 breaks on 26; class-file 65 breaks 17 forks) ---
pick_jdk21() {
  # Respect an already-correct JAVA_HOME.
  if [[ -n "${JAVA_HOME:-}" ]] && "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"21\.'; then
    return
  fi
  # macOS: ask the system for a 21 JDK (Temurin).
  if [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"
    export JAVA_HOME
    return
  fi
  # Linux fallback: common Temurin install locations.
  for candidate in /usr/lib/jvm/temurin-21-jdk* /usr/lib/jvm/java-21-*; do
    if [[ -x "$candidate/bin/java" ]]; then
      JAVA_HOME="$candidate"
      export JAVA_HOME
      return
    fi
  done
  echo "ERROR: JDK 21 not found. Install Temurin 21 or export JAVA_HOME to a 21 JDK (see AGENTS.md)." >&2
  exit 1
}
pick_jdk21
echo "Using JAVA_HOME=$JAVA_HOME"

# --- Maven toolchain: the build compiles/tests with JDK 21 no matter which JVM
#     runs Maven (see root pom maven-toolchains-plugin). Generate the user-level
#     toolchains.xml once if it doesn't exist yet.
if [[ ! -f "$HOME/.m2/toolchains.xml" ]]; then
  mkdir -p "$HOME/.m2"
  cat > "$HOME/.m2/toolchains.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
      <vendor>temurin</vendor>
    </provides>
    <configuration>
      <jdkHome>${JAVA_HOME}</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
EOF
  echo "Generated $HOME/.m2/toolchains.xml (jdkHome=$JAVA_HOME)"
fi

# --- Local credentials: docker compose reads .env automatically; export them here
#     too so Testcontainers/local runs launched from this script see the same values.
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

mvn -T1C verify
if [[ -f web/package.json ]]; then
  (cd web && npm install && npm run test:ci)
fi
