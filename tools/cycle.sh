#!/bin/zsh
# End-to-end check on Linux ARM64/x64 via Docker: cold start -> hibernate -> restore -> exercise -> hibernate -> restore.
# Usage: tools/cycle.sh <server dir>
#   The server dir needs fabric-server.jar, this mod in mods/, and server.properties with enable-rcon=true,
#   rcon.password=<something> and use-native-transport=false. It is copied into a Docker volume (bind mounts from
#   macOS break the checkpoint: files come back under paths that don't exist in the next container).
# Prints timings; any "FAIL" line means the port or the change is broken.
set -u
SRV=${1:a}; HERE=${0:a:h:h}; IMG=azul/zulu-openjdk:21-jdk-crac-latest; VOL=hibernation-cycle
r() { python3 $HERE/tools/rcon.py $SRV "$@"; }
now() { python3 -c 'import time; print(time.time())'; }
since() { python3 -c "print(round($(now) - $1, 1))"; }
run() { docker rm -f $VOL >/dev/null 2>&1; docker run -d --name $VOL -p 25575:25575 -v $VOL:/srv -v $HERE:/h:ro -w /srv $IMG sh /h/start.sh -Xmx4G >/dev/null; }
up() { for i in $(seq 1200); do r list >/dev/null 2>&1 && return 0; docker ps -q -f name=$VOL -f status=running | grep -q . || return 1; sleep 0.25; done; return 1; }
gone() { for i in $(seq 600); do docker ps -q -f name=$VOL -f status=running | grep -q . || return 0; sleep 0.5; done; return 1; }
docker volume rm -f $VOL >/dev/null; docker volume create $VOL >/dev/null
docker run --rm -v $SRV:/from:ro -v $VOL:/srv alpine sh -c 'cp -a /from/. /srv/ && rm -rf /srv/.hibernation /srv/.tmp'
T=$(now); run; up || echo FAIL: cold start; echo "cold start: $(since $T)s"
sleep 3; r hibernate; T=$(now); gone; echo "checkpoint: $(since $T)s"
docker logs $VOL 2>&1 | grep -q "Hibernated" || { echo FAIL: no image; docker logs $VOL 2>&1 | grep -E "Hibernation|Suppressed" | head; exit 1; }
T=$(now); run; up || echo FAIL: restore; echo "restore: $(since $T)s (includes the image key, ~2 s)"
r forceload add 0 0 >/dev/null; r setblock 0 200 0 minecraft:gold_block | grep -q Changed || echo FAIL: setblock
r save-all flush | grep -q Saved || echo FAIL: save
docker exec $VOL grep -q "Hibernation: restored" /srv/logs/latest.log || echo FAIL: log not written after restore
docker logs $VOL 2>&1 | grep -E "Failed to (store|save) chunk|Bad file descriptor" | head -3 | sed 's/^/FAIL: /'
r hibernate; gone; T=$(now); run; up || echo FAIL: second restore; echo "second restore: $(since $T)s"
docker rm -f $VOL >/dev/null
