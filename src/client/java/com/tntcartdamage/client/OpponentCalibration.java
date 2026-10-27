package com.tntcartdamage.client;

import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Impara dai colpi veri quanto un avversario riduce DAVVERO il danno dopo l'armatura.
 *
 * Il client non può sapere tutto: il server può nascondere gli incantesimi degli altri
 * (Paper "item obfuscation"), non manda il livello della Resistenza e può avere plugin che
 * cambiano il danno dei cart. Però dopo ogni colpo sappiamo quanto danno è arrivato davvero:
 *     fattore = danno vero / danno dopo l'armatura
 * e lo usiamo per i colpi successivi sullo stesso player, finché ha la stessa armatura.
 *
 * Per non imparare cose sbagliate:
 *  - se la previsione ha dovuto INDOVINARE qualcosa (incantesimi nascosti, Resistenza vista
 *    solo dalle particelle), basta un colpo per usare quello imparato;
 *  - altrimenti il calcolo è già esatto, e lo cambiamo solo se due colpi di fila dicono la
 *    stessa cosa (es. un plugin del server che cambia il danno dei cart).
 */
public final class OpponentCalibration {
	/** Dopo quanto tempo dimentichiamo quello che abbiamo imparato (3 minuti). */
	private static final int MAX_AGE_TICKS = 20 * 60 * 3;
	/** Due misure "dicono la stessa cosa" se differiscono meno del 10%. */
	private static final float SAME_RESULT_TOLERANCE = 0.1f;

	/**
	 * equipment: "impronta" dell'armatura (tipo + incantesimi visibili);
	 * resistanceLevel: la Resistenza che vedevamo (per gli altri player: 1 se ha le particelle).
	 * Se una delle due cambia, quello che abbiamo imparato non vale più.
	 * confirmations: quanti colpi di fila hanno dato lo stesso fattore.
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
	 * Il fattore imparato per questo player, oppure null se non ne abbiamo uno di cui fidarci.
	 * uncertain: la previsione ha dovuto indovinare qualcosa (vedi sopra).
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
			// Conferma: stessa cosa di prima. Facciamo la media.
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
