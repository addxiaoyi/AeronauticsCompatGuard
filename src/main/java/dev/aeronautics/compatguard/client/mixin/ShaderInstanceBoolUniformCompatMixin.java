package dev.aeronautics.compatguard.client.mixin;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import dev.aeronautics.compatguard.compat.CompatibilityPatchRegistry;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;

/**
 * Keeps shader bool uniforms usable when Veil enumerates a shader produced by Iris.
 * Minecraft's Uniform API has no boolean type, so OpenGL bools use the int path.
 */
@Mixin(value = ShaderInstance.class, priority = 900)
public abstract class ShaderInstanceBoolUniformCompatMixin implements Shader {
    @Shadow
    @Final
    private int programId;

    @Shadow
    @Final
    private List<Integer> uniformLocations;

    @Shadow
    @Final
    private Map<String, Uniform> uniformMap;

    @Shadow
    @Final
    private List<String> samplerNames;

    @Inject(method = "updateLocations", at = @At("TAIL"), require = 0, order = 10000)
    private void aeronauticsCompatGuard$registerBooleanUniforms(CallbackInfo callback) {
        if (!CompatibilityPatchRegistry.isEnabled(
                CompatibilityPatchRegistry.SABLE_IRIS_VEIL_BOOL_UNIFORMS)) {
            return;
        }

        int uniformCount = GL20C.glGetProgrami(programId, GL20C.GL_ACTIVE_UNIFORMS);
        int maxUniformLength = GL20C.glGetProgrami(programId, GL20C.GL_ACTIVE_UNIFORM_MAX_LENGTH);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1);
            IntBuffer type = stack.mallocInt(1);
            for (int index = 0; index < uniformCount; index++) {
                String name = GL20C.glGetActiveUniform(programId, index, maxUniformLength, size, type);
                int length = size.get(0);
                if (length > 1 && name.endsWith("[0]")) {
                    name = name.substring(0, name.length() - 3);
                }

                int minecraftType = minecraftTypeForBoolean(type.get(0));
                if (minecraftType < 0 || uniformMap.containsKey(name) || samplerNames.contains(name)) {
                    continue;
                }

                for (int element = 0; element < length; element++) {
                    String uniformName = length > 1 ? name + '[' + element + ']' : name;
                    int location = Uniform.glGetUniformLocation(programId, uniformName);
                    if (location < 0) {
                        continue;
                    }
                    Uniform uniform = new Uniform(uniformName, minecraftType, booleanComponentCount(type.get(0)), this);
                    uniform.setLocation(location);
                    uniformLocations.add(location);
                    uniformMap.put(uniformName, uniform);
                }
            }
        }
    }

    @Unique
    private static int minecraftTypeForBoolean(int glType) {
        return switch (glType) {
            case GL20C.GL_BOOL -> Uniform.UT_INT1;
            case GL20C.GL_BOOL_VEC2 -> Uniform.UT_INT2;
            case GL20C.GL_BOOL_VEC3 -> Uniform.UT_INT3;
            case GL20C.GL_BOOL_VEC4 -> Uniform.UT_INT4;
            default -> -1;
        };
    }

    @Unique
    private static int booleanComponentCount(int glType) {
        return switch (glType) {
            case GL20C.GL_BOOL_VEC2 -> 2;
            case GL20C.GL_BOOL_VEC3 -> 3;
            case GL20C.GL_BOOL_VEC4 -> 4;
            default -> 1;
        };
    }
}
