package ml.mypals.sky.mixin.client;

import ml.mypals.sky.client.SkyItemRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpecialModelWrapper.class)
public class SpecialModelWrapperMixin {
    @Shadow
    @Final
    private SpecialModelRenderer<?> specialRenderer;

    // The sky changes: the GUI draws the item again every frame instead of keeping it in its item atlas.
    @Inject(method = "update", at = @At("TAIL"))
    private void sky$animated(ItemStackRenderState output, ItemStack item, ItemModelResolver resolver, ItemDisplayContext displayContext,
                              @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed, CallbackInfo ci) {
        if (this.specialRenderer instanceof SkyItemRenderer) {
            output.setAnimated();
        }
    }
}
