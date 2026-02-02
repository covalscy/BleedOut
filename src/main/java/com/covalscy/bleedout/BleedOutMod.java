package com.covalscy.bleedout;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(BleedOutMod.MODID)
public class BleedOutMod
{
    public static final String MODID = "bleedout";
    private static final Logger LOGGER = LogUtils.getLogger();

    public BleedOutMod(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        modEventBus.addListener(this::commonSetup);

        // Регистрируем звуки
        ModSounds.REGISTRY.register(modEventBus);
        LOGGER.info("[BleedOut] Registered sound registry");

        MinecraftForge.EVENT_BUS.register(this);

        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        context.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // LOGGER.info("BleedOut mod initialized");
        event.enqueueWork(() -> {
            com.covalscy.bleedout.network.BleedOutNetwork.register();
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // LOGGER.info("BleedOut mod server starting");
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event)
        {
            // Проверяем, что звук зарегистрирован
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
        public static void registerOverlays(RegisterGuiOverlaysEvent event)
        {
            event.registerAboveAll("bleedout", new BleedOutOverlay());
        }
    }
}
