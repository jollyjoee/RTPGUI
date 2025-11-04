package com.jolly.rtp;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;


public class RTPPlugin extends JavaPlugin {
    private static RTPPlugin instance;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final FileConfiguration config = getConfig();
    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new RTPListener(this), this);
        Metrics metrics = new Metrics(this, 27848);
        registerRTPCommand();
        registerReloadCommand();
        getLogger().info("RTPGUI enabled successfully on Paper/Folia!");
    }

    private void registerRTPCommand() {
        this.registerCommand("rtp", new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if (!(stack.getSender() instanceof Player player)) {
                    stack.getSender().sendMessage("This command can only be used by players!");
                    return;
                }
                if (!player.hasPermission("rtp.use")) {
                    player.sendActionBar(mm.deserialize(config.getString("no-permission", "<red>You have no permission to use /rtp!")));
                    return;
                }
                RTPListener.openGUI(player);
            }
        });
    }

    private void registerReloadCommand() {
        this.registerCommand("rtpreload", new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                if ((stack.getSender() instanceof Player player)) {
                    if (!player.hasPermission("rtp.reload")) {
                        player.sendActionBar(mm.deserialize(config.getString("no-permission", "<red>You have no permission to reload!")));
                        return;
                    } else {
                        player.sendActionBar(mm.deserialize("<green>Successfully reloaded RTP!"));
                    }
                reloadConfig();
                getLogger().info("Reloading RTP config...");
                getLogger().info("Successfully reloaded RTP config!");
                }
            }
        });
    }

    public static RTPPlugin getInstance() {
        return instance;
    }

    public MiniMessage mm() {
        return mm;
    }

    public String parsePlaceholders(Player player, String text) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable ignored) {
                return text;
            }
        }
        return text;
    }
}
