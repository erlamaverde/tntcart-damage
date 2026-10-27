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
 * handleDamageEvent viene chiamato sul client quando il server manda il pacchetto
 * "questa entità ha preso danno" (quello che fa diventare rosso il player).
 * Il pacchetto dice la FONTE del danno ma non la quantità: quella la ricaviamo
 * dopo, guardando di quanto scende la vita (vedi DamageTracker).
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
