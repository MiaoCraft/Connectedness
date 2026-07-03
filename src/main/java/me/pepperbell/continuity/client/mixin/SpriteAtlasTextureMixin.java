package me.pepperbell.continuity.client.mixin;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import me.pepperbell.continuity.client.mixinterface.SpriteAtlasTextureDataExtension;
import me.pepperbell.continuity.client.mixinterface.SpriteExtension;
import me.pepperbell.continuity.client.resource.EmissiveSuffixLoader;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

@Mixin(SpriteAtlasTexture.class)
public class SpriteAtlasTextureMixin {
	@Shadow
	@Final
	private Map<Identifier, Sprite> sprites;

	@Unique
	private boolean continuity$loadingEmissiveSprites;
	@Unique
	private boolean continuity$suffixLoaded;
	@Unique
	private Map<Identifier, Identifier> continuity$emissiveIdMap;

	@Shadow
	private Collection<Sprite.Info> loadSprites(ResourceManager resourceManager, Set<Identifier> ids) {
		return null;
	}

	@Shadow
	private Identifier getTexturePath(Identifier id) {
		return null;
	}

	@Unique
	private Identifier resolveTexturePath(Identifier id) {
		String path = id.getPath();
		if (path.startsWith("optifine/")) {
			if (path.endsWith(".png")) {
				return new Identifier(id.getNamespace(), path);
			}
			return new Identifier(id.getNamespace(), path + ".png");
		}
		return getTexturePath(id);
	}

	// Fix getTexturePath for optifine textures.
	// getTexturePath prepends "textures/" and appends ".png", but optifine/
	// textures use paths like "optifine/cit/xxx.png" without the textures/ prefix
	// and already include the .png extension.
	@Inject(method = "getTexturePath", at = @At("HEAD"), cancellable = true)
	private void continuity$fixGetTexturePath(Identifier id, CallbackInfoReturnable<Identifier> cir) {
		String path = id.getPath();
		if (path.startsWith("optifine/")) {
			if (path.endsWith(".png")) {
				cir.setReturnValue(new Identifier(id.getNamespace(), path));
			} else {
				cir.setReturnValue(new Identifier(id.getNamespace(), path + ".png"));
			}
		}
	}

	@Inject(method = "loadSprites(Lnet/minecraft/resource/ResourceManager;Ljava/util/Set;)Ljava/util/Collection;", at = @At("TAIL"))
	private void continuity$onTailLoadSprites(ResourceManager resourceManager, Set<Identifier> ids, CallbackInfoReturnable<Collection<Sprite.Info>> cir) {
		if (!continuity$suffixLoaded) {
			continuity$suffixLoaded = true;
			EmissiveSuffixLoader.load(resourceManager);
		}
		if (!continuity$loadingEmissiveSprites) {
			continuity$loadingEmissiveSprites = true;
			String emissiveSuffix = EmissiveSuffixLoader.getEmissiveSuffix();
			if (emissiveSuffix != null) {
				Collection<Sprite.Info> spriteInfos = cir.getReturnValue();
				Set<Identifier> emissiveIds = new ObjectOpenHashSet<>();
				continuity$emissiveIdMap = new Object2ObjectOpenHashMap<>();
				for (Sprite.Info spriteInfo : spriteInfos) {
					Identifier id = spriteInfo.getId();
					if (!id.getPath().endsWith(emissiveSuffix)) {
						Identifier emissiveId = new Identifier(id.getNamespace(), id.getPath() + emissiveSuffix);
						Identifier emissiveLocation = resolveTexturePath(emissiveId);
						if (resourceManager.getResource(emissiveLocation).isPresent()) {
							emissiveIds.add(emissiveId);
							continuity$emissiveIdMap.put(id, emissiveId);
						}
					}
				}
				// Scan CIT textures (optifine/cit/*) — paths include .png
				for (Sprite.Info spriteInfo : spriteInfos) {
					Identifier id = spriteInfo.getId();
					String path = id.getPath();
					if (path.startsWith("optifine/cit/")) {
						if (!path.endsWith(emissiveSuffix)) {
							String emissivePath;
							if (path.endsWith(".png")) {
								emissivePath = path.substring(0, path.length() - 4) + emissiveSuffix + ".png";
							} else {
								emissivePath = path + emissiveSuffix;
							}
							Identifier emissiveId = new Identifier(id.getNamespace(), emissivePath);
							Identifier emissiveLocation = resolveTexturePath(emissiveId);
							if (resourceManager.getResource(emissiveLocation).isPresent()) {
								continuity$emissiveIdMap.put(id, emissiveId);
								emissiveIds.add(emissiveId);
							}
						}
					}
				}
				if (!emissiveIds.isEmpty()) {
					Collection<Sprite.Info> emissiveSpriteInfos = loadSprites(resourceManager, emissiveIds);
					spriteInfos.addAll(emissiveSpriteInfos);
				}
				if (continuity$emissiveIdMap.isEmpty()) {
					continuity$emissiveIdMap = null;
				}
			}
			continuity$loadingEmissiveSprites = false;
		}
	}

	@Inject(method = "stitch", at = @At("TAIL"))
	private void continuity$onTailStitch(ResourceManager resourceManager, Stream<Identifier> idStream, Profiler profiler, int mipmapLevel, CallbackInfoReturnable<SpriteAtlasTexture.Data> cir) {
		SpriteAtlasTexture.Data data = cir.getReturnValue();
		((SpriteAtlasTextureDataExtension) data).continuity$setEmissiveIdMap(continuity$emissiveIdMap);
		continuity$emissiveIdMap = null;
	}

	@Inject(method = "upload(Lnet/minecraft/client/texture/SpriteAtlasTexture$Data;)V", at = @At("TAIL"))
	private void continuity$onTailUpload(SpriteAtlasTexture.Data data, CallbackInfo ci) {
		Map<Identifier, Identifier> emissiveIdMap = ((SpriteAtlasTextureDataExtension) data).continuity$getEmissiveIdMap();
		if (emissiveIdMap != null) {
			for (Map.Entry<Identifier, Identifier> e : emissiveIdMap.entrySet()) {
				Sprite sprite = sprites.get(e.getKey());
				Sprite emissiveSprite = sprites.get(e.getValue());
				if (sprite != null && emissiveSprite != null) {
					((SpriteExtension) sprite).continuity$setEmissiveSprite(emissiveSprite);
				}
			}
		}
	}
}
