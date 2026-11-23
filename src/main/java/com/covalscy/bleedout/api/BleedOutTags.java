package com.covalscy.bleedout.api;

import com.covalscy.bleedout.BleedOutMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Item tags recognized by BleedOut for addon integration.
 */
public final class BleedOutTags {
    private BleedOutTags() {
    }

    public static final class Items {
        /**
         * Items in this tag may be used via right-click while the player is knocked/bleeding.
         */
        public static final TagKey<Item> USABLE_WHILE_BLEEDING = TagKey.create(
                Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "usable_while_bleeding")
        );

        /**
         * Items in this tag bypass BleedOut's automatic bare-hand 5s revive when right-clicking
         * a knocked player or ragdoll, allowing the item's own use/charge mechanics to run.
         */
        public static final TagKey<Item> CUSTOM_REVIVE_TOOL = TagKey.create(
                Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "custom_revive_tool")
        );

        private Items() {
        }
    }
}
