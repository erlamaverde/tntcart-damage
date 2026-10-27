package com.tntcartdamage.gametest;

import com.tntcartdamage.client.DamageIndicators;
import com.tntcartdamage.client.DamagePredictor;
import com.tntcartdamage.client.DamageTracker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.entity.player.ChatVisiblity;

/**
 * Apre un mondo, fa esplodere un TNT cart vicino al player e fa degli screenshot
 * (in build/run/clientGameTest/screenshots). I messaggi [TCD] finiscono anche nel log.
 */
public class TntCartDamageClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.runOnClient(client -> {
				DamageTracker.debug = true;
				client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
			});

			singleplayer.getServer().runCommand("difficulty normal");
			singleplayer.getServer().runCommand("gamemode survival @a");
			singleplayer.getServer().runCommand("time set day");
			singleplayer.getServer().runCommand("effect give @a absorption 60 1 true");
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(24);
			context.takeScreenshot("tntcart-1-just-hit");
			context.waitTicks(24);
			context.takeScreenshot("tntcart-2-fading");
			context.waitTicks(15);
			context.takeScreenshot("tntcart-3-gone");

			// Caso totem: poca vita + totem in mano, l'esplosione lo fa scoppiare -> "pop!".
			singleplayer.getServer().runCommand("effect clear @a");
			singleplayer.getServer().runCommand("item replace entity @p weapon.offhand with totem_of_undying");
			singleplayer.getServer().runCommand("damage @p 15");
			context.waitTicks(15);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-3 {fuse:20}");
			context.waitTicks(26);
			context.takeScreenshot("tntcart-4-totem-pop");

			// Caso altro danno (cactus) subito dopo il cart -> NON deve diventare un secondo numero.
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			context.waitTicks(10);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(25);
			singleplayer.getServer().runCommand("damage @p 14 minecraft:cactus");
			context.waitTicks(10);

			// Caso vita mandata in due pezzi: dopo il cart la vita scende ancora SENZA un nuovo
			// evento di danno (simulato abbassando la vita massima) -> va sommato allo stesso numero.
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			context.waitTicks(10);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(28);
			singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set 4");
			context.waitTicks(4);
			context.takeScreenshot("tntcart-4c-split-merged");
			singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set 20");
			context.waitTicks(30);

			// Caso due cart a 1 secondo di distanza: il secondo testo va SOPRA il primo.
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			singleplayer.getServer().runCommand("effect give @a absorption 60 1 true");
			// Chat nascosta, altrimenti i messaggi di debug coprono il testo nello screenshot.
			context.runOnClient(client -> client.options.chatVisibility().set(ChatVisiblity.HIDDEN));
			singleplayer.getServer().runCommand("execute as @p at @s run tp @s ~40 ~ ~");
			context.waitTicks(20);
			// Uno a sinistra e uno a destra, lontani tra loro: così il primo non fa esplodere il secondo.
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^6 ^ ^ {fuse:20}");
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^-6 ^ ^ {fuse:40}");
			context.waitTicks(46);
			context.takeScreenshot("tntcart-4d-stacked");
			// Il primo testo sparisce ~60 tick dopo i summon: quello sopra deve scivolare giù
			// in modo fluido (non saltare) e continuare a salire/sfumare da dove era.
			context.waitTicks(15);
			context.takeScreenshot("tntcart-4d2-sliding");
			context.waitTicks(5);
			context.takeScreenshot("tntcart-4d3-settled");
			context.runOnClient(client -> client.options.chatVisibility().set(ChatVisiblity.FULL));
			context.waitTicks(10);

			// Caso previsione con armatura e incantesimi, difficoltà difficile: nel log
			// "[TCD] ... previsto -X, reale -Y" i due numeri devono essere uguali.
			singleplayer.getServer().runCommand("difficulty hard");
			singleplayer.getServer().runCommand("item replace entity @p armor.chest with diamond_chestplate[enchantments={blast_protection:4}]");
			singleplayer.getServer().runCommand("item replace entity @p armor.legs with iron_leggings[enchantments={protection:4}]");
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-5 {fuse:20}");
			context.waitTicks(45);
			singleplayer.getServer().runCommand("item replace entity @p armor.chest with air");
			singleplayer.getServer().runCommand("item replace entity @p armor.legs with air");
			singleplayer.getServer().runCommand("difficulty normal");

			// Caso totem senza cart: "pop!" deve comparire lo stesso.
			singleplayer.getServer().runCommand("item replace entity @p weapon.offhand with totem_of_undying");
			singleplayer.getServer().runCommand("damage @p 100");
			context.waitTicks(10);

			// Aspetto della previsione: sotto un numero vero, sopra uno previsto (sbiadito).
			context.waitTicks(40);
			context.runOnClient(client -> {
				client.options.chatVisibility().set(ChatVisiblity.HIDDEN);
				DamageIndicators.addDamage(client.player, 11.0f, 4.0f, false);
			});
			context.waitTicks(5);
			context.runOnClient(client -> DamageIndicators.addPrediction(client.player, 11.0f, 4.0f));
			context.waitTicks(2);
			context.takeScreenshot("tntcart-4e-real-vs-predicted");
			context.runOnClient(client -> client.options.chatVisibility().set(ChatVisiblity.FULL));

			// ===== Previsione: casi nuovi. Nel log: "[TCD] ... previsto -X, reale -Y" =====

			// Copertura parziale: un muretto basso di pietra tra il cart e il player.
			// I blocchi che l'esplosione rompe non devono contare (il server calcola il danno prima).
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("execute as @p at @s run fill ^-1 ^ ^-2 ^1 ^ ^-2 stone");
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-4 {fuse:20}");
			context.waitTicks(45);

			// Incantesimi "nascosti" come fa Paper (un solo incantesimo di livello I): la prima
			// previsione sbaglia apposta (assume Protezione IV), poi impara e la seconda è giusta.
			context.runOnClient(client -> DamagePredictor.testTreatSelfAsOpponent = true);
			singleplayer.getServer().runCommand("item replace entity @p armor.head with iron_helmet[enchantments={unbreaking:1}]");
			singleplayer.getServer().runCommand("item replace entity @p armor.chest with iron_chestplate[enchantments={unbreaking:1}]");
			singleplayer.getServer().runCommand("item replace entity @p armor.legs with iron_leggings[enchantments={unbreaking:1}]");
			singleplayer.getServer().runCommand("item replace entity @p armor.feet with iron_boots[enchantments={unbreaking:1}]");
			for (int hit = 0; hit < 2; hit++) {
				freshSpot(singleplayer, context);
				singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-5 {fuse:20}");
				context.waitTicks(45);
			}
			for (String slot : new String[] {"head", "chest", "legs", "feet"}) {
				singleplayer.getServer().runCommand("item replace entity @p armor." + slot + " with air");
			}

			// Resistenza II vista solo come particelle: la mod assume livello I, poi impara.
			for (int hit = 0; hit < 2; hit++) {
				freshSpot(singleplayer, context);
				singleplayer.getServer().runCommand("effect give @p resistance 60 1");
				context.waitTicks(5);
				context.runOnClient(client -> System.out.println("[TEST] particelle Resistenza viste: "
						+ DamagePredictor.hasResistanceParticles(client.player)));
				singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-5 {fuse:20}");
				context.waitTicks(45);
			}
			context.runOnClient(client -> DamagePredictor.testTreatSelfAsOpponent = false);
			singleplayer.getServer().runCommand("effect clear @a");

			// ===== Server con la vita FINTA (simulato) =====
			// Il nostro player fa finta di essere un avversario. La sua vita scende 3 volte senza
			// colpi (abbassando la vita massima): la mod deve capire che la vita non è affidabile.
			context.runOnClient(client -> DamagePredictor.testTreatSelfAsOpponent = true);
			freshSpot(singleplayer, context);
			context.waitTicks(20);
			for (int maxHealth : new int[] {14, 10, 6}) {
				singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set " + maxHealth);
				context.waitTicks(5);
			}
			singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set 20");
			// Colpo assorbito tutto dai cuori gialli (che sono veri): il danno vero si sa lo stesso.
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("effect give @p absorption 60 3");
			context.waitTicks(5);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(45);
			// Colpo senza cuori gialli: il danno vero non si può sapere, resta la previsione sbiadita.
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(24);
			context.takeScreenshot("tntcart-6-fake-health-prediction");
			context.waitTicks(21);
			context.runOnClient(client -> DamagePredictor.testTreatSelfAsOpponent = false);
			singleplayer.getServer().runCommand("effect clear @a");

			// Caso prima persona: su di te il testo NON deve comparire.
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^6 {fuse:20}");
			context.waitTicks(24);
			context.takeScreenshot("tntcart-5-first-person");
		}
	}

	/** Porta il player in un posto pulito (niente crateri), senza effetti, con la vita piena e fermo a terra. */
	private static void freshSpot(TestSingleplayerContext singleplayer, ClientGameTestContext context) {
		singleplayer.getServer().runCommand("execute as @p at @s run spreadplayers ~40 ~ 0 1 false @s");
		singleplayer.getServer().runCommand("effect clear @a");
		// Cura a passi di +8 (come una vera pozione di cura II): una cura più grande in un colpo
		// solo sembrerebbe una "vita finta" alla mod, quando il player fa finta di essere un avversario.
		for (int i = 0; i < 3; i++) {
			singleplayer.getServer().runCommand("effect give @a instant_health 1 1");
			context.waitTicks(3);
		}
		context.waitTicks(11);
	}
}
