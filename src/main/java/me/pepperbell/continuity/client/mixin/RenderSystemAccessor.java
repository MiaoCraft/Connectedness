package me.pepperbell.continuity.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.util.math.Vec3f;

@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
	@Accessor("shaderLightDirections")
	static Vec3f[] continuity$getShaderLightDirections() {
		throw new AssertionError();
	}
}
