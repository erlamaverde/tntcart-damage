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
			// Prima invecchiamo i testi esistenti, poi aggiungiamo quelli nuovi (età 0).
			DamageIndicators.tick(client);
			DamageTracker.tick(client);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(DamageIndicators::render);

		// Comandi solo client (non vengono mandati al server):
		//   /tntcartdamage test  -> mostra un danno finto su di te (in terza persona, F5)
		//   /tntcartdamage debug -> scrive in chat esplosioni, danni e cali di vita
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(ClientCommands.literal("tntcartdamage")
						.then(ClientCommands.literal("test").executes(context -> {
							// 11 HP di danno, di cui 4 HP sui cuori gialli -> "-7" rosso e "(-4)" giallo
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
