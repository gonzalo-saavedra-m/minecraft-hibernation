package hibernation.mixin;

import hibernation.Hibernation;
import hibernation.RegionFiles;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.IOException;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements RegionFiles {
	@Shadow @Final private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;

	@Shadow public abstract void close() throws IOException;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void hibernation$track(CallbackInfo ci) {
		Hibernation.REGION_FILES.add(this);
	}

	@Override
	public void hibernation$release() throws IOException {
		close(); // vanilla close() keeps the closed files cached
		regionCache.clear();
	}
}
