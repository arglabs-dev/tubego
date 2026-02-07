# Telegram Bot Configuration Guide

This guide explains how to set up the credentials required for the TubeGo Hybrid Bot.

## 1. Create the Bot (BotFather)

1. Open Telegram and search for **`@BotFather`**.
2. Send the command `/newbot`.
3. Follow the instructions to name your bot.
4. **Copy the HTTP API Token**.

## 2. Get your User ID (Security)

To prevent unauthorized access, the bot is restricted to your personal ID.

1. Search for **`@userinfobot`** on Telegram.
2. Send `/start`.
3. **Copy your numeric ID** (e.g., `34678180`).

## 3. Get Userbot Credentials (Telethon)

This allows the bot to upload files larger than 50MB (up to 2GB).

1. Go to **[my.telegram.org](https://my.telegram.org)**.
2. Login with your phone number.
3. Click on **"API Development Tools"**.
4. Create a new app (Name can be anything, e.g., "TubeGo Uploader").
5. **Copy the `App api_id` and `App api_hash`**.

## 4. Setup `.env`

Create a file named `.env` in the project root and fill it with your data:

```env
TELEGRAM_TOKEN=your_bot_token_here
ALLOWED_USER_ID=your_numeric_id
API_ID=your_app_id
API_HASH=your_app_hash
```

## 5. First Run Authentication

The first time you run the bot or the setup script, it will ask for a login code to authorize the Userbot session.

```bash
# Generate session file
python setup_session.py
```

Follow the prompts. Once `user_session.session` is created, the bot is ready.