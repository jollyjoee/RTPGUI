package com.jolly.rtp;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;


public class RTPPlugin extends JavaPlugin {
    private static RTPPlugin instance;
    private final MiniMessage mm = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(new RTPListener(this), this);

        // ✅ Register /rtp command using the new Paper API
        registerRTPCommand();

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

                RTPListener.openGUI(player);
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
