package ru.factor.tgauth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Плагин сам стучится в Bot API: так подтверждение прилетает мгновенно, без опроса. */
public class Telegram {

    private final TgAuth plugin;

    public Telegram(TgAuth plugin) {
        this.plugin = plugin;
    }

    /** Сообщение с кнопками «Это я» / «Не я». */
    public void sendConfirm(long chatId, String player, String ip, String requestId) {
        String text = "\uD83D\uDD10 <b>Вход в игру</b>\n\n"
                + "Ник: <code>" + esc(player) + "</code>\n"
                + "IP: <code>" + esc(HttpApi.mask(ip)) + "</code>\n"
                + "Сервер: " + esc(plugin.getConfig().getString("telegram.server-name", "Minecraft")) + "\n\n"
                + "Это вы?";

        JsonObject yes = new JsonObject();
        yes.addProperty("text", "\u2705 Это я");
        yes.addProperty("callback_data", "ok:" + requestId);

        JsonObject no = new JsonObject();
        no.addProperty("text", "\u26D4 Не я");
        no.addProperty("callback_data", "no:" + requestId);

        JsonArray row = new JsonArray();
        row.add(yes);
        row.add(no);
        JsonArray keyboard = new JsonArray();
        keyboard.add(row);

        JsonObject markup = new JsonObject();
        markup.add("inline_keyboard", keyboard);

        JsonObject payload = new JsonObject();
        payload.addProperty("chat_id", chatId);
        payload.addProperty("text", text);
        payload.addProperty("parse_mode", "HTML");
        payload.add("reply_markup", markup);

        call("sendMessage", payload);
    }

    public void notify(long chatId, String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("chat_id", chatId);
        payload.addProperty("text", text);
        payload.addProperty("parse_mode", "HTML");
        call("sendMessage", payload);
    }

    private void call(String method, JsonObject payload) {
        String token = plugin.getConfig().getString("telegram.bot-token", "");
        if (token.isEmpty()) {
            plugin.getLogger().warning("bot-token пуст — сообщение в Telegram не отправлено.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URL("https://api.telegram.org/bot" + token + "/" + method);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setDoOutput(true);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
                int code = c.getResponseCode();
                if (code != 200) {
                    plugin.getLogger().warning("Telegram ответил " + code + " на " + method);
                }
                c.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка обращения к Telegram: " + e.getMessage());
            }
        });
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
