package ru.factor.tgauth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TgAuth — авторизация на сервере через Telegram.
 * Пароля не существует: личность подтверждается привязанным аккаунтом Telegram.
 */
public class TgAuth extends JavaPlugin {

    private Storage storage;
    private AuthManager auth;
    private Telegram telegram;
    private HttpApi api;
    private TelegramPoller poller;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        storage  = new Storage(this);
        telegram = new Telegram(this);
        auth     = new AuthManager(this, storage, telegram);

        getServer().getPluginManager().registerEvents(new AuthListener(this, auth), this);
        AuthCommand cmd = new AuthCommand(this, auth, storage);
        if (getCommand("tgauth") != null) {
            getCommand("tgauth").setExecutor(cmd);
            getCommand("tgauth").setTabCompleter(cmd);
        }

        String mode = getConfig().getString("telegram.mode", "polling").toLowerCase();

        if (mode.equals("polling")) {
            // Плагин сам общается с Telegram. Ничего открывать наружу не нужно.
            poller = new TelegramPoller(this, auth);
            poller.start();
        } else {
            // Внешний бот стучится к нам по HTTP.
            api = new HttpApi(this, auth);
            try {
                api.start();
            } catch (Exception e) {
                getLogger().severe("Не удалось поднять HTTP API: " + e.getMessage());
                getLogger().severe("Плагин выключается, иначе игроки не смогут войти.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("Режим внешнего бота. API: "
                    + getConfig().getString("api.bind", "127.0.0.1") + ":" + getConfig().getInt("api.port", 8787));
        }

        // Тик проверки таймаутов раз в секунду
        Bukkit.getScheduler().runTaskTimer(this, auth::tick, 20L, 20L);

        if (getConfig().getString("telegram.bot-token", "").isEmpty()) {
            getLogger().warning("В config.yml не задан telegram.bot-token — авторизация работать не будет.");
        }

        getLogger().info("TgAuth запущен.");
    }

    @Override
    public void onDisable() {
        if (poller != null) poller.stop();
        if (api != null) api.stop();
        if (storage != null) storage.save();
        getLogger().info("TgAuth остановлен.");
    }

    public Storage storage()   { return storage; }
    public AuthManager auth()  { return auth; }
    public Telegram telegram() { return telegram; }

    /** Цветной текст из config.yml с подстановкой плейсхолдеров. */
    public String msg(String path, String... kv) {
        String s = getConfig().getString("messages." + path, path);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s.replace('&', '\u00A7');
    }
}
