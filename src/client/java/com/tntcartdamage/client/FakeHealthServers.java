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
 * Alcuni server (es. PvPHQ) mandano una vita FINTA degli altri player, per non far
 * funzionare le mod che mostrano la vita degli avversari. Su questi server la vita degli
 * altri non vale niente: sono veri solo i cuori gialli.
 * Ce li ricordiamo in un file, così dalla prossima volta lo sappiamo subito.
 */
public final class FakeHealthServers {
	private static final Path FILE =
			FabricLoader.getInstance().getConfigDir().resolve("tntcartdamage-fake-health-servers.txt");

	/** Server già conosciuti (caricati dal file la prima volta che servono). */
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
				// Non riuscire a salvare non è grave: lo riscopriamo la prossima volta.
			}
		}
	}

	/**
	 * Il server a cui siamo connessi, ridotto al dominio principale, così i vari
	 * sotto-server di una rete contano come uno solo:
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
			// File illeggibile: partiamo senza server conosciuti.
		}
	}
}
