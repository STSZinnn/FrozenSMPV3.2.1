package com.example.punchsnowball;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Stray;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Frost event subsystem. One active server-wide event at a time.
 * Runs entirely on the server's main thread; event-owned entities/blocks are
 * tagged or tracked and removed when the event ends or the plugin disables.
 */
final class FrostEvents implements Listener {
    private final PunchSnowballPlugin plugin;
    private final NamespacedKey relicMarker;
    private final Random random = new Random();
    private final Map<UUID, Setup> adminSetups = new HashMap<>();
    private Active active;
    private long generation;

    private static final LinkedHashMap<String, String> TYPES = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> REWARDS = new LinkedHashMap<>();
    static {
        TYPES.put("meteor", "Frozen Meteor");
        TYPES.put("koth", "King of the Hill");
        TYPES.put("chosen", "The Chosen One");
        TYPES.put("titan", "Frost Titan");
        TYPES.put("treasure", "Treasure Hunt");
        TYPES.put("assassin", "Assassin Hunt");

        REWARDS.put("sword", "Frozen Sword");
        REWARDS.put("battleaxe", "Frozen Battleaxe");
        REWARDS.put("spear", "Frozen Spear");
        REWARDS.put("bow", "Frozen Bow");
        REWARDS.put("crossbow", "Frozen Crossbow");
        REWARDS.put("shield", "Frozen Shield");
        REWARDS.put("grapple", "Loyalty Trident");
        REWARDS.put("riptide", "Riptide Trident");
        REWARDS.put("pickaxe", "Frozen Pickaxe");
        REWARDS.put("snowball", "Frozen Snowball");
        REWARDS.put("tnt", "Frozen TNT");
        REWARDS.put("windcharge", "Frozen Wind Charge");
        REWARDS.put("firework", "Frozen Firework");
        REWARDS.put("lava", "Frozen Flint and Steel");
        REWARDS.put("totem", "Frozen Totem");
        REWARDS.put("rod", "Frozen Fishing Rod");
        REWARDS.put("horn", "Frozen Horn");
        REWARDS.put("helmet", "Frozen Helmet");
        REWARDS.put("chestplate", "Frozen Chestplate");
        REWARDS.put("leggings", "Frozen Leggings");
        REWARDS.put("boots", "Frozen Boots");
    }

    private static final int[] TYPE_SLOTS = {10, 12, 14, 28, 30, 32};
    private static final int[] REWARD_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private static class Setup {
        String type = "meteor";
        String reward = "sword";
        int minutes = 20;
    }

