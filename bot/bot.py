# -*- coding: utf-8 -*-
"""
TgAuth Bot — телеграм-половина авторизации на Minecraft-сервере.

Что делает:
  • принимает код из игры и привязывает аккаунт
  • обрабатывает кнопки «Это я / Не я» при входе с нового IP
  • /status, /unlink

Плагин слушает HTTP на localhost, бот к нему стучится.
Запуск:  python bot.py
"""

import asyncio
import json
import logging
import os
import re
import sys

import httpx
from telegram import InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.constants import ParseMode
from telegram.ext import (
    Application,
    CallbackQueryHandler,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

# ── конфиг ──────────────────────────────────────────────────────────────
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")

DEFAULT_CONFIG = {
    "bot_token": "",
    "api_url": "http://127.0.0.1:8787",
    "api_token": "СМЕНИТЕ_МЕНЯ_НА_ДЛИННУЮ_СЛУЧАЙНУЮ_СТРОКУ",
    "server_name": "Мой сервер",
    "server_ip": "play.example.ru",
}


def load_config() -> dict:
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(DEFAULT_CONFIG, f, ensure_ascii=False, indent=2)
        print(f"Создан {CONFIG_PATH} — впишите bot_token и api_token, затем запустите снова.")
        sys.exit(1)
    with open(CONFIG_PATH, encoding="utf-8") as f:
        cfg = json.load(f)
    for k, v in DEFAULT_CONFIG.items():
        cfg.setdefault(k, v)
    if not cfg["bot_token"]:
        print("В config.json пустой bot_token.")
        sys.exit(1)
    return cfg


CFG = load_config()

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(message)s",
    level=logging.INFO,
    datefmt="%H:%M:%S",
)
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("tgauth")

CODE_RE = re.compile(r"^[A-Z0-9]{4,10}$")


# ── обращение к плагину ─────────────────────────────────────────────────
async def api(endpoint: str, payload: dict) -> dict:
    url = CFG["api_url"].rstrip("/") + "/api/" + endpoint
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.post(url, json=payload, headers={"X-Token": CFG["api_token"]})
            if r.status_code == 403:
                return {"ok": False, "error": "forbidden"}
            return r.json()
    except httpx.ConnectError:
        return {"ok": False, "error": "offline"}
    except Exception as e:  # noqa: BLE001
        log.warning("api %s: %s", endpoint, e)
        return {"ok": False, "error": "api_error"}


def api_error_text(err: str) -> str:
    return {
        "offline": "⚠️ Сервер сейчас недоступен. Попробуйте позже.",
        "forbidden": "⚠️ Бот и плагин не сходятся по токену. Напишите администратору.",
        "bad_code": "❌ Код неверный или уже истёк.\nЗайдите на сервер заново и получите новый.",
        "already_linked": "❌ К вашему Telegram уже привязан аккаунт.\nСначала отвяжите: /unlink",
        "not_linked": "У вас нет привязанного аккаунта.",
        "expired": "⌛ Запрос устарел. Зайдите на сервер заново.",
    }.get(err, "⚠️ Что-то пошло не так. Попробуйте ещё раз.")


