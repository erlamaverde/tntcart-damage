package com.tntcartdamage.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out how much damage a TNT minecart dealt to each player.
 *
 * The server sends us separate pieces of information:
 *  1. the explosion (position and radius) and/or a "damage" event with its source, but no amount;
 *  2. shortly after, the player's new health.
 * So: when we see that a TNT cart hit a player we mark them as "hit",
 * and for a few ticks we check how much their health (+ absorption hearts) drops.
 *
 * To avoid waiting for the new health, as soon as the explosion arrives we also compute the
 * predicted damage (DamagePredictor) and show it right away; then the real damage replaces it.
 */
public final class DamageTracker {
	/** How many ticks after the explosion we wait for the health to update. Some servers
	 *  send the health drop in several pieces a few ticks apart: all pieces arriving
	 *  within this window are added to the same number. */
	private static final int HIT_WINDOW_TICKS = 20;
	/** Radius in which to look for a TNT cart when the damage arrives indirectly. */
	private static final double NEARBY_CART_RADIUS = 8.0;

	/** If true, prints in chat what the mod sees (command /tntcartdamage debug). */
	public static boolean debug = false;

	/** entity id -> health and absorption hearts at the previous tick */
	private static final Map<Integer, HealthSnapshot> lastHealth = new HashMap<>();

	private record HealthSnapshot(float health, float absorption) {
	}
	/** entity id -> ticks left during which a health drop counts as TNT cart damage */
	private static final Map<Integer, Integer> pendingHits = new HashMap<>();
	/** ids of players whose current hit already created a number: later pieces of the
	 *  same hit are added to that number instead of creating a new one. */
	private static final Set<Integer> hitShown = new HashSet<>();
	/** entity id -> ticks left during which we DON'T show numbers because their totem just popped */
	private static final Map<Integer, Integer> recentPops = new HashMap<>();

	/** How many ticks we remember a TNT cart after it disappears. */
	private static final int CART_MEMORY_TICKS = 10;
	/** cart id -> last seen position. Needed because on servers the cart can disappear
	 *  from the client just before the explosion packet arrives. */
	private static final Map<Integer, RecentCart> recentCarts = new HashMap<>();

	private record RecentCart(Vec3 pos, int ticksLeft) {
	}

	/** Client tick counter, to know "how long ago" something happened. */
	private static int clientTicks = 0;
	/** player id -> tick in which their latest cart damage event arrived. */
	private static final Map<Integer, Integer> cartHitTick = new HashMap<>();

	/** The real damage of a hit arrives within a few ticks: we only learn from that. */
	private static final int CALIBRATION_WINDOW_TICKS = 4;
	/** If the real position is within this distance (in blocks) of the one used for the
	 *  prediction, the distance and exposure math was right and we can learn from it. */
	private static final double POSITION_MATCH_BLOCKS = 0.05;

	/** A prediction we made, to compare with the real damage and learn from it. */
	private static final class PredictionRecord {
		final DamagePredictor.Prediction prediction;
		final int tick;
		/** Real damage received for this hit. */
		float actualTotal;
		/** Was the real position (sent with the health) the one we used? null = not arrived yet. */
		Boolean positionMatches;
		/** Another explosion hit this player: the real damage isn't only from this hit. */
		boolean contaminated;
		/** Is this player's health real? (Not on fake-health servers, except our own.) */
		boolean healthTrusted;
		/** With fake health: an exactly measured drop arrived (fully absorbed by absorption hearts). */
		boolean sawExact;
		/** With fake health: a drop we CAN'T measure arrived (absorption hearts ran out). */
		boolean sawInexact;

		PredictionRecord(DamagePredictor.Prediction prediction, int tick, boolean healthTrusted) {
			this.prediction = prediction;
			this.tick = tick;
			this.healthTrusted = healthTrusted;
		}

		/** Do we know the real damage of this hit? */
		boolean actualKnown() {
			return healthTrusted ? !sawInexact : sawExact && !sawInexact;
		}
	}

	/** player id -> prediction for the ongoing hit. */
	private static final Map<Integer, PredictionRecord> records = new HashMap<>();

