package com.tntcartdamage.client.mixin;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Il server non manda al client gli effetti (pozioni) degli altri player, ma manda la lista
 * delle particelle degli effetti visibili (i "pallini" colorati). Il campo che la contiene è
 * privato: questo accessor ci permette di leggerlo.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("DATA_EFFECT_PARTICLES")
	static EntityDataAccessor<List<ParticleOptions>> tntcartdamage$getEffectParticlesData() {
		throw new AssertionError();
	}
}