# ── команды ─────────────────────────────────────────────────────────────
async def cmd_start(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    arg = ctx.args[0].upper() if ctx.args else None
    if arg and CODE_RE.match(arg):
        await handle_code(update, arg)
        return

    await update.message.reply_text(
        f"👋 Это бот авторизации <b>{CFG['server_name']}</b>.\n\n"
        f"<b>Как войти:</b>\n"
        f"1. Зайдите на сервер <code>{CFG['server_ip']}</code>\n"
        f"2. Игра покажет код — пришлите его сюда одним сообщением\n"
        f"3. Готово, аккаунт привязан\n\n"
        f"Дальше вход будет автоматическим. Если зайдут с чужого IP — "
        f"я спрошу подтверждение, и без вашей кнопки в игру не пустит.\n\n"
        f"/status — состояние привязки\n"
        f"/unlink — отвязать аккаунт",
        parse_mode=ParseMode.HTML,
    )


async def cmd_status(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    res = await api("status", {"tg_id": update.effective_user.id})
    if not res.get("ok"):
        await update.message.reply_text(api_error_text(res.get("error", "")))
        return
    if not res.get("linked"):
        await update.message.reply_text(
            "Аккаунт не привязан.\nЗайдите на сервер и пришлите код сюда."
        )
        return
    online = "🟢 в игре" if res.get("online") else "⚪ не в сети"
    await update.message.reply_text(
        f"<b>Привязка активна</b>\n\n"
        f"Ник: <code>{res.get('player')}</code>\n"
        f"Статус: {online}\n"
        f"Последний IP: <code>{res.get('last_ip') or '—'}</code>",
        parse_mode=ParseMode.HTML,
    )


async def cmd_unlink(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    kb = InlineKeyboardMarkup([[
        InlineKeyboardButton("Да, отвязать", callback_data="unlink:yes"),
        InlineKeyboardButton("Отмена", callback_data="unlink:no"),
    ]])
    await update.message.reply_text(
        "Отвязать аккаунт? После этого вход в игру будет заново через код.",
        reply_markup=kb,
    )


async def cmd_help(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    await cmd_start(update, ctx)


# ── код из игры ─────────────────────────────────────────────────────────
async def on_text(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    text = (update.message.text or "").strip().upper()
    if not CODE_RE.match(text):
        await update.message.reply_text(
            "Пришлите код из игры — он выглядит так: <code>K7F2QX</code>",
            parse_mode=ParseMode.HTML,
        )
        return
    await handle_code(update, text)


async def handle_code(update: Update, code: str):
    user = update.effective_user
    tg_name = ("@" + user.username) if user.username else (user.full_name or str(user.id))

    res = await api("link", {"code": code, "tg_id": user.id, "tg_name": tg_name})
    if res.get("ok"):
        await update.message.reply_text(
            f"✅ Аккаунт <code>{res.get('player')}</code> привязан.\n\n"
            f"Вы уже в игре. Дальше вход будет без кода.",
            parse_mode=ParseMode.HTML,
        )
        log.info("Привязан %s -> %s", res.get("player"), tg_name)
    else:
        await update.message.reply_text(api_error_text(res.get("error", "")))


# ── кнопки ──────────────────────────────────────────────────────────────
async def on_callback(update: Update, ctx: ContextTypes.DEFAULT_TYPE):
    q = update.callback_query
    data = q.data or ""
    uid = q.from_user.id

    if data.startswith("unlink:"):
        if data.endswith("no"):
            await q.answer("Отменено")
            await q.edit_message_text("Отменено.")
            return
        res = await api("unlink", {"tg_id": uid})
        await q.answer()
        if res.get("ok"):
            await q.edit_message_text(f"Аккаунт <code>{res.get('player')}</code> отвязан.",
                                      parse_mode=ParseMode.HTML)
        else:
            await q.edit_message_text(api_error_text(res.get("error", "")))
        return

    if data.startswith(("ok:", "no:")):
        approve = data.startswith("ok:")
        request_id = data.split(":", 1)[1]
        res = await api("confirm", {"request": request_id, "approve": approve, "tg_id": uid})
        await q.answer("Готово" if res.get("ok") else "Запрос устарел")
        if res.get("ok"):
            await q.edit_message_text(
                "✅ Вход подтверждён." if approve else
                "⛔ Вход отклонён, игрока выкинуло с сервера.\n\n"
                "Если это были не вы — смените пароль от Telegram "
                "и напишите администратору сервера.",
            )
        else:
            await q.edit_message_text(api_error_text(res.get("error", "expired")))
        return

    await q.answer()


# ── запуск ──────────────────────────────────────────────────────────────
async def on_error(update: object, ctx: ContextTypes.DEFAULT_TYPE):
    log.error("Ошибка обработчика: %s", ctx.error)


def main():
    app = Application.builder().token(CFG["bot_token"]).build()

    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("help", cmd_help))
    app.add_handler(CommandHandler("status", cmd_status))
    app.add_handler(CommandHandler("unlink", cmd_unlink))
    app.add_handler(CallbackQueryHandler(on_callback))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_text))
    app.add_error_handler(on_error)

    log.info("Бот запущен. API плагина: %s", CFG["api_url"])
    app.run_polling(allowed_updates=Update.ALL_TYPES)


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    main()
