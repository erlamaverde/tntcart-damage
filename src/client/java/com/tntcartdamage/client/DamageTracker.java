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
 * Capisce quanto danno ha fatto un TNT minecart a ogni player.
 *
 * Il server ci manda informazioni separate:
 *  1. l'esplosione (posizione e raggio) e/o un evento "danno" con la fonte, ma senza quantità;
 *  2. poco dopo, la nuova vita del player.
 * Quindi: quando capiamo che un TNT cart ha colpito un player lo segniamo come "colpito",
 * e per qualche tick controlliamo di quanto scende la sua vita (+ cuori gialli).
 *
 * Per non aspettare la nuova vita, appena arriva l'esplosione calcoliamo anche il danno
 * previsto (DamagePredictor) e lo mostriamo subito; poi il danno vero lo sostituisce.
 */
public final class DamageTracker {
	/** Per quanti tick dopo l'esplosione aspettiamo che la vita si aggiorni. Alcuni server
	 *  mandano il calo di vita in più pezzi a distanza di qualche tick: tutti i pezzi
	 *  che arrivano in questa finestra vengono sommati nello stesso numero. */
	private static final int HIT_WINDOW_TICKS = 20;
	/** Raggio in cui cercare un TNT cart se il danno arriva in modo indiretto. */
	private static final double NEARBY_CART_RADIUS = 8.0;

	/** Se true scrive in chat cosa vede la mod (comando /tntcartdamage debug). */
	public static boolean debug = false;

	/** id entità -> vita e cuori gialli (assorbimento) al tick precedente */
	private static final Map<Integer, HealthSnapshot> lastHealth = new HashMap<>();

	private record HealthSnapshot(float health, float absorption) {
	}
	/** id entità -> tick rimasti in cui un calo di vita conta come danno da TNT cart */
	private static final Map<Integer, Integer> pendingHits = new HashMap<>();
	/** id dei player per cui il colpo attuale ha già creato un numero: i pezzi successivi
	 *  dello stesso colpo vanno sommati a quel numero invece di crearne uno nuovo. */
	private static final Set<Integer> hitShown = new HashSet<>();
	/** id entità -> tick rimasti in cui NON mostriamo numeri perché gli è appena scoppiato il totem */
	private static final Map<Integer, Integer> recentPops = new HashMap<>();

	/** Per quanti tick ci ricordiamo di un TNT cart dopo che è sparito. */
	private static final int CART_MEMORY_TICKS = 10;
	/** id del cart -> ultima posizione vista. Serve perché sui server il cart può
	 *  sparire dal client un attimo prima che arrivi il pacchetto dell'esplosione. */
	private static final Map<Integer, RecentCart> recentCarts = new HashMap<>();

	private record RecentCart(Vec3 pos, int ticksLeft) {
	}

	/** Contatore dei tick del client, per sapere "quanto tempo fa" è successa una cosa. */
	private static int clientTicks = 0;
	/** id del player -> tick in cui è arrivato il suo ultimo evento di danno da cart. */
	private static final Map<Integer, Integer> cartHitTick = new HashMap<>();

	/** Il danno vero di un colpo arriva entro pochi tick: per imparare usiamo solo quello. */
	private static final int CALIBRATION_WINDOW_TICKS = 4;
	/** Se la posizione vera è entro questa distanza (in blocchi) da quella usata per la
	 *  previsione, il calcolo di distanza ed esposizione era giusto e possiamo imparare. */
	private static final double POSITION_MATCH_BLOCKS = 0.05;

