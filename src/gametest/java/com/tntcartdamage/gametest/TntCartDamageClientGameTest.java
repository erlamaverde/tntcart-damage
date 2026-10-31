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
 * Opens a world, blows up a TNT cart near the player and takes screenshots
 * (in build/run/clientGameTest/screenshots). The [TCD] messages also end up in the log.
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

			// Totem case: low health + totem in hand, the explosion pops it -> "pop!".
			singleplayer.getServer().runCommand("effect clear @a");
			singleplayer.getServer().runCommand("item replace entity @p weapon.offhand with totem_of_undying");
			singleplayer.getServer().runCommand("damage @p 15");
			context.waitTicks(15);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-3 {fuse:20}");
			context.waitTicks(26);
			context.takeScreenshot("tntcart-4-totem-pop");

			// Other damage (cactus) right after the cart -> must NOT become a second number.
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			context.waitTicks(10);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(25);
			singleplayer.getServer().runCommand("damage @p 14 minecraft:cactus");
			context.waitTicks(10);

			// Health sent in two pieces: after the cart, health drops again WITHOUT a new damage
			// event (simulated by lowering max health) -> it must be added to the same number.
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			context.waitTicks(10);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(28);
			singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set 4");
			context.waitTicks(4);
			context.takeScreenshot("tntcart-4c-split-merged");
			singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set 20");
			context.waitTicks(30);

			// Two carts 1 second apart: the second label goes ABOVE the first.
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			singleplayer.getServer().runCommand("effect give @a absorption 60 1 true");
			// Chat hidden, otherwise the debug messages cover the label in the screenshot.
			context.runOnClient(client -> client.options.chatVisibility().set(ChatVisiblity.HIDDEN));
			singleplayer.getServer().runCommand("execute as @p at @s run tp @s ~40 ~ ~");
			context.waitTicks(20);
			// One on the left and one on the right, far apart: so the first doesn't set off the second.
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^6 ^ ^ {fuse:20}");
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^-6 ^ ^ {fuse:40}");
			context.waitTicks(46);
			context.takeScreenshot("tntcart-4d-stacked");
			// The first label disappears ~60 ticks after the summons: the one above must slide down
			// smoothly (not jump) and keep rising/fading from where it was.
			context.waitTicks(15);
			context.takeScreenshot("tntcart-4d2-sliding");
			context.waitTicks(5);
			context.takeScreenshot("tntcart-4d3-settled");
			context.runOnClient(client -> client.options.chatVisibility().set(ChatVisiblity.FULL));
			context.waitTicks(10);

			// Prediction with armor and enchantments on hard difficulty: in the log
			// "[TCD] ... predicted -X, real -Y" the two numbers must match.
			singleplayer.getServer().runCommand("difficulty hard");
			singleplayer.getServer().runCommand("item replace entity @p armor.chest with diamond_chestplate[enchantments={blast_protection:4}]");
			singleplayer.getServer().runCommand("item replace entity @p armor.legs with iron_leggings[enchantments={protection:4}]");
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-5 {fuse:20}");
			context.waitTicks(45);
			singleplayer.getServer().runCommand("item replace entity @p armor.chest with air");
			singleplayer.getServer().runCommand("item replace entity @p armor.legs with air");
			singleplayer.getServer().runCommand("difficulty normal");

			// Totem without a cart: "pop!" must show up anyway.
			singleplayer.getServer().runCommand("item replace entity @p weapon.offhand with totem_of_undying");
			singleplayer.getServer().runCommand("damage @p 100");
			context.waitTicks(10);

			// How the prediction looks: a real number below, a predicted (faded) one above.
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

			// ===== Prediction: new cases. In the log: "[TCD] ... predicted -X, real -Y" =====

			// Partial cover: a low stone wall between the cart and the player.
			// Blocks broken by the explosion must not count (the server computes the damage first).
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("execute as @p at @s run fill ^-1 ^ ^-2 ^1 ^ ^-2 stone");
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-4 {fuse:20}");
			context.waitTicks(45);

			// "Hidden" enchantments like Paper does (a single level I enchantment): the first
			// prediction is wrong on purpose (assumes Protection IV), then it learns and the second is right.
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

			// Resistance II only seen as particles: the mod assumes level I, then learns.
			for (int hit = 0; hit < 2; hit++) {
				freshSpot(singleplayer, context);
				singleplayer.getServer().runCommand("effect give @p resistance 60 1");
				context.waitTicks(5);
				context.runOnClient(client -> System.out.println("[TEST] Resistance particles seen: "
						+ DamagePredictor.hasResistanceParticles(client.player)));
				singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-5 {fuse:20}");
				context.waitTicks(45);
			}
			context.runOnClient(client -> DamagePredictor.testTreatSelfAsOpponent = false);
			singleplayer.getServer().runCommand("effect clear @a");

			// ===== Server with FAKE health (simulated) =====
			// Our player pretends to be an opponent. Its health drops 3 times without hits
			// (by lowering max health): the mod must realize the health can't be trusted.
			context.runOnClient(client -> DamagePredictor.testTreatSelfAsOpponent = true);
			freshSpot(singleplayer, context);
			context.waitTicks(20);
			for (int maxHealth : new int[] {14, 10, 6}) {
				singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set " + maxHealth);
				context.waitTicks(5);
			}
			singleplayer.getServer().runCommand("attribute @p minecraft:max_health base set 20");
			// Hit fully absorbed by absorption hearts (which are real): the real damage is still known.
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("effect give @p absorption 60 3");
			context.waitTicks(5);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(45);
			// Hit without absorption hearts: the real damage can't be known, the faded prediction stays.
			freshSpot(singleplayer, context);
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^-6 {fuse:20}");
			context.waitTicks(24);
			context.takeScreenshot("tntcart-6-fake-health-prediction");
			context.waitTicks(21);
			context.runOnClient(client -> DamagePredictor.testTreatSelfAsOpponent = false);
			singleplayer.getServer().runCommand("effect clear @a");

			// First-person case: the label must NOT appear on yourself.
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			singleplayer.getServer().runCommand("effect give @a instant_health 1 5");
			singleplayer.getServer().runCommand("execute as @p at @s run summon tnt_minecart ^ ^ ^6 {fuse:20}");
			context.waitTicks(24);
			context.takeScreenshot("tntcart-5-first-person");
		}
	}

	/** Moves the player to a clean spot (no craters), with no effects, full health and standing on the ground. */
	private static void freshSpot(TestSingleplayerContext singleplayer, ClientGameTestContext context) {
		singleplayer.getServer().runCommand("execute as @p at @s run spreadplayers ~40 ~ 0 1 false @s");
		singleplayer.getServer().runCommand("effect clear @a");
		// Heals in steps of +8 (like a real Instant Health II potion): a bigger heal in one go
		// would look like "fake health" to the mod while the player pretends to be an opponent.
		for (int i = 0; i < 3; i++) {
			singleplayer.getServer().runCommand("effect give @a instant_health 1 1");
			context.waitTicks(3);
		}
		context.waitTicks(11);
	}
}
