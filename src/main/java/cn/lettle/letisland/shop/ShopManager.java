package cn.lettle.letisland.shop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 商店管理器
 * 管理商店配置、可购买/可出售物品池和当前刷新库存
 */
public class ShopManager {

    /** 商店标题 */
    public static final String SHOP_TITLE = "§6§l空岛商店";

    /** 商店大小（行数），购买区 + 分隔 + 出售区 */
    public static final int SHOP_SIZE = 54;

    /** 购买区域：0-26 槽位（前3行，共27格） */
    public static final int BUY_AREA_START = 0;
    public static final int BUY_AREA_END = 26;

    /** 分隔栏：27-35 槽位（第4行，共9格） */
    public static final int SEPARATOR_START = 27;
    public static final int SEPARATOR_END = 35;

    /** 出售区域：36-53 槽位（最后2行，共18格） */
    public static final int SELL_AREA_START = 36;
    public static final int SELL_AREA_END = 53;

    /** 商店是否启用 */
    private boolean enabled;

    /** 随机刷新的购买物品数量 */
    private int buySlotCount;

    /** 随机刷新的出售物品数量 */
    private int sellSlotCount;

    /** 刷新间隔（分钟） */
    private long refreshIntervalMinutes;

    /** 上次刷新时间（毫秒时间戳） */
    private long lastRefreshTime;

    /** 是否在刷新后广播通知（构造函数首次刷新时为 false，避免启动时刷屏） */
    private boolean announceRefresh = false;

    private final File shopFile;
    private FileConfiguration shopConfig;

    /** 可购买物品池（配置中定义的所有物品） */
    private final List<ShopItem> buyPool = new ArrayList<>();

    /** 可出售物品池（配置中定义的所有物品） */
    private final List<ShopItem> sellPool = new ArrayList<>();

    /** 当前商店库存（槽位 -> 商店物品） */
    private final Map<Integer, ShopItem> currentStock = new HashMap<>();

    public ShopManager(@NotNull File dataFolder) {
        this.shopFile = new File(dataFolder, "shop.yml");
        loadConfig();
        refresh();
        // 首次构造刷新完成后再开启广播，避免插件启动时给全服发通知
        this.announceRefresh = true;
    }

    /**
     * 加载商店配置文件
     */
    public void loadConfig() {
        if (!shopFile.exists()) {
            try {
                shopFile.getParentFile().mkdirs();
                shopFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("无法创建商店配置文件: " + e.getMessage(), e);
            }
        }
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
        parseConfig();
    }

    /**
     * 保存商店配置文件
     */
    public void saveConfig() {
        try {
            shopConfig.save(shopFile);
        } catch (IOException e) {
            throw new RuntimeException("无法保存商店配置文件: " + e.getMessage(), e);
        }
    }

    /**
     * 热重载配置
     */
    public void reload() {
        loadConfig();
        refresh();
    }

    /**
     * 商店是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置商店启用状态并保存
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        shopConfig.set("settings.enabled", enabled);
        saveConfig();
    }

    /**
     * 解析配置内容
     */
    private void parseConfig() {
        buyPool.clear();
        sellPool.clear();

        enabled = shopConfig.getBoolean("settings.enabled", true);
        buySlotCount = shopConfig.getInt("settings.buy-slot-count", 27);
        sellSlotCount = shopConfig.getInt("settings.sell-slot-count", 18);
        refreshIntervalMinutes = shopConfig.getLong("settings.refresh-interval-minutes", 30);

        // 解析购买物品池
        ConfigurationSection buySection = shopConfig.getConfigurationSection("buy-items");
        if (buySection != null) {
            for (String key : buySection.getKeys(false)) {
                ShopItem item = parseShopItem(buySection, key, true);
                if (item != null) {
                    buyPool.add(item);
                }
            }
        }

        // 解析出售物品池
        ConfigurationSection sellSection = shopConfig.getConfigurationSection("sell-items");
        if (sellSection != null) {
            for (String key : sellSection.getKeys(false)) {
                ShopItem item = parseShopItem(sellSection, key, false);
                if (item != null) {
                    sellPool.add(item);
                }
            }
        }
    }

    /**
     * 解析单个商店物品
     */
    @Nullable
    private ShopItem parseShopItem(@NotNull ConfigurationSection section, @NotNull String key, boolean isBuy) {
        String materialStr = section.getString(key + ".material");
        if (materialStr == null) {
            return null;
        }
        Material material = Material.matchMaterial(materialStr);
        if (material == null) {
            return null;
        }

        int amount = section.getInt(key + ".amount", 1);
        double price = section.getDouble(key + ".price", 0);
        double weight = section.getDouble(key + ".weight", 1.0);
        String displayName = section.getString(key + ".display-name");

        return new ShopItem(key, material, amount, price, weight, displayName, isBuy);
    }