	/** Una previsione fatta, da confrontare con il danno vero per imparare. */
	private static final class PredictionRecord {
		final DamagePredictor.Prediction prediction;
		final int tick;
		/** Danno vero arrivato per questo colpo. */
		float actualTotal;
		/** La posizione vera (arrivata insieme alla vita) era quella usata? null = non ancora arrivata. */
		Boolean positionMatches;
		/** Un'altra esplosione ha preso questo player: il danno vero non è solo di questo colpo. */
		boolean contaminated;
		/** La vita di questo player è vera? (No sui server con la vita finta, tranne la nostra.) */
		boolean healthTrusted;
		/** Con la vita finta: è arrivato un calo misurato esatto (tutto assorbito dai cuori gialli). */
		boolean sawExact;
		/** Con la vita finta: è arrivato un calo NON misurabile (cuori gialli finiti). */
		boolean sawInexact;

		PredictionRecord(DamagePredictor.Prediction prediction, int tick, boolean healthTrusted) {
			this.prediction = prediction;
			this.tick = tick;
			this.healthTrusted = healthTrusted;
		}

		/** Sappiamo il danno vero di questo colpo? */
		boolean actualKnown() {
			return healthTrusted ? !sawInexact : sawExact && !sawInexact;
		}
	}

	/** id del player -> previsione del colpo in corso. */
	private static final Map<Integer, PredictionRecord> records = new HashMap<>();

	/** true se il server di adesso manda la vita FINTA degli altri player (vedi FakeHealthServers).
	 *  Su questi server della vita degli altri sono veri solo i cuori gialli. */
	private static boolean fakeHealthServer = false;
	/** Più di questa cura in un tick è impossibile in vanilla (una pozione di cura II dà 8). */
	private static final float IMPOSSIBLE_HEAL = 8.5f;
	/** Per 10 tick dopo un colpo, i colpi seguenti tolgono vita SENZA evento di danno:
	 *  oltre questo tempo, una vita che scende senza evento di danno è impossibile in vanilla. */
	private static final int NO_EVENT_DAMAGE_TICKS = 12;
	/** Quanti cambi di vita impossibili, entro quanto tempo, per capire che la vita è finta. */
	private static final int IMPOSSIBLE_CHANGES_NEEDED = 3;
	private static final int IMPOSSIBLE_CHANGES_WINDOW_TICKS = 20 * 30;
	/** Tick in cui abbiamo visto un cambio di vita impossibile. */
	private static final ArrayDeque<Integer> impossibleChanges = new ArrayDeque<>();
	/** id del player -> tick del suo ultimo evento di danno (di qualsiasi tipo). */
	private static final Map<Integer, Integer> lastDamageEventTick = new HashMap<>();
	/** id del player -> tick in cui l'abbiamo visto la prima volta (appena comparso la vita
	 *  può "saltare" al valore vero: non è sospetto). */
	private static final Map<Integer, Integer> firstSeenTick = new HashMap<>();

	private DamageTracker() {
	}

	/** Il nostro player (nei test automatici può fare finta di essere un avversario). */
	private static boolean isSelf(Player player) {
		return player instanceof LocalPlayer && !DamagePredictor.testTreatSelfAsOpponent;
	}

	/** Chiamato dalla mixin quando il server dice "questo player ha preso danno". */
	public static void onDamageEvent(Player player, DamageSource source) {
		lastDamageEventTick.put(player.getId(), clientTicks);
		boolean fromCart = isFromTntCart(player, source);
		if (debug) {
			debugMessage(player.getName().getString() + " damage: type=" + source.getMsgId()
					+ ", source=" + describe(source.getDirectEntity())
					+ (fromCart ? " -> TNT cart!" : ""));
		}
		if (fromCart) {
			// Ogni evento di danno da cart è un colpo nuovo: avrà il suo numero.
			startHit(player);
			cartHitTick.put(player.getId(), clientTicks);
		} else {
			// Danno da qualcos'altro (fuoco, caduta, spada...): il prossimo calo di vita
			// è suo, non del cart, quindi chiudiamo la finestra del cart.
			endHit(player.getId());
		}
	}

