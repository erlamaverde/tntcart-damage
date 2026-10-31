package com.tntcartdamage.client.mixin;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * The server does not send other players' effects (potions) to the client, but it does send the
 * list of particles of their visible effects (the colored swirls). The field holding it is
 * private: this accessor lets us read it.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("DATA_EFFECT_PARTICLES")
	static EntityDataAccessor<List<ParticleOptions>> tntcartdamage$getEffectParticlesData() {
		throw new AssertionError();
	}
}
