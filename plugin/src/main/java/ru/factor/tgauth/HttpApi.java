package ru.factor.tgauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Маленький HTTP-сервер для бота. Слушает только localhost и требует общий токен,
 * поэтому наружу ничего не торчит.
 */
public class HttpApi {

    private final TgAuth plugin;
    private final AuthManager auth;
    private HttpServer server;

    public HttpApi(TgAuth plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    public void start() throws IOException {
        String bind = plugin.getConfig().getString("api.bind", "127.0.0.1");
        int port = plugin.getConfig().getInt("api.port", 8787);

        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));

        server.createContext("/api/ping",    this::ping);
        server.createContext("/api/link",    this::link);
        server.createContext("/api/confirm", this::confirm);
        server.createContext("/api/unlink",  this::unlink);
        server.createContext("/api/status",  this::status);

        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ───────────────────────────────────────────────────── хендлеры

    private void ping(HttpExchange x) throws IOException {
        if (!authorized(x)) return;
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("online", Bukkit.getOnlinePlayers().size());
        o.addProperty("linked", plugin.storage().size());
        send(x, 200, o);
    }

    private void link(HttpExchange x) throws IOException {
        if (!authorized(x)) return;
        JsonObject in = body(x);
        if (in == null) { fail(x, "плохой JSON"); return; }

        String code   = str(in, "code");
        long tgId     = in.has("tg_id") ? in.get("tg_id").getAsLong() : 0L;
        String tgName = str(in, "tg_name");
        if (code.isEmpty() || tgId == 0) { fail(x, "нужны code и tg_id"); return; }

        String name = auth.linkByCode(code, tgId, tgName);
        JsonObject o = new JsonObject();
        if ("@ALREADY".equals(name)) {
            o.addProperty("ok", false);
            o.addProperty("error", "already_linked");
        } else if (name == null) {
            o.addProperty("ok", false);
            o.addProperty("error", "bad_code");
        } else {
            o.addProperty("ok", true);
            o.addProperty("player", name);
        }
        send(x, 200, o);
    }

    private void confirm(HttpExchange x) throws IOException {
        if (!authorized(x)) return;
        JsonObject in = body(x);
        if (in == null) { fail(x, "плохой JSON"); return; }

        String req    = str(in, "request");
        boolean ok    = in.has("approve") && in.get("approve").getAsBoolean();
        long tgId     = in.has("tg_id") ? in.get("tg_id").getAsLong() : 0L;

        boolean done = auth.answerConfirm(req, ok, tgId);
        JsonObject o = new JsonObject();
        o.addProperty("ok", done);
        if (!done) o.addProperty("error", "expired");
        send(x, 200, o);
    }

    private void unlink(HttpExchange x) throws IOException {
        if (!authorized(x)) return;
        JsonObject in = body(x);
        if (in == null) { fail(x, "плохой JSON"); return; }
        long tgId = in.has("tg_id") ? in.get("tg_id").getAsLong() : 0L;

        Storage.Account acc = plugin.storage().byTelegram(tgId);
        JsonObject o = new JsonObject();
        if (acc == null) {
            o.addProperty("ok", false);
            o.addProperty("error", "not_linked");
        } else {
            String name = acc.name;
            UUID uuid = acc.uuid;
            plugin.storage().unlink(tgId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                var p = Bukkit.getPlayer(uuid);
                if (p != null) p.kickPlayer(plugin.msg("unlinked-kick"));
            });
            o.addProperty("ok", true);
            o.addProperty("player", name);
        }
        send(x, 200, o);
    }

    private void status(HttpExchange x) throws IOException {
        if (!authorized(x)) return;
        JsonObject in = body(x);
        long tgId = in != null && in.has("tg_id") ? in.get("tg_id").getAsLong() : 0L;
        Storage.Account acc = plugin.storage().byTelegram(tgId);

        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("linked", acc != null);
        if (acc != null) {
            o.addProperty("player", acc.name);
            o.addProperty("last_ip", mask(acc.lastIp));
            o.addProperty("last_login", acc.lastLogin);
            o.addProperty("online", Bukkit.getPlayer(acc.uuid) != null);
        }
        send(x, 200, o);
    }

    // ───────────────────────────────────────────────────── утилиты

    private boolean authorized(HttpExchange x) throws IOException {
        String expected = plugin.getConfig().getString("api.token", "");
        String got = x.getRequestHeaders().getFirst("X-Token");
        if (expected.isEmpty() || !expected.equals(got)) {
            send(x, 403, err("forbidden"));
            return false;
        }
        return true;
    }

    private JsonObject body(HttpExchange x) {
        try {
            String raw = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) return new JsonObject();
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private void fail(HttpExchange x, String why) throws IOException {
        send(x, 400, err(why));
    }

    private JsonObject err(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", why);
        return o;
    }

    private String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private void send(HttpExchange x, int code, JsonObject body) throws IOException {
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(code, data.length);
        try (OutputStream os = x.getResponseBody()) {
            os.write(data);
        }
    }

    /** В Telegram шлём IP не полностью. */
    static String mask(String ip) {
        if (ip == null || ip.isEmpty()) return "";
        String[] p = ip.split("\\.");
        return p.length == 4 ? p[0] + "." + p[1] + ".*.*" : ip;
    }
}
