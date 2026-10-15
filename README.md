# Hibernation

A Fabric mod for dedicated Minecraft servers that sit idle most of the time, such as a server for friends that
shuts down when nobody plays. Instead of stopping, the server **hibernates**: the whole JVM, with every mod and the
world already loaded, is saved to disk with [CRaC](https://docs.azul.com/crac/). The next start restores it in about
a second instead of loading everything again.

Measured with a Cobblemon pack of 114 mods (Minecraft 1.21.1, ARM64):

| | Time |
|---|---|
| Cold start | 46–53 s |
| Restore | 0.8 s (about 3 s with `start.sh`, which hashes the server first) |
| Hibernate (image of ~1.5 GB) | 7–10 s |

## Compatibility

| | |
|---|---|
| Minecraft | **1.21.1 only.** The mod touches server internals, so each version needs its own build ([AGENTS.md](AGENTS.md) explains how to port it) |
| Loader | Fabric, with **Fabric API** |
| Side | Server only. Players don't install it |
| JDK | A JDK with CRaC on **Linux x64 or ARM64**, such as [Azul Zulu 21 CRaC](https://www.azul.com/downloads/?package=jdk-crac). Not Windows or macOS |
| Paper, Spigot, Forge, NeoForge | No |

On a normal JDK the mod loads and does nothing: `/hibernate` answers that it can't hibernate.

## Install

1. Install a CRaC JDK, for example by unpacking Zulu 21 CRaC into `/opt/zulu-crac`.
2. Put the mod jar in `mods/`, next to Fabric API.
3. Copy [`start.sh`](start.sh) and [`hibernation-policies.yaml`](hibernation-policies.yaml) into the server folder
   (or anywhere else, as long as both are together).
4. In `server.properties`, set `use-native-transport=false`. The checkpoint can't carry the native epoll
   descriptors.
5. Start the server with the script instead of `java -jar`:

   ```sh
   JAVA=/opt/zulu-crac/bin/java ./start.sh -Xmx6G
   ```

   Arguments go to the JVM. `SERVER_JAR` changes the launcher's name (default: `fabric-server.jar`).

## Use

- **Hibernate:** run `hibernate` in the console or over RCON (permission level 4). Two seconds later the server saves
  the world, writes the image to `.hibernation/` and exits. If anyone is online, it doesn't hibernate.
- **Resume:** run `./start.sh` with the same arguments. If the image still matches the server, it restores in seconds.
  Otherwise it starts cold, like `java -jar` would.

A typical setup automates both: a script that runs `hibernate` over RCON after N minutes without players, and
whatever starts your machine or container runs `./start.sh`.

### When it starts cold anyway

The image is discarded, and the server starts cold, when any of these changed since it hibernated:

- mods, configs, `server.properties` or the server jar (for example, a modpack update);
- the JDK or the JVM arguments;
- the CPU model, because the JIT compiled code for that CPU.

It is also discarded when the restore fails. The world on disk is always the one the server saved, so nothing is
lost in any of these cases.

To hibernate on one machine and resume on another, both need the same CPU model and the server folder (with
`.hibernation/` and `.tmp/`) at the same path.

## How it works

`/hibernate` runs on the server thread, so nothing ticks meanwhile:

1. Refuses if anyone is online.
2. Saves the world and flushes it to disk.
3. Closes what a checkpoint can't carry: the game port, RCON, region files (the checkpoint engine reopens files
   read-only) and the file watchers that config mods use (CRaC hangs on them).
4. Checkpoints the JVM, which then exits. A transient failure is retried twice; if it still fails, the server
   reopens everything and keeps running.

On restore, execution continues right after the checkpoint: the mod reopens the logs, the port and RCON, deletes the
image, and logs `Hibernation: restored`.

**The image is valid only while the world on disk is the one in memory.** That's why the mod deletes it on every
restore and every cold start, and `start.sh` deletes it when the server changed or a restore failed.

## Known limits

- Only vanilla region files are closed. A mod that keeps its own file open for writing gets it back read-only.
- spark's background profiler logs a JFR parsing error after a restore. It is harmless.
- In Docker, the image must live in a volume, not a bind mount from macOS. Tested only in Docker and on AWS EC2
  (Graviton).

## Build

```sh
JAVA_HOME=<JDK 25> ./gradlew build   # build/libs/hibernation-<version>.jar
```
