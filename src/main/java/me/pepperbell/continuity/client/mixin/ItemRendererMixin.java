package me.pepperbell.continuity.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.pepperbell.continuity.client.model.EmissiveBakedModel;
import me.pepperbell.continuity.client.util.EmissiveQuadModifier;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

	@ModifyVariable(
			method = "renderBakedItemModel",
			at = @At("HEAD"),
			argsOnly = true,
			ordinal = 0,
			require = 0)
	private BakedModel continuity$wrapEmissiveModel(BakedModel model) {
		if (model != null && !(model instanceof EmissiveBakedModel)) {
			if (EmissiveQuadModifier.modelHasEmissiveQuads(model)) {
				return new EmissiveBakedModel(model);
			}
		}
		return model;
	}

	// Item shaders multiply the fullbright lightmap of emissive quads by directional lighting
	// derived from the vertex normal, which varies with the item's orientation and darkens
	// some faces. Rewrite emissive quad normals per render call so they always face the
	// current shader lights and glow from every angle. See EmissiveQuadModifier.rewriteEmissiveNormals.
	@Inject(method = "renderBakedItemQuads", at = @At("HEAD"))
	private void continuity$rewriteEmissiveNormals(MatrixStack matrices, VertexConsumer vertexConsumer, List<BakedQuad> quads, ItemStack stack, int light, int overlay, CallbackInfo ci) {
		EmissiveQuadModifier.rewriteEmissiveNormals(quads, matrices.peek().getNormalMatrix());
	}
}
