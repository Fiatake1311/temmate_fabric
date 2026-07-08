package com.teammate;

import com.mojang.logging.LogUtils;
import com.teammate.event.BannerEvents;
import com.teammate.event.HealingLockEvents;
import com.teammate.event.ServerEvents;
import com.teammate.network.ModNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(TeammateMod.MODID)
public class TeammateMod {
    public static final String MODID = "teammate";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TeammateMod(IEventBus modEventBus) {
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);

        modEventBus.addListener(ModNetworking::register);

        NeoForge.EVENT_BUS.register(ServerEvents.class);
        NeoForge.EVENT_BUS.register(HealingLockEvents.class);
        NeoForge.EVENT_BUS.register(BannerEvents.class);
    }
}
