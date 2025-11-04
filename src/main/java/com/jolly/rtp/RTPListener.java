package com.jolly.rtp;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RTPListener extends RTPPlugin implements Listener {
    private static final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final Set<UUID> teleporting = ConcurrentHashMap.newKeySet(); // players in countdown
    private static final Map<UUID, Location> startLocations = new ConcurrentHashMap<>();

    private final RTPPlugin plugin;
    private final Random random = new Random();
    private String displayName = null;
    public RTPListener(RTPPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean canUseRTP(Player p) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);

        long cooldown = getPlayerCooldown(p); // dynamic
        return (now - last) >= cooldown;
    }


    public static long getCooldownRemaining(Player p) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);

        long cooldown = getPlayerCooldown(p); // dynamic
        return Math.max(0, (cooldown - (now - last)) / 1000);
    }

    private static long getPlayerCooldown(Player p) {
        int result = Integer.MAX_VALUE;
        FileConfiguration cfg = RTPPlugin.getInstance().getConfig();
        ConfigurationSection cooldownSection = cfg.getConfigurationSection("cooldown");
        if (cooldownSection == null) return 180 * 1000; // fallback 3 min
        for (String key : cooldownSection.getKeys(false)) {
            String permission = cooldownSection.getConfigurationSection(key).getString("permission", "");
            if (permission != null && !permission.isEmpty()) {
                String permissionNode = "rtp.cooldown." + permission; // e.g., rtp.cooldown.rank1
                if (p.isPermissionSet(permissionNode) && p.hasPermission(permissionNode)) {
                    int seconds = cooldownSection.getConfigurationSection(key).getInt("cooldown", 180);
                    result = Math.min(result, seconds);
                }
            }
        }
        int def = cooldownSection.getConfigurationSection("default").getInt("cooldown", 180);
        return Math.min(result, def)  * 1000L;
    }

    public static void openGUI(Player p) {
        RTPPlugin plugin = RTPPlugin.getInstance();
        ConfigurationSection gui = plugin.getConfig().getConfigurationSection("gui");

        int size = gui.getInt("size", 9);
        String title = gui.getString("title", "<green>Select a World");
        Inventory inv = Bukkit.createInventory(null, size, plugin.mm().deserialize(title));

        ConfigurationSection items = gui.getConfigurationSection("items");
        for (String key : items.getKeys(false)) {
            ConfigurationSection sec = items.getConfigurationSection(key);
            Material mat = Material.matchMaterial(sec.getString("material", "GRASS_BLOCK"));
            if (mat == null) continue;

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(plugin.mm().deserialize(sec.getString("name", key)));

            List<Component> lore = new ArrayList<>();
            for (String l : sec.getStringList("lore")) {
                lore.add(plugin.mm().deserialize(l));
            }
            meta.lore(lore);
            item.setItemMeta(meta);

            inv.setItem(sec.getInt("slot", 0), item);
        }

        p.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String guiTitle = plugin.getConfig().getString("gui.title");
        if (!e.getView().title().equals(plugin.mm().deserialize(guiTitle))) return;
        e.setResult(Event.Result.DENY);
        String disabledSound = plugin.getConfig().getString("gui.disabled-sound", "ENTITY_VILLAGER_NO");
        if (!RTPListener.canUseRTP(p)) {
            long remaining = RTPListener.getCooldownRemaining(p);
            String msg = plugin.getConfig().getString("messages.cooldown", "<red>Wait %time%s.")
                    .replace("%time%", String.valueOf(remaining));
            try {
                p.playSound(p.getLocation(), Sound.valueOf(disabledSound), 1f, 1f);
            } catch (IllegalArgumentException ignored) {}
            p.sendActionBar(mm().deserialize(parsePlaceholders(p, msg)));
            return;
        }

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        String clickSound = plugin.getConfig().getString("gui.click-sound", "UI_BUTTON_CLICK");

        ConfigurationSection items = plugin.getConfig().getConfigurationSection("gui.items");
        if (items == null) return;

        String targetWorld = null;

        for (String key : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(key);
            if (section == null) continue;

            Material material = Material.matchMaterial(section.getString("material", ""));
            if (material == null) continue;

            if (clicked.getType() == material) {
                if (!section.getBoolean("enabled", true)) {
                    p.sendActionBar(mm().deserialize(section.getString("name", key) + " <red>is disabled!"));
                    try {
                        p.playSound(p.getLocation(), Sound.valueOf(disabledSound), 1f, 1f);
                    } catch (IllegalArgumentException ignored) {}
                    continue;
                }
                try {
                    p.playSound(p.getLocation(), Sound.valueOf(clickSound), 1f, 1f);
                } catch (IllegalArgumentException ignored) {}
                targetWorld = section.getString("world-name");
                displayName = section.getString("name");
                break;
            }
        }

        if (targetWorld == null) return;
        World world = Bukkit.getWorld(targetWorld);
        if (world == null) {
            p.sendActionBar(Component.text("§cWorld not found!"));
            return;
        }
        p.closeInventory();
        startTeleportCountdown(p, world);
    }

    // --- Countdown & cancel ---
    private void startTeleportCountdown(Player player, World world) {
        if (teleporting.contains(player.getUniqueId())) return;
        AtomicReference<Location> loc = new AtomicReference<>();
        teleporting.add(player.getUniqueId());
        startLocations.put(player.getUniqueId(), player.getLocation().clone());
        AtomicInteger countdown = new AtomicInteger(plugin.getConfig().getInt("teleport.countdown", 5));
        WorldBorder border = world.getWorldBorder();
        double radius = border.getSize() / 2;
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();
        int minY = world.getMinHeight();
        AtomicInteger attempt = new AtomicInteger();
        Runnable[] attemptRunner = new Runnable[1];
        attemptRunner[0] = () -> {
            int x = (int) (centerX + (random.nextDouble() * 2 - 1) * radius);
            int z = (int) (centerZ + (random.nextDouble() * 2 - 1) * radius);

            Bukkit.getRegionScheduler().run(plugin, world, x >> 4, z >> 4, regionTask -> {
                if (!teleporting.contains(player.getUniqueId())) {
                    regionTask.cancel();
                    return;
                }
                attempt.getAndIncrement();
                int y;
                if (world.getEnvironment() == World.Environment.NETHER) {
                    y = random.nextInt(33, 120);
                } else {
                    y = world.getHighestBlockYAt(x, z);
                }
                //plugin.getLogger().info("Scanning for a safe location in " + world.getName() + ". Environment = " + world.getEnvironment() + ". Currently at " + x + ", " + y + ", " + z);
                player.sendActionBar(plugin.mm().deserialize(plugin.getConfig().getString("messages.scanning", "<aqua>Scanning for a safe teleport location. <gray>(Attempt#{attempt})")
                        .replace("%attempt%", String.valueOf(attempt))));
                loc.set(new Location(world, x + 0.5, y + 1, z + 0.5));
                world.loadChunk(loc.get().getChunk().getX(), loc.get().getChunk().getZ(), true);
                if (!border.isInside(loc.get()) || loc.get().getBlockY() < minY || !isSafeLocation(loc.get(), getUnsafeBlocks())) {
                    attemptRunner[0].run();
                    return;
                }
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                    if (!player.isOnline() || !teleporting.contains(player.getUniqueId())) {
                        task.cancel();
                        return;
                    }

                    if (countdown.get() <= 0) {
                        task.cancel();
                        teleporting.remove(player.getUniqueId());
                        startLocations.remove(player.getUniqueId());
                        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                        teleportPlayer(player, loc.get());
                        return;
                    }

                    String msg = plugin.getConfig().getString("messages.countdown", "<yellow>Teleporting in %countdown%s...")
                            .replace("%countdown%", String.valueOf(countdown.get()));
                    player.sendActionBar(mm().deserialize(parsePlaceholders(player, msg)));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);

                    countdown.getAndDecrement();
                }, 1L, 20L);
            });
        };
        attemptRunner[0].run();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!teleporting.contains(p.getUniqueId())) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // Check only horizontal movement
        if (from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ()) {
            cancelTeleport(p, "<red>Teleport cancelled because you moved!");
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (teleporting.contains(p.getUniqueId())) {
            cancelTeleport(p, "<red>Teleport cancelled because you took damage!");
        }
    }

    private void cancelTeleport(Player p, String message) {
        teleporting.remove(p.getUniqueId());
        startLocations.remove(p.getUniqueId());
        p.sendActionBar(mm().deserialize(message));
    }

    // --- Folia-safe teleport ---
    public void teleportPlayer(Player player, Location loc) {
        RegionScheduler regionScheduler = Bukkit.getRegionScheduler();
        loc.add(0, 1, 0);
        Runnable[] attemptRunner = new Runnable[1];
        attemptRunner[0] = () -> {
            regionScheduler.run(plugin, player.getLocation(), playerRegionTask -> {
                    player.teleportAsync(loc).thenRun(() -> {
                        String msg = plugin.getConfig().getString("messages.teleported", "<green>Teleported to a random location in the %world%<green>!");
                        msg = msg.replace("%world%", displayName);
                        player.sendActionBar(plugin.mm().deserialize(plugin.parsePlaceholders(player, msg)));
                        String soundStr = plugin.getConfig().getString("effects.teleport-sound", "ENTITY_ENDERMAN_TELEPORT");
                        try {
                            player.playSound(loc, Sound.valueOf(soundStr), 1f, 1f);
                        } catch (IllegalArgumentException ignored) {}
                        try {
                            Particle particle = Particle.valueOf(plugin.getConfig().getString("effects.teleport-particle", "PORTAL"));
                            int count = plugin.getConfig().getInt("effects.particle-count", 40);
                            player.getWorld().spawnParticle(particle, loc.clone().add(0, 1, 0), count, 0.5, 1, 0.5, 0.1);
                        } catch (Exception ignored) {}
                    }).exceptionally(ex -> {
                        player.sendMessage(Component.text("§cTeleport failed."));
                        return null;
                    });
                });
            };
        attemptRunner[0].run();
        }

    private boolean isSafeLocation(Location loc, Set<Material> unsafe) {
        Block block = loc.getBlock();
        Block below = block.getRelative(0, -1, 0);
        Block head = block.getRelative(0, +1, 0);
        return !below.isEmpty() && !unsafe.contains(below.getType()) && !unsafe.contains(block.getType()) && !unsafe.contains(head.getType()) && !head.isSolid() && loc.getY() > 5;
    }

    private Set<Material> getUnsafeBlocks() {
        return EnumSet.of(
                Material.LAVA, Material.FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
                Material.CAMPFIRE, Material.SOUL_FIRE, Material.SOUL_CAMPFIRE, Material.WATER
        );
    }
}
