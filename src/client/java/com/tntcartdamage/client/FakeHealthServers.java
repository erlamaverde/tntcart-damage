package com.tntcartdamage.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Some servers (e.g. PvPHQ) send FAKE health values for other players, so that mods showing
 * opponents' health don't work. On these servers other players' health is meaningless: only
 * the absorption hearts are real.
 * We remember them in a file, so next time we know right away.
 */
public final class FakeHealthServers {
	private static final Path FILE =
			FabricLoader.getInstance().getConfigDir().resolve("tntcartdamage-fake-health-servers.txt");

	/** Servers we already know about (loaded from the file the first time they're needed). */
	private static Set<String> known;

	private FakeHealthServers() {
	}

	public static boolean isKnown(String server) {
		load();
		return known.contains(server);
	}

	public static void remember(String server) {
		load();
		if (known.add(server)) {
			try {
				Files.write(FILE, known);
			} catch (IOException ignored) {
				// Failing to save is not a big deal: we'll find out again next time.
			}
		}
	}

	/**
	 * The server we're connected to, reduced to its main domain so that all the sub-servers
	 * of a network count as one:
	 * "play.lim-de.pvphq.com:25565" -> "pvphq.com". null in singleplayer.
	 */
	public static String currentServerKey() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		if (server == null || server.ip == null) {
			return null;
		}
		String host = server.ip.trim().toLowerCase(Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon >= 0 && host.indexOf(':') == colon) {
			host = host.substring(0, colon);
		}
		if (host.matches("[0-9.]+")) {
			return host;
		}
		String[] parts = host.split("\\.");
		return parts.length <= 2 ? host : parts[parts.length - 2] + "." + parts[parts.length - 1];
	}

	private static void load() {
		if (known != null) {
			return;
		}
		known = new HashSet<>();
		try {
			if (Files.exists(FILE)) {
				for (String line : Files.readAllLines(FILE)) {
					if (!line.isBlank()) {
						known.add(line.trim());
					}
				}
			}
		} catch (IOException ignored) {
			// Unreadable file: start with no known servers.
		}
	}
}
