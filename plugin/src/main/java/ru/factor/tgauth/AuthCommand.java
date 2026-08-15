package ru.factor.tgauth;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class AuthCommand implements CommandExecutor, TabCompleter {

    private final TgAuth plugin;
    private final AuthManager auth;
    private final Storage storage;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    public AuthCommand(TgAuth plugin, AuthManager auth, Storage storage) {
        this.plugin = plugin;
        this.auth = auth;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("tgauth.admin")) {
            s.sendMessage("\u00A7cНет прав.");
            return true;
        }
        if (a.length == 0) {
            s.sendMessage("\u00A7e/tgauth reload \u00A77— перечитать конфиг");
            s.sendMessage("\u00A7e/tgauth info <ник> \u00A77— кто привязан");
            s.sendMessage("\u00A7e/tgauth unlink <ник> \u00A77— снять привязку");
            s.sendMessage("\u00A7e/tgauth force <ник> \u00A77— пустить без подтверждения");
            s.sendMessage("\u00A7e/tgauth unstick <ник> \u00A77— вернуть движение, если игрок застрял");
            s.sendMessage("\u00A7e/tgauth block <ник|tgID> [причина] \u00A77— закрыть доступ к боту");
            s.sendMessage("\u00A7e/tgauth unblock <ник|tgID> \u00A77— вернуть доступ");
            s.sendMessage("\u00A77Привязок: \u00A7f" + storage.size()
                    + " \u00A77· заблокировано: \u00A7f" + storage.blockedCount());
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                storage.load();
                s.sendMessage("\u00A7aКонфиг и база перечитаны.");
            }
            case "info" -> {
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник."); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                Storage.Account acc = storage.byUuid(op.getUniqueId());
                if (acc == null) {
                    s.sendMessage("\u00A7cПривязки нет.");
                } else {
                    s.sendMessage("\u00A77Ник: \u00A7f" + acc.name);
                    s.sendMessage("\u00A77Telegram: \u00A7f" + acc.tgName + " \u00A78(" + acc.tgId + ")");
                    s.sendMessage("\u00A77Последний IP: \u00A7f" + acc.lastIp);
                    s.sendMessage("\u00A77Последний вход: \u00A7f"
                            + (acc.lastLogin == 0 ? "—" : fmt.format(new Date(acc.lastLogin))));
                    s.sendMessage("\u00A77Привязан: \u00A7f"
                            + (acc.linkedAt == 0 ? "—" : fmt.format(new Date(acc.linkedAt))));
                    s.sendMessage("\u00A77Бан: \u00A7f" + (auth.isBanned(acc) ? "\u00A7cда" : "нет")
                            + " \u00A77· доступ к боту: \u00A7f"
                            + (storage.isBlocked(acc.tgId) ? "\u00A7cзакрыт" : "открыт"));
                }
            }
            case "unlink" -> {
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник."); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                Storage.Account acc = storage.byUuid(op.getUniqueId());
                if (acc == null) {
                    s.sendMessage("\u00A7cПривязки нет.");
                } else {
                    if (auth.isBanned(acc)) {
                        storage.block(acc.tgId, "бан аккаунта " + acc.name);
                        s.sendMessage("\u00A7eИгрок забанен — его Telegram тоже заблокирован.");
                    }
                    storage.unlink(acc.tgId);
                    Player p = Bukkit.getPlayer(op.getUniqueId());
                    if (p != null) p.kickPlayer(plugin.msg("unlinked-kick"));
                    s.sendMessage("\u00A7aПривязка снята: " + acc.name);
                }
            }
            case "block" -> {
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник или Telegram ID."); return true; }
                Long tg = resolveTg(a[1]);
                if (tg == null) { s.sendMessage("\u00A7cНе нашёл привязку."); return true; }
                String reason = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length))
                        : "заблокирован администратором";
                storage.block(tg, reason);
                s.sendMessage("\u00A7aTelegram " + tg + " заблокирован: " + reason);
            }
            case "unblock" -> {
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник или Telegram ID."); return true; }
                Long tg = resolveTg(a[1]);
                if (tg == null) { s.sendMessage("\u00A7cНе нашёл привязку."); return true; }
                s.sendMessage(storage.unblock(tg)
                        ? "\u00A7aTelegram " + tg + " разблокирован."
                        : "\u00A77Он и не был заблокирован.");
            }
            case "unstick" -> {
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник."); return true; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { s.sendMessage("\u00A7cИгрок не в сети."); return true; }
                auth.unstick(p);
                p.sendMessage("\u00A7aДвижение восстановлено.");
                s.sendMessage("\u00A7aРазблокирован: " + p.getName());
            }
            case "force" -> {
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник."); return true; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { s.sendMessage("\u00A7cИгрок не в сети."); return true; }
                auth.release(p);
                p.sendMessage(plugin.msg("approved"));
                s.sendMessage("\u00A7aПущен: " + p.getName());
            }
            default -> s.sendMessage("\u00A7cНеизвестная команда.");
        }
        return true;
    }

    /** Принимает и ник, и числовой Telegram ID. */
    private Long resolveTg(String arg) {
        try {
            return Long.parseLong(arg);
        } catch (NumberFormatException ignored) {
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(arg);
        Storage.Account acc = storage.byUuid(op.getUniqueId());
        return acc == null ? null : acc.tgId;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) return Arrays.asList("reload", "info", "unlink", "force", "unstick", "block", "unblock");
        if (a.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return List.of();
    }
}
