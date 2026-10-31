package com.tntcartdamage.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the list of active "-damage" labels and draws them in the world.
 * Each label lasts 2 seconds (40 ticks): fully visible for 1 second,
 * then it fades out while rising a little.
 *
 * Damage is shown in HP (2 HP = 1 heart): in red the part taken by normal health,
 * below it in yellow "(-X)" the part taken by absorption hearts.
 * If the player's totem pops, a green "pop!" is shown instead of the numbers.
 */
public final class DamageIndicators {
	private static final int LIFETIME_TICKS = 40;
	private static final int FADE_START_TICKS = 20;
	/** Damage arriving within this many ticks is added to the same label. */
	private static final int MERGE_TICKS = 4;
	private static final float RISE_BLOCKS = 0.5f;
	/** Space (in font pixels) between a label and the one from another cart stacked above it. */
	private static final float STACK_GAP = 2.0f;
	/** How fast a label slides down when the one below it disappears:
	 *  higher is faster (at 15 it covers 95% of the way in 0.2 seconds). */
	private static final float SLIDE_SPEED = 15.0f;
	/** Time (System.nanoTime) of the last drawn frame. */
	private static long lastRenderNanos = 0;
	/** 1 font pixel = 0.045 blocks (vanilla name tags use 0.025). */
	private static final float SCALE = 0.045f;
	/** Moves the label towards the camera so it doesn't end up inside the player model. */
	private static final double TOWARDS_CAMERA = 0.6;
	/** Bright colors: full red and golden yellow. */
	private static final int DAMAGE_COLOR = 0xFF1F1F;
	private static final int ABSORPTION_COLOR = 0xFFD800;
	/** Bright green, like the totem particles. */
	private static final int POP_COLOR = 0x33FF55;
	/** The shadow is the text color darkened (Minecraft uses 0.25). */
	private static final float SHADOW_BRIGHTNESS = 0.25f;
	/** Label opacity while it only shows the predicted damage (1 = solid). */
	private static final float PREDICTED_OPACITY = 0.55f;
	/** How much farther from the camera the shadow is (in font pixels). */
	private static final float SHADOW_DEPTH = 1.0f;

	private static final List<Indicator> indicators = new ArrayList<>();

	private DamageIndicators() {
	}

	private static final class Indicator {
		final int entityId;
		Vec3 lastPos;
		float height;
		/** Total damage in HP (absorption hearts included). */
		float damage;
		/** How much of the damage was taken by absorption hearts. */
		float absorptionDamage;
		/** true if the player's totem popped: shows "pop!" instead of the numbers. */
		boolean popped;
		/** true while we show the predicted damage and not the real one yet. */
		boolean predicted;
		int age;
		/** Where the bottom edge of the label is drawn right now (in font pixels, upwards).
		 *  Used to make it slide smoothly instead of jumping. */
		float shownBottom;
		/** false until the label has been drawn for the first time. */
		boolean placed;

		Indicator(Entity entity, float damage, float absorptionDamage) {
			this.entityId = entity.getId();
			this.lastPos = entity.position();
			this.height = entity.getBbHeight();
			this.damage = damage;
			this.absorptionDamage = absorptionDamage;
		}
	}

	/**
	 * damage and absorptionDamage are in HP.
	 * continueLastHit = true when it's another piece of the same hit (the server sent the
	 * health drop in several parts): we add it to that player's latest number.
	 */
	public static void addDamage(Entity entity, float damage, float absorptionDamage, boolean continueLastHit) {
		if (continueLastHit) {
			for (int i = indicators.size() - 1; i >= 0; i--) {
				Indicator indicator = indicators.get(i);
				if (indicator.entityId == entity.getId()) {
					if (indicator.predicted) {
						// It was a prediction: now that we know the real damage, replace it.
						indicator.damage = damage;
						indicator.absorptionDamage = absorptionDamage;
						indicator.predicted = false;
					} else {
						indicator.damage += damage;
						indicator.absorptionDamage += absorptionDamage;
					}
					return;
				}
			}
		}
		// If there's already a freshly created label for this player (e.g. two carts exploding
		// together), add the damage to it instead of drawing two overlapping labels.
		for (Indicator indicator : indicators) {
			if (indicator.entityId == entity.getId() && indicator.age < MERGE_TICKS) {
				indicator.damage += damage;
				indicator.absorptionDamage += absorptionDamage;
				return;
			}
		}
		indicators.add(new Indicator(entity, damage, absorptionDamage));
	}

	/**
	 * Immediately shows the PREDICTED damage (see DamagePredictor). When the real damage
	 * arrives (addDamage with continueLastHit = true) it takes its place.
	 */
	public static void addPrediction(Entity entity, float damage, float absorptionDamage) {
		// Two carts exploding together: add the predictions up in the same label.
		for (Indicator indicator : indicators) {
			if (indicator.entityId == entity.getId() && indicator.age < MERGE_TICKS && indicator.predicted) {
				indicator.damage += damage;
				indicator.absorptionDamage += absorptionDamage;
				return;
			}
		}
		Indicator indicator = new Indicator(entity, damage, absorptionDamage);
		indicator.predicted = true;
		indicators.add(indicator);
	}

