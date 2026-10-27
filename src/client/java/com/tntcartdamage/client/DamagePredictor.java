package com.tntcartdamage.client;

import com.tntcartdamage.client.mixin.LivingEntityAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Calcola in anticipo quanto danno fa l'esplosione di un TNT cart a un player, rifacendo
 * gli stessi conti del server (Minecraft 26.2, verificati sul codice del gioco).
 *
 * Cosa NON serve calcolare: la potenza dell'esplosione dipende dalla velocità della freccia
 * infuocata (o del cart, o dall'altezza da cui cade) più un numero casuale, ma il server la
 * manda già finita nel pacchetto dell'esplosione (il "raggio"). Quindi angolo e velocità
 * della freccia sono già compresi.
 *
 * I passaggi, nello stesso ordine del server:
 *  1. ServerExplosion.hurtEntities: distanza dei piedi dal centro + quanto il corpo è
 *     "esposto" (raggi dal corpo al centro che non sbattono contro blocchi)
 *  2. Player.hurtServer: difficoltà
 *  3. LivingEntity.getDamageAfterArmorAbsorb: armatura e resistenza dell'armatura
 *  4. LivingEntity.getDamageAfterMagicAbsorb: effetto Resistenza, poi Protezione /
 *     Protezione dalle esplosioni
 *  5. Player.actuallyHurt: prima i cuori gialli, poi la vita
 *
 * Scudo e invulnerabilità (colpito da meno di 10 tick) non servono: in quei casi il server
 * non manda l'evento di danno, e senza evento non facciamo nessuna previsione.
 */
public final class DamagePredictor {
	/** Livello di Protezione che assumiamo per un pezzo di armatura con gli incantesimi
	 *  nascosti dal server (in PvP quasi tutti usano Protezione IV). */
	private static final int ASSUMED_HIDDEN_PROTECTION_LEVEL = 4;

	/**
	 * damage: danno finale in HP; absorptionDamage: quanta parte va sui cuori gialli.
	 * Gli altri campi servono per il debug e per imparare dai colpi veri.
	 */
	public record Prediction(
			float damage, float absorptionDamage,
			float rawDamage, float afterArmor, float magicFactor,
			double distance, float exposure, float protectionPoints, int resistanceLevel,
			boolean hiddenEnchantments, boolean calibrated,
			Vec3 positionUsed, boolean positionCertain, float healthBefore, String equipment) {
	}

	/** Solo per i test automatici: tratta il nostro player come un avversario (non conosciamo
	 *  i suoi effetti e i suoi incantesimi possono essere "nascosti"). */
	public static boolean testTreatSelfAsOpponent = false;

	private DamagePredictor() {
	}

	/** @return la previsione, oppure null se l'esplosione non dovrebbe fargli danno */
	public static Prediction predict(Player player, Vec3 center, float radius, int clientTick) {
		if (player.isSpectator() || player.isCreative()) {
			return null;
		}

		// 1. Danno base dell'esplosione, con il player dove lo vede il server.
		Vec3 position = ServerPositions.estimate(player, clientTick);
		float diameter = radius * 2.0f;
		double distance = Math.sqrt(position.distanceToSqr(center)) / diameter;
		if (distance > 1.0) {
			return null;
		}
		AABB box = player.getBoundingBox().move(position.subtract(player.position()));
		float exposure = exposure(center, player, box);
		double impact = (1.0 - distance) * exposure;
		float rawDamage = (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);

		// 2. Difficoltà del server.
		rawDamage = scaleWithDifficulty(player.level().getDifficulty(), rawDamage);
		if (rawDamage <= 0) {
			return null;
		}

		// 3. Armatura. Valore e resistenza dell'armatura degli altri player arrivano al client.
		DamageSource source = player.level().damageSources().explosion(null, null);
		float afterArmor = CombatRules.getDamageAfterAbsorb(player, rawDamage, source,
				player.getArmorValue(), (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

		// 4. Resistenza + incantesimi, oppure quello che abbiamo imparato dai colpi precedenti.
		boolean self = player instanceof LocalPlayer && !testTreatSelfAsOpponent;
		int resistanceLevel = resistanceLevel(player, self);
		ProtectionInfo protection = protection(player, self);
		String equipment = equipmentKey(player);
		// La previsione ha dovuto indovinare qualcosa? Allora ci fidiamo prima di quello imparato.
		boolean uncertain = protection.hidden() || (!self && resistanceLevel > 0);
		Float learned = OpponentCalibration.factor(player, equipment, resistanceLevel, uncertain, clientTick);
		float damage;
		if (learned != null) {
			damage = afterArmor * learned;
		} else {
			damage = afterArmor;
			if (resistanceLevel > 0) {
				// Resistenza: -20% per livello (stessa formula di getDamageAfterMagicAbsorb).
				int reduction = resistanceLevel * 5;
				damage = Math.max(damage * (25 - reduction) / 25.0f, 0.0f);
			}
			damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection.points());
		}
		float magicFactor = afterArmor > 0 ? damage / afterArmor : 0;

		// 5. I cuori gialli si consumano per primi.
		float absorptionDamage = Math.min(damage, player.getAbsorptionAmount());
		// La nostra posizione la conosciamo, se siamo fermi. Per gli altri lo verifichiamo
		// quando arriva la posizione vera (DamageTracker).
		boolean positionCertain = player instanceof LocalPlayer
				&& player.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4 && player.onGround();
		return new Prediction(damage, absorptionDamage, rawDamage, afterArmor, magicFactor,
				distance * diameter, exposure, protection.points(), resistanceLevel,
				protection.hidden(), learned != null,
				position, positionCertain, player.getHealth() + player.getAbsorptionAmount(), equipment);
	}

	/**
	 * Copia esatta di ServerExplosion.getSeenPercent, ma con il box del player spostato
	 * dove lo vede il server. Dal corpo partono raggi verso il centro (una griglia di punti,
	 * per un player 3 × 5 × 3 = 45): conta quanti NON sbattono contro un blocco.
	 * I blocchi distrutti dall'esplosione non contano: il server calcola il danno prima di
	 * romperli, e il client in questo momento ha ancora i blocchi di prima.
	 */
	private static float exposure(Vec3 center, Player player, AABB box) {
		double stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
		double stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
		double stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
		double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
		double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
		if (stepX < 0 || stepY < 0 || stepZ < 0) {
			return 0;
		}
		int visible = 0;
		int total = 0;
		for (double x = 0; x <= 1.0; x += stepX) {
			for (double y = 0; y <= 1.0; y += stepY) {
				for (double z = 0; z <= 1.0; z += stepZ) {
					Vec3 from = new Vec3(
							Mth.lerp(x, box.minX, box.maxX) + offsetX,
							Mth.lerp(y, box.minY, box.maxY),
							Mth.lerp(z, box.minZ, box.maxZ) + offsetZ);
					ClipContext ray = new ClipContext(from, center,
							ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
					if (player.level().clip(ray).getType() == HitResult.Type.MISS) {
						visible++;
					}
					total++;
				}
			}
		}
		return (float) visible / (float) total;
	}

	/** Copia di Player.hurtServer per i danni che dipendono dalla difficoltà (le esplosioni sì). */
	private static float scaleWithDifficulty(Difficulty difficulty, float damage) {
		return switch (difficulty) {
			case PEACEFUL -> 0.0f;
			case EASY -> Math.min(damage / 2.0f + 1.0f, damage);
			case NORMAL -> damage;
			case HARD -> damage * 3.0f / 2.0f;
		};
	}

	/**
	 * Livello di Resistenza (1 = Resistenza I), 0 se non ce l'ha.
	 * Del nostro player conosciamo gli effetti. Degli altri no, ma il server manda i colori
	 * delle particelle degli effetti visibili: se c'è quello della Resistenza assumiamo il
	 * livello I (quello della mela d'oro incantata), perché il livello non viene mandato.
	 */
	private static int resistanceLevel(Player player, boolean self) {
		if (self) {
			MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
			return resistance == null ? 0 : resistance.getAmplifier() + 1;
		}
		return hasResistanceParticles(player) ? 1 : 0;
	}

	/** true se tra le particelle degli effetti del player c'è il colore della Resistenza. */
	public static boolean hasResistanceParticles(LivingEntity entity) {
		int resistanceColor = MobEffects.RESISTANCE.value().getColor() & 0xFFFFFF;
		List<ParticleOptions> particles =
				entity.getEntityData().get(LivingEntityAccessor.tntcartdamage$getEffectParticlesData());
		for (ParticleOptions particle : particles) {
			if (particle instanceof ColorParticleOption color) {
				int rgb = Math.round(color.getRed() * 255) << 16
						| Math.round(color.getGreen() * 255) << 8
						| Math.round(color.getBlue() * 255);
				if (rgb == resistanceColor) {
					return true;
				}
			}
		}
		return false;
	}

	/** points: punti protezione contro le esplosioni; hidden: c'erano incantesimi nascosti. */
	private record ProtectionInfo(float points, boolean hidden) {
	}

	/** Protezione vale 1 punto per livello, Protezione dalle esplosioni 2 (massimo 20 in tutto). */
	private static ProtectionInfo protection(Player player, boolean self) {
		Holder<Enchantment> protection = enchantment(player, Enchantments.PROTECTION);
		Holder<Enchantment> blastProtection = enchantment(player, Enchantments.BLAST_PROTECTION);
		float points = 0;
		boolean hidden = false;
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (!slot.isArmor()) {
				continue;
			}
			ItemEnchantments enchantments = player.getItemBySlot(slot)
					.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
			if (!self && looksHidden(enchantments)) {
				points += ASSUMED_HIDDEN_PROTECTION_LEVEL;
				hidden = true;
				continue;
			}
			if (protection != null) {
				points += enchantments.getLevel(protection);
			}
			if (blastProtection != null) {
				points += 2 * enchantments.getLevel(blastProtection);
			}
		}
		return new ProtectionInfo(points, hidden);
	}

	/**
	 * Paper (il server che usano quasi tutti) può nascondere gli incantesimi degli altri:
	 * li sostituisce con UN incantesimo a caso di livello I, solo per far vedere il luccichio.
	 * Un pezzo di armatura da PvP con un solo incantesimo di livello I è quasi sicuramente così.
	 */
	private static boolean looksHidden(ItemEnchantments enchantments) {
		if (enchantments.size() != 1) {
			return false;
		}
		for (var entry : enchantments.entrySet()) {
			return entry.getIntValue() == 1;
		}
		return false;
	}

	/** "Impronta" dell'armatura: tipo + incantesimi visibili (non la durabilità, che cambia sempre). */
	private static String equipmentKey(Player player) {
		StringBuilder key = new StringBuilder();
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (!slot.isArmor()) {
				continue;
			}
			ItemStack item = player.getItemBySlot(slot);
			key.append(BuiltInRegistries.ITEM.getKey(item.getItem()))
					.append(item.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY))
					.append(';');
		}
		return key.toString();
	}

	private static Holder<Enchantment> enchantment(Player player, ResourceKey<Enchantment> key) {
		Optional<? extends Holder<Enchantment>> holder =
				player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key);
		return holder.isPresent() ? holder.get() : null;
	}
}
