package ml.mypals.sky.smoke.mixin;

import ml.mypals.sky.client.ShaderSkyHoles;
import ml.mypals.sky.client.iris.IrisAccess;
import ml.mypals.sky.smoke.Capture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderSkyHoles.class, remap = false)
public abstract class ShaderSkyHolesCapture {
    @Shadow private static int beforeDepth;
    @Shadow private static int afterDepth;
    @Shadow private static int skySnapshot;
    @Shadow private static boolean skyCaptured;

    private static void colortex0(String name) {
        IrisAccess.ColorTarget colortex0 = IrisAccess.colortex0();
        if (colortex0 != null) {
            Capture.color(colortex0.id(), name);
        }
    }

    private static void mainDepth(String name) {
        Capture.depth(Minecraft.getInstance().getMainRenderTarget().getDepthTextureId(), true, name);
    }

    @Inject(method = "captureSky", at = @At("RETURN"))
    private static void sky$capturedSky(CallbackInfo ci) {
        if (Capture.active() && skyCaptured) {
            Capture.color(skySnapshot, "s1-sky-snapshot");
        }
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private static void sky$beforeHoles(CallbackInfo ci) {
        if (Capture.active()) {
            colortex0("s2-colortex0-solid-block");
            mainDepth("s2-depth-solid-block");
        }
    }

    @Inject(method = "draw", at = @At(value = "INVOKE", target = "Lml/mypals/sky/client/Gl;drawScreen(Lnet/minecraft/client/renderer/ShaderInstance;IIII)V", ordinal = 0))
    private static void sky$afterRedraw(CallbackInfo ci) {
        if (Capture.active()) {
            colortex0("s4-colortex0-after-redraw");
            Capture.depth(beforeDepth, false, "s3-depth-before-redraw");
            Capture.depth(afterDepth, false, "s4-depth-after-redraw");
            Capture.difference(beforeDepth, afterDepth, "s5-holes-mask");
        }
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private static void sky$afterHoles(CallbackInfo ci) {
        if (Capture.active()) {
            colortex0("s6-colortex0-holes");
            mainDepth("s6-depth-holes");
        }
    }

    @Inject(method = "restoreDepth", at = @At("HEAD"))
    private static void sky$afterDeferred(CallbackInfo ci) {
        if (Capture.active()) {
            colortex0("s7-colortex0-after-deferred");
        }
    }

    @Inject(method = "restoreDepth", at = @At("RETURN"))
    private static void sky$restored(CallbackInfo ci) {
        if (Capture.active()) {
            mainDepth("s8-depth-restored");
        }
    }

    @Inject(method = "farAgain", at = @At("HEAD"))
    private static void sky$beforeComposite(CallbackInfo ci) {
        if (Capture.active()) {
            mainDepth("s9-depth-after-translucents");
        }
    }

    @Inject(method = "farAgain", at = @At("RETURN"))
    private static void sky$farAgain(CallbackInfo ci) {
        if (Capture.active()) {
            mainDepth("s10-depth-far-again");
            Capture.frameDone();
        }
    }
}
