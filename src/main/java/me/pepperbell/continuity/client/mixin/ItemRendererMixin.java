package me.pepperbell.continuity.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import me.pepperbell.continuity.client.model.EmissiveBakedModel;
import me.pepperbell.continuity.client.util.EmissiveQuadModifier;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.item.ItemRenderer;

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
}