    /**
     * 刷新商店库存
     */
    public void refresh() {
        currentStock.clear();
        lastRefreshTime = System.currentTimeMillis();

        // 随机选择购买物品
        List<ShopItem> selectedBuy = randomSelect(buyPool, Math.min(buySlotCount, buyPool.size()));
        int slot = BUY_AREA_START;
        for (ShopItem item : selectedBuy) {
            currentStock.put(slot, item);
            slot++;
        }

        // 随机选择出售物品
        List<ShopItem> selectedSell = randomSelect(sellPool, Math.min(sellSlotCount, sellPool.size()));
        slot = SELL_AREA_START;
        for (ShopItem item : selectedSell) {
            currentStock.put(slot, item);
            slot++;
        }

        // 刷新后通知所有在线玩家（构造函数首次刷新时跳过）
        if (announceRefresh) {
            Bukkit.broadcastMessage("§6[商店] §a商店已刷新！快去看看有什么新商品吧～");
        }
    }

    /**
     * 检查是否需要自动刷新
     */
    public void checkAutoRefresh() {
        long elapsed = System.currentTimeMillis() - lastRefreshTime;
        long intervalMillis = refreshIntervalMinutes * 60 * 1000;
        if (elapsed >= intervalMillis) {
            refresh();
        }
    }

    /**
     * 按权重随机选择物品
     */
    private List<ShopItem> randomSelect(@NotNull List<ShopItem> pool, int count) {
        if (pool.isEmpty() || count <= 0) {
            return new ArrayList<>();
        }
        List<ShopItem> result = new ArrayList<>();
        List<ShopItem> remaining = new ArrayList<>(pool);

        Random random = new Random();
        while (result.size() < count && !remaining.isEmpty()) {
            double totalWeight = remaining.stream().mapToDouble(ShopItem::getWeight).sum();
            double r = random.nextDouble() * totalWeight;

            double cumulative = 0;
            ShopItem selected = null;
            for (ShopItem item : remaining) {
                cumulative += item.getWeight();
                if (r <= cumulative) {
                    selected = item;
                    break;
                }
            }

            if (selected != null) {
                result.add(selected);
                remaining.remove(selected);
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * 获取指定槽位的商店物品
     */
    @Nullable
    public ShopItem getShopItem(int slot) {
        return currentStock.get(slot);
    }

    /**
     * 获取当前库存（不可变视图）
     */
    @NotNull
    public Map<Integer, ShopItem> getCurrentStock() {
        return Collections.unmodifiableMap(currentStock);
    }

    /**
     * 判断槽位是否在购买区
     */
    public boolean isBuySlot(int slot) {
        return slot >= BUY_AREA_START && slot <= BUY_AREA_END;
    }

    /**
     * 判断槽位是否在出售区
     */
    public boolean isSellSlot(int slot) {
        return slot >= SELL_AREA_START && slot <= SELL_AREA_END;
    }

    /**
     * 判断槽位是否在分隔栏
     */
    public boolean isSeparatorSlot(int slot) {
        return slot >= SEPARATOR_START && slot <= SEPARATOR_END;
    }

    /**
     * 获取购买物品池
     */
    @NotNull
    public List<ShopItem> getBuyPool() {
        return Collections.unmodifiableList(buyPool);
    }

    /**
     * 获取出售物品池
     */
    @NotNull
    public List<ShopItem> getSellPool() {
        return Collections.unmodifiableList(sellPool);
    }

    /**
     * 获取刷新间隔（分钟）
     */
    public long getRefreshIntervalMinutes() {
        return refreshIntervalMinutes;
    }

    /**
     * 获取上次刷新时间
     */
    public long getLastRefreshTime() {
        return lastRefreshTime;
    }

    /**
     * 获取距离下次刷新的剩余分钟数
     */
    public long getRemainingMinutes() {
        long elapsed = System.currentTimeMillis() - lastRefreshTime;
        long intervalMillis = refreshIntervalMinutes * 60 * 1000;
        long remaining = intervalMillis - elapsed;
        return Math.max(0, remaining / (60 * 1000));
    }

    /**
     * 在出售物品池中查找匹配的物品（用于玩家出售物品时查询价格）
     */
    @Nullable
    public ShopItem findSellItem(@NotNull ItemStack item) {
        for (ShopItem shopItem : sellPool) {
            if (shopItem.getMaterial() == item.getType()) {
                return shopItem;
            }
        }
        return null;
    }
}
