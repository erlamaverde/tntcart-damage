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
 * Where a player is "according to the server".
 *
 * Two problems:
 *  1. The client draws other players slightly behind: when a new position arrives, the player
 *     slides onto it over a few frames (interpolation).
 *  2. The server only sends a moving player's position every 2 ticks, and the one it used for
 *     the explosion arrives AFTER the explosion packet (together with the health).
 * So we take the last position sent by the server and, if the player is moving, we push it
 * 1 tick ahead using their velocity.
 */
public final class ServerPositions {
	/** A moving player gets a new position every 2 ticks: if none has arrived for more than
	 *  this many ticks, they're standing still. */
	private static final int MOVING_TIMEOUT_TICKS = 3;
	/** If more than this many ticks pass between two positions, we don't use them for velocity. */
	private static final int MAX_SAMPLE_GAP_TICKS = 4;
	/** Above this speed (blocks/tick) it's a teleport, not movement. */
	private static final double MAX_SPEED = 4.0;

	/** A position sent by the server and the client tick in which we saw it. */
	private record Sample(Vec3 pos, int tick) {
	}

	/** player id -> [0] = latest position, [1] = the one before it. */
	private static final Map<Integer, Sample[]> history = new HashMap<>();

	private ServerPositions() {
	}

	/** The last position sent by the server (for our own player, the current one). */
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

	/** Call every tick: records when new positions arrive. */
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

	/** Estimate of the player's position on the server right now. */
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
		// Did a new position arrive in this same frame, before the explosion?
		if (newest.pos().distanceToSqr(latest) > 1.0e-6) {
			older = newest;
			newest = new Sample(latest, clientTick);
		}
		if (older == null) {
			return latest;
		}
		int sinceNewest = clientTick - newest.tick();
		int between = newest.tick() - older.tick();
		// Standing still (no new positions for a while) or data too old: no movement.
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
