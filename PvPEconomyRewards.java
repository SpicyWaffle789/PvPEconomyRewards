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

    // Killer UUID -> (Victim UUID -> Last Kill Timestamp ms)
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new HashMap<>();

    // Settings
    private static final double STEAL_PERCENTAGE = 0.05;    // 5%
    private static final double MAX_STEAL = 15000.0;        // Max $15,000 cap
    private static final double MIN_BALANCE = 100.0;        // Min $100 victim balance
    private static final long COOLDOWN_MS = 600 * 1000;     // 10 minute cooldown (600s)
    private static final int MIN_PLAYTIME_MINUTES = 30;     // Min 30m victim playtime

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault plugin or Economy provider (e.g. EssentialsX) not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PvPEconomyRewards enabled successfully!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // 1. Must be killed by another player
        if (killer == null || killer.equals(victim)) {
            return;
        }

        // 2. Anti-Farm: Same IP Check
        if (killer.getAddress() != null && victim.getAddress() != null) {
            if (killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) {
                killer.sendMessage(ChatColor.RED + "[PvP] Reward denied: Same IP network detected.");
                return;
            }
        }

        // 3. Anti-Farm: Minimum Playtime Check (Ticks to Minutes)
        int victimPlaytimeMinutes = victim.getStatistic(Statistic.PLAY_ONE_MINUTE) / (20 * 60);
        if (victimPlaytimeMinutes < MIN_PLAYTIME_MINUTES) {
            killer.sendMessage(ChatColor.RED + "[PvP] Reward denied: Victim has under 30 minutes playtime.");
            return;
        }

        // 4. Anti-Farm: Gear Check (Victim must wear at least 1 piece of armor)
        boolean isWearingArmor = false;
        for (ItemStack armor : victim.getInventory().getArmorContents()) {
            if (armor != null && !armor.getType().isAir()) {
                isWearingArmor = true;
                break;
            }
        }
        if (!isWearingArmor) {
            killer.sendMessage(ChatColor.RED + "[PvP] Reward denied: Victim had no armor equipped.");
            return;
        }

        // 5. Anti-Farm: Killer -> Victim Cooldown Check
        long currentTime = System.currentTimeMillis();
        Map<UUID, Long> victimMap = killCooldowns.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>());
        if (victimMap.containsKey(victim.getUniqueId())) {
            long lastKillTime = victimMap.get(victim.getUniqueId());
            if (currentTime - lastKillTime < COOLDOWN_MS) {
                long remainingSec = (COOLDOWN_MS - (currentTime - lastKillTime)) / 1000;
                killer.sendMessage(ChatColor.RED + "[PvP] Reward denied: Target cooldown active (" + remainingSec + "s left).");
                return;
            }
        }

        // 6. Minimum Balance Check
        double victimBalance = econ.getBalance(victim);
        if (victimBalance < MIN_BALANCE) {
            killer.sendMessage(ChatColor.YELLOW + "[PvP] Victim has less than $" + MIN_BALANCE + ". No money stolen.");
            return;
        }

        // 7. Calculate and Transfer Cash
        double stealAmount = Math.min(victimBalance * STEAL_PERCENTAGE, MAX_STEAL);

        econ.withdrawPlayer(victim, stealAmount);
        econ.depositPlayer(killer, stealAmount);

        // Update cooldown timestamp
        victimMap.put(victim.getUniqueId(), currentTime);

        // Notify both players
        killer.sendMessage(ChatColor.GREEN + "[PvP] You stole $" + String.format("%.2f", stealAmount) + " from " + victim.getName() + "!");
        victim.sendMessage(ChatColor.RED + "[PvP] You lost $" + String.format("%.2f", stealAmount) + " to " + killer.getName() + "!");
    }
}
