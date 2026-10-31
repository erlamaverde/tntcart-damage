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
 * Predicts how much damage a TNT minecart explosion deals to a player, by redoing the same
 * math as the server (Minecraft 26.2, checked against the game's code).
 *
 * What we DON'T need to compute: the explosion power depends on the speed of the flaming
 * arrow (or of the cart, or the height it fell from) plus a random number, but the server
 * sends the final value in the explosion packet (the "radius"). So the arrow's angle and
 * speed are already included.
 *
 * The steps, in the same order as the server:
 *  1. ServerExplosion.hurtEntities: distance from the feet to the center + how "exposed"
 *     the body is (rays from the body to the center that don't hit any block)
 *  2. Player.hurtServer: difficulty
 *  3. LivingEntity.getDamageAfterArmorAbsorb: armor and armor toughness
 *  4. LivingEntity.getDamageAfterMagicAbsorb: Resistance effect, then Protection /
 *     Blast Protection
 *  5. Player.actuallyHurt: absorption hearts first, then health
 *
 * Shields and invulnerability (hit less than 10 ticks ago) don't matter: in those cases the
 * server doesn't send the damage event, and without the event we make no prediction.
 */
public final class DamagePredictor {
	/** Protection level we assume for an armor piece whose enchantments the server hides
	 *  (in PvP almost everyone uses Protection IV). */
	private static final int ASSUMED_HIDDEN_PROTECTION_LEVEL = 4;

	/**
	 * damage: final damage in HP; absorptionDamage: how much of it goes to absorption hearts.
	 * The other fields are used for debugging and for learning from real hits.
	 */
	public record Prediction(
			float damage, float absorptionDamage,
			float rawDamage, float afterArmor, float magicFactor,
			double distance, float exposure, float protectionPoints, int resistanceLevel,
			boolean hiddenEnchantments, boolean calibrated,
			Vec3 positionUsed, boolean positionCertain, float healthBefore, String equipment) {
	}

	/** Automated tests only: treat our own player as an opponent (we don't know their
	 *  effects and their enchantments may be "hidden"). */
	public static boolean testTreatSelfAsOpponent = false;

	private DamagePredictor() {
	}

	/** @return the prediction, or null if the explosion shouldn't damage them */
	public static Prediction predict(Player player, Vec3 center, float radius, int clientTick) {
		if (player.isSpectator() || player.isCreative()) {
			return null;
		}

		// 1. Base explosion damage, with the player where the server sees them.
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

		// 2. Server difficulty.
		rawDamage = scaleWithDifficulty(player.level().getDifficulty(), rawDamage);
		if (rawDamage <= 0) {
			return null;
		}

		// 3. Armor. Other players' armor value and toughness are sent to the client.
		DamageSource source = player.level().damageSources().explosion(null, null);
		float afterArmor = CombatRules.getDamageAfterAbsorb(player, rawDamage, source,
				player.getArmorValue(), (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

		// 4. Resistance + enchantments, or what we learned from previous hits.
		boolean self = player instanceof LocalPlayer && !testTreatSelfAsOpponent;
		int resistanceLevel = resistanceLevel(player, self);
		ProtectionInfo protection = protection(player, self);
		String equipment = equipmentKey(player);
		// Did the prediction have to guess something? Then we trust the learned value sooner.
		boolean uncertain = protection.hidden() || (!self && resistanceLevel > 0);
		Float learned = OpponentCalibration.factor(player, equipment, resistanceLevel, uncertain, clientTick);
		float damage;
		if (learned != null) {
			damage = afterArmor * learned;
		} else {
			damage = afterArmor;
			if (resistanceLevel > 0) {
				// Resistance: -20% per level (same formula as getDamageAfterMagicAbsorb).
				int reduction = resistanceLevel * 5;
				damage = Math.max(damage * (25 - reduction) / 25.0f, 0.0f);
			}
			damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection.points());
		}
		float magicFactor = afterArmor > 0 ? damage / afterArmor : 0;

		// 5. Absorption hearts are used up first.
		float absorptionDamage = Math.min(damage, player.getAbsorptionAmount());
		// We know our own position, if we're standing still. For others we check it
		// when the real position arrives (DamageTracker).
		boolean positionCertain = player instanceof LocalPlayer
				&& player.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4 && player.onGround();
		return new Prediction(damage, absorptionDamage, rawDamage, afterArmor, magicFactor,
				distance * diameter, exposure, protection.points(), resistanceLevel,
				protection.hidden(), learned != null,
				position, positionCertain, player.getHealth() + player.getAbsorptionAmount(), equipment);
	}

	/**
	 * Exact copy of ServerExplosion.getSeenPercent, but with the player's box moved to where
	 * the server sees them. Rays go from the body to the center (a grid of points, 3 × 5 × 3 = 45
	 * for a player): counts how many DON'T hit a block.
	 * Blocks destroyed by the explosion don't matter: the server computes the damage before
	 * breaking them, and at this moment the client still has the old blocks.
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

	/** Copy of Player.hurtServer for damage that scales with difficulty (explosions do). */
	private static float scaleWithDifficulty(Difficulty difficulty, float damage) {
		return switch (difficulty) {
			case PEACEFUL -> 0.0f;
			case EASY -> Math.min(damage / 2.0f + 1.0f, damage);
			case NORMAL -> damage;
			case HARD -> damage * 3.0f / 2.0f;
		};
	}

	/**
	 * Resistance level (1 = Resistance I), 0 if they don't have it.
	 * We know our own player's effects. Not other players', but the server sends the colors of
	 * their visible effect particles: if the Resistance color is there we assume level I
	 * (the one from an enchanted golden apple), because the level isn't sent.
	 */
	private static int resistanceLevel(Player player, boolean self) {
		if (self) {
			MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
			return resistance == null ? 0 : resistance.getAmplifier() + 1;
		}
		return hasResistanceParticles(player) ? 1 : 0;
	}

	/** true if the player's effect particles include the Resistance color. */
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

	/** points: protection points against explosions; hidden: some enchantments were hidden. */
	private record ProtectionInfo(float points, boolean hidden) {
	}

	/** Protection is worth 1 point per level, Blast Protection 2 (20 at most in total). */
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
	 * Paper (the server software almost everyone uses) can hide other players' enchantments:
	 * it replaces them with ONE random level I enchantment, just to keep the glint.
	 * A PvP armor piece with a single level I enchantment is almost certainly hidden.
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

	/** Armor "fingerprint": item type + visible enchantments (not durability, which keeps changing). */
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
