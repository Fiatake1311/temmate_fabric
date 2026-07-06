package com.teammate;

import com.mojang.logging.LogUtils;
import com.teammate.event.BannerEvents;
import com.teammate.event.HealingLockEvents;
import com.teammate.event.ServerEvents;
import com.teammate.item.SacredAmuletItem;
import com.teammate.network.ModNetworking;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(TeammateMod.MODID)
public class TeammateMod {
    public static final String MODID = "teammate";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredItem<Item> SACRED_AMULET = ITEMS.registerItem("sacred_cloth_amulet",
            SacredAmuletItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));

    public TeammateMod(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);

        modEventBus.addListener(ModNetworking::register);
        modEventBus.addListener(this::addCreative);

        NeoForge.EVENT_BUS.register(ServerEvents.class);
        NeoForge.EVENT_BUS.register(HealingLockEvents.class);
        NeoForge.EVENT_BUS.register(BannerEvents.class);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SACRED_AMULET);
        }
    }
}
