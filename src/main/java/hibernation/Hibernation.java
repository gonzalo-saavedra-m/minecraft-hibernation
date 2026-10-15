package hibernation;

import com.mojang.logging.LogUtils;
import com.sun.management.HotSpotDiagnosticMXBean;
import hibernation.mixin.DedicatedServerAccessor;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.rcon.thread.RconThread;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.crac.CheckpointException;
import org.crac.Core;
import org.crac.RestoreException;
import org.slf4j.Logger;

/**
 * /hibernate saves the world, closes everything a checkpoint can't carry (listening sockets, region files, file
 * watchers) and
 * checkpoints the JVM to -XX:CRaCCheckpointTo; the JVM then exits. Started again with -XX:CRaCRestoreFrom, it
 * resumes right after the checkpoint and reopens what it closed. The image is only valid while the world on disk is
 * the one in memory, so it is deleted on restore and on any cold start.
 */
public class Hibernation implements ModInitializer {
	public static final String RESTORED = "Hibernation: restored";
	public static final Set<RegionFiles> REGION_FILES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static final Logger LOG = LogUtils.getLogger();

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> dispatcher.register(Commands.literal("hibernate")
			.requires(s -> s.hasPermission(4))
			.executes(c -> {
				if (!(c.getSource().getServer() instanceof DedicatedServer server) || image() == null) {
					c.getSource().sendFailure(Component.literal("Hibernation needs a dedicated server on a CRaC JDK with -XX:CRaCCheckpointTo"));
					return 0;
				}
				// Delayed so the RCON client that sent this gets its answer and disconnects first
				CompletableFuture.runAsync(() -> hibernate(server), CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS, server));
				c.getSource().sendSuccess(() -> Component.literal("Hibernating in 2 s"), true);
				return 1;
			})));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> deleteImage());
	}

	private static void hibernate(DedicatedServer server) {
		if (server.getPlayerCount() > 0) {
			LOG.warn("Hibernation: cancelled, {} players online", server.getPlayerCount());
			return;
		}
		server.saveEverything(false, true, true);
		RconThread rcon = ((DedicatedServerAccessor) server).getRconThread();
		if (rcon != null) rcon.stop();
		server.getConnection().stop();
		boolean restored = false;
		try {
			// The checkpoint engine reopens files read-only, so region files open for writing must be closed
			synchronized (REGION_FILES) {
				for (RegionFiles files : REGION_FILES) files.hibernation$release();
			}
			closeWatchServices();
			LOG.info("Hibernation: checkpointing to {}", image());
			checkpoint();
			restored = true;
		} catch (RestoreException e) {
			restored = true;
			LOG.error("Hibernation: restored, but some resources failed to restore", e);
		} catch (Exception e) {
			LOG.error("Hibernation: checkpoint failed, the server keeps running", e);
		}
		if (restored) {
			// Log files came back read-only. Stopping the appenders releases their file managers, which log4j would
			// otherwise reuse; the reconfigure then opens new ones (and rolls latest.log over).
			for (LoggerContext ctx : ((Log4jContextFactory) LogManager.getFactory()).getSelector().getLoggerContexts()) {
				ctx.getConfiguration().getAppenders().values().forEach(Appender::stop);
				ctx.reconfigure();
			}
			deleteImage();
		}
		try {
			String ip = server.getLocalIp();
			server.getConnection().running = true;
			server.getConnection().startTcpServerListener(ip.isEmpty() ? null : InetAddress.getByName(ip), server.getPort());
		} catch (IOException e) {
			LOG.error("Hibernation: can't reopen port {}, stopping", server.getPort(), e);
			server.halt(false);
			return;
		}
		if (server.getProperties().enableRcon) ((DedicatedServerAccessor) server).setRconThread(RconThread.create(server));
		if (restored) LOG.info(RESTORED);
	}

	/** Retries: a background thread (a DNS lookup, a telemetry request) can hold a socket at that exact moment. */
	private static void checkpoint() throws CheckpointException, RestoreException, InterruptedException {
		for (int attempt = 1; ; attempt++) {
			try {
				Core.checkpointRestore();
				return;
			} catch (CheckpointException e) {
				if (attempt == 3) throw e;
				LOG.warn("Hibernation: checkpoint attempt {} failed, retrying in 3 s: {}", attempt, e.getSuppressed().length > 0 ? e.getSuppressed()[0].getMessage() : e.toString());
				Thread.sleep(3000);
			}
		}
	}

	/**
	 * Config mods watch their files with WatchService, and CRaC's inotify support throws while checkpointing several
	 * of them, hanging the checkpoint. Nobody edits a server's config live, so they are closed. Reaching them needs
	 * --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.nio.fs=ALL-UNNAMED.
	 */
	private static void closeWatchServices() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (!t.getName().equals("FileSystemWatchService")) continue;
			try {
				Object holder = field(Thread.class, "holder").get(t);
				Object poller = field(holder.getClass(), "task").get(holder);
				((WatchService) field(poller.getClass(), "watcher").get(poller)).close();
			} catch (ReflectiveOperationException | RuntimeException | IOException e) {
				LOG.warn("Hibernation: can't close a file watcher ({}); the checkpoint may hang", e.toString());
			}
		}
	}

	private static Field field(Class<?> c, String name) throws NoSuchFieldException {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	/** -XX:CRaCCheckpointTo, or null when this JVM can't checkpoint. */
	private static Path image() {
		try {
			String dir = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("CRaCCheckpointTo").getValue();
			return dir.isEmpty() ? null : Path.of(dir);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void deleteImage() {
		Path dir = image();
		if (dir == null || !Files.isDirectory(dir)) return;
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path f : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) if (!f.equals(dir)) Files.delete(f);
		} catch (IOException e) {
			LOG.error("Hibernation: can't delete the stale image in {}; delete it before the next start", dir, e);
		}
	}
}
