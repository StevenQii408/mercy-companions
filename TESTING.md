# 验证记录

当前源码版本：Mercy Companions **0.4.2**，目标环境为 Minecraft 26.3、Fabric Loader 0.20.1、Fabric API 0.166.2+26.3。

本文记录各版本已经完成的测试，不表示每次修改都会自动重跑全部历史测试。下列通过数量只对应注明的被测版本。测试日志、截图、生成存档、依赖 JAR 和本地运行配置位于 `build/`，不随源码仓库发布。

## 运行测试

先按 [README](README.md#构建与验证) 准备游戏依赖并构建：

```sh
python3 scripts/build.py
python3 scripts/run-tests.py
```

服务器测试使用独立世界和合成玩家，并分进程验证存档重启。客户端测试还需要本地 Minecraft 资源、图形环境，以及服务器测试生成的测试世界；附加模组测试需要另行准备兼容模组。当前客户端脚本仍有原开发工作区的路径约定，不能仅凭克隆源码直接运行。

```sh
python3 scripts/run-ui-tests.py --bag-only --with-addons
python3 scripts/run-death-flow-tests.py
```

`src/test/java` 保存服务器集成测试和粒子名册单测；`src/testClient/java` 保存客户端界面、粒子及死亡流程测试。运行结果应连同被测 JAR 的哈希一起核对。

## 0.4.2 公开测试版

最终发布 JAR 在 Minecraft 26.3 上通过了 236 项服务端检查和 14 项停服重启检查，共 250 项通过，0 项失败。被测文件为 `mercy-companions-0.4.2+26.3.jar`，SHA-256 为 `2e117e5071c682c57ca0976b1d0c3c69cb63249d7ed275f2c345424073972c79`。

### 袋格浏览

真实中文客户端分两段验证了同一发行包，组合包含 Xaero 双地图、Iris 及辅助模组：

- 六次双向滚轮浏览及鼠标移开均保留服务器的下一位出战伙伴；只有右键袋格才提交置顶或召回。
- 鼠标离开实际袋格，预览立即关闭；原头像区域下面的物品格可执行原版右键取半组。
- 左键拿起袋子、鼠标已有物品时的交换保持原版行为。

该行为取代旧版可在预览头像上悬停、点击的交互。旧版滚轮自动调整出战顺序的断言也不再适用于 0.4.2。

本轮生成报告为 `ui-results.json` 和 `bag-action-results.json`。界面测试同时区分本地浏览状态与服务端实际出战顺序。

## 0.4.1：召唤地面

独立测试服务器与重启验证共 **250 项通过、0 项失败**。新增 16 项覆盖冰、浮冰、蓝冰、雪块、1/4/8 层薄雪、上下半砖、地毯、树叶、水下落点、玻璃及空间检查。

验证重点是按实际碰撞高度放置伙伴，以及空间不足时保留袋内记录，不将方块是否完整遮光当作可站立条件。

## 0.4.0：服务器集成测试

**234 项通过、0 项失败**。使用真实 Minecraft/Fabric 专用服务器、独立测试世界与合成玩家；主测试和重启测试分别在两个进程运行，均正常退出。固定世界种子并禁用结构，避免村庄占用召唤位置。两份服务器日志未发现 ERROR、Exception 或 AssertionError。

- 45 个支持的原版类型及变种：逐一验证致命收服、收回、召唤、身份、类型、变种与 AI tick。完整清单见文末。
- 真实袋子方块/实体右键：后进先出、指定顺序、失败回滚、重复召唤防护、所有权、容量、冷却、持久化与死亡不复活。
- 退出、主人死亡、跨维度、移走剑、容量下降、区块卸载均不会自动入袋；真实卸载及重启后在原维度和位置恢复同一 UUID。
- 和平难度中野怪移除，伙伴仍保留；和平重启及袋子召唤正常，真实致命伤仍永久死亡。
- 原版攻击方式：苦力帕接近目标时没有额外近战扣血，实际伤害来自自爆；骷髅有弓时射箭、失去弓后使用原版近战；巨人不增加原版没有的近战。
- 巨人、僵尸马、骆驼尸壳、空硫方怪与治愈村民在主人战斗期间仍可跟随，不分配无法完成的攻击目标；僵尸鹦鹉螺保留原生冲刺战斗目标。
- 普通伙伴只响应主人战斗；监守者、凋灵、僵尸疣猪兽保留原生主动索敌。十类代表实体实际运行 AI 并造成战斗伤害。
- 主人直接伤害保护及范围误伤：野怪对照可伤玩家，真实苦力帕爆炸、烈焰人火球及后续火伤可伤主人。
- 基础 3 名额、契约 I–IV、最多 7 名额、附魔台候选、多把剑不叠加；初次赠送、收服不掉经验或战利品、首次满血、手动药水及亡灵反转、无自动治疗、死亡释放槽位。

### 远程回收与仁慈伤害

- 分别回收远距离、跨维度和真正已卸载区块中的伙伴：保留 7 HP、原 UUID 和其他伙伴相对顺序，回收后成为栈顶。无袋、其他玩家、过期 UUID 或尚未脱战 10 秒时拒绝请求，重复操作不复制伙伴。
- 卸载后的回收不强制加载区块；原区块重新加载不会产生副本，重启后仍能召出同一个伙伴。再次召唤遵守 2 秒冷却。
- 牛、村民、狼、鸡、现有伙伴、临时恼鬼、末影龙的直接致命剑伤保留 1 HP；重复命中不治愈或重新收服。其他武器或非剑伤害仍可致死；玩家和盔甲架不受该保护。
- 通过真实 `player.attack` 触发横扫：健康旁观者确实受伤，1 HP 副目标存活且不入册，主目标收服规则保持独立。

### 唤魔者临时恼鬼

实际执行原生 `EvokerSummonSpellGoal`，一次生成三只恼鬼。确认临时归属、主人/施法者/同组保护、实际原生冲刺攻击、不能手动收纳或致命收服、不能刷掉落、永久名册不增加槽位。唤魔者死亡或主动收回时，随从立即清理。

停服前保留活体临时恼鬼，验证不写入原版实体存档。重启后切换正常难度并加载对应区域，确认唤魔者恢复但没有孤立恼鬼，避免和平难度自然清除掩盖错误。野生恼鬼仍通过逐种收服测试。

服务器测试报告输出至 `build/test-server/results.json`，主流程与重启日志分别为 `integration.log`、`restart.log`。这些是本地生成文件，不是仓库预置测试结果。

## 客户端界面与粒子的历史覆盖

0.4.0 客户端验证覆盖紫色剑名和袋名、空袋、3/4/5/6/7 格、容量下降后的封存区、出战标记、选中名称及 HP。45 个已支持原版类型都有对应脸部纹理，资源路径、尺寸和 UV 边界已核验；未打包原版图像资产。

输入测试使用真实 `MouseHandler.onScroll` 和 `MouseHandler.onButton`，区分服务端伙伴与纯展示数据。七伙伴容量画面使用样例数据；滚轮网络交互、召唤与粒子使用真实服务端伙伴。历史英文普通客户端完整检查了 14 个主视图；中文附加模组环境采用分段检查。旧版头像点击和滚轮重排行为已被 0.4.2 替换，不能当作当前交互规范。

粒子名册单测 **9 项通过**。客户端检查蓝色 `#72C7FF` 与白色 `#EDF8FF` 的原版 `ENTITY_EFFECT` / `SpellParticle`，每 8 tick 两枚，按实际体型沿身体外侧分布。恶魂与巨人不再受小体型的半径或高度上限限制。

实测召唤伙伴、移走全部袋子、重新同步名册，并从 `ParticleEngine` 观察实际蓝白药水粒子；野生 UUID、不同维度、已收纳、未加载和死亡伙伴不会进入标记名单。中文光影运行记录到 79 个授权帧、117 个蓝色与 122 个白色粒子样本，移除袋子后仍能正常同步。

报告包括 `ui-results.json`、`bag-action-results.json`、`particle-results.json` 与截图，分别位于相应的 `build/*-client` 测试目录。历史测试观察到原版 Tooltip 贴近屏幕底边时装饰边缘可能裁切约 2 个物理像素；文字仍完整可读。

## 0.3.0：死亡与 HUD 控制实验

独立测试世界检查 **20 个状态、20 张截图**：非致命真实摔落、普通/Freecam 两种状态的致命受伤、死亡界面及实际点击重生按钮。重生后生命与 HUD 均正常。

通过真实按键绑定验证背包、暂停、第一/第三人称，以及 F1 隐藏和恢复 HUD。该实验未发现需要修改原版死亡或受伤显示机制的问题，因此没有为此增加生产补丁。入口为 `scripts/run-death-flow-tests.py`。

## 可选附加模组检查

历史客户端组合包括 Iris 1.11.7、Sodium 0.9.2、Freecam 1.5.1、Gamma Utils 3.2.2、Cloth Config 26.3.159，以及 Complementary Unbound r5.9.3。测试检查光影加载、相机启用/关闭和伙伴界面。该记录只覆盖这些具体组合，不代表所有版本或设置均已验证。

`scripts/addons.py` 是本地测试辅助工具：从预先准备的游戏实例复制指定模组、对应配置、光影和相关快捷键到隔离测试目录。它不提供这些第三方文件，也不是模组安装依赖；调用前需检查其来源目录设置。

Xaero/Carry On 初始化检查的自有源码保存在 `src/testSmoke/java/dev/mercy/maptest/XaeroSmoke.java`，不再依赖 `build/` 中的生成源码。完成构建、准备本地游戏资源后，可指定附加模组来源：

```sh
python3 scripts/run-xaero-smoke.py --source /path/to/minecraft
python3 scripts/run-xaero-smoke.py --source /path/to/minecraft --carryon
```

来源目录需要包含本模组、Fabric API、Xaero Minimap、Xaero World Map、XaeroLib、Iris 和 Sodium；`--carryon` 额外要求 Carry On。脚本将模组复制到 `build/` 下的隔离目录，只在主菜单检查初始化与按键注册，然后自动退出，不打开世界。Carry On 检查使用默认拾取和潜行按键。

这类冒烟检查不验证地图探索、实际搬运行为或与伙伴持久化的完整兼容性。Minecraft 资源及客户端启动路径仍使用本地工作区约定，源码自包含不意味着无需外部游戏依赖。

## 覆盖边界

逐种收服和收纳验证不等于穷举每种生物在所有地形、维度与装备组合下的全部技能。保留原版攻击招式和持械/距离条件，同时仍执行本模组限制：蠹虫不唤醒石内同类、僵尸不召援军、史莱姆死亡不分裂、潜影贝不复制、凋灵不无目标轰炸、伙伴爆炸不破坏地形。不可将其表述为“所有机制完全原版”。

尚未穷举第三方自定义生物、其他光影包与真实多客户端联机；Johnny 卫道士仍缺少该命名变种的长时间战斗测试。独立测试中的离线身份可能产生 Realms/账号认证错误，需要与模组自身错误区分。

## 逐种收纳清单

`minecraft:rabbit[evil]`, `minecraft:blaze`, `minecraft:bogged`, `minecraft:breeze`, `minecraft:camel_husk`, `minecraft:cave_spider`, `minecraft:creaking`, `minecraft:creeper`, `minecraft:drowned`, `minecraft:elder_guardian`, `minecraft:enderman`, `minecraft:endermite`, `minecraft:evoker`, `minecraft:ghast`, `minecraft:giant`, `minecraft:guardian`, `minecraft:hoglin`, `minecraft:husk`, `minecraft:illusioner`, `minecraft:magma_cube`, `minecraft:parched`, `minecraft:phantom`, `minecraft:piglin`, `minecraft:piglin_brute`, `minecraft:pillager`, `minecraft:ravager`, `minecraft:shulker`, `minecraft:silverfish`, `minecraft:skeleton`, `minecraft:slime`, `minecraft:spider`, `minecraft:stray`, `minecraft:sulfur_cube`, `minecraft:vex`, `minecraft:vindicator`, `minecraft:warden`, `minecraft:witch`, `minecraft:wither`, `minecraft:wither_skeleton`, `minecraft:zoglin`, `minecraft:zombie`, `minecraft:zombie_horse`, `minecraft:zombie_nautilus`, `minecraft:zombie_villager`, `minecraft:zombified_piglin`
