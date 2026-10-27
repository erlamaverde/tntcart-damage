package com.tntcartdamage.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dove si trova un player "secondo il server".
 *
 * Due problemi:
 *  1. Il client disegna gli altri player un po' in ritardo: quando arriva una posizione nuova,
 *     il player ci scivola sopra in qualche frame (interpolazione).
 *  2. Il server manda la posizione di un player che si muove solo ogni 2 tick, e quella che ha
 *     usato per l'esplosione arriva DOPO il pacchetto dell'esplosione (insieme alla vita).
 * Quindi prendiamo l'ultima posizione mandata dal server e, se il player si sta muovendo,
 * la spostiamo avanti di 1 tick con la sua velocità.
 */
public final class ServerPositions {
	/** Un player che si muove riceve una posizione nuova ogni 2 tick: se non ne arriva una
	 *  da più di questi tick, è fermo. */
	private static final int MOVING_TIMEOUT_TICKS = 3;
	/** Se tra due posizioni passano più di questi tick, non le usiamo per la velocità. */
	private static final int MAX_SAMPLE_GAP_TICKS = 4;
	/** Oltre questa velocità (blocchi/tick) è un teletrasporto, non movimento. */
	private static final double MAX_SPEED = 4.0;

	/** Una posizione mandata dal server e il tick del client in cui l'abbiamo vista. */
	private record Sample(Vec3 pos, int tick) {
	}

	/** id del player -> [0] = ultima posizione, [1] = quella prima. */
	private static final Map<Integer, Sample[]> history = new HashMap<>();

	private ServerPositions() {
	}

	/** L'ultima posizione mandata dal server (per il nostro player, quella attuale). */
	public static Vec3 latest(Player player) {
		if (player instanceof LocalPlayer) {
			return player.position();
		}
		InterpolationHandler interpolation = player.getInterpolation();
		if (interpolation != null && interpolation.hasActiveInterpolation()) {
			return interpolation.position();
		}
		return player.position();
	}

	/** Da chiamare ogni tick: si segna quando arrivano posizioni nuove. */
	public static void tick(ClientLevel level, int clientTick) {
		Set<Integer> present = new HashSet<>();
		for (AbstractClientPlayer player : level.players()) {
			present.add(player.getId());
			Vec3 pos = latest(player);
			Sample[] samples = history.computeIfAbsent(player.getId(), id -> new Sample[2]);
			if (samples[0] == null || samples[0].pos().distanceToSqr(pos) > 1.0e-6) {
				samples[1] = samples[0];
				samples[0] = new Sample(pos, clientTick);
			}
		}
		history.keySet().retainAll(present);
	}

	public static void clear() {
		history.clear();
	}

	/** Stima della posizione del player sul server in questo momento. */
	public static Vec3 estimate(Player player, int clientTick) {
		Vec3 latest = latest(player);
		if (player instanceof LocalPlayer) {
			return latest;
		}
		Sample[] samples = history.get(player.getId());
		if (samples == null || samples[0] == null) {
			return latest;
		}
		Sample newest = samples[0];
		Sample older = samples[1];
		// È arrivata una posizione nuova in questo stesso frame, prima dell'esplosione?
		if (newest.pos().distanceToSqr(latest) > 1.0e-6) {
			older = newest;
			newest = new Sample(latest, clientTick);
		}
		if (older == null) {
			return latest;
		}
		int sinceNewest = clientTick - newest.tick();
		int between = newest.tick() - older.tick();
		// Fermo (niente posizioni nuove da un po') o dati troppo vecchi: niente movimento.
		if (sinceNewest > MOVING_TIMEOUT_TICKS || between < 1 || between > MAX_SAMPLE_GAP_TICKS) {
			return latest;
		}
		Vec3 velocity = newest.pos().subtract(older.pos()).scale(1.0 / between);
		if (velocity.lengthSqr() > MAX_SPEED * MAX_SPEED) {
			return latest;
		}
		return latest.add(velocity);
	}
}
