package hibernation.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.rcon.thread.RconThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DedicatedServer.class)
public interface DedicatedServerAccessor {
	@Accessor RconThread getRconThread();
	@Accessor void setRconThread(RconThread thread);
}