	/** Chiamato dalla mixin quando a un player scoppia un totem (per qualsiasi motivo). */
	public static void onTotemPop(Player player) {
		if (debug) {
			debugMessage(player.getName().getString() + " pop!");
		}
		DamageIndicators.addPop(player);
		// Il totem rimette la vita a 1 e dà cuori gialli, quindi il calo di vita
		// che arriva subito dopo non va mostrato come numero. Lo blocchiamo per un po'
		// perché il pacchetto dell'esplosione può arrivare dopo il pop e rimarcarlo.
		endHit(player.getId());
		recentPops.put(player.getId(), HIT_WINDOW_TICKS);
	}

	/** Chiamato dalla mixin quando il server manda un'esplosione. */
	public static void onExplosion(ClientLevel level, Vec3 center, float radius) {
		// Il cart esplode nel punto in cui si trova, quindi se c'è un TNT cart
		// proprio al centro dell'esplosione, l'esplosione è sua.
		boolean fromCart = isTntCartNear(level, center, 2.0);
		if (debug) {
			debugMessage(String.format(Locale.ROOT, "explosion radius %.1f%s", radius, fromCart ? " -> TNT cart!" : ""));
		}
		if (!fromCart) {
			return;
		}
		// Un'esplosione fa danno fino a circa 2 volte il suo raggio.
		double range = radius * 2.0 + 1.0;
		for (AbstractClientPlayer player : level.players()) {
			int id = player.getId();
			// Il server manda "questo player ha preso danno dal cart" PRIMA del pacchetto
			// dell'esplosione. Se è appena arrivato, sappiamo per certo che il player è stato
			// colpito (niente scudo, niente invulnerabilità: in quei casi l'evento non arriva):
			// calcoliamo subito il danno, senza aspettare la nuova vita.
			Integer hitTick = cartHitTick.get(id);
			boolean justHit = hitTick != null && clientTicks - hitTick <= 1;
			if (justHit && !hitShown.contains(id) && !recentPops.containsKey(id)) {
				predictHit(player, center, radius);
				continue;
			}
			boolean inRange = ServerPositions.latest(player).distanceToSqr(center) <= range * range;
			// Un'altra esplosione sullo stesso player: il danno vero non sarà solo del colpo previsto.
			PredictionRecord record = records.get(id);
			if (inRange && record != null) {
				record.contaminated = true;
			}
			// Se il player ha già un colpo da cart in corso è lo stesso colpo: non ne apriamo uno nuovo.
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
		// Il numero c'è già: quando arriva la vita vera, va a sostituire la previsione.
		hitShown.add(id);
		records.put(id, new PredictionRecord(prediction, clientTicks, isSelf(player) || !fakeHealthServer));
	}

	private static void startHit(Player player) {
		// Un colpo nuovo: quello prima è finito, impariamo da lui.
		finishRecord(player.getId());
		pendingHits.put(player.getId(), HIT_WINDOW_TICKS);
		hitShown.remove(player.getId());
	}

	private static void endHit(int id) {
		PredictionRecord record = records.get(id);
		if (record != null && clientTicks - record.tick <= CALIBRATION_WINDOW_TICKS) {
			// Totem o altro danno subito dopo il colpo: il danno vero è "sporco", non impariamo.
			records.remove(id);
		} else {
			finishRecord(id);
		}
		pendingHits.remove(id);
		hitShown.remove(id);
	}

	/** Colpo finito: confronta previsione e danno vero e, se il confronto è affidabile, impara. */
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
				// Se è finita la vita (morto) il danno vero è "tagliato": non dice niente.
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
		// Caso normale: la fonte diretta del danno è il cart stesso.
		if (source.getDirectEntity() instanceof MinecartTNT) {
			return true;
		}
		// Caso di riserva: è un'esplosione e c'è (o c'era appena) un TNT cart vicino.
		if (source.is(DamageTypeTags.IS_EXPLOSION) && player.level() instanceof ClientLevel level) {
			return isTntCartNear(level, player.position(), NEARBY_CART_RADIUS);
		}
		return false;
	}