	/** true if the current server sends FAKE health for other players (see FakeHealthServers).
	 *  On these servers only the absorption hearts of other players are real. */
	private static boolean fakeHealthServer = false;
	/** More healing than this in one tick is impossible in vanilla (an Instant Health II potion gives 8). */
	private static final float IMPOSSIBLE_HEAL = 8.5f;
	/** For 10 ticks after a hit, further hits remove health WITHOUT a damage event:
	 *  after that, health dropping without a damage event is impossible in vanilla. */
	private static final int NO_EVENT_DAMAGE_TICKS = 12;
	/** How many impossible health changes, within how much time, before we decide health is fake. */
	private static final int IMPOSSIBLE_CHANGES_NEEDED = 3;
	private static final int IMPOSSIBLE_CHANGES_WINDOW_TICKS = 20 * 30;
	/** Ticks in which we saw an impossible health change. */
	private static final ArrayDeque<Integer> impossibleChanges = new ArrayDeque<>();
	/** player id -> tick of their latest damage event (of any type). */
	private static final Map<Integer, Integer> lastDamageEventTick = new HashMap<>();
	/** player id -> tick we first saw them (right after appearing, health may "jump" to its
	 *  real value: that's not suspicious). */
	private static final Map<Integer, Integer> firstSeenTick = new HashMap<>();

	private DamageTracker() {
	}

	/** Our own player (in automated tests it can pretend to be an opponent). */
	private static boolean isSelf(Player player) {
		return player instanceof LocalPlayer && !DamagePredictor.testTreatSelfAsOpponent;
	}

	/** Called by the mixin when the server says "this player took damage". */
	public static void onDamageEvent(Player player, DamageSource source) {
		lastDamageEventTick.put(player.getId(), clientTicks);
		boolean fromCart = isFromTntCart(player, source);
		if (debug) {
			debugMessage(player.getName().getString() + " damage: type=" + source.getMsgId()
					+ ", source=" + describe(source.getDirectEntity())
					+ (fromCart ? " -> TNT cart!" : ""));
		}
		if (fromCart) {
			// Every cart damage event is a new hit: it gets its own number.
			startHit(player);
			cartHitTick.put(player.getId(), clientTicks);
		} else {
			// Damage from something else (fire, fall, sword...): the next health drop belongs
			// to it, not to the cart, so we close the cart's window.
			endHit(player.getId());
		}
	}

	/** Called by the mixin when a player's totem pops (for any reason). */
	public static void onTotemPop(Player player) {
		if (debug) {
			debugMessage(player.getName().getString() + " pop!");
		}
		DamageIndicators.addPop(player);
		// The totem sets health to 1 and gives absorption hearts, so the health drop that
		// follows must not be shown as a number. We block it for a while because the
		// explosion packet may arrive after the pop and mark the player again.
		endHit(player.getId());
		recentPops.put(player.getId(), HIT_WINDOW_TICKS);
	}

	/** Called by the mixin when the server sends an explosion. */
	public static void onExplosion(ClientLevel level, Vec3 center, float radius) {
		// A cart explodes where it is, so if there's a TNT cart right at the
		// center of the explosion, the explosion is its own.
		boolean fromCart = isTntCartNear(level, center, 2.0);
		if (debug) {
			debugMessage(String.format(Locale.ROOT, "explosion radius %.1f%s", radius, fromCart ? " -> TNT cart!" : ""));
		}
		if (!fromCart) {
			return;
		}
		// An explosion deals damage up to about 2 times its radius.
		double range = radius * 2.0 + 1.0;
		for (AbstractClientPlayer player : level.players()) {
			int id = player.getId();
			// The server sends "this player took damage from the cart" BEFORE the explosion
			// packet. If it just arrived, we know for sure the player was hit (no shield, no
			// invulnerability: in those cases the event doesn't arrive), so we compute the
			// damage right away without waiting for the new health.
			Integer hitTick = cartHitTick.get(id);
			boolean justHit = hitTick != null && clientTicks - hitTick <= 1;
			if (justHit && !hitShown.contains(id) && !recentPops.containsKey(id)) {
				predictHit(player, center, radius);
				continue;
			}
			boolean inRange = ServerPositions.latest(player).distanceToSqr(center) <= range * range;
			// Another explosion on the same player: the real damage won't only be from the predicted hit.
			PredictionRecord record = records.get(id);
			if (inRange && record != null) {
				record.contaminated = true;
			}
			// If the player already has a cart hit in progress it's the same hit: don't open a new one.
			if (inRange && !pendingHits.containsKey(id)) {
				startHit(player);
			}
		}
	}

