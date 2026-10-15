# Instructions for agents

Fabric mod that hibernates an idle dedicated server with CRaC. Read [README.md](README.md) first: what it does, how
it's installed and run. This file is for changing, integrating or porting it.

## Files

| File | What it is |
|---|---|
| `src/main/java/hibernation/Hibernation.java` | All the logic: the `/hibernate` command, what gets closed and reopened, retries, deleting the image |
| `src/main/java/hibernation/mixin/RegionFileStorageMixin.java` + `RegionFiles.java` | Tracks every `RegionFileStorage` so the mod can close its open files |
| `src/main/java/hibernation/mixin/DedicatedServerAccessor.java` | Gets and sets the private RCON thread |
| `start.sh` | Generic launcher: restore if the image matches, otherwise cold start with the CRaC flags. The reference integration |
| `hibernation-policies.yaml` | CRaC policy for open sockets and files at checkpoint time |
| `tools/cycle.sh`, `tools/rcon.py` | End-to-end test in Docker; the definition of "works" |

## Invariants (don't break them)

1. **An image is valid only while the world on disk is the one in memory.** It's deleted on restore (the mod), on any
   cold start (the mod and `start.sh`), and when a restore fails (`start.sh`). Any new launcher must keep all three.
   Breaking this means restoring an old world over a newer one.
2. **The restore needs the same JDK path, CPU model, JVM flags and files** that were open at checkpoint time.
   `start.sh` hashes all of that into `.hibernation/key`. Anything new that the JVM depends on goes into `key()`.
3. **Hibernating never loses the server.** If anything fails before the checkpoint, the mod reopens the port and RCON
   and keeps running. Keep every new close step inside the `try` in `hibernate()`.
4. **Checkpoint and restore both run on the server thread.** Don't move the work to another thread: the tick loop
   being blocked is what makes the world consistent.

## Why each piece exists

Each one was found by testing; removing any of them breaks something:

| Piece | Without it |
|---|---|
| Closing `WatchService`s by reflection (+ `--add-opens`) | The checkpoint hangs: CRaC's inotify support throws `ConcurrentModificationException` with several watchers (night-config, config libs) |
| Closing region files (`RegionFileStorageMixin`) | Saving chunks fails with `Bad file descriptor` after restore: the engine (warp) reopens files read-only. Vanilla `close()` keeps closed files cached, hence the `clear()` |
| Stopping the log4j appenders + `reconfigure()` after restore | `latest.log` stays read-only. Without the stop, log4j reuses the broken file manager |
| Closing and reopening the game port and RCON | Listening sockets can't be checkpointed |
| `use-native-transport=false` | Netty's epoll descriptors are native and can't be checkpointed |
| File policy `ignore` | The JDK's own `reopen` can't reopen `FileChannel`s (mod jars opened by Fabric, region files) |
| Socket policy `reopen` for listening sockets | authlib-injector's local httpd blocks the checkpoint |
| `-Djava.io.tmpdir` inside the server folder | The restore fails: libraries extract native `.so` files to `/tmp`, which is empty on a new machine or container |
| `-Djdk.httpclient.keepalive.timeout=2` | Mod telemetry (e.g. matthiesen-core to faststats.dev) keeps HTTPS connections open, which can't be checkpointed |
| `CRaCAllowedOpenFilePrefixes` | Files opened by native code (cgroup limits, `/proc`, spark's profiler) fail the checkpoint |
| 3 attempts, 3 s apart | A background DNS lookup or request can hold a socket at that exact moment |
| `-Dauthlibinjector.noLogFile` | authlib-injector's log comes back read-only |
| `delayedExecutor(2 s)` before hibernating | The RCON client that sent `/hibernate` is still connected |

## Porting to another Minecraft version

The mod uses Mojang names (`loom.officialMojangMappings()`). For each version:

1. Set `minecraft_version`, `loader_version` and `fabric_api_version` in `gradle.properties`, and `minecraft` in
   `src/main/resources/fabric.mod.json`. Build with a JDK 25 for Loom (`JAVA_HOME`); the mod targets Java 21.
2. Check that these still exist with the same shape, with
   `javap -p -cp <loom's minecraft-merged jar> <class>`:
   - `DedicatedServer`: field `rconThread`, `getProperties().enableRcon`, `getLocalIp()`, `getPort()`, `halt(boolean)`
   - `ServerConnectionListener`: `stop()`, `startTcpServerListener(InetAddress, int)`, public `running`
   - `RconThread`: `create(ServerInterface)`, `stop()`
   - `RegionFileStorage`: field `regionCache`, `close()`
   - `MinecraftServer.saveEverything(boolean, boolean, boolean)`
   - From Minecraft 26.1, names come from Mojang mappings by default and the build setup differs; follow the official
     Fabric example mod.
3. If the JDK changes (Java 25 for newer versions), check that the `WatchService` reflection still finds
   `Thread.holder.task` and `LinuxWatchService$Poller.watcher`.
4. Run `tools/cycle.sh` with a real server of that version and its mods. It must print no `FAIL` line.

## Integrating into another launcher (systemd, Docker, panels)

Copy the logic of `start.sh`, not just its flags: the key file, `restoring` and the three deletions are what keep
invariant 1. Two things to watch:

- The image is about the size of the used heap (~1.5 GB with 114 mods). It has to live in persistent storage next to
  the world, and so does `.tmp/`.
- Whatever triggers hibernation must wait for the JVM to exit and check for `.hibernation/key` before shutting the
  machine down. Without the key, the hibernation failed: stop normally.

## Test

```sh
tools/cycle.sh <server dir>   # Docker, Linux ARM64 or x64; see the header for what the server dir needs
```
