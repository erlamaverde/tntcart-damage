package com.tntcartdamage.client.mixin;

import com.tntcartdamage.client.DamageTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * handleDamageEvent is called on the client when the server sends the "this entity took damage"
 * packet (the one that makes the player flash red). The packet tells us the SOURCE of the damage
 * but not the amount: we work that out afterwards by watching how much the health drops
 * (see DamageTracker).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void tntcartdamage$onDamageEvent(DamageSource source, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide() && self instanceof Player player) {
			DamageTracker.onDamageEvent(player, source);
		}
	}
}