	private static void predictHit(Player player, Vec3 center, float radius) {
		int id = player.getId();
		DamagePredictor.Prediction prediction = DamagePredictor.predict(player, center, radius, clientTicks);
		if (prediction == null || prediction.damage() <= 0) {
			return;
		}
		if (debug) {
			debugMessage(describe(player, prediction, radius));
		}
		DamageIndicators.addPrediction(player, prediction.damage(), prediction.absorptionDamage());
		// The number is already there: when the real health arrives, it replaces the prediction.
		hitShown.add(id);
		records.put(id, new PredictionRecord(prediction, clientTicks, isSelf(player) || !fakeHealthServer));
	}

	private static void startHit(Player player) {
		// A new hit: the previous one is over, learn from it.
		finishRecord(player.getId());
		pendingHits.put(player.getId(), HIT_WINDOW_TICKS);
		hitShown.remove(player.getId());
	}

	private static void endHit(int id) {
		PredictionRecord record = records.get(id);
		if (record != null && clientTicks - record.tick <= CALIBRATION_WINDOW_TICKS) {
			// Totem or other damage right after the hit: the real damage is "dirty", don't learn.
			records.remove(id);
		} else {
			finishRecord(id);
		}
		pendingHits.remove(id);
		hitShown.remove(id);
	}

	/** Hit finished: compare prediction and real damage and, if the comparison is reliable, learn. */
	private static void finishRecord(int id) {
		PredictionRecord record = records.remove(id);
		Minecraft client = Minecraft.getInstance();
		if (record == null || client.level == null) {
			return;
		}
		DamagePredictor.Prediction prediction = record.prediction;
		Entity entity = client.level.getEntity(id);
		String name = entity != null ? entity.getName().getString() : "?";
		if (debug) {
			if (!record.actualKnown()) {
				debugMessage(String.format(Locale.ROOT, "%s predicted -%.1f, real: unknown (fake health)",
						name, prediction.damage()));
			} else {
				debugMessage(String.format(Locale.ROOT, "%s predicted -%.1f, real -%.1f",
						name, prediction.damage(), record.actualTotal));
			}
		}
		boolean reliable = entity instanceof Player
				&& record.actualKnown()
				&& Boolean.TRUE.equals(record.positionMatches)
				&& !record.contaminated
				&& record.actualTotal > 0
				&& prediction.afterArmor() > 0.01f
				// If health ran out (dead) the real damage is "cut off": it tells us nothing.
				&& record.actualTotal < prediction.healthBefore() - 0.01f;
		if (!reliable) {
			return;
		}
		float factor = Mth.clamp(record.actualTotal / prediction.afterArmor(), 0.0f, 2.0f);
		OpponentCalibration.learn((Player) entity, prediction.equipment(), prediction.resistanceLevel(),
				factor, clientTicks);
		if (debug) {
			debugMessage(String.format(Locale.ROOT, "%s: learned, %.0f%% of the damage gets through after armor",
					name, factor * 100));
		}
	}

	private static String describe(Player player, DamagePredictor.Prediction p, float radius) {
		return String.format(Locale.ROOT,
				"%s predicted -%.1f (absorption -%.1f) | radius %.2f, distance %.2f, exposure %.0f%%, raw %.1f, "
						+ "after armor %.1f, protection %.0f, resistance %d%s%s",
				player.getName().getString(), p.damage(), p.absorptionDamage(), radius, p.distance(),
				p.exposure() * 100, p.rawDamage(), p.afterArmor(), p.protectionPoints(), p.resistanceLevel(),
				p.hiddenEnchantments() ? " [hidden enchantments: assumed Prot IV]" : "",
				p.calibrated() ? " [used learned value]" : "");
	}

	private static boolean isFromTntCart(Player player, DamageSource source) {
		// Normal case: the direct source of the damage is the cart itself.
		if (source.getDirectEntity() instanceof MinecartTNT) {
			return true;
		}
		// Fallback: it's an explosion and there is (or just was) a TNT cart nearby.
		if (source.is(DamageTypeTags.IS_EXPLOSION) && player.level() instanceof ClientLevel level) {
			return isTntCartNear(level, player.position(), NEARBY_CART_RADIUS);
		}
		return false;
	}

