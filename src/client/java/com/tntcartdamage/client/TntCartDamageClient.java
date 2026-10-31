package com.tntcartdamage.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.network.chat.Component;

public class TntCartDamageClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// First age the existing labels, then add the new ones (age 0).
			DamageIndicators.tick(client);
			DamageTracker.tick(client);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(DamageIndicators::render);

		// Client-only commands (never sent to the server):
		//   /tntcartdamage test  -> shows a fake damage number on yourself (third person, F5)
		//   /tntcartdamage debug -> prints explosions, damage events and health drops in chat
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(ClientCommands.literal("tntcartdamage")
						.then(ClientCommands.literal("test").executes(context -> {
							// 11 HP of damage, 4 HP of it on absorption hearts -> red "-7" and yellow "(-4)"
							DamageIndicators.addDamage(context.getSource().getPlayer(), 11.0f, 4.0f, false);
							context.getSource().sendFeedback(Component.literal("[TCD] test text shown"));
							return 1;
						}))
						.then(ClientCommands.literal("debug").executes(context -> {
							DamageTracker.debug = !DamageTracker.debug;
							context.getSource().sendFeedback(Component.literal("[TCD] debug " + (DamageTracker.debug ? "on" : "off")));
							return 1;
						}))));
	}
}