	/** C'è un TNT cart entro radius blocchi da pos, adesso o negli ultimi CART_MEMORY_TICKS? */
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
			// Server già riconosciuto in passato (salvato nel file)?
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
			// La nostra vita è sempre vera (il server ce la manda a parte). Quella degli altri
			// no, se il server la falsa: in quel caso sono veri solo i cuori gialli.
			boolean healthTrusted = self || !fakeHealthServer;

			// Il danno toglie prima i cuori gialli e poi la vita normale:
			// misuriamo separatamente di quanto è sceso ognuno dei due.
			float absorptionLost = Math.max(0, before.absorption() - now.absorption());
			float healthLost = healthTrusted ? Math.max(0, before.health() - now.health()) : 0;
			float totalLost = absorptionLost + healthLost;
			// Con la vita finta il danno vero lo sappiamo solo se i cuori gialli non sono finiti:
			// vuol dire che tutto il danno è andato lì.
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
						// La vita arriva insieme alla posizione vera che il server ha usato.
						double offset = ServerPositions.latest(player).distanceTo(record.prediction.positionUsed());
						record.positionMatches = record.prediction.positionCertain() || offset < POSITION_MATCH_BLOCKS;
					}
				}
				if (fromCart && exact) {
					// Primo pezzo del colpo -> nuovo numero; pezzi successivi -> sommati allo stesso.
					DamageIndicators.addDamage(player, totalLost, absorptionLost, hitShown.contains(id));
					hitShown.add(id);
				} else if (fromCart) {
					// Vita finta e cuori gialli finiti: sappiamo solo la parte gialla. La mettiamo
					// nella previsione, che resta una previsione (sbiadita).
					DamageIndicators.setKnownAbsorption(player, absorptionLost);
				}
			}
		}
		lastHealth.clear();
		lastHealth.putAll(currentHealth);

		// Fa scadere i player "colpiti" dopo HIT_WINDOW_TICKS.
		countDown(pendingHits);
		hitShown.retainAll(pendingHits.keySet());
		countDown(recentPops);
		// Colpi finiti (finestra scaduta): confronta previsione e danno vero.
		for (Integer id : new ArrayList<>(records.keySet())) {
			if (!pendingHits.containsKey(id)) {
				finishRecord(id);
			}
		}

		// Avanza il contatore e dimentica i colpi ormai vecchi.
		clientTicks++;
		cartHitTick.values().removeIf(tick -> clientTicks - tick > 2);
		lastDamageEventTick.values().removeIf(tick -> clientTicks - tick > NO_EVENT_DAMAGE_TICKS);
		firstSeenTick.keySet().retainAll(currentHealth.keySet());

		// Aggiorna la memoria dei TNT cart: quelli spariti restano per CART_MEMORY_TICKS,
		// quelli ancora presenti vengono "rinfrescati" con la posizione attuale.
		recentCarts.replaceAll((id, cart) -> new RecentCart(cart.pos(), cart.ticksLeft() - 1));
		recentCarts.values().removeIf(cart -> cart.ticksLeft() <= 0);
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof MinecartTNT) {
				recentCarts.put(entity.getId(), new RecentCart(entity.position(), CART_MEMORY_TICKS));
			}
		}
	}

	/**
	 * Controlla se la vita di un altro player cambia in un modo impossibile in vanilla:
	 *  - sale di più di quanto può curare una pozione;
	 *  - scende senza nessun evento di danno (e senza un colpo appena prima).
	 * Alcuni server mandano vite finte (a caso) degli altri player: se succede più volte
	 * in poco tempo, smettiamo di fidarci della loro vita.
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
			// Quello imparato finora sugli altri player veniva da vite finte: buttiamo via tutto.
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

	/** Toglie 1 tick a ogni valore e rimuove quelli arrivati a 0. */
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
