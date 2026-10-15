package hibernation;

import java.io.IOException;

/** Duck interface added to every RegionFileStorage (chunks, POI and entities of each dimension). */
public interface RegionFiles {
	/** Closes the open region files and forgets them; the next read or write reopens them. */
	void hibernation$release() throws IOException;
}
