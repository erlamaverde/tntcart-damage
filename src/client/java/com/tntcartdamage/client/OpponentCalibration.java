package com.tntcartdamage.client;

import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Learns from real hits how much an opponent ACTUALLY reduces damage after armor.
 *
 * The client can't know everything: the server may hide other players' enchantments
 * (Paper "item obfuscation"), it doesn't send the Resistance level, and it may run plugins that
 * change cart damage. But after each hit we know how much damage really got through:
 *     factor = real damage / damage after armor
 * and we use it for the next hits on the same player, as long as they wear the same armor.
 *
 * To avoid learning wrong things:
 *  - if the prediction had to GUESS something (hidden enchantments, Resistance only seen
 *    through its particles), one hit is enough to use the learned value;
 *  - otherwise the calculation is already exact, and we only change it if two hits in a row
 *    agree (e.g. a server plugin that changes cart damage).
 */
public final class OpponentCalibration {
	/** How long until we forget what we learned (3 minutes). */
	private static final int MAX_AGE_TICKS = 20 * 60 * 3;
	/** Two measurements "agree" if they differ by less than 10%. */
	private static final float SAME_RESULT_TOLERANCE = 0.1f;

	/**
	 * equipment: "fingerprint" of the armor (item type + visible enchantments);
	 * resistanceLevel: the Resistance level we saw (for other players: 1 if they have the particles).
	 * If either of them changes, what we learned is no longer valid.
	 * confirmations: how many hits in a row gave the same factor.
	 */
	private record Entry(String equipment, int resistanceLevel, float factor, int confirmations, int tick) {
		boolean matches(String otherEquipment, int otherResistance, int now) {
			return now - tick <= MAX_AGE_TICKS
					&& equipment.equals(otherEquipment)
					&& resistanceLevel == otherResistance;
		}
	}

	private static final Map<UUID, Entry> entries = new HashMap<>();

	private OpponentCalibration() {
	}

	/**
	 * The learned factor for this player, or null if we don't have one we can trust.
	 * uncertain: the prediction had to guess something (see above).
	 */
	public static Float factor(Player player, String equipment, int resistanceLevel, boolean uncertain, int now) {
		Entry entry = entries.get(player.getUUID());
		if (entry == null || !entry.matches(equipment, resistanceLevel, now)) {
			return null;
		}
		int needed = uncertain ? 1 : 2;
		return entry.confirmations() >= needed ? entry.factor() : null;
	}

	public static void learn(Player player, String equipment, int resistanceLevel, float factor, int now) {
		Entry old = entries.get(player.getUUID());
		if (old != null && old.matches(equipment, resistanceLevel, now)
				&& Math.abs(old.factor() - factor) <= Math.max(SAME_RESULT_TOLERANCE * old.factor(), 0.02f)) {
			// Confirmed: same result as before. Average them.
			entries.put(player.getUUID(), new Entry(equipment, resistanceLevel,
					(old.factor() + factor) / 2.0f, old.confirmations() + 1, now));
		} else {
			entries.put(player.getUUID(), new Entry(equipment, resistanceLevel, factor, 1, now));
		}
	}

	public static void clear() {
		entries.clear();
	}
}
