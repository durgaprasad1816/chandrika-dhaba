#!/bin/sh
# Build (if Maven is installed) and start Chandrika Dhaba
cd "$(dirname "$0")" || exit 1
if [ ! -f target/chandrika-dhaba.jar ] && command -v mvn >/dev/null 2>&1; then
  echo "Building with Maven (first time needs internet for Maven plugins)..."
  mvn -q clean package || echo "Maven build failed - using the ready-made jar in dist/"
fi
JAR=target/chandrika-dhaba.jar
[ -f "$JAR" ] || JAR=dist/chandrika-dhaba.jar
exec java -jar "$JAR" "$@"
