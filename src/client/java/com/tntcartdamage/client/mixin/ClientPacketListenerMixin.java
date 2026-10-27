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
 * Ascolta i pacchetti "esplosione" ed "evento entità". Questi metodi vengono chiamati
 * prima sul thread di rete e poi di nuovo sul thread principale del gioco: lavoriamo solo
 * sul thread del gioco.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	// HEAD (e non TAIL): vanilla, dentro questo metodo, dà subito al nostro player la spinta
	// dell'esplosione. Noi vogliamo calcolare tutto con lo stato di PRIMA dell'esplosione.
	// La prima chiamata arriva dal thread di rete (vanilla la rimanda al thread del gioco):
	// quella la saltiamo, e lavoriamo solo nella seconda, sul thread del gioco.
	@Inject(method = "handleExplosion", at = @At("HEAD"))
	private void tntcartdamage$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread() && client.level != null) {
			DamageTracker.onExplosion(client.level, packet.center(), packet.radius());
		}
	}

	// RETURN (e non TAIL) perché il metodo ha più punti di uscita.
	@Inject(method = "handleEntityEvent", at = @At("RETURN"))
	private void tntcartdamage$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		// L'evento 35 è quello del totem che salva dalla morte (animazione + particelle).
		if (client.level == null || packet.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) {
			return;
		}
		Entity entity = packet.getEntity(client.level);
		if (entity instanceof Player player) {
			DamageTracker.onTotemPop(player);
		}
	}
}
