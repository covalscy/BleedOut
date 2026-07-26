package com.covalscy.bleedout;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModSounds
{
    public static final DeferredRegister<SoundEvent> REGISTRY = DeferredRegister.create(Registries.SOUND_EVENT, BleedOutMod.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> HEARTBEAT = REGISTRY.register("heartbeat",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "heartbeat")));
}