	/**
	 * Fake-health server: we know the real absorption hearts lost, but not the health.
	 * Put the real absorption part into the prediction, which stays a (faded) prediction.
	 */
	public static void setKnownAbsorption(Entity entity, float absorptionDamage) {
		for (int i = indicators.size() - 1; i >= 0; i--) {
			Indicator indicator = indicators.get(i);
			if (indicator.entityId == entity.getId()) {
				if (indicator.predicted) {
					indicator.absorptionDamage = absorptionDamage;
					indicator.damage = Math.max(indicator.damage, absorptionDamage);
				}
				return;
			}
		}
	}

	/** The totem popped: show "pop!" instead of the numbers. */
	public static void addPop(Entity entity) {
		// If there's already a freshly created label for this player, turn it into "pop!".
		for (Indicator indicator : indicators) {
			if (indicator.entityId == entity.getId() && indicator.age < MERGE_TICKS) {
				indicator.popped = true;
				return;
			}
		}
		Indicator indicator = new Indicator(entity, 0, 0);
		indicator.popped = true;
		indicators.add(indicator);
	}

	public static void tick(Minecraft client) {
		if (client.level == null) {
			indicators.clear();
			return;
		}
		if (client.isPaused()) {
			return;
		}
		Iterator<Indicator> it = indicators.iterator();
		while (it.hasNext()) {
			Indicator indicator = it.next();
			indicator.age++;
			if (indicator.age >= LIFETIME_TICKS) {
				it.remove();
				continue;
			}
			Entity entity = client.level.getEntity(indicator.entityId);
			if (entity != null) {
				indicator.lastPos = entity.position();
				indicator.height = entity.getBbHeight();
			}
		}
	}

	public static void render(LevelRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		if (indicators.isEmpty() || client.level == null) {
			return;
		}

		CameraRenderState camera = context.levelState().cameraRenderState;
		PoseStack poseStack = context.poseStack();
		Font font = client.font;
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		int lineSpacing = font.lineHeight + 1;
		// player id -> where the last label drawn above them ends (in font pixels, upwards).
		// Used to stack the labels of different carts on top of each other.
		Map<Integer, Float> stackTop = new HashMap<>();

		// Seconds since the last frame, so labels slide at the same speed at any FPS.
		// Capped at 0.1 s so that after a pause or lag spike they don't "teleport".
		long now = System.nanoTime();
		float frameSeconds = lastRenderNanos == 0 ? 0 : Math.min((now - lastRenderNanos) / 1.0e9f, 0.1f);
		lastRenderNanos = now;
		// How much of the way to its target the label moves in this frame (0..1).
		float slide = 1.0f - (float) Math.exp(-SLIDE_SPEED * frameSeconds);

		// The list is in creation order: for each player the older labels come first,
		// so each new label ends up above the previous ones.
		for (Indicator indicator : indicators) {
			float time = indicator.age + partialTick;
			if (time >= LIFETIME_TICKS) {
				continue;
			}

			List<Line> lines = linesFor(indicator);
			float blockHeight = lines.size() * lineSpacing;
			// How far the label has risen (in font pixels) over time.
			float risePx = RISE_BLOCKS * (time / LIFETIME_TICKS) / SCALE;
			// Where the bottom edge of the label SHOULD be: where the rise takes it,
			// but never below the top of the same player's previous label.
			float targetBottom = risePx - blockHeight / 2.0f;
			Float previousTop = stackTop.get(indicator.entityId);
			if (previousTop != null) {
				targetBottom = Math.max(targetBottom, previousTop + STACK_GAP);
			}
			if (!indicator.placed || targetBottom > indicator.shownBottom) {
				// First frame, or it has to move up (pushed by the label below): jump there
				// right away, so it never overlaps the one below.
				indicator.shownBottom = targetBottom;
				indicator.placed = true;
			} else {
				// It has to move down (the label below disappeared): slide down fast but smoothly,
				// without jerks and without restarting the rise.
				indicator.shownBottom += (targetBottom - indicator.shownBottom) * slide;
			}
			float bottom = indicator.shownBottom;
			stackTop.put(indicator.entityId, bottom + blockHeight);

			// Opacity: 1 until FADE_START_TICKS, then it goes linearly down to 0.
			float alpha = 1.0f;
			if (time > FADE_START_TICKS) {
				alpha = 1.0f - (time - FADE_START_TICKS) / (LIFETIME_TICKS - FADE_START_TICKS);
			}
			// While it's only a prediction the label is faded; with the real damage it becomes solid.
			if (indicator.predicted) {
				alpha *= PREDICTED_OPACITY;
			}
			int alphaByte = (int) (alpha * 255);
			// Minecraft's font treats an almost-zero alpha as "opaque", so we skip it.
			if (alphaByte < 5) {
				continue;
			}

			// You are the one hit and you're in first person: show nothing,
			// otherwise the label would be right in front of the screen and get in the way.
			if (isSelfInFirstPerson(client, indicator)) {
				continue;
			}

			// Follows the player (if still loaded), otherwise stays where it was.
			Entity entity = client.level.getEntity(indicator.entityId);
			Vec3 base = entity != null ? entity.getPosition(partialTick) : indicator.lastPos;
			Vec3 pos = base.add(0, indicator.height * 0.75, 0);

			Vec3 toCamera = camera.pos.subtract(pos);
			if (toCamera.lengthSqr() > 1.0e-4) {
				pos = pos.add(toCamera.normalize().scale(TOWARDS_CAMERA));
			}

			// In text space y grows DOWNWARDS, so "up by bottom pixels" is -bottom.
			// lineY is the y of the first (topmost) line.
			float lineY = -(bottom + blockHeight);

			poseStack.pushPose();
			poseStack.translate(pos.x - camera.pos.x, pos.y - camera.pos.y, pos.z - camera.pos.z);
			// Rotate the label to face the camera (like name tags).
			poseStack.mulPose(camera.orientation);
			poseStack.scale(SCALE, -SCALE, SCALE);

			for (Line line : lines) {
				drawCenteredLine(context, poseStack, font, line.text(), lineY, line.color(), alphaByte);
				lineY += lineSpacing;
			}
			poseStack.popPose();
		}
	}

