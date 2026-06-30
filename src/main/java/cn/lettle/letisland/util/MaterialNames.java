package cn.lettle.letisland.util;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 材质名称中文化工具
 * 将 Material 枚举名（如 OAK_LOG）翻译为中文显示名（如 橡木原木）
 * 未收录的材质回退为美化后的英文名（如 OAK_LOG → Oak Log）
 */
public final class MaterialNames {

    private MaterialNames() {}

    private static final Map<Material, String> ZH_MAP = new ConcurrentHashMap<>();

    static {
        // 基础方块
        put(Material.COBBLESTONE, "圆石");
        put(Material.STONE, "石头");
        put(Material.DIRT, "泥土");
        put(Material.SAND, "沙子");
        put(Material.GRAVEL, "沙砾");
        put(Material.GRASS_BLOCK, "草方块");
        put(Material.OAK_LEAVES, "橡树树叶");
        put(Material.BIRCH_LEAVES, "白桦树叶");

        // 原木
        put(Material.OAK_LOG, "橡木原木");
        put(Material.BIRCH_LOG, "白桦原木");
        put(Material.SPRUCE_LOG, "云杉原木");
        put(Material.JUNGLE_LOG, "丛林原木");
        put(Material.CHERRY_LOG, "樱花原木");
        put(Material.ACACIA_LOG, "金合欢原木");
        put(Material.DARK_OAK_LOG, "深色橡木原木");

        // 木板
        put(Material.OAK_PLANKS, "橡木木板");
        put(Material.BIRCH_PLANKS, "白桦木板");
        put(Material.SPRUCE_PLANKS, "云杉木板");
        put(Material.JUNGLE_PLANKS, "丛林木板");
        put(Material.CHERRY_PLANKS, "樱花木板");
        put(Material.ACACIA_PLANKS, "金合欢木板");
        put(Material.DARK_OAK_PLANKS, "深色橡木木板");

        // 其他基础材料
        put(Material.STICK, "木棍");
        put(Material.MANGROVE_LOG, "红树原木");

        // 锭与矿物
        put(Material.IRON_INGOT, "铁锭");
        put(Material.GOLD_INGOT, "金锭");
        put(Material.DIAMOND, "钻石");
        put(Material.NETHERITE_INGOT, "下界合金锭");
        put(Material.COAL, "煤炭");
        put(Material.CHARCOAL, "木炭");
        put(Material.EMERALD, "绿宝石");
        put(Material.LAPIS_LAZULI, "青金石");
        put(Material.QUARTZ, "下界石英");
        put(Material.REDSTONE, "红石粉");
        put(Material.AMETHYST_SHARD, "紫水晶碎片");

        // 方块
        put(Material.IRON_BLOCK, "铁块");
        put(Material.GOLD_BLOCK, "金块");
        put(Material.DIAMOND_BLOCK, "钻石块");
        put(Material.NETHERITE_BLOCK, "下界合金块");
        put(Material.COBBLESTONE, "圆石");
        put(Material.MAGMA_BLOCK, "岩浆块");
        put(Material.AMETHYST_BLOCK, "紫水晶块");

        // 杂物与掉落物
        put(Material.SLIME_BALL, "粘液球");
        put(Material.LEATHER, "皮革");
        put(Material.WHEAT, "小麦");
        put(Material.ROTTEN_FLESH, "腐肉");
        put(Material.GUNPOWDER, "火药");
        put(Material.STRING, "线");
        put(Material.SPIDER_EYE, "蜘蛛眼");
        put(Material.EGG, "鸡蛋");
        put(Material.CARROT, "胡萝卜");
        put(Material.POTATO, "马铃薯");
        put(Material.BONE, "骨头");
        put(Material.ENDER_PEARL, "末影珍珠");
        put(Material.BLAZE_ROD, "烈焰棒");
        put(Material.GHAST_TEAR, "恶魂之泪");
        put(Material.NAUTILUS_SHELL, "鹦鹉螺壳");
        put(Material.PHANTOM_MEMBRANE, "幻翼膜");
        put(Material.PRISMARINE_SHARD, "海晶碎片");
        put(Material.PRISMARINE_CRYSTALS, "海晶砂粒");
        put(Material.SHULKER_SHELL, "潜影壳");
        put(Material.TOTEM_OF_UNDYING, "不死图腾");

        // 紫水晶系列
        put(Material.SMALL_AMETHYST_BUD, "小型紫水晶芽");
        put(Material.MEDIUM_AMETHYST_BUD, "中型紫水晶芽");
        put(Material.LARGE_AMETHYST_BUD, "大型紫水晶芽");
        put(Material.AMETHYST_CLUSTER, "紫水晶簇");

        // 特殊方块
        put(Material.BEE_NEST, "蜂巢");
        put(Material.BEEHIVE, "蜂箱");
        put(Material.TURTLE_EGG, "海龟蛋");
        put(Material.WITHER_ROSE, "凋零玫瑰");
        put(Material.SCULK_SENSOR, "幽匿感测体");
        put(Material.SCULK_SHRIEKER, "幽匿尖啸体");
        put(Material.SCULK_CATALYST, "幽匿催发体");
        put(Material.END_PORTAL_FRAME, "末地传送门框架");

        // 头颅
        put(Material.DRAGON_HEAD, "末影龙之首");
        put(Material.ZOMBIE_HEAD, "僵尸的头");
        put(Material.SKELETON_SKULL, "骷髅头颅");
        put(Material.CREEPER_HEAD, "苦力怕的头");
        put(Material.WITHER_SKELETON_SKULL, "凋零骷髅头");
        put(Material.PIGLIN_HEAD, "猪灵的头");

        // 稀有物品
        put(Material.ELYTRA, "鞘翅");
        put(Material.DRAGON_EGG, "龙蛋");
        put(Material.DRAGON_BREATH, "龙息");
        put(Material.NETHER_STAR, "下界之星");
        put(Material.HEART_OF_THE_SEA, "海洋之心");
        put(Material.CONDUIT, "潮涌核心");
        put(Material.END_CRYSTAL, "末地水晶");
        put(Material.CHORUS_FLOWER, "紫颂花");
        put(Material.CHORUS_FRUIT, "紫颂果");
    }

    private static void put(@NotNull Material material, @NotNull String chinese) {
        ZH_MAP.put(material, chinese);
    }

    /**
     * 将材质翻译为中文名称
     * 未收录的材质回退为美化后的英文名（如 OAK_LOG → Oak Log）
     *
     * @param material 材质枚举
     * @return 中文名称
     */
    @NotNull
    public static String toChinese(@NotNull Material material) {
        String zh = ZH_MAP.get(material);
        if (zh != null) return zh;
        return prettify(material.name());
    }

    /**
     * 将枚举名美化为首字母大写的英文短语
     * 如 OAK_LOG → Oak Log, NETHERITE_INGOT → Netherite Ingot
     */
    @NotNull
    private static String prettify(@NotNull String enumName) {
        String[] parts = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