	/** Is there a TNT cart within radius blocks of pos, now or in the last CART_MEMORY_TICKS? */
	private static boolean isTntCartNear(ClientLevel level, Vec3 pos, double radius) {
		AABB area = AABB.ofSize(pos, radius * 2, radius * 2, radius * 2);
		if (!level.getEntitiesOfClass(MinecartTNT.class, area).isEmpty()) {
			return true;
		}
		for (RecentCart cart : recentCarts.values()) {
			if (cart.pos().distanceToSqr(pos) <= radius * radius) {
				return true;
			}
		}
		return false;
	}

	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			lastHealth.clear();
			pendingHits.clear();
			hitShown.clear();
			recentPops.clear();
			recentCarts.clear();
			cartHitTick.clear();
			records.clear();
			ServerPositions.clear();
			OpponentCalibration.clear();
			fakeHealthServer = false;
			impossibleChanges.clear();
			lastDamageEventTick.clear();
			firstSeenTick.clear();
			return;
		}

		ServerPositions.tick(level, clientTicks);
		if (!fakeHealthServer) {
			// Server already recognized in the past (saved in the file)?
			String server = FakeHealthServers.currentServerKey();
			if (server != null && FakeHealthServers.isKnown(server)) {
				fakeHealthServer = true;
				if (debug) {
					debugMessage("this server sends FAKE health for other players (seen before)");
				}
			}
		}

		Map<Integer, HealthSnapshot> currentHealth = new HashMap<>();
		for (AbstractClientPlayer player : level.players()) {
			int id = player.getId();
			HealthSnapshot now = new HealthSnapshot(player.getHealth(), player.getAbsorptionAmount());
			currentHealth.put(id, now);
			firstSeenTick.putIfAbsent(id, clientTicks);

			HealthSnapshot before = lastHealth.get(id);
			if (before == null) {
				continue;
			}
			boolean self = isSelf(player);
			if (!self) {
				checkForFakeHealth(player, before, now);
			}
			// Our own health is always real (the server sends it separately). Other players'
			// isn't, if the server fakes it: in that case only the absorption hearts are real.
			boolean healthTrusted = self || !fakeHealthServer;

			// Damage takes absorption hearts first, then normal health:
			// we measure how much each of the two dropped separately.
			float absorptionLost = Math.max(0, before.absorption() - now.absorption());
			float healthLost = healthTrusted ? Math.max(0, before.health() - now.health()) : 0;
			float totalLost = absorptionLost + healthLost;
			// With fake health we only know the real damage if the absorption hearts didn't run
			// out: that means all the damage went there.
			boolean exact = healthTrusted || now.absorption() > 0.01f;
			if (totalLost > 0) {
				boolean fromCart = pendingHits.containsKey(id) && !recentPops.containsKey(id);
				if (debug) {
					debugMessage(String.format(Locale.ROOT, "%s health %.1f->%.1f%s, absorption %.1f->%.1f%s",
							player.getName().getString(), before.health(), now.health(),
							healthTrusted ? "" : " (fake)",
							before.absorption(), now.absorption(), fromCart ? " (TNT cart)" : ""));
				}
				PredictionRecord record = records.get(id);
				if (fromCart && record != null && clientTicks - record.tick <= CALIBRATION_WINDOW_TICKS) {
					record.actualTotal += totalLost;
					if (exact) {
						record.sawExact = true;
					} else {
						record.sawInexact = true;
					}
					if (record.positionMatches == null) {
						// The health arrives together with the real position the server used.
						double offset = ServerPositions.latest(player).distanceTo(record.prediction.positionUsed());
						record.positionMatches = record.prediction.positionCertain() || offset < POSITION_MATCH_BLOCKS;
					}
				}
				if (fromCart && exact) {
					// First piece of the hit -> new number; later pieces -> added to the same one.
					DamageIndicators.addDamage(player, totalLost, absorptionLost, hitShown.contains(id));
					hitShown.add(id);
				} else if (fromCart) {
					// Fake health and absorption hearts ran out: we only know the absorption part.
					// We put it into the prediction, which stays a (faded) prediction.
					DamageIndicators.setKnownAbsorption(player, absorptionLost);
				}
			}
		}
		lastHealth.clear();
		lastHealth.putAll(currentHealth);

		// Expire "hit" players after HIT_WINDOW_TICKS.
		countDown(pendingHits);
		hitShown.retainAll(pendingHits.keySet());
		countDown(recentPops);
		// Finished hits (window expired): compare prediction and real damage.
		for (Integer id : new ArrayList<>(records.keySet())) {
			if (!pendingHits.containsKey(id)) {
				finishRecord(id);
			}
		}

		// Advance the counter and forget old hits.
		clientTicks++;
		cartHitTick.values().removeIf(tick -> clientTicks - tick > 2);
		lastDamageEventTick.values().removeIf(tick -> clientTicks - tick > NO_EVENT_DAMAGE_TICKS);
		firstSeenTick.keySet().retainAll(currentHealth.keySet());

		// Update the TNT cart memory: carts that disappeared stay for CART_MEMORY_TICKS,
		// carts still present are "refreshed" with their current position.
		recentCarts.replaceAll((id, cart) -> new RecentCart(cart.pos(), cart.ticksLeft() - 1));
		recentCarts.values().removeIf(cart -> cart.ticksLeft() <= 0);
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof MinecartTNT) {
				recentCarts.put(entity.getId(), new RecentCart(entity.position(), CART_MEMORY_TICKS));
			}
		}
	}

	/**
	 * Checks whether another player's health changes in a way that's impossible in vanilla:
	 *  - it goes up more than a potion can heal;
	 *  - it drops without any damage event (and without a hit just before).
	 * Some servers send fake (random) health for other players: if this happens several times
	 * in a short time, we stop trusting their health.
	 */
	private static void checkForFakeHealth(Player player, HealthSnapshot before, HealthSnapshot now) {
		if (fakeHealthServer || before.health() <= 0 || now.health() <= 0) {
			return;
		}
		Integer firstSeen = firstSeenTick.get(player.getId());
		if (firstSeen != null && clientTicks - firstSeen < 20) {
			return;
		}
		float change = now.health() - before.health();
		Integer lastEvent = lastDamageEventTick.get(player.getId());
		boolean recentlyHit = lastEvent != null && clientTicks - lastEvent <= NO_EVENT_DAMAGE_TICKS;
		boolean impossibleHeal = change > IMPOSSIBLE_HEAL;
		boolean dropWithoutHit = change < -1.0f && !recentlyHit;
		if (!impossibleHeal && !dropWithoutHit) {
			return;
		}
		if (debug) {
			debugMessage(String.format(Locale.ROOT, "%s: suspicious health %.1f->%.1f (%s)",
					player.getName().getString(), before.health(), now.health(),
					impossibleHeal ? "impossible heal" : "dropped without a hit"));
		}
		impossibleChanges.addLast(clientTicks);
		while (!impossibleChanges.isEmpty() && clientTicks - impossibleChanges.peekFirst() > IMPOSSIBLE_CHANGES_WINDOW_TICKS) {
			impossibleChanges.removeFirst();
		}
		if (impossibleChanges.size() >= IMPOSSIBLE_CHANGES_NEEDED) {
			fakeHealthServer = true;
			// What we learned so far about other players came from fake health: throw it all away.
			OpponentCalibration.clear();
			for (PredictionRecord record : records.values()) {
				record.healthTrusted = false;
				record.sawInexact = true;
			}
			String server = FakeHealthServers.currentServerKey();
			if (server != null) {
				FakeHealthServers.remember(server);
			}
			if (debug) {
				debugMessage("this server sends FAKE health for other players: for them I use "
						+ "the prediction and absorption hearts (which are real)");
			}
		}
	}

	/** Subtracts 1 tick from every value and removes the ones that reached 0. */
	private static void countDown(Map<Integer, Integer> ticksById) {
		Iterator<Map.Entry<Integer, Integer>> it = ticksById.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Integer> entry = it.next();
			int ticksLeft = entry.getValue() - 1;
			if (ticksLeft <= 0) {
				it.remove();
			} else {
				entry.setValue(ticksLeft);
			}
		}
	}

	private static String describe(Entity entity) {
		return entity == null ? "none" : entity.getClass().getSimpleName();
	}

	private static void debugMessage(String text) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal("[TCD] " + text).withStyle(ChatFormatting.GRAY));
		}
	}
}
