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
            s.sendMessage("\u00A77Привязок в базе: \u00A7f" + storage.size());
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
                }
            }
            case "unlink" -> {
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник."); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                Storage.Account acc = storage.byUuid(op.getUniqueId());
                if (acc == null) {
                    s.sendMessage("\u00A7cПривязки нет.");
                } else {
                    storage.unlink(acc.tgId);
                    Player p = Bukkit.getPlayer(op.getUniqueId());
                    if (p != null) p.kickPlayer(plugin.msg("unlinked-kick"));
                    s.sendMessage("\u00A7aПривязка снята: " + acc.name);
                }
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

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) return Arrays.asList("reload", "info", "unlink", "force", "unstick");
        if (a.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return List.of();
    }
}
