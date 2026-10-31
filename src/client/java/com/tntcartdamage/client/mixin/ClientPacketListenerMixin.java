package com.tntcartdamage.client.mixin;

import com.tntcartdamage.client.DamageTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Listens to the "explosion" and "entity event" packets. These methods are called first on
 * the network thread and then again on the main game thread: we only do work on the game thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	// HEAD (not TAIL): inside this method vanilla immediately applies the explosion knockback
	// to our player, and we want to compute everything with the state from BEFORE the explosion.
	// The first call comes from the network thread (vanilla re-schedules it on the game thread):
	// we skip that one and only do work in the second call, on the game thread.
	@Inject(method = "handleExplosion", at = @At("HEAD"))
	private void tntcartdamage$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread() && client.level != null) {
			DamageTracker.onExplosion(client.level, packet.center(), packet.radius());
		}
	}

	// RETURN (not TAIL) because the method has more than one exit point.
	@Inject(method = "handleEntityEvent", at = @At("RETURN"))
	private void tntcartdamage$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		// Event 35 is the totem of undying saving someone from death (animation + particles).
		if (client.level == null || packet.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) {
			return;
		}
		Entity entity = packet.getEntity(client.level);
		if (entity instanceof Player player) {
			DamageTracker.onTotemPop(player);
		}
	}
}