    private static class EventMenu implements InventoryHolder {
        final String view;
        final Inventory inventory;
        EventMenu(String view) {
            this.view = view;
            inventory = Bukkit.createInventory(this, 54,
                    Component.text(view.equals("rewards") ? "Frost Events - Rewards" : "Frost Events - Setup"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static class Active {
        final String type;
        final String reward;
        final World world;
        final UUID token = UUID.randomUUID();
        final int totalSeconds;
        int seconds;
        final BossBar bar;
        Location origin;
        Location objective;
        Block objectiveBlock;
        BlockData oldBlockData;
        final List<Entity> visuals = new ArrayList<>();
        final Map<UUID, Integer> points = new HashMap<>();
        final Map<UUID, UUID> targets = new HashMap<>();
        final Map<UUID, Double> bossDamage = new HashMap<>();
        UUID chosenHolder;
        UUID lastHolder;
        Ravager titan;
        boolean meteorLanded;
        boolean enraged;
        long serial;

        Active(String type, String reward, World world, int duration, BossBar bar) {
            this.type = type;
            this.reward = reward;
            this.world = world;
            this.totalSeconds = duration;
            this.bar = bar;
        }
    }

    FrostEvents(PunchSnowballPlugin plugin) {
        this.plugin = plugin;
        this.relicMarker = NamespacedKey.fromString("punchsnowball:frost_event_relic");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private String label(String type) { return TYPES.getOrDefault(type, type); }
    private String rewardName(String reward) { return REWARDS.getOrDefault(reward, reward); }
    private int configuredInt(String key, int fallback) { return plugin.getConfig().getInt("events." + key, fallback); }
    private double configuredDouble(String key, double fallback) { return plugin.getConfig().getDouble("events." + key, fallback); }
    private String prefix() { return ChatColor.AQUA + "[Frost Event] " + ChatColor.RESET; }
    private void announce(String message) { Bukkit.broadcastMessage(prefix() + message); }

    private static String normalize(String s) { return s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", ""); }
    private String findType(String input) {
        String name = normalize(input);
        if (name.equals("frosttitan") || name.equals("boss")) return "titan";
        if (name.equals("kingofthehill")) return "koth";
        if (name.equals("treasurehunt")) return "treasure";
        if (name.equals("frozenmeteor")) return "meteor";
        if (name.equals("chosenone")) return "chosen";
        if (name.equals("assassinhunt")) return "assassin";
        return TYPES.containsKey(name) ? name : null;
    }
    private String findReward(String input) {
        String name = normalize(input);
        for (String key : REWARDS.keySet()) {
            if (name.equals(key) || name.equals("frost" + key) || name.equals("frozen" + key)) return key;
        }
        if (name.equals("frozentrident")) return "grapple";
        if (name.equals("frosttridentdepths")) return "riptide";
        if (name.equals("frostpickaxe")) return "pickaxe";
        return null;
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("frost.event")) {
                sender.sendMessage(prefix() + ChatColor.RED + "Only event administrators can open this menu."); return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(prefix() + "Use /frost event <type> <reward> [minutes] from console."); return true;
            }
            openSetup(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("status")) {
            status(sender);
            return true;
        }
        if (!sender.hasPermission("frost.event")) {
            sender.sendMessage(prefix() + ChatColor.RED + "No permission."); return true;
        }
        if (args[0].equalsIgnoreCase("stop") || args[0].equalsIgnoreCase("cancel")) {
            if (active == null) sender.sendMessage(prefix() + "There is no active event.");
            else { announce("Event stopped by an administrator."); closeEvent(); }
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(prefix() + "Usage: /frost event <meteor|koth|chosen|titan|treasure|assassin> <frost item> [minutes]");
            sender.sendMessage(prefix() + "Or open /frost event for the GUI.");
            return true;
        }
        String type = findType(args[0]);
        String reward = findReward(args[1]);
        if (type == null || reward == null) {
            sender.sendMessage(prefix() + ChatColor.RED + "Unknown event or reward. Use /frost event for the GUI."); return true;
        }
        int minutes = configuredInt("default-duration-minutes", 20);
        if (args.length >= 3) {
            try {
                String raw = args[2].toLowerCase(Locale.ROOT).replace("minutes", "").replace("min", "").replace("m", "");
                minutes = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                sender.sendMessage(prefix() + "Duration example: 20m"); return true;
            }
        }
        start(sender, type, reward, minutes);
        return true;
    }

    List<String> tabComplete(String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(TYPES.keySet());
            suggestions.add("status"); suggestions.add("stop");
            return suggestions.stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status") && !args[0].equalsIgnoreCase("stop"))
            return REWARDS.keySet().stream().map(v -> "frost" + v)
                    .filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3) return List.of("10m", "15m", "20m", "30m", "45m");
        return List.of();
    }

    private Setup getSetup(Player player) {
        return adminSetups.computeIfAbsent(player.getUniqueId(), unused -> {
            Setup setup = new Setup();
            setup.minutes = configuredInt("default-duration-minutes", 20);
            return setup;
        });
    }
    private ItemStack icon(Material type, String title, String... lore) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + title);
        if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(v -> ChatColor.GRAY + v).toList());
        stack.setItemMeta(meta);
        return stack;
    }
    private void fill(Inventory inv) {
        ItemStack background = icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, background);
    }
    private Material typeIcon(String type) {
        return switch (type) {
            case "meteor" -> Material.BLUE_ICE;
            case "koth" -> Material.BEACON;
            case "chosen" -> Material.HEART_OF_THE_SEA;
            case "titan" -> Material.RAVAGER_SPAWN_EGG;
            case "treasure" -> Material.CHEST;
            case "assassin" -> Material.IRON_SWORD;
            default -> Material.SNOW_BLOCK;
        };
    }
    private void openSetup(Player player) {
        Setup setup = getSetup(player);
        EventMenu menu = new EventMenu("setup");
        fill(menu.inventory);
        int idx = 0;
        for (String type : TYPES.keySet()) {
            String chosen = setup.type.equals(type) ? ChatColor.GREEN + "Selected" : ChatColor.GRAY + "Click to select";
            menu.inventory.setItem(TYPE_SLOTS[idx++], icon(typeIcon(type), label(type), chosen));
        }
        menu.inventory.setItem(38, icon(Material.NETHER_STAR, "Reward: " + rewardName(setup.reward), "Click to select any Frost item"));
        menu.inventory.setItem(40, icon(Material.CLOCK, "Duration: " + setup.minutes + " minutes", "Left click: next duration", "Right click: previous duration"));
        menu.inventory.setItem(42, icon(Material.EMERALD_BLOCK, "START EVENT", "Event: " + label(setup.type), "Prize: " + rewardName(setup.reward)));
        menu.inventory.setItem(44, icon(Material.BARRIER, "STOP ACTIVE EVENT", "Immediately end the current event"));
        menu.inventory.setItem(49, icon(Material.BOOK, "Event status", active == null ? "No event active" : label(active.type) + " is active"));
        player.openInventory(menu.inventory);
    }
    private void openRewards(Player player) {
        EventMenu menu = new EventMenu("rewards");
        fill(menu.inventory);
        int idx = 0;
        for (String group : REWARDS.keySet()) {
            ItemStack item = plugin.frostEventReward(group);
            if (item == null) item = icon(Material.SNOW_BLOCK, rewardName(group));
            else {
                item = item.clone();
                ItemMeta meta = item.getItemMeta();
                List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
                lore.add(ChatColor.GOLD + "Click to choose this reward");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            menu.inventory.setItem(REWARD_SLOTS[idx++], item);
        }
        menu.inventory.setItem(49, icon(Material.ARROW, "Back to event setup"));
        player.openInventory(menu.inventory);
    }
    private static final int[] DURATIONS = {5, 10, 15, 20, 30, 45, 60};
    @EventHandler(ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EventMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("frost.event")) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        Setup setup = getSetup(player);
        if (menu.view.equals("rewards")) {
            for (int i = 0; i < REWARD_SLOTS.length; i++) {
                if (slot != REWARD_SLOTS[i]) continue;
                setup.reward = new ArrayList<>(REWARDS.keySet()).get(i);
                openSetup(player);
                return;
            }
            if (slot == 49) openSetup(player);
            return;
        }
        for (int i = 0; i < TYPE_SLOTS.length; i++) {
            if (slot == TYPE_SLOTS[i]) {
                setup.type = new ArrayList<>(TYPES.keySet()).get(i);
                openSetup(player);
                return;
            }
        }
        if (slot == 38) { openRewards(player); return; }
        if (slot == 40) {
            int index = 0;
            for (int i = 0; i < DURATIONS.length; i++) if (DURATIONS[i] == setup.minutes) index = i;
            index = (index + (event.isRightClick() ? DURATIONS.length - 1 : 1)) % DURATIONS.length;
            setup.minutes = DURATIONS[index];
            openSetup(player);
            return;
        }
        if (slot == 42) {
            player.closeInventory();
            start(player, setup.type, setup.reward, setup.minutes);
            return;
        }
        if (slot == 44) {
            player.closeInventory();
            if (active != null) { announce("Event stopped by an administrator."); closeEvent(); }
            else player.sendMessage(prefix() + "No active event.");
            return;
        }
        if (slot == 49) { player.closeInventory(); status(player); }
    }
    @EventHandler(ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EventMenu) { event.setCancelled(true); return; }
        if (active == null || !active.type.equals("chosen")) return;
        int size = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(raw -> raw < size)
                && event.getNewItems().values().stream().anyMatch(item -> isRelic(item, active)))
            event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void protectChosenRelicInStorage(InventoryClickEvent event) {
        if (active == null || !active.type.equals("chosen")) return;
        if (event.getView().getTopInventory().getHolder() instanceof EventMenu) return;
        // Never let the event relic be hidden inside a chest, shulker, or ender chest.
        if (event.getView().getTopInventory() instanceof org.bukkit.inventory.PlayerInventory) return;
        if (isRelic(event.getCursor(), active)
                || (event.getRawSlot() < event.getView().getTopInventory().getSize()
                    && isRelic(event.getCurrentItem(), active))) {
            event.setCancelled(true);
        }
    }

    private void status(CommandSender sender) {
        if (active == null) { sender.sendMessage(prefix() + "No active event."); return; }
        sender.sendMessage(prefix() + "Active: " + label(active.type) + " | Prize: " + rewardName(active.reward)
                + " | Time left: " + Math.max(0, active.totalSeconds - active.seconds) + "s");
        if (active.objective != null) sender.sendMessage(prefix() + "Location: " + loc(active.objective));
        if (active.type.equals("assassin")) sender.sendMessage(prefix() + "Only eliminations of your assigned target score points.");
        if (active.type.equals("assassin") || active.type.equals("koth")) {
            List<Map.Entry<UUID, Integer>> leaders = active.points.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).limit(3).toList();
            for (int i = 0; i < leaders.size(); i++) {
                var entry = leaders.get(i);
                Player player = Bukkit.getPlayer(entry.getKey());
                sender.sendMessage(prefix() + "#" + (i+1) + " "
                        + (player == null ? entry.getKey().toString().substring(0,8) : player.getName())
                        + ": " + entry.getValue() + (active.type.equals("koth") ? " seconds" : " kills"));
            }
        }
    }
    private String loc(Location l) { return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(); }

    private boolean eligible(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR;
    }
    private List<Player> participants() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (eligible(player)) {
                players.add(player);
            }
        }
        return players;
    }
    private void start(CommandSender sender, String type, String reward, int minutes) {
        if (active != null) { sender.sendMessage(prefix() + ChatColor.RED + "Finish or stop the current event first."); return; }
        if (minutes < 1 || minutes > 120) {
            sender.sendMessage(prefix() + "Duration must be from 1 to 120 minutes."); return;
        }
        List<Player> players = participants();
        if (players.isEmpty()) { sender.sendMessage(prefix() + "At least one survival player must be online."); return; }
        if (type.equals("assassin") && players.size() < 2) {
            sender.sendMessage(prefix() + "Assassin requires at least two online survival players."); return;
        }
        Player host = sender instanceof Player p ? p : players.get(0);
        World world = host.getWorld();
        if ((type.equals("meteor") || type.equals("treasure") || type.equals("titan"))
                && world.getEnvironment() != World.Environment.NORMAL) {
            sender.sendMessage(prefix() + "Start the " + label(type) + " event from an Overworld location.");
            return;
        }
        Location origin = host.getLocation().clone();
        if (!world.getWorldBorder().isInside(origin)) {
            sender.sendMessage(prefix() + ChatColor.RED + "Stand inside the world border before starting this event."); return;
        }
        Location objective = null;
        if (type.equals("meteor") || type.equals("treasure")) {
            objective = randomSurfaceLocation(origin, Math.max(24, configuredInt("spawn-range-blocks", 450)));
            if (objective == null) {
                sender.sendMessage(prefix() + ChatColor.RED + "Could not find a safe location inside the world border."); return;
            }
        }
        if (type.equals("titan")) {
            objective = randomSurfaceLocation(origin, Math.max(16, configuredInt("boss-spawn-range-blocks", 40)));
            if (objective == null) {
                sender.sendMessage(prefix() + ChatColor.RED + "Could not find a safe surface for Frost Titan."); return;
            }
        }
        BossBar bar = Bukkit.createBossBar(ChatColor.AQUA + "Frost Event: " + label(type), BarColor.BLUE, BarStyle.SEGMENTED_10);
        Active event = new Active(type, reward, world, minutes * 60, bar);
        event.serial = ++generation;
        event.origin = origin;
        event.objective = objective;
        active = event;
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
        announce(ChatColor.WHITE + label(type) + ChatColor.AQUA + " started! Prize: " + ChatColor.GOLD + rewardName(reward)
                + ChatColor.AQUA + ". Duration: " + minutes + "m.");
        switch (type) {
            case "meteor" -> startMeteor(event);
            case "treasure" -> {
                if (!placeObjectiveChest(event)) {
                    sender.sendMessage(prefix() + "The treasure chest location became occupied. Event cancelled.");
                    closeEvent(); return;
                }
                int nearX = Math.floorDiv(event.objective.getBlockX(), 200) * 200;
                int nearZ = Math.floorDiv(event.objective.getBlockZ(), 200) * 200;
                announce("A Frost Chest spawned in " + world.getName() + "! First clue: somewhere near X="
                        + nearX + ", Z=" + nearZ + " (approximately 200 blocks). New clues every 50 seconds.");
            }
            case "koth" -> {
                event.objective = origin;
                announce("Capture the hill at " + loc(origin) + ". Only the sole player inside earns progress!");
            }
            case "chosen" -> startChosen(event, players);
            case "assassin" -> {
                for (Player p : players) assignTarget(event, p);
                announce("Hunt YOUR assigned target! Check the action bar for their name and distance.");
            }
            case "titan" -> spawnTitan(event);
            default -> { }
        }
    }

    /** Checks candidate and final chest/meteor positions against the actual, current border. */
    private Location randomSurfaceLocation(Location origin, int radius) {
        World world = origin.getWorld();
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0;
        Location center = border.getCenter();
        double margin = Math.min(8.0, Math.max(1.0, half / 5.0));
        double minX = center.getX() - half + margin, maxX = center.getX() + half - margin;
        double minZ = center.getZ() - half + margin, maxZ = center.getZ() + half - margin;
        if (minX >= maxX || minZ >= maxZ) return null;
        for (int i = 0; i < 28; i++) {
            int x = (int) Math.floor(Math.max(minX, Math.min(maxX, origin.getX() + random.nextInt(radius * 2 + 1) - radius)));
            int z = (int) Math.floor(Math.max(minZ, Math.min(maxZ, origin.getZ() + random.nextInt(radius * 2 + 1) - radius)));
            Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!ground.getType().isSolid()) continue;
            Location p = new Location(world, x + .5, ground.getY() + 1, z + .5);
            if (!border.isInside(p) || !border.isInside(p.clone().add(3, 0, 3))
                    || !border.isInside(p.clone().add(-3, 0, -3))) continue;
            if (!p.getBlock().isPassable() || !p.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            if (ground.getY() < world.getMinHeight() + 2 || ground.getY() > world.getMaxHeight() - 7) continue;
            return p;
        }
        return null;
    }

    private void startMeteor(Active event) {
        Location landing = event.objective.clone();
        double skyY = Math.min(event.world.getMaxHeight() - 5, landing.getY() + 50);
        double distance = Math.max(8, skyY - landing.getY());
        int flightTicks = 90;
        BlockDisplay display = event.world.spawn(landing.clone().add(0, distance, 0), BlockDisplay.class);
        display.setBlock(Material.BLUE_ICE.createBlockData());
        display.setGlowing(true);
        event.visuals.add(display);
        announce("An icy meteor has appeared in the sky over " + event.world.getName()
                + " near X=" + landing.getBlockX() + ", Z=" + landing.getBlockZ() + "!");
        new BukkitRunnable() {
            int tick;
            @Override public void run() {
                if (active != event || !display.isValid()) { cancel(); return; }
                double progress = Math.min(1.0, ++tick / (double) flightTicks);
                Location point = landing.clone().add(0, distance * (1.0 - progress), 0);
                display.teleport(point);
                event.world.spawnParticle(Particle.SNOWFLAKE, point.clone().add(.5, .5, .5), 14, 1.0, 1.0, 1.0, .015);
                if (tick % 15 == 0) event.world.playSound(point, Sound.BLOCK_GLASS_BREAK, 0.85f, .65f);
                if (tick >= flightTicks) {
                    display.remove();
                    event.visuals.remove(display);
                    // Border may have changed while the meteor was falling.
                    if (!event.world.getWorldBorder().isInside(landing)) {
                        announce("The world border moved; the meteor event was cancelled safely.");
                        closeEvent(); cancel(); return;
                    }
                    if (!placeObjectiveChest(event)) {
                        announce("The meteor's landing spot became blocked. Event cancelled safely.");
                        closeEvent(); cancel(); return;
                    }
                    event.meteorLanded = true;
                    event.world.spawnParticle(Particle.EXPLOSION, landing, 6, 1.2, 0.6, 1.2);
                    event.world.playSound(landing, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, .75f);
                    announce("The meteor LANDED at " + loc(landing) + "! Right-click its Frost Chest first to win!");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private boolean placeObjectiveChest(Active event) {
        Location location = event.objective;
        if (location == null || !location.getWorld().getWorldBorder().isInside(location)) return false;
        Block block = location.getBlock();
        if (!block.getType().isAir()) return false; // Never overwrite a player's structure or liquid.
        event.objectiveBlock = block;
        event.oldBlockData = block.getBlockData().clone();
        block.setType(Material.CHEST, false);
        event.world.spawnParticle(Particle.SNOWFLAKE, location.clone().add(0, .5, 0), 50, 1, 1, 1, .04);
        event.world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, .7f);
        return true;
    }

    private void startChosen(Active event, List<Player> players) {
        Player chosen = players.get(random.nextInt(players.size()));
        event.chosenHolder = chosen.getUniqueId();
        giveRelic(chosen, event);
        announce(ChatColor.GOLD + chosen.getName() + ChatColor.AQUA + " is the Chosen One!");
        announce("Hold the event relic when time runs out. Defeat the holder to claim it!");
    }
    private ItemStack createRelic(Active event) {
        ItemStack relic = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = relic.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Frost Chosen Relic");
        meta.setLore(List.of(ChatColor.GRAY + "Event " + event.token, ChatColor.GOLD + "Hold this when time expires to win!"));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(relicMarker, PersistentDataType.STRING, event.token.toString());
        relic.setItemMeta(meta);
        return relic;
    }
    private boolean isRelic(ItemStack item, Active event) {
        if (item == null || !item.hasItemMeta()) return false;
        String token = item.getItemMeta().getPersistentDataContainer().get(relicMarker, PersistentDataType.STRING);
        return token != null && token.equals(event.token.toString());
    }
    private void giveRelic(Player target, Active event) {
        Map<Integer, ItemStack> extra = target.getInventory().addItem(createRelic(event));
        for (ItemStack leftover : extra.values()) target.getWorld().dropItemNaturally(target.getLocation(), leftover);
    }
    private UUID currentRelicHolder(Active event) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!eligible(player)) continue;
            for (ItemStack item : player.getInventory().getContents())
                if (isRelic(item, event)) return player.getUniqueId();
        }
        return null;
    }
    private void removeAllRelics(Active event) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                if (isRelic(item, event)) player.getInventory().setItem(slot, null);
            }
        }
        for (World world : Bukkit.getWorlds()) {
            for (Item entity : world.getEntitiesByClass(Item.class)) {
                if (isRelic(entity.getItemStack(), event)) entity.remove();
            }
        }
    }

    private void spawnTitan(Active event) {
        Location spot = event.objective;
        Ravager boss = event.world.spawn(spot, Ravager.class);
        boss.setCustomName(ChatColor.AQUA + "FROST TITAN");
        boss.setCustomNameVisible(true);
        double hp = Math.max(100.0, configuredDouble("titan-health", 500.0));
        if (boss.getAttribute(Attribute.MAX_HEALTH) != null) boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
        boss.setHealth(hp);
        boss.setPersistent(true);
        boss.setRemoveWhenFarAway(false);
        event.titan = boss;
        event.bar.setColor(BarColor.PURPLE);
        announce("FROST TITAN spawned at " + loc(spot) + "! Highest damage dealt wins!");
        event.world.playSound(spot, Sound.ENTITY_WITHER_SPAWN, 1.4f, .7f);
    }
    private void titanAttacks(Active event) {
        Ravager boss = event.titan;
        if (boss == null || !boss.isValid() || boss.isDead()) return;
        Location center = boss.getLocation();
        double maxHealth = boss.getAttribute(Attribute.MAX_HEALTH) == null ? 500 : boss.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (!event.enraged && boss.getHealth() < maxHealth * .5) {
            event.enraged = true;
            announce(ChatColor.RED + "The Frost Titan is ENRAGED! Its attacks are faster!");
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true));
        }
        int slamInterval = event.enraged ? 4 : 7;
        if (event.seconds % slamInterval == 0) {
            event.world.playSound(center, Sound.ENTITY_RAVAGER_ROAR, 1.2f, .65f);
            event.world.spawnParticle(Particle.BLOCK, center.clone().add(0, 1, 0), 85, 4.0, .6, 4.0, .1,
                    Material.PACKED_ICE.createBlockData());
            for (Player player : participants()) {
                if (!player.getWorld().equals(event.world) || player.getLocation().distanceSquared(center) > 9 * 9) continue;
                player.damage(event.enraged ? 9.0 : 6.0, boss);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
            }
        }
        if (event.seconds % (event.enraged ? 7 : 11) == 0) {
            event.world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.6f, .7f);
            for (Player player : participants()) {
                if (!player.getWorld().equals(event.world) || player.getLocation().distanceSquared(center) > 23 * 23) continue;
                player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().clone().add(0, 1, 0), 55, .7, 1.3, .7, .08);
                player.damage(4.0, boss);
                Vector push = player.getLocation().toVector().subtract(center.toVector());
                if (push.lengthSquared() > .001) player.setVelocity(push.normalize().multiply(1.1).setY(.4));
            }
        }
        if (event.seconds % 20 == 0) {
            int count = event.enraged ? 3 : 2;
            for (int i = 0; i < count; i++) {
                Location spot = center.clone().add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
                Block ground = event.world.getHighestBlockAt(spot, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                spot.setY(ground.getY() + 1);
                if (!event.world.getWorldBorder().isInside(spot)) continue;
                if (event.visuals.size() >= 12) {
                    Entity oldest = event.visuals.remove(0);
                    if (oldest.isValid()) oldest.remove();
                }
                Stray minion = event.world.spawn(spot, Stray.class);
                minion.setCustomName(ChatColor.AQUA + "Titan's Frost Guard");
                minion.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
                ItemStack sword = new ItemStack(Material.IRON_SWORD);
                minion.getEquipment().setItemInMainHand(sword);
                minion.setFireTicks(0);
                event.visuals.add(minion);
                Player nearest = participants().stream().filter(p -> p.getWorld().equals(event.world))
                        .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(spot))).orElse(null);
                if (nearest != null) minion.setTarget(nearest);
            }
        }
    }

    private void assignTarget(Active event, Player hunter) {
        List<Player> options = participants().stream()
                .filter(player -> !player.getUniqueId().equals(hunter.getUniqueId())).toList();
        if (options.isEmpty()) { event.targets.remove(hunter.getUniqueId()); return; }
        UUID previous = event.targets.get(hunter.getUniqueId());
        List<Player> others = options.stream().filter(p -> !p.getUniqueId().equals(previous)).toList();
        if (!others.isEmpty()) options = others;
        Player target = options.get(random.nextInt(options.size()));
        event.targets.put(hunter.getUniqueId(), target.getUniqueId());
        hunter.sendMessage(prefix() + "Your next target is " + ChatColor.GOLD + target.getName() + ChatColor.RESET + "!");
    }
    private void tickAssassin(Active event) {
        for (Player hunter : participants()) {
            UUID id = hunter.getUniqueId();
            UUID targetId = event.targets.get(id);
            Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
            if (target == null || !eligible(target) || target.equals(hunter)) {
                assignTarget(event, hunter);
                UUID assigned = event.targets.get(id);
                target = assigned == null ? null : Bukkit.getPlayer(assigned);
            }
            int kills = event.points.getOrDefault(id, 0);
            String distance = target == null ? "?" :
                    (target.getWorld().equals(hunter.getWorld()) ?
                            ((int) Math.round(target.getLocation().distance(hunter.getLocation()))) + " blocks" : "Other dimension");
            String name = target == null ? "Waiting for a player" : target.getName();
            hunter.sendActionBar(Component.text("❄ Target: " + name + " | Distance: " + distance
                    + " | Target kills: " + kills + " | " + (event.totalSeconds - event.seconds) + "s"));
        }
    }

    private void tick() {
        Active event = active;
        if (event == null) return;
        event.seconds++;
        event.bar.setProgress(Math.max(0, Math.min(1, (event.totalSeconds - event.seconds) / (double) event.totalSeconds)));
        for (Player player : Bukkit.getOnlinePlayers()) if (!event.bar.getPlayers().contains(player)) event.bar.addPlayer(player);
        if (event.type.equals("titan")) {
            if (event.titan == null || !event.titan.isValid() || event.titan.isDead()) {
                chooseTitanWinner(event); return;
            }
            double max = event.titan.getAttribute(Attribute.MAX_HEALTH) == null ? 500.0
                    : event.titan.getAttribute(Attribute.MAX_HEALTH).getValue();
            event.bar.setTitle(ChatColor.AQUA + "Frost Titan " + (int) event.titan.getHealth() + "/" + (int) max
                    + " HP | " + Math.max(0, event.totalSeconds - event.seconds) + "s");
            event.bar.setProgress(Math.max(0, Math.min(1, event.titan.getHealth() / max)));
            titanAttacks(event);
        } else {
            event.bar.setTitle(ChatColor.AQUA + label(event.type) + " | " + Math.max(0, event.totalSeconds - event.seconds) + "s");
        }
        switch (event.type) {
            case "meteor" -> {
                if (event.meteorLanded && event.seconds % 5 == 0 && event.objective != null)
                    event.world.spawnParticle(Particle.SNOWFLAKE, event.objective.clone().add(0, 1, 0), 45, 1.4, 1.3, 1.4, .02);
            }
            case "treasure" -> {
                if (event.seconds % 50 == 0 && event.objective != null) {
                    Location l = event.objective;
                    int coarse = event.seconds < 150 ? 100 : 20;
                    int x = Math.floorDiv(l.getBlockX(), coarse) * coarse;
                    int z = Math.floorDiv(l.getBlockZ(), coarse) * coarse;
                    announce("Treasure clue: " + event.world.getName() + " | near X=" + x + ", Z=" + z
                            + " (within ~" + coarse + " blocks)");
                }
            }
            case "koth" -> tickKoth(event);
            case "chosen" -> {
                UUID holder = currentRelicHolder(event);
                event.chosenHolder = holder;
                if (holder != null && !holder.equals(event.lastHolder)) {
                    Player player = Bukkit.getPlayer(holder);
                    if (player != null) announce(player.getName() + " now holds the Chosen Relic!");
                }
                event.lastHolder = holder;
                if (holder != null) {
                    Player player = Bukkit.getPlayer(holder);
                    if (player != null) player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0,1,0), 20, .4, .7, .4, .01);
                }
            }
            case "assassin" -> tickAssassin(event);
        }
        if (event.seconds >= event.totalSeconds && active == event) endDueToTime(event);
    }
    private void tickKoth(Active event) {
        int radius = Math.max(3, configuredInt("koth-radius-blocks", 10));
        int target = Math.max(20, configuredInt("koth-capture-seconds", 90));
        List<Player> inside = participants().stream().filter(p -> p.getWorld().equals(event.world))
                .filter(p -> p.getLocation().distanceSquared(event.objective) <= radius * radius).toList();
        if (event.seconds % 2 == 0) event.world.spawnParticle(Particle.SNOWFLAKE,
                event.objective.clone().add(0, .5, 0), 40, radius / 2.0, .3, radius / 2.0, 0.02);
        if (inside.size() != 1) return; // A contested or empty hill earns no points.
        Player leader = inside.get(0);
        int score = event.points.merge(leader.getUniqueId(), 1, Integer::sum);
        leader.sendActionBar(Component.text("❄ Capturing the hill: " + score + "/" + target + " seconds"));
        if (score >= target) win(event, leader.getUniqueId(), "Captured the Frost Hill");
    }

    private UUID leader(Map<UUID, ? extends Number> scores) {
        UUID result = null;
        double top = 0.0;
        for (var entry : scores.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !eligible(player)) continue;
            double amount = entry.getValue().doubleValue();
            if (amount > top) { result = entry.getKey(); top = amount; }
        }
        return result;
    }
    private void endDueToTime(Active event) {
        switch (event.type) {
            case "koth" -> win(event, leader(event.points), "Most hill-control seconds");
            case "assassin" -> win(event, leader(event.points), "Most assigned-target eliminations");
            case "chosen" -> win(event, currentRelicHolder(event), "Held the chosen relic at the final second");
            case "titan" -> chooseTitanWinner(event);
            case "meteor", "treasure" -> win(event, null, "No one claimed the objective before the timer expired");
        }
    }
    private void chooseTitanWinner(Active event) {
        if (active != event) return;
        win(event, leader(event.bossDamage), "Dealt the most damage to the Frost Titan");
    }
    private void win(Active event, UUID winnerId, String reason) {
        if (active != event) return;
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        if (winner == null || !winner.isOnline()) {
            announce("Event ended without an eligible winner. " + reason + ".");
            closeEvent(); return;
        }
        ItemStack prize = plugin.frostEventReward(event.reward);
        if (prize != null) {
            Map<Integer, ItemStack> leftovers = winner.getInventory().addItem(prize);
            for (ItemStack item : leftovers.values()) winner.getWorld().dropItemNaturally(winner.getLocation(), item);
        }
        announce(ChatColor.GOLD + winner.getName() + ChatColor.AQUA + " WON " + label(event.type)
                + "! Prize: " + ChatColor.GREEN + rewardName(event.reward) + ChatColor.AQUA + ". " + reason + ".");
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 1.0f);
        closeEvent();
    }
    void shutdown() { closeEvent(); }
    private void closeEvent() {
        Active event = active;
        if (event == null) return;
        active = null;
        generation++;
        event.bar.removeAll();
        for (Entity entity : event.visuals) if (entity.isValid()) entity.remove();
        if (event.titan != null && event.titan.isValid()) event.titan.remove();
        if (event.objectiveBlock != null && event.oldBlockData != null
                && event.objectiveBlock.getType() == Material.CHEST)
            event.objectiveBlock.setBlockData(event.oldBlockData, false);
        if (event.type.equals("chosen")) removeAllRelics(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onObjectiveClick(PlayerInteractEvent event) {
        Active current = active;
        if (current == null || (!current.type.equals("meteor") && !current.type.equals("treasure"))) return;
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || !event.getClickedBlock().equals(current.objectiveBlock)) return;
        event.setCancelled(true);
        if (eligible(event.getPlayer())) win(current, event.getPlayer().getUniqueId(), "First player to claim the Frost Chest");
    }
    @EventHandler(ignoreCancelled = true)
    public void onObjectiveBreak(BlockBreakEvent event) {
        if (active != null && active.objectiveBlock != null && event.getBlock().equals(active.objectiveBlock)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(prefix() + "Right-click the Frost Chest to claim it!");
        }
    }
    @EventHandler(ignoreCancelled = true)
    public void protectObjectiveFromExplosion(EntityExplodeEvent event) {
        if (active != null && active.objectiveBlock != null) event.blockList().remove(active.objectiveBlock);
    }
    @EventHandler(ignoreCancelled = true)
    public void protectObjectiveFromBlockExplosion(BlockExplodeEvent event) {
        if (active != null && active.objectiveBlock != null) event.blockList().remove(active.objectiveBlock);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTitanDamage(EntityDamageByEntityEvent event) {
        Active current = active;
        if (current == null || !current.type.equals("titan") || current.titan == null || !current.titan.equals(event.getEntity())) return;
        Player player = null;
        if (event.getDamager() instanceof Player direct) player = direct;
        else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player owner) player = owner;
        else if (event.getDamageSource().getCausingEntity() instanceof Player source) player = source;
        if (player != null && eligible(player)) {
            double amount = Math.max(0.0, event.getFinalDamage());
            current.bossDamage.merge(player.getUniqueId(), amount, Double::sum);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTitanDeath(EntityDeathEvent event) {
        Active current = active;
        if (current == null || !current.type.equals("titan") || !event.getEntity().equals(current.titan)) return;
        event.getDrops().clear();
        Bukkit.getScheduler().runTask(plugin, () -> chooseTitanWinner(current));
    }
    @EventHandler(ignoreCancelled = true)
    public void onAssassinOrChosenDeath(PlayerDeathEvent event) {
        Active current = active;
        if (current == null) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null && event.getDamageSource().getCausingEntity() instanceof Player source)
            killer = source;
        if (current.type.equals("assassin")) {
            if (killer != null && !killer.equals(victim) && eligible(killer)
                    && victim.getUniqueId().equals(current.targets.get(killer.getUniqueId()))) {
                int kills = current.points.merge(killer.getUniqueId(), 1, Integer::sum);
                announce(killer.getName() + " eliminated their assigned target " + victim.getName() + "! Score: " + kills);
                assignTarget(current, killer);
            }
            current.targets.remove(victim.getUniqueId());
            return;
        }
        if (!current.type.equals("chosen")) return;
        boolean relicPresent = event.getDrops().removeIf(item -> isRelic(item, current));
        if (!relicPresent) {
            for (ItemStack item : victim.getInventory().getContents()) if (isRelic(item, current)) { relicPresent = true; break; }
        }
        if (!relicPresent) return;
        if (event.getKeepInventory()) {
            for (int slot = 0; slot < victim.getInventory().getSize(); slot++) {
                if (isRelic(victim.getInventory().getItem(slot), current))
                    victim.getInventory().setItem(slot, null);
            }
        }
        if (killer != null && killer.isOnline()) {
            final Player winningKiller = killer;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (active != current) return;
                removeAllRelics(current);
                giveRelic(winningKiller, current);
                current.chosenHolder = winningKiller.getUniqueId();
                announce(winningKiller.getName() + " defeated " + victim.getName() + " and claimed the Chosen Relic!");
            });
        } else {
            ItemStack drop = createRelic(current);
            event.getDrops().add(drop);
            current.chosenHolder = null;
            announce("The chosen relic dropped where " + victim.getName() + " was defeated!");
        }
    }
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Active current = active;
        if (current == null) return;
        current.bar.addPlayer(event.getPlayer());
        if (current.type.equals("assassin"))
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (active == current && eligible(event.getPlayer())) assignTarget(current, event.getPlayer());
            }, 20L);
    }
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Active current = active;
        if (current == null) return;
        if (current.type.equals("assassin")) active.targets.remove(event.getPlayer().getUniqueId());
        if (!current.type.equals("chosen")) return;
        Player holder = event.getPlayer();
        boolean dropped = false;
        for (int slot = 0; slot < holder.getInventory().getSize(); slot++) {
            ItemStack item = holder.getInventory().getItem(slot);
            if (!isRelic(item, current)) continue;
            holder.getInventory().setItem(slot, null);
            holder.getWorld().dropItemNaturally(holder.getLocation(), item);
            dropped = true;
        }
        if (dropped) announce("The Chosen One disconnected; their relic dropped at their last position!");
    }
}
