package me.pepperbell.continuity.client.util;

import java.util.ArrayList;
import java.util.List;

import me.pepperbell.continuity.api.client.EmissiveSpriteApi;
import me.pepperbell.continuity.client.config.ContinuityConfig;
import me.pepperbell.continuity.client.mixin.RenderSystemAccessor;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Matrix3f;
import net.minecraft.util.math.Vec3f;
import net.minecraft.util.math.random.Random;

public class EmissiveQuadModifier {

	// Fullbright lightmap written into emissive quads. Doubles as the marker used to
	// recognize emissive quads at render time.
	public static final int FULLBRIGHT_LIGHTMAP = 0xF000F0;

	// Packed vertex normal pointing straight up (+Y): bytes (nx=0, ny=127, nz=0).
	// Item/entity shaders compute vertexColor = minecraft_mix_light(Light0, Light1, Normal, Color) * lightmap.
	// minecraft_mix_light applies directional shading from the vertex normal, so a downward-facing normal only
	// receives the 0.4 ambient term and darkens the quad even when its lightmap is fullbright. That is why the
	// underside of held emissive items did not glow. The up-facing normal is used as a baked fallback; the exact
	// normal for the current view is computed at render time by rewriteEmissiveNormals().
	private static final int UP_NORMAL_PACKED = 127 << 8;

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

				// Fullbright lightmap (UV2), also acts as the emissive marker
				if (vertexSize >= 7) {
					newData[offset + 6] = FULLBRIGHT_LIGHTMAP;
				}

				// Up-facing fallback normal; the exact normal for the current view is
				// computed at render time by rewriteEmissiveNormals(). See UP_NORMAL_PACKED.
				if (vertexSize >= 8) {
					newData[offset + 7] = UP_NORMAL_PACKED;
				}
			}

			return new BakedQuad(newData, quad.getColorIndex(), quad.getFace(), emissiveSprite, quad.hasShade());

		} catch (Exception e) {
			return null;
		}
	}

	public static boolean isEmissiveQuad(BakedQuad quad) {
		int[] vertexData = quad.getVertexData();
		int vertexSize = vertexData.length / 4;
		if (vertexSize < 7) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			if (vertexData[i * vertexSize + 6] != FULLBRIGHT_LIGHTMAP) {
				return false;
			}
		}
		return true;
	}

	// Item/entity shaders multiply the fullbright lightmap by directional lighting derived from the vertex normal
	// (minecraft_mix_light). A fixed baked normal only lines up with the shader lights in some item orientations,
	// which made the glow flicker between bright and dark as the item (held, armor stand, GUI) rotated.
	// This recomputes the emissive quads' normals at render time so the directional lighting factor is always 1.0:
	// the shader-side normal is (normalMatrix * bakedNormal), so bake (normalMatrix^-1 * optimalViewNormal), where
	// optimalViewNormal is the direction maximizing minecraft_mix_light for the current shader light directions.
	public static void rewriteEmissiveNormals(List<BakedQuad> quads, Matrix3f normalMatrix) {
		boolean hasEmissive = false;
		for (BakedQuad quad : quads) {
			if (isEmissiveQuad(quad)) {
				hasEmissive = true;
				break;
			}
		}
		if (!hasEmissive) {
			return;
		}

		Vec3f[] lightDirections = RenderSystemAccessor.continuity$getShaderLightDirections();
		Vec3f light0 = lightDirections[0];
		Vec3f light1 = lightDirections[1];
		if (light0 == null || light1 == null) {
			return;
		}

		Vec3f l0 = light0.copy();
		Vec3f l1 = light1.copy();
		if (!l0.normalize() || !l1.normalize()) {
			return;
		}

		// minecraft_mix_light: factor = min(1, (max(0, dot(L0, n)) + max(0, dot(L1, n))) * 0.6 + 0.4)
		// For n = normalize(L0 + L1) the dot sum equals |L0 + L1|, reaching factor 1.0 when |L0 + L1| >= 1.
		// Otherwise the lights are more than 120 degrees apart; aligning n with either light alone
		// gives one dot of 1.0 and a non-positive other, also reaching factor 1.0.
		Vec3f optimal = l0.copy();
		optimal.add(l1);
		if (optimal.dot(optimal) < 1.0f) {
			optimal.set(l0);
		}
		optimal.normalize();

		Matrix3f inverseNormalMatrix = normalMatrix.copy();
		if (!inverseNormalMatrix.invert()) {
			return;
		}
		optimal.transform(inverseNormalMatrix);
		optimal.normalize();

		int packedNormal = packNormal(optimal);
		for (BakedQuad quad : quads) {
			if (!isEmissiveQuad(quad)) {
				continue;
			}
			int[] vertexData = quad.getVertexData();
			int vertexSize = vertexData.length / 4;
			if (vertexSize < 8) {
				continue;
			}
			for (int i = 0; i < 4; i++) {
				vertexData[i * vertexSize + 7] = packedNormal;
			}
		}
	}

	private static int packNormal(Vec3f normal) {
		int x = Math.round(normal.getX() * 127.0f) & 0xFF;
		int y = Math.round(normal.getY() * 127.0f) & 0xFF;
		int z = Math.round(normal.getZ() * 127.0f) & 0xFF;
		return x | (y << 8) | (z << 16);
	}
}
