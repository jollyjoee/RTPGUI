package com.jolly.rtp;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
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

public class RTPListener extends RTPPlugin implements Listener {
    private static final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final Set<UUID> teleporting = ConcurrentHashMap.newKeySet(); // players in countdown
    private static final Map<UUID, Location> startLocations = new ConcurrentHashMap<>();

    private final RTPPlugin plugin;
    private final Random random = new Random();

    public RTPListener(RTPPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean canUseRTP(Player p) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        long cooldown = RTPPlugin.getInstance().getConfig().getLong("cooldown", 300) * 1000;
        return (now - last) >= cooldown;
    }

    public static long getCooldownRemaining(Player p) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        long cooldown = RTPPlugin.getInstance().getConfig().getLong("cooldown", 300) * 1000;
        return Math.max(0, (cooldown - (now - last)) / 1000);
    }

    public static void openGUI(Player p) {
        RTPPlugin plugin = RTPPlugin.getInstance();
        ConfigurationSection gui = plugin.getConfig().getConfigurationSection("gui");

        int size = gui.getInt("size", 9);
        String title = gui.getString("title", "<green>Select a World>");
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

        if (!RTPListener.canUseRTP(p)) {
            long remaining = RTPListener.getCooldownRemaining(p);
            String msg = plugin.getConfig().getString("messages.cooldown", "<red>Wait %time%s.")
                    .replace("%time%", String.valueOf(remaining));
            p.sendActionBar(mm().deserialize(parsePlaceholders(p, msg)));
            return;
        }

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        String clickSound = plugin.getConfig().getString("gui.click-sound", "UI_BUTTON_CLICK");
        try {
            p.playSound(p.getLocation(), Sound.valueOf(clickSound), 1f, 1f);
        } catch (IllegalArgumentException ignored) {}

        String worldName = switch (clicked.getType()) {
            case GRASS_BLOCK -> plugin.getConfig().getString("worldnames.overworld", "world");
            case NETHERRACK -> plugin.getConfig().getString("worldnames.world_nether", "world_nether");
            case END_STONE -> plugin.getConfig().getString("worldnames.world_the_end", "world_the_end");
            default -> null;
        };

        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            p.sendMessage(Component.text("§cWorld not found!"));
            return;
        }

        p.closeInventory();
        startTeleportCountdown(p, world);
    }

    // --- Countdown & cancel ---
    private void startTeleportCountdown(Player player, World world) {
        if (teleporting.contains(player.getUniqueId())) return;

        teleporting.add(player.getUniqueId());
        startLocations.put(player.getUniqueId(), player.getLocation().clone());

        AtomicInteger countdown = new AtomicInteger(plugin.getConfig().getInt("teleport.countdown", 5));
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
                teleportPlayer(player, world);
                return;
            }

            String msg = plugin.getConfig().getString("messages.countdown", "<yellow>Teleporting in %countdown%s...")
                    .replace("%countdown%", String.valueOf(countdown.get()));
            player.sendActionBar(mm().deserialize(parsePlaceholders(player, msg)));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);

            countdown.getAndDecrement();
        }, 1L, 20L);
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
    public void teleportPlayer(Player player, World world) {
        RegionScheduler regionScheduler = Bukkit.getRegionScheduler();
        WorldBorder border = world.getWorldBorder();
        double radius = border.getSize() / 2;
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();
        int minY = world.getMinHeight();

        Runnable[] attemptRunner = new Runnable[1];
        attemptRunner[0] = () -> {
            int x = (int) (centerX + (random.nextDouble() * 2 - 1) * radius);
            int z = (int) (centerZ + (random.nextDouble() * 2 - 1) * radius);

            regionScheduler.run(plugin, world, x >> 4, z >> 4, regionTask -> {
                int y = world.getHighestBlockYAt(x, z);
                Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);

                if (!border.isInside(loc) || loc.getBlockY() < minY || !isSafeLocation(loc, getUnsafeBlocks())) {
                    attemptRunner[0].run();
                    return;
                }

                regionScheduler.run(plugin, player.getLocation(), playerRegionTask -> {
                    player.teleportAsync(loc).thenRun(() -> {
                        String msg = plugin.getConfig().getString("messages.teleporting", "<green>Teleported!");
                        msg = msg.replace("%world%", world.getName());
                        player.sendActionBar(plugin.mm().deserialize(plugin.parsePlaceholders(player, msg)));

                        String soundStr = plugin.getConfig().getString("effects.teleport-sound", "ENTITY_ENDERMAN_TELEPORT");
                        try {
                            player.playSound(loc, Sound.valueOf(soundStr), 1f, 1f);
                        } catch (IllegalArgumentException ignored) {}

                        try {
                            Particle particle = Particle.valueOf(plugin.getConfig().getString("effects.teleport-particle", "PORTAL"));
                            int count = plugin.getConfig().getInt("effects.particle-count", 40);
                            world.spawnParticle(particle, loc.clone().add(0, 1, 0), count, 0.5, 1, 0.5, 0.1);
                        } catch (Exception ignored) {}
                    }).exceptionally(ex -> {
                        player.sendMessage(Component.text("§cTeleport failed."));
                        return null;
                    });
                });
            });
        };
        attemptRunner[0].run();
    }

    private boolean isSafeLocation(Location loc, Set<Material> unsafe) {
        Block block = loc.getBlock();
        Block below = block.getRelative(0, -1, 0);
        return !below.isEmpty() && !unsafe.contains(below.getType()) && !unsafe.contains(block.getType()) && loc.getY() > 5;
    }

    private Set<Material> getUnsafeBlocks() {
        return EnumSet.of(
                Material.LAVA, Material.FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
                Material.CAMPFIRE, Material.SOUL_FIRE, Material.SOUL_CAMPFIRE, Material.WATER
        );
    }
}
