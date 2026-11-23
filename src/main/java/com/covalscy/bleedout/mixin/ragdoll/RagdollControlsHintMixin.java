package com.covalscy.bleedout.mixin.ragdoll;

import dev.leo.sableplayerragdoll.neoforge.client.RagdollControlsHint;
import dev.leo.sableplayerragdoll.neoforge.client.RagdollControlsHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the ragdoll grab/reach controls overlay while the local player is knocked out.
 * Only applied when sable_player_ragdoll is installed (see BleedOutMixinPlugin).
 */
@Mixin(value = RagdollControlsHint.class, remap = false)
public abstract class RagdollControlsHintMixin {
    @Inject(method = "onClientTick", at = @At("TAIL"))
    private static void bleedout$hideControlsHintWhileBleeding(ClientTickEvent.Post event, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getPersistentData().getBoolean("bleedout:bleeding")) {
            RagdollControlsHud.hide();
        }
    }
}
