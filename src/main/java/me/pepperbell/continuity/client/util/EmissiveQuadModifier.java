package me.pepperbell.continuity.client.util;

import java.util.ArrayList;
import java.util.List;

import me.pepperbell.continuity.api.client.EmissiveSpriteApi;
import me.pepperbell.continuity.client.config.ContinuityConfig;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

public class EmissiveQuadModifier {

	public static boolean modelHasEmissiveQuads(BakedModel model) {
		if (!ContinuityConfig.INSTANCE.emissiveTextures.get()) {
			return false;
		}

		Random random = net.minecraft.util.math.random.Random.create(42);
		Direction[] dirs = { null, Direction.DOWN, Direction.UP, Direction.NORTH,
				Direction.SOUTH, Direction.WEST, Direction.EAST };
		for (Direction dir : dirs) {
			List<BakedQuad> quads = model.getQuads(null, dir, random);
			for (BakedQuad quad : quads) {
				Sprite sprite = quad.getSprite();
				if (sprite != null) {
					Sprite emissiveSprite = EmissiveSpriteApi.get().getEmissiveSprite(sprite);
					if (emissiveSprite != null) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static List<BakedQuad> createEmissiveQuads(List<BakedQuad> baseQuads) {
		if (!ContinuityConfig.INSTANCE.emissiveTextures.get()) {
			return List.of();
		}

		List<BakedQuad> result = new ArrayList<>();

		for (BakedQuad quad : baseQuads) {
			Sprite baseSprite = quad.getSprite();
			if (baseSprite == null) continue;

			Sprite emissiveSprite = EmissiveSpriteApi.get().getEmissiveSprite(baseSprite);
			if (emissiveSprite == null) continue;

			BakedQuad emissiveQuad = createEmissiveQuad(quad, baseSprite, emissiveSprite);
			if (emissiveQuad != null) {
				result.add(emissiveQuad);
			}
		}

		return result;
	}

	private static BakedQuad createEmissiveQuad(BakedQuad quad, Sprite baseSprite, Sprite emissiveSprite) {
		try {
			int[] vertexData = quad.getVertexData();
			int vertexSize = vertexData.length / 4;

			float baseMinU = baseSprite.getMinU();
			float baseMinV = baseSprite.getMinV();
			float baseMaxU = baseSprite.getMaxU();
			float baseMaxV = baseSprite.getMaxV();
			float emissiveMinU = emissiveSprite.getMinU();
			float emissiveMinV = emissiveSprite.getMinV();
			float emissiveMaxU = emissiveSprite.getMaxU();
			float emissiveMaxV = emissiveSprite.getMaxV();

			float uFactor = (emissiveMaxU - emissiveMinU) / (baseMaxU - baseMinU);
			float vFactor = (emissiveMaxV - emissiveMinV) / (baseMaxV - baseMinV);

			int[] newData = new int[vertexData.length];

			for (int i = 0; i < 4; i++) {
				int offset = i * vertexSize;

				// Copy position
				newData[offset] = vertexData[offset];
				newData[offset + 1] = vertexData[offset + 1];
				newData[offset + 2] = vertexData[offset + 2];

				// White color (full brightness)
				newData[offset + 3] = -1;

				// Interpolate UV to emissive sprite
				float u = Float.intBitsToFloat(vertexData[offset + 4]);
				float v = Float.intBitsToFloat(vertexData[offset + 5]);
				float newU = emissiveMinU + (u - baseMinU) * uFactor;
				float newV = emissiveMinV + (v - baseMinV) * vFactor;
				newData[offset + 4] = Float.floatToIntBits(newU);
				newData[offset + 5] = Float.floatToIntBits(newV);

				// Fullbright lightmap (UV2 = 0xF000F0)
				if (vertexSize >= 7) {
					newData[offset + 6] = 0xF000F0;
				}

				// Copy normal
				if (vertexSize >= 8) {
					newData[offset + 7] = vertexData[offset + 7];
				}
			}

			return new BakedQuad(newData, quad.getColorIndex(), quad.getFace(), emissiveSprite, quad.hasShade());

		} catch (Exception e) {
			return null;
		}
	}
}
