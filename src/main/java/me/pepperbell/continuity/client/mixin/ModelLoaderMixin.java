package me.pepperbell.continuity.client.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.util.Pair;

import me.pepperbell.continuity.client.ContinuityClient;
import me.pepperbell.continuity.client.resource.CTMPropertiesLoader;
import me.pepperbell.continuity.client.resource.ModelWrappingHandler;
import me.pepperbell.continuity.client.resource.ResourcePackUtil;
import me.pepperbell.continuity.client.util.biome.BiomeHolderManager;
import net.minecraft.client.render.model.ModelLoader;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import net.minecraftforge.client.model.geometry.GeometryLoaderManager;

@Mixin(ModelLoader.class)
public class ModelLoaderMixin {
	@Shadow
	@Final
	private Map<Identifier, UnbakedModel> unbakedModels;
	@Shadow
	@Final
	private Map<Identifier, UnbakedModel> modelsToBake;
	@Shadow
	@Final
	private Map<Identifier, Pair<SpriteAtlasTexture, SpriteAtlasTexture.Data>> spriteAtlasData;
	@Shadow
	@Final
	protected ResourceManager resourceManager;

	// Redirect GeometryLoaderManager.init() to do CTM setup first.
	@Redirect(method = "<init>(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/client/color/block/BlockColors;Lnet/minecraft/util/profiler/Profiler;I)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraftforge/client/model/geometry/GeometryLoaderManager;init()V"))
	private void continuity$redirectGeometryInit() {
		ContinuityClient.LOGGER.warn("[Connectedness] Early setup: loading CTM properties...");
		ResourcePackUtil.setup(resourceManager);
		BiomeHolderManager.clearCache();
		CTMPropertiesLoader.clearAll();
		CTMPropertiesLoader.loadAll(resourceManager);
		ContinuityClient.LOGGER.warn("[Connectedness] CTM properties loaded. isEmpty={}", CTMPropertiesLoader.isEmpty());
		GeometryLoaderManager.init();
	}

	// Redirect profiler.swap("textures") to wrap CTM models before texture resolution.
	// @Inject at INVOKE/INVOKE_STRING is not supported in constructors with Mixin 0.8.5,
	// so we redirect ALL swap calls and filter by the string argument.
	@Redirect(method = "<init>(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/client/color/block/BlockColors;Lnet/minecraft/util/profiler/Profiler;I)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V"))
	private void continuity$redirectSwap(Profiler profiler, String name) {
		if ("textures".equals(name)) {
			ContinuityClient.LOGGER.warn("[Connectedness] swap(textures): wrapping CTM models. modelsToBake size={}", modelsToBake.size());
			ModelWrappingHandler.wrapCTMModels(unbakedModels, modelsToBake);
			ContinuityClient.LOGGER.warn("[Connectedness] CTM wrapping done.");
		}
		profiler.swap(name);
	}

	// Cleanup after the constructor completes.
	// @Inject at RETURN in constructor is supported.
	@Inject(method = "<init>(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/client/color/block/BlockColors;Lnet/minecraft/util/profiler/Profiler;I)V",
			at = @At("RETURN"))
	private void continuity$onInitReturn(ResourceManager resourceManager, BlockColors blockColors, Profiler profiler, int mipmap, CallbackInfo ci) {
		CTMPropertiesLoader.clearAll();
		ResourcePackUtil.clear();
		BiomeHolderManager.refreshHolders();
	}
}
