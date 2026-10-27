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
 * Tiene la lista dei testi "-danno" attivi e li disegna nel mondo.
 * Ogni testo dura 2 secondi (40 tick): è pieno per 1 secondo,
 * poi sfuma fino a sparire, mentre sale un po' verso l'alto.
 *
 * Il danno è mostrato in HP (2 HP = 1 cuore): in rosso quello sulla vita normale,
 * sotto in giallo "(-X)" quello sui cuori gialli (assorbimento).
 * Se al player scoppia il totem, al posto dei numeri compare "pop!" in verde.
 */
public final class DamageIndicators {
	private static final int LIFETIME_TICKS = 40;
	private static final int FADE_START_TICKS = 20;
	/** Danni arrivati entro questi tick vengono sommati nello stesso testo. */
	private static final int MERGE_TICKS = 4;
	private static final float RISE_BLOCKS = 0.5f;
	/** Spazio (in pixel del font) tra un testo e quello di un altro cart impilato sopra. */
	private static final float STACK_GAP = 2.0f;
	/** Velocità con cui un testo scivola giù quando sparisce quello sotto:
	 *  più è alta più è veloce (con 15 fa il 95% della strada in 0.2 secondi). */
	private static final float SLIDE_SPEED = 15.0f;
	/** Momento (System.nanoTime) dell'ultimo frame disegnato. */
	private static long lastRenderNanos = 0;
	/** 1 pixel del font = 0.045 blocchi (i nametag vanilla usano 0.025). */
	private static final float SCALE = 0.045f;
	/** Sposta il testo verso la telecamera così non finisce dentro il modello del player. */
	private static final double TOWARDS_CAMERA = 0.6;
	/** Colori accesi: rosso pieno e giallo oro. */
	private static final int DAMAGE_COLOR = 0xFF1F1F;
	private static final int ABSORPTION_COLOR = 0xFFD800;
	/** Verde acceso, come le particelle del totem. */
	private static final int POP_COLOR = 0x33FF55;
	/** L'ombra è il colore del testo scurito (Minecraft usa 0.25). */
	private static final float SHADOW_BRIGHTNESS = 0.25f;
	/** Opacità del testo quando mostra solo il danno previsto (1 = pieno). */
	private static final float PREDICTED_OPACITY = 0.55f;
	/** Quanto più lontana dalla telecamera sta l'ombra (in pixel del font). */
	private static final float SHADOW_DEPTH = 1.0f;

	private static final List<Indicator> indicators = new ArrayList<>();

	private DamageIndicators() {
	}

