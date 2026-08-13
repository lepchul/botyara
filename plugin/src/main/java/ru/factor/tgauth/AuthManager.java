package ru.factor.tgauth;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    /** Игрок в игре, но ещё не пущен. */
    public static class Session {
        public UUID uuid;
        public String name;
        public String ip;
        public Location spawn;
        public GameMode gameMode;
        public float walkSpeed;
        public long deadline;        // до какого времени ждём
        public String code;          // для регистрации
        public String requestId;     // для подтверждения входа
        public boolean registering;  // true — привязка, false — подтверждение
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // без похожих символов
    private static final SecureRandom RNG = new SecureRandom();

    private final TgAuth plugin;
    private final Storage storage;
    private final Telegram telegram;

    private final Map<UUID, Session> sessions   = new ConcurrentHashMap<>();
    private final Map<String, UUID> byCode      = new ConcurrentHashMap<>();
    private final Map<String, UUID> byRequest   = new ConcurrentHashMap<>();

    public AuthManager(TgAuth plugin, Storage storage, Telegram telegram) {
        this.plugin = plugin;
        this.storage = storage;
        this.telegram = telegram;
    }

    public boolean isPending(UUID uuid) { return sessions.containsKey(uuid); }
    public Session session(UUID uuid)   { return sessions.get(uuid); }

    // ─────────────────────────────────────────────────────── вход

    public void onJoin(Player player) {
        String ip = player.getAddress() == null ? "" : player.getAddress().getAddress().getHostAddress();
        Storage.Account acc = storage.byUuid(player.getUniqueId());

        // Доверенное устройство: тот же IP и недавний вход — пускаем молча
        if (acc != null && !acc.frozen) {
            long trustMs = plugin.getConfig().getLong("auth.trusted-days", 7) * 86_400_000L;
            boolean sameIp = !ip.isEmpty() && ip.equals(acc.lastIp);
            boolean fresh  = System.currentTimeMillis() - acc.lastLogin < trustMs;
            if (sameIp && fresh) {
                storage.touch(acc, ip);
                player.sendMessage(plugin.msg("trusted"));
                return;
            }
        }

        if (acc != null && acc.frozen) {
            Bukkit.getScheduler().runTask(plugin, () -> player.kickPlayer(plugin.msg("frozen")));
            return;
        }

        Session s = freeze(player, ip);

        if (acc == null) {
            // Регистрация: выдаём код
            s.registering = true;
            s.code = newCode();
            byCode.put(s.code, player.getUniqueId());
            s.deadline = System.currentTimeMillis()
                    + plugin.getConfig().getInt("auth.code-lifetime-seconds", 300) * 1000L;

            String bot = plugin.getConfig().getString("telegram.bot-username", "bot");
            player.sendMessage(plugin.msg("register-1"));
            player.sendMessage(plugin.msg("register-2", "bot", bot));
            player.sendMessage(plugin.msg("register-3", "code", s.code));
            title(player, plugin.msg("title-register"), plugin.msg("subtitle-code", "code", s.code));
        } else {
            // Подтверждение входа с нового IP
            s.registering = false;
            s.requestId = UUID.randomUUID().toString().substring(0, 12);
            byRequest.put(s.requestId, player.getUniqueId());
            s.deadline = System.currentTimeMillis()
                    + plugin.getConfig().getInt("auth.confirm-timeout-seconds", 60) * 1000L;

            player.sendMessage(plugin.msg("confirm-1"));
            title(player, plugin.msg("title-confirm"), plugin.msg("subtitle-confirm"));
            telegram.sendConfirm(acc.tgId, player.getName(), s.ip, s.requestId);
        }
    }

    public void onQuit(UUID uuid) {
        Session s = sessions.remove(uuid);
        if (s == null) return;
        if (s.code != null) byCode.remove(s.code);
        if (s.requestId != null) byRequest.remove(s.requestId);
    }

    // ─────────────────────────────────────────────── вызовы из бота

    /** Бот прислал код регистрации. Возвращает ник или null. */
    public String linkByCode(String code, long tgId, String tgName) {
        if (code == null) return null;
        UUID uuid = byCode.get(code.toUpperCase().trim());
        if (uuid == null) return null;
        Session s = sessions.get(uuid);
        if (s == null) return null;
        if (storage.byTelegram(tgId) != null) return "@ALREADY";

        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return null;

        Storage.Account acc = storage.link(uuid, p.getName(), tgId, tgName);
        storage.touch(acc, s.ip);

        Bukkit.getScheduler().runTask(plugin, () -> {
            release(p);
            p.sendMessage(plugin.msg("linked", "tg", tgName));
        });
        return p.getName();
    }

    /** Бот прислал ответ на кнопку подтверждения. */
    public boolean answerConfirm(String requestId, boolean approve, long tgId) {
        UUID uuid = byRequest.get(requestId);
        if (uuid == null) return false;
        Storage.Account acc = storage.byUuid(uuid);
        if (acc == null || acc.tgId != tgId) return false;   // чужой не может подтвердить

        Session s = sessions.get(uuid);
        if (s == null) return false;
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return false;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (approve) {
                storage.touch(acc, s.ip);
                release(p);
                p.sendMessage(plugin.msg("approved"));
            } else {
                sessions.remove(uuid);
                byRequest.remove(requestId);
                p.kickPlayer(plugin.msg("denied"));
            }
        });
        return true;
    }

    // ─────────────────────────────────────────────────────── тайминг

    public void tick() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            if (now < s.deadline) {
                Player p = Bukkit.getPlayer(s.uuid);
                if (p != null && s.registering) {
                    long left = (s.deadline - now) / 1000;
                    p.sendActionBar(plugin.msg("actionbar-code",
                            "code", s.code, "sec", String.valueOf(left)));
                }
                continue;
            }
            Player p = Bukkit.getPlayer(s.uuid);
            onQuit(s.uuid);
            if (p != null) p.kickPlayer(plugin.msg(s.registering ? "code-expired" : "confirm-timeout"));
        }
    }

    // ─────────────────────────────────────────────────── заморозка

    private Session freeze(Player p, String ip) {
        Session s = new Session();
        s.uuid = p.getUniqueId();
        s.name = p.getName();
        s.ip = ip;
        s.spawn = p.getLocation().clone();
        s.gameMode = p.getGameMode();
        s.walkSpeed = p.getWalkSpeed();
        sessions.put(s.uuid, s);

        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        p.setInvulnerable(true);
        if (plugin.getConfig().getBoolean("auth.blindness", true)) {
            PotionEffectType blind = PotionEffectType.getByName("BLINDNESS");
            if (blind != null) p.addPotionEffect(new PotionEffect(blind, Integer.MAX_VALUE, 1, false, false));
        }
        return s;
    }

    /** Снять заморозку и пустить в игру. */
    public void release(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) {
            if (s.code != null) byCode.remove(s.code);
            if (s.requestId != null) byRequest.remove(s.requestId);
            p.setWalkSpeed(s.walkSpeed);
        } else {
            p.setWalkSpeed(0.2f);
        }
        p.setFlySpeed(0.1f);
        p.setInvulnerable(false);
        PotionEffectType blind = PotionEffectType.getByName("BLINDNESS");
        if (blind != null) p.removePotionEffect(blind);
        p.sendActionBar("");
    }

    private String newCode() {
        int len = Math.max(4, Math.min(10, plugin.getConfig().getInt("auth.code-length", 6)));
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
            code = sb.toString();
        } while (byCode.containsKey(code));
        return code;
    }

    private void title(Player p, String a, String b) {
        try {
            p.sendTitle(a, b, 10, 20 * 3600, 10);
        } catch (Throwable ignored) {
        }
    }
}
