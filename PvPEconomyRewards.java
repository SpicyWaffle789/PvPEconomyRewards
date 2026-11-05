package com.example.pvpeconomy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PvPEconomyRewards extends JavaPlugin implements Listener {

    private Economy econ = null;
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        // Save default config.yml if it doesn't exist
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vault plugin or Economy provider not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PvPEconomyRewards enabled with config!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    private String getMsg(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&cPvP&8] ");
        String msg = getConfig().getString("messages." + path, "");
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) return;

        // 1. Same IP Check
        if (getConfig().getBoolean("anti-farm.check-ip", true)) {
            if (killer.getAddress() != null && victim.getAddress() != null) {
                if (killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) {
                    killer.sendMessage(getMsg("denied-ip"));
                    return;
                }
            }
        }

        // 2. Playtime Check
        int minPlaytime = getConfig().getInt("anti-farm.min-playtime-minutes", 30);
        int victimPlaytimeMinutes = victim.getStatistic(Statistic.PLAY_ONE_MINUTE) / (20 * 60);
        if (victimPlaytimeMinutes < minPlaytime) {
            killer.sendMessage(getMsg("denied-playtime"));
            return;
        }

        // 3. Armor Check
        if (getConfig().getBoolean("anti-farm.require-armor", true)) {
            boolean wearingArmor = false;
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir()) {
                    wearingArmor = true;
                    break;
                }
            }
            if (!wearingArmor) {
                killer.sendMessage(getMsg("denied-armor"));
                return;
            }
        }

        // 4. Target Cooldown Check
        long cooldownMs = getConfig().getLong("cooldown-seconds", 600) * 1000L;
        long currentTime = System.currentTimeMillis();
        Map<UUID, Long> victimMap = killCooldowns.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>());
        
        if (victimMap.containsKey(victim.getUniqueId())) {
            long lastKillTime = victimMap.get(victim.getUniqueId());
            if (currentTime - lastKillTime < cooldownMs) {
                long remainingSec = (cooldownMs - (currentTime - lastKillTime)) / 1000;
                killer.sendMessage(getMsg("denied-cooldown").replace("%time%", String.valueOf(remainingSec)));
                return;
            }
        }

        // 5. Minimum Balance Check
        double minBalance = getConfig().getDouble("minimum-victim-balance", 100.0);
        double victimBalance = econ.getBalance(victim);
        if (victimBalance < minBalance) {
            killer.sendMessage(getMsg("denied-low-balance"));
            return;
        }

        // 6. Calculate & Process Steal
        double percentage = getConfig().getDouble("steal-percentage", 0.05);
        double maxSteal = getConfig().getDouble("max-steal-amount", 15000.0);
        double stealAmount = Math.min(victimBalance * percentage, maxSteal);

        econ.withdrawPlayer(victim, stealAmount);
        econ.depositPlayer(killer, stealAmount);

        victimMap.put(victim.getUniqueId(), currentTime);

        String formattedAmount = String.format("%.2f", stealAmount);
        killer.sendMessage(getMsg("stole-money").replace("%amount%", formattedAmount).replace("%victim%", victim.getName()));
        victim.sendMessage(getMsg("lost-money").replace("%amount%", formattedAmount).replace("%killer%", killer.getName()));
    }
}
