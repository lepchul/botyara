package ru.factor.tgauth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Плагин сам общается с Telegram через long polling.
 * Внешний бот и открытые порты не нужны — только исходящий HTTPS,
 * который разрешён на любом хостинге.
 */
public class TelegramPoller implements Runnable {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9]{4,10}$");

    private final TgAuth plugin;
    private final AuthManager auth;
    private volatile boolean running = false;
    private Thread thread;
    private long offset = 0;

    public TelegramPoller(TgAuth plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "TgAuth-Poller");
        thread.setDaemon(true);
        thread.start();
        plugin.getLogger().info("Telegram: режим прямого опроса, внешний бот не нужен.");
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        int failures = 0;
        while (running) {
            try {
                JsonObject res = call("getUpdates",
                        "{\"offset\":" + offset + ",\"timeout\":25,"
                        + "\"allowed_updates\":[\"message\",\"callback_query\"]}");
                failures = 0;
                if (res == null || !res.get("ok").getAsBoolean()) continue;
                JsonArray updates = res.getAsJsonArray("result");
                for (JsonElement el : updates) {
                    JsonObject u = el.getAsJsonObject();
                    offset = u.get("update_id").getAsLong() + 1;
                    try {
                        if (u.has("message")) handleMessage(u.getAsJsonObject("message"));
                        else if (u.has("callback_query")) handleCallback(u.getAsJsonObject("callback_query"));
                    } catch (Exception e) {
                        plugin.getLogger().warning("Ошибка обработки апдейта: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                if (!running) return;
                failures++;
                long wait = Math.min(60, (long) Math.pow(2, Math.min(failures, 6)));
                plugin.getLogger().warning("Telegram недоступен (" + e.getMessage()
                        + "), повтор через " + wait + " с.");
                try { Thread.sleep(wait * 1000L); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    // ─────────────────────────────────────────────────── обработка

    private void handleMessage(JsonObject m) {
        if (!m.has("text") || !m.has("from")) return;
        JsonObject from = m.getAsJsonObject("from");
        if (from.has("is_bot") && from.get("is_bot").getAsBoolean()) return;

        long chatId = m.getAsJsonObject("chat").get("id").getAsLong();
        long userId = from.get("id").getAsLong();
        String userName = from.has("username")
                ? "@" + from.get("username").getAsString()
                : from.get("first_name").getAsString();
        String text = m.get("text").getAsString().trim();

        // Забаненным бот не отвечает ничем, кроме причины отказа
        String deny = auth.denyReason(userId);
        if (deny != null) {
            send(chatId, deny);
            return;
        }

        // /start CODE — вход по ссылке t.me/bot?start=CODE
        if (text.startsWith("/start")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length == 2 && CODE.matcher(parts[1]).matches()) {
                tryLink(chatId, userId, userName, parts[1]);
            } else {
                sendGreeting(chatId);
            }
            return;
        }
        if (text.startsWith("/help")) { sendGreeting(chatId); return; }
        if (text.startsWith("/status")) { sendStatus(chatId, userId); return; }
        if (text.startsWith("/unlink")) { doUnlink(chatId, userId); return; }

        if (CODE.matcher(text).matches()) {
            tryLink(chatId, userId, userName, text);
        } else {
            send(chatId, "Пришлите код из игры — он выглядит так: <code>K7F2QX</code>");
        }
    }

    private void handleCallback(JsonObject q) {
        String id = q.get("id").getAsString();
        String data = q.has("data") ? q.get("data").getAsString() : "";
        long userId = q.getAsJsonObject("from").get("id").getAsLong();
        long chatId = q.has("message")
                ? q.getAsJsonObject("message").getAsJsonObject("chat").get("id").getAsLong()
                : userId;

        String deny = auth.denyReason(userId);
        if (deny != null) {
            answerCallback(id, "Доступ закрыт");
            send(chatId, deny);
            return;
        }

        if (data.startsWith("ok:") || data.startsWith("no:")) {
            boolean approve = data.startsWith("ok:");
            boolean done = auth.answerConfirm(data.substring(3), approve, userId);
            answerCallback(id, done ? (approve ? "Вход разрешён" : "Вход отклонён") : "Запрос устарел");
            if (done) {
                send(chatId, approve
                        ? "\u2705 Вход подтверждён."
                        : "\u26D4 Вход отклонён, игрока выкинуло с сервера.\n\n"
                          + "Если это были не вы — сообщите администратору сервера.");
            } else {
                send(chatId, "\u231B Запрос устарел. Зайдите на сервер заново.");
            }
            return;
        }

        if (data.equals("unlink:yes")) {
            answerCallback(id, "");
            doUnlink(chatId, userId);
            return;
        }
        if (data.equals("unlink:no")) {
            answerCallback(id, "Отменено");
            send(chatId, "Отменено.");
            return;
        }
        answerCallback(id, "");
    }

    // ─────────────────────────────────────────────────── действия

    private void tryLink(long chatId, long userId, String userName, String code) {
        String result = auth.linkByCode(code.toUpperCase(), userId, userName);
        if ("@BLOCKED".equals(result)) {
            send(chatId, plugin.msg("tg-blocked",
                    "reason", plugin.storage().blockReason(userId)));
        } else if ("@ALREADY".equals(result)) {
            send(chatId, "\u274C К вашему Telegram уже привязан аккаунт.\n"
                    + "Сначала отвяжите: /unlink");
        } else if (result == null) {
            send(chatId, "\u274C Код неверный или уже истёк.\n"
                    + "Зайдите на сервер заново и получите новый.");
        } else {
            send(chatId, "\u2705 Аккаунт <code>" + esc(result) + "</code> привязан.\n\n"
                    + "Вы уже в игре. Дальше вход будет без кода.");
            plugin.getLogger().info("Привязан " + result + " -> " + userName);
        }
    }

    private void sendGreeting(long chatId) {
        String server = plugin.getConfig().getString("telegram.server-name", "сервер");
        String ip = plugin.getConfig().getString("telegram.server-ip", "");
        send(chatId,
                "\uD83D\uDC4B Это бот авторизации <b>" + esc(server) + "</b>.\n\n"
                + "<b>Как войти:</b>\n"
                + "1. Зайдите на сервер" + (ip.isEmpty() ? "" : " <code>" + esc(ip) + "</code>") + "\n"
                + "2. Игра покажет код — пришлите его сюда\n"
                + "3. Готово, аккаунт привязан\n\n"
                + "Дальше вход автоматический. При заходе с чужого IP я спрошу "
                + "подтверждение, и без вашей кнопки в игру не пустит.\n\n"
                + "/status — состояние привязки\n"
                + "/unlink — отвязать аккаунт");
    }

    private void sendStatus(long chatId, long userId) {
        Storage.Account acc = plugin.storage().byTelegram(userId);
        if (acc == null) {
            send(chatId, "Аккаунт не привязан.\nЗайдите на сервер и пришлите код сюда.");
            return;
        }
        boolean online = org.bukkit.Bukkit.getPlayer(acc.uuid) != null;
        send(chatId, "<b>Привязка активна</b>\n\n"
                + "Ник: <code>" + esc(acc.name) + "</code>\n"
                + "Статус: " + (online ? "\uD83D\uDFE2 в игре" : "\u26AA не в сети") + "\n"
                + "Последний IP: <code>" + esc(HttpApi.mask(acc.lastIp)) + "</code>");
    }

    private void doUnlink(long chatId, long userId) {
        Storage.Account acc = plugin.storage().byTelegram(userId);
        if (acc == null) {
            send(chatId, "У вас нет привязанного аккаунта.");
            return;
        }
        // Иначе забаненный отвязался бы и зарегистрировал новый ник
        if (auth.isBanned(acc)) {
            plugin.storage().block(userId, "бан аккаунта " + acc.name);
            send(chatId, plugin.msg("tg-blocked", "reason", "бан аккаунта " + acc.name));
            return;
        }
        String name = acc.name;
        java.util.UUID uuid = acc.uuid;
        plugin.storage().unlink(userId);
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            var p = org.bukkit.Bukkit.getPlayer(uuid);
            if (p != null) p.kickPlayer(plugin.msg("unlinked-kick"));
        });
        send(chatId, "Аккаунт <code>" + esc(name) + "</code> отвязан.");
    }

    // ─────────────────────────────────────────────────── транспорт

    private void send(long chatId, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("chat_id", chatId);
        o.addProperty("text", text);
        o.addProperty("parse_mode", "HTML");
        callAsync("sendMessage", o.toString());
    }

    private void answerCallback(String id, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("callback_query_id", id);
        if (!text.isEmpty()) o.addProperty("text", text);
        callAsync("answerCallbackQuery", o.toString());
    }

    private void callAsync(String method, String body) {
        new Thread(() -> {
            try { call(method, body); } catch (Exception ignored) { }
        }, "TgAuth-Send").start();
    }

    private JsonObject call(String method, String body) throws Exception {
        String token = plugin.getConfig().getString("telegram.bot-token", "");
        if (token.isEmpty()) throw new IllegalStateException("не задан bot-token");

        URL url = new URL("https://api.telegram.org/bot" + token + "/" + method);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(35_000);          // больше, чем timeout у long polling
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        var stream = code == 200 ? c.getInputStream() : c.getErrorStream();
        String raw = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        c.disconnect();
        if (code != 200) {
            if (code == 409) {
                plugin.getLogger().severe("Telegram 409: этот бот уже опрашивается другой программой. "
                        + "Остановите второго бота или заведите отдельный токен.");
            }
            throw new IllegalStateException("HTTP " + code + " " + raw);
        }
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
