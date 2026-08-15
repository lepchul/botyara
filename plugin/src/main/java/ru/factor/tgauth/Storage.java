package ru.factor.tgauth;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Привязки «игрок ↔ Telegram» в data.yml. Для сервера на сотни игроков этого с запасом. */
public class Storage {

    public static class Account {
        public UUID uuid;
        public String name;
        public long tgId;
        public String tgName;
        public String lastIp = "";
        public long lastLogin = 0L;
        public long linkedAt = 0L;
        public boolean frozen = false;   // блокировка входа самим игроком через бота
    }

    private final TgAuth plugin;
    private final File file;
    private final Map<UUID, Account> byUuid = new HashMap<>();
    private final Map<Long, UUID> byTg = new HashMap<>();
    /** Telegram-аккаунты, которым закрыт доступ к боту. */
    private final Map<Long, String> blockedTg = new HashMap<>();

    public Storage(TgAuth plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    public synchronized void load() {
        byUuid.clear();
        byTg.clear();
        blockedTg.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Account a = new Account();
                a.uuid      = UUID.fromString(key);
                a.name      = s.getString("name", "");
                a.tgId      = s.getLong("tg-id");
                a.tgName    = s.getString("tg-name", "");
                a.lastIp    = s.getString("last-ip", "");
                a.lastLogin = s.getLong("last-login");
                a.linkedAt  = s.getLong("linked-at");
                a.frozen    = s.getBoolean("frozen", false);
                byUuid.put(a.uuid, a);
                byTg.put(a.tgId, a.uuid);
            } catch (IllegalArgumentException ignored) {
            }
        }
        ConfigurationSection blocked = cfg.getConfigurationSection("blocked-telegram");
        if (blocked != null) {
            for (String key : blocked.getKeys(false)) {
                try {
                    blockedTg.put(Long.parseLong(key), blocked.getString(key, "бан"));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        plugin.getLogger().info("Загружено привязок: " + byUuid.size()
                + ", заблокировано Telegram: " + blockedTg.size());
    }

    public synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Account a : byUuid.values()) {
            String p = "players." + a.uuid;
            cfg.set(p + ".name", a.name);
            cfg.set(p + ".tg-id", a.tgId);
            cfg.set(p + ".tg-name", a.tgName);
            cfg.set(p + ".last-ip", a.lastIp);
            cfg.set(p + ".last-login", a.lastLogin);
            cfg.set(p + ".linked-at", a.linkedAt);
            cfg.set(p + ".frozen", a.frozen);
        }
        for (Map.Entry<Long, String> e : blockedTg.entrySet()) {
            cfg.set("blocked-telegram." + e.getKey(), e.getValue());
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    public synchronized Account byUuid(UUID uuid) { return byUuid.get(uuid); }

    public synchronized Account byTelegram(long tgId) {
        UUID u = byTg.get(tgId);
        return u == null ? null : byUuid.get(u);
    }

    public synchronized Account link(UUID uuid, String name, long tgId, String tgName) {
        Account a = new Account();
        a.uuid = uuid; a.name = name; a.tgId = tgId; a.tgName = tgName;
        a.linkedAt = System.currentTimeMillis();
        byUuid.put(uuid, a);
        byTg.put(tgId, uuid);
        save();
        return a;
    }

    public synchronized boolean unlink(long tgId) {
        UUID u = byTg.remove(tgId);
        if (u == null) return false;
        byUuid.remove(u);
        save();
        return true;
    }

    public synchronized void touch(Account a, String ip) {
        a.lastIp = ip;
        a.lastLogin = System.currentTimeMillis();
        save();
    }

    public synchronized int size() { return byUuid.size(); }

    // ────────────────────────────────────── блокировка Telegram

    public synchronized boolean isBlocked(long tgId) {
        return blockedTg.containsKey(tgId);
    }

    public synchronized String blockReason(long tgId) {
        return blockedTg.getOrDefault(tgId, "");
    }

    public synchronized void block(long tgId, String reason) {
        blockedTg.put(tgId, reason == null || reason.isBlank() ? "бан на сервере" : reason);
        save();
    }

    public synchronized boolean unblock(long tgId) {
        boolean had = blockedTg.remove(tgId) != null;
        if (had) save();
        return had;
    }

    public synchronized int blockedCount() { return blockedTg.size(); }
}
