package com.covalscy.bleedout;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(BleedOutMod.MODID)
public class BleedOutMod
{
    public static final String MODID = "bleedout";
    private static final Logger LOGGER = LogUtils.getLogger();

    public BleedOutMod(IEventBus modEventBus, ModContainer modContainer)
    {
        modEventBus.addListener(this::commonSetup);

        // Регистрируем звуки
        ModSounds.REGISTRY.register(modEventBus);
        LOGGER.info("[BleedOut] Registered sound registry");

        // Сетевые пакеты
        com.covalscy.bleedout.network.BleedOutNetwork.init(modEventBus);

        NeoForge.EVENT_BUS.register(this);
        com.covalscy.bleedout.compat.ragdoll.SableRagdollCompat.registerEvents();
        if (net.neoforged.fml.ModList.get().isLoaded("tacz")
                && com.covalscy.bleedout.compat.ragdoll.SableRagdollCompat.isAvailable()) {
            com.covalscy.bleedout.compat.tacz.TaczRagdollCompat.registerEvents();
        }

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Setup code if needed
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            if (ModSounds.HEARTBEAT.get() != null)
            {
                LOGGER.info("[BleedOut] Heartbeat sound registered successfully: {}", ModSounds.HEARTBEAT.get().getLocation());
            }
            else
            {
                LOGGER.error("[BleedOut] Heartbeat sound is null during client setup!");
            }
        }

        @SubscribeEvent
        public static void registerGuiLayers(RegisterGuiLayersEvent event)
        {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MODID, "overlay"), new BleedOutOverlay());
        }
    }
}
