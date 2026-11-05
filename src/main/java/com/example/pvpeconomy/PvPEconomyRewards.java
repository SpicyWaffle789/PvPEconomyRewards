package com.example.pvpeconomy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PvPEconomyRewards extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Economy econ = null;
    // killer -> (victim -> last reward time)
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("Vault not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        // The economy plugin may register its provider after we enable, so don't
        // disable here; look it up lazily (see getEconomy()).
        getServer().getScheduler().runTask(this, () -> {
            if (getEconomy() == null) {
                getLogger().warning("No Vault economy provider registered yet. "
                        + "Rewards stay inactive until an economy plugin (e.g. EssentialsX) loads.");
            } else {
                getLogger().info("Hooked into economy: " + econ.getName());
            }
        });
    }

    /** Lazily resolves the Vault economy so plugin load order doesn't matter. */
    private Economy getEconomy() {
        if (econ != null) return econ;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) econ = rsp.getProvider();
        return econ;
    }

    private Component msg(String path, Map<String, String> placeholders) {
        String prefix = getConfig().getString("messages.prefix", "&8[&cPvP&8] ");
        String text = getConfig().getString("messages." + path, "");
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return LEGACY.deserialize(prefix + text);
    }

    private Component msg(String path) {
        return msg(path, Map.of());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Prevent the cooldown map from growing forever
        killCooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null || killer.equals(victim)) return;

        Economy economy = getEconomy();
        if (economy == null) return;

        // 1. Same IP Check
        if (getConfig().getBoolean("anti-farm.check-ip", true)) {
            if (killer.getAddress() != null && victim.getAddress() != null
                    && killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) {
                killer.sendMessage(msg("denied-ip"));
                return;
            }
        }

        // 2. Playtime Check (PLAY_TIME is measured in ticks)
        int minPlaytime = getConfig().getInt("anti-farm.min-playtime-minutes", 30);
        int victimPlaytimeMinutes = victim.getStatistic(Statistic.PLAY_TIME) / (20 * 60);
        if (victimPlaytimeMinutes < minPlaytime) {
            killer.sendMessage(msg("denied-playtime", Map.of("%minutes%", String.valueOf(minPlaytime))));
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
                killer.sendMessage(msg("denied-armor"));
                return;
            }
        }

        // 4. Target Cooldown Check
        long cooldownMs = getConfig().getLong("cooldown-seconds", 600) * 1000L;
        long now = System.currentTimeMillis();
        Map<UUID, Long> victimMap = killCooldowns.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>());
        Long lastKill = victimMap.get(victim.getUniqueId());
        if (lastKill != null && now - lastKill < cooldownMs) {
            long remainingSec = (cooldownMs - (now - lastKill)) / 1000;
            killer.sendMessage(msg("denied-cooldown", Map.of("%time%", String.valueOf(remainingSec))));
            return;
        }

        // 5. Minimum Balance Check
        double minBalance = getConfig().getDouble("minimum-victim-balance", 1000.0);
        double victimBalance = economy.getBalance(victim);
        if (victimBalance < minBalance) {
            killer.sendMessage(msg("denied-low-balance", Map.of("%min%", String.format("%.2f", minBalance))));
            return;
        }

        // 6. Calculate & Process Steal (rounded down to cents so what's withdrawn == what's shown)
        double percentage = getConfig().getDouble("steal-percentage", 0.05);
        double maxSteal = getConfig().getDouble("max-steal-amount", 15000.0);
        double stealAmount = Math.floor(Math.min(victimBalance * percentage, maxSteal) * 100.0) / 100.0;
        if (stealAmount <= 0) return;

        // Only pay the killer if the withdrawal really succeeded (prevents money duplication)
        EconomyResponse withdraw = economy.withdrawPlayer(victim, stealAmount);
        if (!withdraw.transactionSuccess()) {
            getLogger().warning("Withdraw from " + victim.getName() + " failed: " + withdraw.errorMessage);
            return;
        }
        EconomyResponse deposit = economy.depositPlayer(killer, stealAmount);
        if (!deposit.transactionSuccess()) {
            // Refund the victim
            economy.depositPlayer(victim, stealAmount);
            getLogger().warning("Deposit to " + killer.getName() + " failed, refunded victim: " + deposit.errorMessage);
            return;
        }

        victimMap.put(victim.getUniqueId(), now);

        String amount = String.format("%.2f", stealAmount);
        killer.sendMessage(msg("stole-money", Map.of("%amount%", amount, "%victim%", victim.getName())));
        victim.sendMessage(msg("lost-money", Map.of("%amount%", amount, "%killer%", killer.getName())));
    }
}
