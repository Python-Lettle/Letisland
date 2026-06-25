# Letisland

Minecraft 26.2 空岛服插件

## 功能模块

- [经济系统](#经济系统)
- [商店系统](#商店系统)

## 经济系统

### 简介

Letisland 经济系统提供玩家余额管理、转账、管理员操作等功能，数据以 YAML 文件持久化存储，并支持自定义事件供其他模块监听。

### 配置

插件首次启动会生成 `config.yml`：

```yaml
# Letisland 插件配置文件

# 经济系统配置
economy:
  # 货币符号
  currency-symbol: "$"
  # 起始余额（新玩家加入时给予的金额，0 表示不给予）
  starting-balance: 0.0
```

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `economy.currency-symbol` | 货币符号，用于金额格式化显示 | `$` |
| `economy.starting-balance` | 新玩家起始余额 | `0.0` |

### 命令

主命令 `/economy`，别名 `/eco`、`/money`。

#### 玩家命令

| 命令 | 说明 |
|------|------|
| `/economy balance` | 查询自己的余额 |
| `/economy balance <player>` | 查询其他玩家的余额（需要权限） |
| `/economy pay <player> <amount>` | 向指定玩家转账 |

#### 管理员命令

| 命令 | 说明 |
|------|------|
| `/economy give <player> <amount>` | 给予玩家金额 |
| `/economy take <player> <amount>` | 扣除玩家金额 |
| `/economy set <player> <amount>` | 设置玩家余额 |
| `/economy reload` | 重新加载经济数据 |

### 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `letisland.economy.use` | 使用经济系统基础命令 | 所有玩家 |
| `letisland.economy.balance.others` | 查询其他玩家的余额 | 管理员 |
| `letisland.economy.admin` | 管理经济系统（给予、扣除、设置、重载） | 管理员 |

### 数据存储

玩家余额存储在插件数据目录下的 `economy.yml` 文件中：

```yaml
players:
  <玩家UUID>:
    balance: 100.50
```

### 开发者 API

#### 获取经济管理器

```java
// 获取插件实例
Letisland plugin = (Letisland) Bukkit.getPluginManager().getPlugin("Letisland");

// 获取经济管理器
EconomyManager economy = plugin.getEconomyManager();
```

#### 常用方法

```java
// 查询余额
double balance = economy.getBalance(player);

// 检查余额是否充足
boolean hasEnough = economy.has(player, 100.0);

// 存入金额
economy.deposit(player, 50.0);

// 扣除金额（余额不足返回 false）
boolean success = economy.withdraw(player, 30.0);

// 转账
boolean transferred = economy.transfer(fromPlayer, toPlayer, 100.0);

// 设置余额
economy.setBalance(player, 500.0);

// 格式化金额显示（返回带货币符号的字符串）
String formatted = economy.format(100.5);  // "$100.50"
```

#### 监听交易事件

当玩家余额发生变动时，会触发 `EconomyTransactionEvent`，其他模块可以监听该事件：

```java
import cn.lettle.letisland.economy.EconomyTransactionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class MyListener implements Listener {

    @EventHandler
    public void onTransaction(EconomyTransactionEvent event) {
        switch (event.getType()) {
            case DEPOSIT -> {
                // 玩家存入金额
            }
            case WITHDRAW -> {
                // 玩家扣除金额
            }
            case TRANSFER_IN -> {
                // 玩家收到转账
            }
            case TRANSFER_OUT -> {
                // 玩家转出金额
            }
            case INITIALIZE -> {
                // 玩家账户初始化
            }
        }

        // 获取交易详情
        OfflinePlayer player = event.getPlayer();
        double amount = event.getAmount();
        double before = event.getBalanceBefore();
        double after = event.getBalanceAfter();
    }
}
```

#### 交易类型

| 类型 | 说明 |
|------|------|
| `DEPOSIT` | 存入金额 |
| `WITHDRAW` | 取出金额 |
| `TRANSFER_OUT` | 转账转出 |
| `TRANSFER_IN` | 转账转入 |
| `INITIALIZE` | 账户初始化 |

---

## 商店系统

### 简介

Letisland 商店系统是一个全服共用的随机刷新商店，以箱子 UI 形式展示。商店分为**购买区**和**出售区**，物品从配置定义的物品池中按权重随机刷新，价格可随时修改并支持热重载。商店与经济系统打通，购买扣款、出售进账。

### 商店界面布局

商店为 54 格箱子界面（6 行），布局如下：

```
┌─────────────────────────────────────────────────┐
│ 购买区（槽位 0-26，前3行共27格）                    │
│  左键点击购买物品，扣除对应金额并获得物品            │
├─────────────────────────────────────────────────┤
│ 分隔栏（槽位 27-35，第4行共9格）                    │
│  左4格：灰色玻璃板，悬停显示"↑上面是出售商店↑"      │
│  中1格：红色玻璃板，悬停显示刷新剩余倒计时           │
│  右4格：灰色玻璃板，悬停显示"↓下面是收购商店↓"      │
├─────────────────────────────────────────────────┤
│ 出售区（槽位 36-53，最后2行共18格）                 │
│  点击对应项，系统自动从背包扣除物品并获得金额        │
└─────────────────────────────────────────────────┘
```

### 配置

插件首次启动会生成 `shop.yml`：

```yaml
settings:
  # 商店随机刷新的购买物品数量（0-27）
  buy-slot-count: 27
  # 商店随机刷新的出售物品数量（0-18）
  sell-slot-count: 18
  # 自动刷新间隔（分钟）
  refresh-interval-minutes: 30

# 可购买物品池
buy-items:
  diamond:
    material: DIAMOND          # 物品材质
    amount: 1                  # 单次购买数量
    price: 100.0               # 购买价格
    weight: 1.0                # 随机权重（越大越容易被刷新到）
    display-name: "§b钻石"      # 自定义显示名称（可选）

# 可出售物品池
sell-items:
  dirt:
    material: DIRT             # 玩家需手持此材质物品
    amount: 32                 # 单次出售数量
    price: 5.0                 # 出售价格
    weight: 3.0                # 随机权重
```

#### 配置项说明

| 配置项 | 说明 |
|--------|------|
| `settings.buy-slot-count` | 购买区显示的物品数量（最多 27 个） |
| `settings.sell-slot-count` | 出售区显示的物品数量（最多 18 个） |
| `settings.refresh-interval-minutes` | 自动刷新间隔（分钟），玩家打开商店时检查 |
| `buy-items.<id>.material` | 物品材质名称（参考 [Material 枚举](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Material.html)） |
| `buy-items.<id>.amount` | 单次购买获得的物品数量 |
| `buy-items.<id>.price` | 购买价格 |
| `buy-items.<id>.weight` | 随机刷新权重，值越大越容易被选中 |
| `buy-items.<id>.display-name` | 物品自定义显示名称（可选，支持颜色代码） |
| `sell-items.<id>.material` | 玩家出售时需手持的物品材质 |
| `sell-items.<id>.amount` | 单次出售需要的物品数量 |
| `sell-items.<id>.price` | 出售获得金额 |
| `sell-items.<id>.weight` | 随机刷新权重 |

### 命令

主命令 `/shop`，别名 `/store`。

| 命令 | 权限 | 说明 |
|------|------|------|
| `/shop` | `letisland.shop.use` | 打开商店 |
| `/shop open` | `letisland.shop.use` | 打开商店 |
| `/shop refresh` | `letisland.shop.admin` | 手动刷新商店库存 |
| `/shop reload` | `letisland.shop.admin` | 热重载商店配置并刷新库存 |

### 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `letisland.shop.use` | 使用商店 | 所有玩家 |
| `letisland.shop.admin` | 管理商店（手动刷新、热重载） | 管理员 |

### 使用说明

#### 购买物品
1. 输入 `/shop` 打开商店
2. 在购买区（上半部分）左键点击想购买的物品
3. 系统会扣除对应金额并将物品放入背包
4. 若余额不足或背包已满会提示失败

#### 出售物品
1. 输入 `/shop` 打开商店
2. 在出售区（下半部分）左键点击对应的出售项
3. 系统会自动从背包和快捷栏中扣除指定数量的物品并增加余额
4. 若背包中物品数量不足会提示失败

#### 修改物品价格（热重载）
1. 编辑服务器 `plugins/Letisland/shop.yml` 文件
2. 修改对应物品的 `price` 值
3. 在游戏内执行 `/shop reload`
4. 商店会立即应用新配置并刷新库存

#### 添加新物品
1. 在 `shop.yml` 的 `buy-items` 或 `sell-items` 下新增条目：

```yaml
buy-items:
  # 新增物品
  netherite_ingot:
    material: NETHERITE_INGOT
    amount: 1
    price: 500.0
    weight: 0.5
    display-name: "§5下界合金锭"
```

2. 执行 `/shop reload` 立即生效

### 刷新机制

- **自动刷新**：商店会根据 `refresh-interval-minutes` 配置自动刷新，玩家每次打开商店时检查是否到刷新时间
- **手动刷新**：管理员可执行 `/shop refresh` 立即刷新
- **权重随机**：物品池中的物品按 `weight` 值加权随机选取，每个物品只会出现一次

### 开发者 API

#### 获取商店管理器

```java
Letisland plugin = (Letisland) Bukkit.getPluginManager().getPlugin("Letisland");
ShopManager shopManager = plugin.getShopManager();
```

#### 常用方法

```java
// 手动刷新商店
shopManager.refresh();

// 热重载配置
shopManager.reload();

// 获取当前库存
Map<Integer, ShopItem> stock = shopManager.getCurrentStock();

// 获取购买/出售物品池
List<ShopItem> buyPool = shopManager.getBuyPool();
List<ShopItem> sellPool = shopManager.getSellPool();
```