	private static final class Indicator {
		final int entityId;
		Vec3 lastPos;
		float height;
		/** Danno totale in HP (cuori gialli compresi). */
		float damage;
		/** Quanta parte di damage è stata tolta ai cuori gialli. */
		float absorptionDamage;
		/** true se al player è scoppiato il totem: si mostra "pop!" invece dei numeri. */
		boolean popped;
		/** true finché mostriamo il danno previsto e non ancora quello vero. */
		boolean predicted;
		int age;
		/** Dove è disegnato adesso il bordo inferiore del testo (in pixel del font, verso l'alto).
		 *  Serve per farlo scivolare in modo fluido invece di farlo saltare. */
		float shownBottom;
		/** false finché il testo non è stato disegnato la prima volta. */
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
	 * damage e absorptionDamage sono in HP.
	 * continueLastHit = true quando è un altro pezzo dello stesso colpo (il server ha mandato
	 * il calo di vita in più parti): lo sommiamo all'ultimo numero di quel player.
	 */
	public static void addDamage(Entity entity, float damage, float absorptionDamage, boolean continueLastHit) {
		if (continueLastHit) {
			for (int i = indicators.size() - 1; i >= 0; i--) {
				Indicator indicator = indicators.get(i);
				if (indicator.entityId == entity.getId()) {
					if (indicator.predicted) {
						// Era una previsione: ora che sappiamo il danno vero, lo sostituiamo.
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
		// Se c'è già un testo appena nato per questo player (es. due cart esplosi
		// insieme), sommiamo il danno invece di sovrapporre due scritte.
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
	 * Mostra subito il danno PREVISTO (vedi DamagePredictor). Quando arriva il danno vero
	 * (addDamage con continueLastHit = true) prende il suo posto.
	 */
	public static void addPrediction(Entity entity, float damage, float absorptionDamage) {
		// Due cart esplosi insieme: sommiamo le previsioni nello stesso testo.
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
	 * Server con la vita finta: dei cuori gialli persi sappiamo il valore vero, della vita no.
	 * Mettiamo la parte gialla vera nella previsione, che resta una previsione (sbiadita).
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

	/** Il totem è scoppiato: mostra "pop!" al posto dei numeri. */
	public static void addPop(Entity entity) {
		// Se c'è già un testo appena nato per questo player, lo trasformiamo in "pop!".
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
		// id del player -> dove finisce (in pixel del font, verso l'alto) l'ultimo testo
		// disegnato sopra di lui. Serve per impilare i testi di cart diversi uno sopra l'altro.
		Map<Integer, Float> stackTop = new HashMap<>();

		// Secondi passati dall'ultimo frame, per far scivolare i testi alla stessa velocità
		// a qualsiasi FPS. Limitato a 0.1 s così dopo una pausa/lag non "teletrasporta".
		long now = System.nanoTime();
		float frameSeconds = lastRenderNanos == 0 ? 0 : Math.min((now - lastRenderNanos) / 1.0e9f, 0.1f);
		lastRenderNanos = now;
		// Quanta strada fa il testo verso la sua posizione in questo frame (0..1).
		float slide = 1.0f - (float) Math.exp(-SLIDE_SPEED * frameSeconds);

		// La lista è in ordine di creazione: per ogni player i testi più vecchi vengono
		// prima, quindi ogni testo nuovo finisce sopra quelli precedenti.
		for (Indicator indicator : indicators) {
			float time = indicator.age + partialTick;
			if (time >= LIFETIME_TICKS) {
				continue;
			}

			List<Line> lines = linesFor(indicator);
			float blockHeight = lines.size() * lineSpacing;
			// Quanto è salito il testo (in pixel del font) con il passare del tempo.
			float risePx = RISE_BLOCKS * (time / LIFETIME_TICKS) / SCALE;
			// Dove DOVREBBE stare il bordo inferiore del testo: dove lo porta la salita,
			// ma mai sotto la cima del testo precedente dello stesso player.
			float targetBottom = risePx - blockHeight / 2.0f;
			Float previousTop = stackTop.get(indicator.entityId);
			if (previousTop != null) {
				targetBottom = Math.max(targetBottom, previousTop + STACK_GAP);
			}
			if (!indicator.placed || targetBottom > indicator.shownBottom) {
				// Primo frame, oppure deve salire (spinto dal testo sotto): va subito lì,
				// così non si sovrappone mai a quello sotto.
				indicator.shownBottom = targetBottom;
				indicator.placed = true;
			} else {
				// Deve scendere (il testo sotto è sparito): scivola giù veloce ma fluido,
				// senza scatti e senza far ripartire la salita.
				indicator.shownBottom += (targetBottom - indicator.shownBottom) * slide;
			}
			float bottom = indicator.shownBottom;
			stackTop.put(indicator.entityId, bottom + blockHeight);

			// Opacità: 1 fino a FADE_START_TICKS, poi scende linearmente a 0.
			float alpha = 1.0f;
			if (time > FADE_START_TICKS) {
				alpha = 1.0f - (time - FADE_START_TICKS) / (LIFETIME_TICKS - FADE_START_TICKS);
			}
			// Finché è solo una previsione il testo è sbiadito; col danno vero torna pieno.
			if (indicator.predicted) {
				alpha *= PREDICTED_OPACITY;
			}
			int alphaByte = (int) (alpha * 255);
			// Il font di Minecraft tratta un alpha quasi zero come "opaco", quindi lo saltiamo.
			if (alphaByte < 5) {
				continue;
			}

			// Sei tu il colpito e sei in prima persona: non mostriamo niente,
			// altrimenti il testo finirebbe davanti allo schermo e distrae.
			if (isSelfInFirstPerson(client, indicator)) {
				continue;
			}

			// Segue il player (se è ancora caricato), altrimenti resta dove era.
			Entity entity = client.level.getEntity(indicator.entityId);
			Vec3 base = entity != null ? entity.getPosition(partialTick) : indicator.lastPos;
			Vec3 pos = base.add(0, indicator.height * 0.75, 0);

			Vec3 toCamera = camera.pos.subtract(pos);
			if (toCamera.lengthSqr() > 1.0e-4) {
				pos = pos.add(toCamera.normalize().scale(TOWARDS_CAMERA));
			}

			// Nel testo la y cresce verso il BASSO, quindi "su di bottom pixel" è -bottom.
			// lineY è la y della prima riga (quella più in alto).
			float lineY = -(bottom + blockHeight);

			poseStack.pushPose();
			poseStack.translate(pos.x - camera.pos.x, pos.y - camera.pos.y, pos.z - camera.pos.z);
			// Ruota il testo verso la telecamera (come i nametag).
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

	/** Le righe di testo da mostrare per un indicatore, dall'alto in basso. */
	private static List<Line> linesFor(Indicator indicator) {
		List<Line> lines = new ArrayList<>();
		if (indicator.popped) {
			lines.add(new Line("pop!", POP_COLOR));
			return lines;
		}
		// Rosso = solo il danno sulla vita normale, giallo = solo quello sui cuori gialli.
		// Es. 9.8 di danno con 8 sui cuori gialli -> "-1.8" rosso e "(-8)" giallo.
		float healthDamage = indicator.damage - indicator.absorptionDamage;
		boolean hasAbsorptionLine = roundToTenth(indicator.absorptionDamage) > 0;
		// Se il colpo ha preso solo cuori gialli, la riga rossa non serve.
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
		// Disegnato "sopra a tutto", l'ombra automatica di Minecraft finirebbe sopra
		// le lettere. Quindi la disegniamo noi: l'ombra è spostata di 1 pixel in basso
		// a destra e un po' più lontana dalla telecamera. Minecraft disegna il testo
		// trasparente dal più lontano al più vicino, quindi l'ombra finisce dietro.
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
				// SEE_THROUGH: disegnato sopra a tutto, così il modello del player
				// (braccia, armi, armatura) non copre mai il numero.
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

	/** Arrotonda a un decimale: 9.84 -> 9.8 */
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
