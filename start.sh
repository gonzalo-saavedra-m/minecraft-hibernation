#!/bin/sh
# Starts a Fabric server that has the Hibernation mod. If a hibernated JVM is waiting and still matches this server
# (same mods, configs, JDK, flags and CPU), restores it in about a second; otherwise starts cold, ready to hibernate.
# Run it from the server folder, where you'd run java -jar. Hibernate with the `hibernate` command (console or RCON):
# the server exits, and the next ./start.sh resumes it.
#
# Usage: ./start.sh [JVM flags, e.g. -Xmx6G]
# Env:   JAVA        a CRaC JDK (default: java on the PATH)
#        SERVER_JAR  the Fabric server launcher (default: fabric-server.jar)
set -u
JAVA=${JAVA:-java}; SERVER_JAR=${SERVER_JAR:-fabric-server.jar}; IMG=.hibernation; POLICIES=$(dirname "$0")/hibernation-policies.yaml
# Everything the restored JVM takes for granted. The CPU line matters: JIT code is compiled for this CPU model.
key() { { "$JAVA" -version 2>&1; grep -m1 -E '^(model name|CPU part)' /proc/cpuinfo; echo "$SERVER_JAR $*"
  find mods config "$SERVER_JAR" server.properties -type f ! -path 'config/spark/*' -print0 2>/dev/null | sort -z | xargs -0 sha256sum
  } | sha256sum | cut -c1-64; }

if [ -f $IMG/key ] && [ "$(cat $IMG/key)" = "$(key "$@")" ]; then
  mv $IMG/key $IMG/restoring
  "$JAVA" -XX:CRaCRestoreFrom=$IMG; rc=$?
else
  rm -rf $IMG .tmp && mkdir -p $IMG .tmp
  "$JAVA" -XX:CRaCCheckpointTo=$IMG -Djdk.crac.resource-policies="$POLICIES" \
    -XX:CRaCAllowedOpenFilePrefixes=/sys/fs/cgroup/,/proc/,/dev/null,"$PWD/config/spark/" \
    --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED \
    -Djava.io.tmpdir="$PWD/.tmp" -Djdk.httpclient.keepalive.timeout=2 -Dauthlibinjector.noLogFile \
    "$@" -jar "$SERVER_JAR" nogui; rc=$?
fi
if [ -f $IMG/restoring ]; then rm -rf $IMG; echo "Restore failed; the next start is cold." >&2   # never retry a bad image
elif [ -f $IMG/core.img ]; then key "$@" > $IMG/key; echo "Hibernated. Run $0 again to resume."; exit 0; fi
exit $rc
