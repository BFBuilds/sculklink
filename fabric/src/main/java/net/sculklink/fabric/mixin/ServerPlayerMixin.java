package net.sculklink.fabric.mixin;

import net.sculklink.fabric.SculkLinkFabric;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the death message the way vanilla does, before vanilla is done with it.
 *
 * <p>{@code die} builds the chat message from the combat tracker near its start and resets the
 * tracker near its end. Fabric's {@code AFTER_DEATH} fires after that reset, where only the
 * damage source is left, and the damage source words some deaths differently: a long fall is
 * "fell from a high place" in chat but "hit the ground too hard" from the source. Reading at
 * HEAD gets exactly what players saw; {@code AFTER_DEATH} still decides whether to post, so a
 * death another mod cancels is not reported.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    // Full descriptor plus require = 0: should a future version rename die() or change its
    // parameters, this matches nothing and deaths fall back to the damage source's wording,
    // rather than the server refusing to start.
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"), require = 0)
    private void sculklink$captureDeathMessage(DamageSource source, CallbackInfo ci) {
        SculkLinkFabric bridge = SculkLinkFabric.get();
        if (bridge != null) {
            bridge.rememberDeathMessage((ServerPlayer) (Object) this);
        }
    }
}
