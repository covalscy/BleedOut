package com.covalscy.bleedout;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds
{
    public static final DeferredRegister<SoundEvent> REGISTRY = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BleedOutMod.MODID);

    public static final RegistryObject<SoundEvent> HEARTBEAT = REGISTRY.register("heartbeat", 
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "heartbeat")));
}