	private record Line(String text, int color) {
	}

	/** The lines of text to show for an indicator, top to bottom. */
	private static List<Line> linesFor(Indicator indicator) {
		List<Line> lines = new ArrayList<>();
		if (indicator.popped) {
			lines.add(new Line("pop!", POP_COLOR));
			return lines;
		}
		// Red = only the damage to normal health, yellow = only the damage to absorption hearts.
		// E.g. 9.8 damage with 8 on absorption hearts -> red "-1.8" and yellow "(-8)".
		float healthDamage = indicator.damage - indicator.absorptionDamage;
		boolean hasAbsorptionLine = roundToTenth(indicator.absorptionDamage) > 0;
		// If the hit only took absorption hearts, the red line isn't needed.
		if (roundToTenth(healthDamage) > 0 || !hasAbsorptionLine) {
			lines.add(new Line("-" + formatHp(healthDamage), DAMAGE_COLOR));
		}
		if (hasAbsorptionLine) {
			lines.add(new Line("(-" + formatHp(indicator.absorptionDamage) + ")", ABSORPTION_COLOR));
		}
		return lines;
	}

	private static void drawCenteredLine(LevelRenderContext context, PoseStack poseStack, Font font,
			String string, float y, int color, int alphaByte) {
		FormattedCharSequence text = Component.literal(string).getVisualOrderText();
		float x = -font.width(text) / 2.0f;
		// Since it's drawn "on top of everything", Minecraft's own shadow would end up over
		// the letters. So we draw it ourselves: the shadow is moved 1 pixel down and to the
		// right and slightly farther from the camera. Minecraft draws translucent text from
		// farthest to nearest, so the shadow ends up behind.
		poseStack.pushPose();
		poseStack.translate(0, 0, SHADOW_DEPTH);
		submitSeeThroughText(context.submitNodeCollector(), poseStack, text, x + 1, y + 1,
				ARGB.color(alphaByte, ARGB.scaleRGB(color, SHADOW_BRIGHTNESS)));
		poseStack.popPose();
		submitSeeThroughText(context.submitNodeCollector(), poseStack, text, x, y,
				ARGB.color(alphaByte, color));
	}

	private static void submitSeeThroughText(OrderedSubmitNodeCollector collector, PoseStack poseStack,
			FormattedCharSequence text, float x, float y, int argb) {
		collector.submitText(
				poseStack,
				x,
				y,
				text,
				false,
				// SEE_THROUGH: drawn on top of everything, so the player model
				// (arms, weapons, armor) never covers the number.
				Font.DisplayMode.SEE_THROUGH,
				LightCoordsUtil.FULL_BRIGHT,
				argb,
				0,
				0
		);
	}

	private static boolean isSelfInFirstPerson(Minecraft client, Indicator indicator) {
		return client.player != null
				&& indicator.entityId == client.player.getId()
				&& !client.gameRenderer.mainCamera().isDetached();
	}

	/** Rounds to one decimal: 9.84 -> 9.8 */
	private static float roundToTenth(float value) {
		return Math.round(value * 10.0f) / 10.0f;
	}

	/** 7.0 -> "7", 7.5 -> "7.5" */
	private static String formatHp(float hp) {
		String number = String.format(Locale.ROOT, "%.1f", roundToTenth(hp));
		if (number.endsWith(".0")) {
			number = number.substring(0, number.length() - 2);
		}
		return number;
	}
}
