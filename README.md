# Letisland

Minecraft 26.2 空岛服插件

## 功能模块

- [经济系统](#经济系统)
- [商店系统](#商店系统)
- [刷石机系统](#刷石机系统)
- [钓鱼科技系统](#钓鱼科技系统)

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
  # 商店系统开关（管理员可通过 /shop enable|disable 切换）
  enabled: true
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
| `settings.enabled` | 商店系统开关（`true` 启用 / `false` 关闭） |
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
| `/shop enable` | `letisland.shop.admin` | 启用商店系统 |
| `/shop disable` | `letisland.shop.admin` | 关闭商店系统 |

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

---

## 刷石机系统

### 简介

Letisland 刷石机系统是一个全服共享的刷石机矿物生成机制。玩家搭建普通的刷石机（水+岩浆生成圆石），系统会根据当前刷石机等级按概率将生成的圆石替换为矿物。等级越高，替换概率越大、矿物种类越丰富。

刷石机等级通过经济系统金钱升级，升级后全服所有刷石机同步生效。

### 等级概览

| 等级 | 名称 | 升级费用 | 替换概率 | 矿物种类 |
|------|------|----------|----------|----------|
| 1 | 初级刷石机 | 初始免费 | 5% | 煤矿、铜矿、铁矿（3种） |
| 2 | 进阶刷石机 | $5,000 | 8% | +金矿、红石矿（5种） |
| 3 | 中级刷石机 | $20,000 | 10% | +青金石矿、石英矿（7种） |
| 4 | 高级刷石机 | $60,000 | 12% | +钻石矿、绿宝石矿（9种） |
| 5 | 大师刷石机 | $150,000 | 15% | +远古残骸（10种） |

### 配置

插件首次启动会生成 `generator.yml`：

```yaml
# 刷石机系统开关（管理员可通过 /generator enable|disable 切换）
enabled: true

# 当前全服刷石机等级（由插件自动维护）
current-level: 1

# 等级配置
levels:
  1:
    name: "初级刷石机"
    upgrade-cost: 0              # 升级费用（等级1为初始，无需费用）
    replace-chance: 0.05         # 生成圆石时替换为矿物的概率
    minerals:
      coal_ore:
        material: COAL_ORE       # 矿物材质
        weight: 50.0             # 随机权重（触发替换时按权重选择矿物）
      iron_ore:
        material: IRON_ORE
        weight: 20.0

  2:
    name: "进阶刷石机"
    upgrade-cost: 5000.0
    replace-chance: 0.08
    minerals:
      # ...更多矿物
```

#### 配置项说明

| 配置项 | 说明 |
|--------|------|
| `enabled` | 刷石机系统开关（`true` 启用 / `false` 关闭） |
| `current-level` | 当前全服刷石机等级（插件自动维护，请勿手动修改） |
| `levels.<等级>.name` | 等级显示名称 |
| `levels.<等级>.upgrade-cost` | 从上一级升级到本级的费用（等级1应为0） |
| `levels.<等级>.replace-chance` | 生成圆石时替换为矿物的概率（0.0-1.0） |
| `levels.<等级>.minerals.<id>.material` | 矿物材质名称 |
| `levels.<等级>.minerals.<id>.weight` | 随机权重，值越大越容易被选中 |

### 命令

主命令 `/generator`，别名 `/gen`。

| 命令 | 权限 | 说明 |
|------|------|------|
| `/generator` | `letisland.generator.use` | 查看当前等级信息 |
| `/generator info` | `letisland.generator.use` | 查看当前等级和下一级信息 |
| `/generator upgrade` | `letisland.generator.use` | 花费金钱升级刷石机 |
| `/generator set <等级>` | `letisland.generator.admin` | 直接设置刷石机等级 |
| `/generator reload` | `letisland.generator.admin` | 热重载刷石机配置 |
| `/generator enable` | `letisland.generator.admin` | 启用刷石机系统 |
| `/generator disable` | `letisland.generator.admin` | 关闭刷石机系统 |

### 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `letisland.generator.use` | 查看等级信息、升级刷石机 | 所有玩家 |
| `letisland.generator.admin` | 设置等级、热重载配置 | 管理员 |

### 使用说明

#### 查看当前等级
输入 `/generator` 或 `/generator info`，显示当前等级、替换概率、矿物种类，以及下一级的升级费用。

#### 升级刷石机
1. 确保余额充足（输入 `/generator info` 查看升级费用）
2. 输入 `/generator upgrade`
3. 系统扣除费用，全服刷石机立即升级
4. 升级成功后全服广播通知

#### 修改配置（热重载）
1. 编辑服务器 `plugins/Letisland/generator.yml` 文件
2. 修改矿物种类、概率、升级费用等
3. 在游戏内执行 `/generator reload`
4. 配置立即生效（不影响当前等级）

#### 添加新等级
在 `generator.yml` 的 `levels` 下新增等级编号即可：

```yaml
levels:
  5:
    name: "大师刷石机"
    upgrade-cost: 150000.0
    replace-chance: 0.15
    minerals:
      ancient_debris:
        material: ANCIENT_DEBRIS
        weight: 3.0
```

执行 `/generator reload` 后新等级即可通过 `/generator upgrade` 解锁。

### 机制说明

- **全服共享**：刷石机等级是全服统一的，任何玩家升级后所有刷石机同步生效
- **自动检测**：系统自动监听水+岩浆生成圆石/石头的事件，无需额外设置
- **权重随机**：触发替换时，矿物池中的矿物按 `weight` 值加权随机选取
- **概率独立**：`replace-chance` 决定是否替换，替换后再按权重决定具体矿物

### 开发者 API

#### 获取刷石机管理器

```java
Letisland plugin = (Letisland) Bukkit.getPluginManager().getPlugin("Letisland");
GeneratorManager generatorManager = plugin.getGeneratorManager();
```

#### 常用方法

```java
// 获取当前等级
int level = generatorManager.getCurrentLevel();

// 获取最高等级
int maxLevel = generatorManager.getMaxLevel();

// 是否已满级
boolean isMax = generatorManager.isMaxLevel();

// 获取当前等级配置
GeneratorManager.LevelConfig config = generatorManager.getCurrentLevelConfig();
config.getName();            // 等级名称
config.getReplaceChance();   // 替换概率
config.getMinerals();        // 矿物列表

// 热重载配置
generatorManager.reload();

// 设置等级（管理员操作）
generatorManager.setCurrentLevel(3);
```

---

## 钓鱼科技系统

### 简介

Letisland 钓鱼科技系统为玩家提供全新的钓鱼体验。玩家通过钓鱼积累经验值，消耗材料升级钓鱼等级，等级越高能钓到的鱼类品种越丰富、越稀有。

系统包含自定义鱼类（带重量和等级）、经验值道具、随机BUFF和鱼市场出售功能。

### 钓鱼等级

| 等级 | 名称 | 升级经验 | 升级材料 |
|------|------|----------|----------|
| 1 | 初级钓鱼 | 100 | 铁锭x16, 橡木x32, 圆石x64 |
| 2 | 中级钓鱼 | 300 | 铁锭x64, 橡木x64, 圆石x128 |
| 3 | 高级钓鱼 | 600 | 金锭x32, 白桦木x64, 铁锭x64 |
| 4 | 专家钓鱼 | 1200 | 钻石x16, 金锭x64, 云杉木x64 |
| 5 | 大师钓鱼 | - | 已满级 |

### 自定义鱼类

鱼类分为5个等级，等级越高越稀有，重量范围也越大：

| 等级 | 名称 | 鱼类示例 |
|------|------|----------|
| 普通(1) | 白色 | 沙丁鱼、鲭鱼、鲤鱼 |
| 罕见(2) | 绿色 | 鲈鱼、鳟鱼、鲶鱼 |
| 稀有(3) | 青色 | 金枪鱼、帝王鲑、剑鱼 |
| 史诗(4) | 粉色 | 深海鲨、巨型蝠鲼、黄金鱼 |
| 传说(5) | 金色 | 克拉肯触手、海龙、利维坦鳞片 |

- **重量系统**：每条鱼有随机重量，越重的鱼越难钓到（平方分布偏向小值）
- **价值计算**：鱼的出售价格 = 基础价值 x 重量
- **等级限制**：玩家只能钓到等级 <= 自己钓鱼等级的鱼

### 经验值道具

- 钓鱼时有概率钓到「钓鱼经验卷轴」（纸材质）
- 拿在手中右键使用，获得随机钓鱼经验（10-50）
- 经验值满后，消耗材料升级钓鱼等级

### BUFF系统

钓鱼时有概率随机触发BUFF，有好有坏：

| 类型 | BUFF示例 |
|------|----------|
| 增益 | 生命恢复、饱和、速度提升、瞬间治疗 |
| 减益 | 中毒、反胃、虚弱 |

### 鱼市场出售

- 输入 `/fishing sell` 打开出售GUI
- 将鱼放入上方区域（前5行45格）
- 点击绿色玻璃板（确认按钮）出售所有鱼
- 出售价格 = 鱼的基础价值 x 重量
- 点击红色玻璃板（取消按钮）关闭并退还物品

### 配置

配置文件 `fishing.yml`：

```yaml
# 系统开关
enabled: true

# 等级配置
levels:
  1:
    name: "初级钓鱼"
    exp-to-next: 100              # 升级所需经验
    upgrade-cost:                  # 升级消耗材料
      - material: IRON_INGOT
        amount: 16
      - material: OAK_LOG
        amount: 32

# 鱼类配置
fish:
  sardine:
    name: "沙丁鱼"
    tier: 1                        # 鱼的等级
    material: COD                  # 物品材质
    min-weight: 0.1                # 最小重量
    max-weight: 0.5                # 最大重量
    base-value: 5.0                # 基础价值（出售价 = base-value * weight）
    weight: 40.0                   # 钓到此鱼的权重

# 钓鱼奖励概率
fishing-rewards:
  custom-fish-chance: 0.60         # 钓到自定义鱼的概率
  exp-item-chance: 0.10            # 钓到经验道具的概率
  buff-chance: 0.15                # 触发BUFF的概率

# 经验道具
exp-item:
  name: "钓鱼经验卷轴"
  material: PAPER
  min-exp: 10
  max-exp: 50

# BUFF配置
buffs:
  regeneration:
    name: "生命恢复"
    type: GOOD                      # GOOD增益 / BAD减益
    effect: REGENERATION             # 药水效果名称
    duration: 10                     # 持续秒数
    amplifier: 1                     # 效果等级（0开始）
    weight: 20.0                     # 触发权重
```

### 命令

主命令 `/fishing`，别名 `/fish`。

| 命令 | 权限 | 说明 |
|------|------|------|
| `/fishing` | `letisland.fishing.use` | 查看钓鱼等级信息 |
| `/fishing info` | `letisland.fishing.use` | 查看钓鱼等级信息 |
| `/fishing upgrade` | `letisland.fishing.use` | 打开钓鱼等级升级GUI |
| `/fishing sell` | `letisland.fishing.use` | 打开鱼市场出售GUI |
| `/fishing codex` | `letisland.fishing.use` | 打开鱼类图鉴 |
| `/fishing autosell [0-5]` | `letisland.fishing.use` | 设置/查看自动出售等级 |
| `/fishing set <玩家> <等级>` | `letisland.fishing.admin` | 设置玩家钓鱼等级 |
| `/fishing reload` | `letisland.fishing.admin` | 热重载配置 |
| `/fishing enable` | `letisland.fishing.admin` | 启用钓鱼系统 |
| `/fishing disable` | `letisland.fishing.admin` | 关闭钓鱼系统 |

### 自动出售

玩家可以设置自动出售等级，钓鱼时品质 <= 设定等级的鱼将自动出售为金币，不占用背包。

- `/fishing autosell` - 查看当前设置
- `/fishing autosell 0` - 关闭自动出售
- `/fishing autosell 3` - 自动出售稀有(3级)及以下的鱼

### 鱼类图鉴

输入 `/fishing codex` 打开鱼类图鉴GUI：

- 图鉴展示所有可钓到的鱼类
- **未发现的鱼**显示为 `??? 未知鱼类`（障幕方块）
- **已发现的鱼**显示真实信息，包括：
  - 鱼的名称和品质颜色
  - 钓到次数
  - 最高重量纪录
  - 重量范围和基础价值
- 每次钓到自定义鱼时自动记录到图鉴

### 权限

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `letisland.fishing.use` | 钓鱼、查看等级、升级、出售 | 所有玩家 |
| `letisland.fishing.admin` | 设置等级、热重载、开关系统 | 管理员 |

### 使用流程

1. **钓鱼** - 正常使用钓鱼竿钓鱼，有60%概率钓到自定义鱼，10%概率钓到经验卷轴
2. **获得经验** - 右键使用经验卷轴获得钓鱼经验
3. **升级等级** - 经验满后，输入 `/fishing shop` 打开GUI查看材料需求，或直接 `/fishing upgrade` 升级
4. **出售鱼** - 输入 `/fishing sell` 打开鱼市场，放入鱼后点击绿色玻璃板出售获得金币
5. **等级提升** - 钓鱼等级越高，可钓到的鱼种类越多、越稀有

### 开发者 API

```java
Letisland plugin = (Letisland) Bukkit.getPluginManager().getPlugin("Letisland");
FishingManager fishingManager = plugin.getFishingManager();

// 获取玩家等级和经验
int level = fishingManager.getPlayerLevel(player.getUniqueId());
int exp = fishingManager.getPlayerExp(player.getUniqueId());

// 检查经验是否已满
boolean maxed = fishingManager.isExpMaxed(player.getUniqueId());

// 尝试升级
FishingManager.UpgradeResult result = fishingManager.tryUpgrade(player);

// 判断物品是否为自定义鱼
boolean isFish = fishingManager.isCustomFish(item);

// 获取鱼的价值信息
FishingManager.FishItemInfo info = fishingManager.getFishInfo(item);
if (info != null) {
    double value = info.totalValue();
}
```
