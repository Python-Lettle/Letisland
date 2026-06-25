package cn.lettle.letisland.shop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店物品数据类
 */
public class ShopItem {

    private final String id;
    private final Material material;
    private final int amount;
    private final double price;
    private final double weight;
    private final String displayName;
    private final boolean isBuy;

    public ShopItem(@NotNull String id, @NotNull Material material, int amount, double price,
                    double weight, @Nullable String displayName, boolean isBuy) {
        this.id = id;
        this.material = material;
        this.amount = Math.max(1, amount);
        this.price = Math.max(0, price);
        this.weight = Math.max(0, weight);
        this.displayName = displayName;
        this.isBuy = isBuy;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public Material getMaterial() {
        return material;
    }

    public int getAmount() {
        return amount;
    }

    public double getPrice() {
        return price;
    }

    public double getWeight() {
        return weight;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    public boolean isBuy() {
        return isBuy;
    }

    /**
     * 创建用于商店展示的 ItemStack
     */
    @NotNull
    public ItemStack createDisplayItem(@NotNull String currencySymbol) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (displayName != null) {
                meta.setDisplayName(displayName);
            }
            List<String> lore = new ArrayList<>();
            if (isBuy) {
                lore.add("§a左键购买");
                lore.add("§7价格: §e" + currencySymbol + String.format("%.2f", price));
            } else {
                lore.add("§a左键出售手中物品");
                lore.add("§7出售价: §e" + currencySymbol + String.format("%.2f", price));
                lore.add("§7需手持 §f" + amount + "x " + material.name());
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 创建原始 ItemStack（不包含 lore，用于给予玩家）
     */
    @NotNull
    public ItemStack createPlainItem() {
        return new ItemStack(material, amount);
    }
}
