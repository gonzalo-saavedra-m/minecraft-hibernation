# Hibernation

A Fabric mod for dedicated servers that sit idle most of the time, such as a server for friends that shuts down
when nobody plays. Instead of stopping, the server **hibernates**: the whole JVM, with every mod and the world already
loaded, is saved to disk with [CRaC](https://docs.azul.com/crac/). The next start restores it in about a second
instead of loading everything again.

Measured with a Cobblemon pack of 114 mods (Minecraft 1.21.1, ARM64):

| | Time |
|---|---|
| Cold start | 53 s |
| Restore | 0.8 s |
| Checkpoint (image of ~1.7 GB) | 7–10 s |

## How it works

`/hibernate` (permission level 4; meant for RCON or the console) waits 2 s so the RCON client gets its answer, then on
the server thread:

1. Refuses if anyone is online.
2. Saves the world and flushes it to disk.
3. Closes what a checkpoint can't carry: the game port, RCON, region files (the checkpoint engine reopens files
   read-only) and the `WatchService`s that config mods use (CRaC hangs on them).
4. Checkpoints the JVM, which then exits. A transient failure (a socket that a background thread opened right then)
   is retried twice, 3 s apart. If it still fails, everything is reopened and the server keeps running.

On restore, the same code continues: it reopens the port, RCON and the log files, and logs `Hibernation: restored`.

**The image is only valid while the world on disk is the one in memory.** The mod deletes it right after a restore and
on every cold start. Whatever launches the server must also discard it whenever anything else changes (mods,
configs, JDK, JVM flags, CPU model), and when a restore fails.

## Requirements

- A JDK with CRaC on Linux x64 or ARM64, such as Azul Zulu 21 CRaC. The bundled `org.crac` API makes the mod load on
  any JDK; without CRaC, `/hibernate` only reports that it can't hibernate.
- The same JDK, at the same path, for the checkpoint and the restore.
- Restore on the same CPU model. The JIT compiles code for the CPU it runs on (for example, AWS Graviton3 and
  Graviton4 have different vector widths).

JVM flags for the cold start:

```sh
java -XX:CRaCCheckpointTo=<image dir> \
  -Djdk.crac.resource-policies=<policies.yaml> \
  -XX:CRaCAllowedOpenFilePrefixes=/sys/fs/cgroup/,/proc/,/dev/null,<server>/config/spark/ \
  --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED \
  -Djava.io.tmpdir=<server>/.tmp \
  -Djdk.httpclient.keepalive.timeout=2 \
  -jar fabric-server.jar nogui
```

To restore: `java -XX:CRaCRestoreFrom=<image dir>`.

- **`CRaCAllowedOpenFilePrefixes`** lists files that native code keeps open: cgroup limits, `/proc`, and spark's
  profiler.
- **`--add-opens`** lets the mod reach and close the `WatchService`s.
- **`java.io.tmpdir`** must survive the restart, because libraries extract native `.so` files there and the restored
  process still maps them. On a fresh machine, `/tmp` is empty.
- **`keepalive.timeout`** closes idle HTTPS connections (mod telemetry) quickly, so none is open at checkpoint time.
- **authlib-injector:** add `-Dauthlibinjector.noLogFile`. Its log file would come back read-only.

In `server.properties`, set `use-native-transport=false`. The checkpoint can't carry epoll's native descriptors.

`policies.yaml`:

```yaml
# Listening sockets (for example, authlib-injector's local httpd): closed, then reopened on the same address
type: socket
listening: true
action: reopen
---
# Files: left open, and the checkpoint engine reopens them read-only (mod jars, session.lock)
type: file
action: ignore
warn: false
```

## Known limits

- Only vanilla region files are closed. A mod that keeps its own file open for writing gets it back read-only.
- spark's background profiler logs a JFR parsing error after a restore. It is harmless.
- `/proc/<pid>/task`, which the JVM keeps open, comes back as `/dev/null`. The first checkpoint after a restore may
  need the retry.

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build   # build/libs/hibernation-<version>.jar
```
