package ml.mypals.sky;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class SkyMod implements ModInitializer {
    public static final String MOD_ID = "sky";

    private static final ResourceKey<Block> SKY_KEY = ResourceKey.create(Registries.BLOCK, id("sky"));
    public static final SkyBlock SKY = Registry.register(BuiltInRegistries.BLOCK, SKY_KEY, new SkyBlock(
            BlockBehaviour.Properties.of().setId(SKY_KEY).mapColor(MapColor.COLOR_LIGHT_BLUE).strength(-1.0F, 3600000.0F).noLootTable()));

    private static final ResourceKey<Item> SKY_ITEM_KEY = ResourceKey.create(Registries.ITEM, id("sky"));
    public static final Item SKY_ITEM = Registry.register(BuiltInRegistries.ITEM, SKY_ITEM_KEY,
            new BlockItem(SKY, new Item.Properties().setId(SKY_ITEM_KEY).useBlockDescriptionPrefix()));

    public static final BlockEntityType<SkyBlockEntity> SKY_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("sky"),
            FabricBlockEntityTypeBuilder.create(SkyBlockEntity::new, SKY).build());

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(output -> output.accept(SKY_ITEM));
    }
}
