import os
import sys
import subprocess
import logging
import asyncio
import traceback
import concurrent.futures
import speedtest
import urllib.request
import json
import yt_dlp
from dotenv import load_dotenv
from telegram import Update, BotCommand, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.request import HTTPXRequest
from telegram.ext import ApplicationBuilder, ContextTypes, CommandHandler, MessageHandler, CallbackQueryHandler, filters
from src.manager import DownloadManager
from telethon import TelegramClient

load_dotenv()
TOKEN = os.getenv("TELEGRAM_TOKEN")
ALLOWED_USER_ID = os.getenv("ALLOWED_USER_ID")
API_ID = os.getenv("API_ID")
API_HASH = os.getenv("API_HASH")

if not os.path.exists('logs'): os.makedirs('logs')
logging.basicConfig(format='%(asctime)s - %(name)s - %(levelname)s - %(message)s', level=logging.INFO, handlers=[logging.FileHandler("logs/bot.log", encoding='utf-8'), logging.StreamHandler()])
logger = logging.getLogger(__name__)

manager = DownloadManager()
download_executor = concurrent.futures.ThreadPoolExecutor(max_workers=4)
FILE_CACHE = []
DEFAULT_QUALITY = 'ask' 
CURRENT_LANG = 'en'
SESSION_PATH = os.path.join('data', 'user_session')

if not TOKEN or not ALLOWED_USER_ID: logger.error("Error: Missing credentials"); exit(1)
ALLOWED_USER_ID = int(ALLOWED_USER_ID)

STRINGS = {
    'en': {
        'start': "👋 **TubeGo Bot v1.1**\nCommands:\n/language, /quality, /files, /status, /speedtest, /lib", 
        'help': "📚 Guide:\n1. Send link to download.\n2. Auto-Userbot for >50MB files.",
        'status_empty': "📭 No active downloads.", 
        'status_header': "📊 **Status:**\n", 
        'clean_done': "🧹 Cleaned.", 
        'files_empty': "📂 No files.", 
        'files_header': "📂 **Files:**\n", 
        'speedtest_start': "🚀 Testing speed...", 
        'speedtest_error': "❌ Error: {}", 
        'update_check': "📡 Checking updates...", 
        'update_done': "✅ Up to date.", 
        'update_downloaded': "⬇️ Updated. Restarting...", 
        'update_error': "❌ Error: {}", 
        'restart_msg': "🔄 Restarting...", 
        'menu_updated': "✅ Menu updated.", 
        'upload_userbot': "🚀 **Userbot Mode**\n`{}` ({:.1f} MB)", 
        'upload_bot': "📤 **Uploading**\n`{}` ({:.1f} MB)", 
        'upload_success': "✅ **Done**\n`{}`", 
        'upload_userbot_success': "✅ **Done (Userbot)**", 
        'upload_error': "❌ Error: {}", 
        'downloading': "⬇️ **Downloading ({})**\n`{}`", 
        'analyzing': "🔍 **Analyzing...**", 
        'quality_select': "📹 **{}**\n⏱ {}\n👇 Select Quality:", 
        'invalid_link': "⚠️ Invalid link.", 
        'task_init': "⏳ Starting `{}`...", 
        'error_generic': "❌ Error: {}", 
        'cancel_ok': "🛑 Cancelled.", 
        'file_not_found': "❌ Not found.", 
        'confirm_clean_ul': "⚠️ Delete ALL uploaded files?", 
        'clean_ul_success': "🗑️ Deleted `{}` files.", 
        'clean_ul_cancel': "❌ Cancelled.", 
        'lang_select': "🌐 Select Language:", 
        'lang_set': "✅ Language set.", 
        'quality_menu': "⚙️ **Quality**\nCurrent: `{}`", 
        'quality_set': "✅ Saved: **{}**", 
        'quality_selected': "👌 **{} Selected**",
        'lib_check': "🔍 **Checking yt-dlp version...**",
        'lib_info': "📦 **Library Status (yt-dlp)**\n\n🔹 Current: `{}`\n🔸 Latest: `{}`\n\n{}",
        'lib_uptodate': "✅ Up to date.",
        'lib_outdated': "⚠️ **Outdated!** Update recommended.",
        'lib_updating': "⏳ **Updating yt-dlp...**\nThis may take a minute.",
        'lib_updated': "✅ **Library Updated!**\nNew version installed. Restarting system...",
        'lib_error': "❌ Update failed: {}",
        'btn_update_lib': "⬆️ Update Library Now",
        'btn_cancel': "❌ Cancel", 'btn_retry': "🔄 Retry", 'btn_delete': "🗑️ Delete", 'btn_log': "📄 Log", 'btn_retry_ul': "📤 Retry Upload", 'btn_upload_now': "📤 Upload Now", 'btn_delete_server': "🗑️ Delete Server", 'btn_upload': "📤 Upload", 'btn_confirm_clean': "✅ YES", 'btn_cancel_clean': "❌ NO"
    },
    'es': {
        'start': "👋 **TubeGo Bot v1.1**\nComandos:\n/language, /quality, /files, /status, /speedtest, /lib", 
        'help': "📚 Guía:\n1. Envía link.\n2. Userbot auto para >50MB.", 
        'status_empty': "📭 Nada activo.", 
        'status_header': "📊 **Estado:**\n", 
        'clean_done': "🧹 Limpio.", 
        'files_empty': "📂 Vacío.", 
        'files_header': "📂 **Archivos:**\n", 
        'speedtest_start': "🚀 Midiendo...", 
        'speedtest_error': "❌ Error: {}", 
        'update_check': "📡 Buscando...", 
        'update_done': "✅ Al día.", 
        'update_downloaded': "⬇️ Actualizado. Reiniciando...", 
        'update_error': "❌ Error: {}", 
        'restart_msg': "🔄 Reiniciando...", 
        'menu_updated': "✅ Menú act.", 
        'upload_userbot': "🚀 **Modo Userbot**\n`{}` ({:.1f} MB)", 
        'upload_bot': "📤 **Subiendo**\n`{}` ({:.1f} MB)", 
        'upload_success': "✅ **Listo**\n`{}`", 
        'upload_userbot_success': "✅ **Listo (Userbot)**", 
        'upload_error': "❌ Error: {}", 
        'downloading': "⬇️ **Descargando ({})**\n`{}`", 
        'analyzing': "🔍 **Analizando...**", 
        'quality_select': "📹 **{}**\n⏱ {}\n👇 Calidad:", 
        'invalid_link': "⚠️ Link inválido.", 
        'task_init': "⏳ Iniciando `{}`...", 
        'error_generic': "❌ Error: {}", 
        'cancel_ok': "🛑 Cancelado.", 
        'file_not_found': "❌ No encontrado.", 
        'confirm_clean_ul': "⚠️ ¿Borrar TODO subido?", 
        'clean_ul_success': "🗑️ `{}` borrados.", 
        'clean_ul_cancel': "❌ Cancelado.", 
        'lang_select': "🌐 Idioma:", 
        'lang_set': "✅ Idioma listo.", 
        'quality_menu': "⚙️ **Calidad**\nActual: `{}`", 
        'quality_set': "✅ Guardado: **{}**", 
        'quality_selected': "👌 **{} Seleccionado**",
        'lib_check': "🔍 **Verificando versión yt-dlp...**",
        'lib_info': "📦 **Estado Librería (yt-dlp)**\n\n🔹 Actual: `{}`\n🔸 Última: `{}`\n\n{}",
        'lib_uptodate': "✅ Al día.",
        'lib_outdated': "⚠️ **¡Desactualizada!** Se recomienda actualizar.",
        'lib_updating': "⏳ **Actualizando yt-dlp...**\nEspere un momento.",
        'lib_updated': "✅ **¡Librería Actualizada!**\nNueva versión instalada. Reiniciando sistema...",
        'lib_error': "❌ Falló actualización: {}",
        'btn_update_lib': "⬆️ Actualizar Librería",
        'btn_cancel': "❌ Cancelar", 'btn_retry': "🔄 Reintentar", 'btn_delete': "🗑️ Borrar", 'btn_log': "📄 Log", 'btn_retry_ul': "📤 Reintentar Subida", 'btn_upload_now': "📤 Subir Ya", 'btn_delete_server': "🗑️ Borrar Servidor", 'btn_upload': "📤 Subir", 'btn_confirm_clean': "✅ SI", 'btn_cancel_clean': "❌ NO"
    }
}

def T(key, *args): return STRINGS[CURRENT_LANG].get(key, STRINGS['en'].get(key, key)).format(*args) if args else STRINGS[CURRENT_LANG].get(key, STRINGS['en'].get(key, key))
def detect_language(user): global CURRENT_LANG; CURRENT_LANG = 'es' if user and user.language_code and user.language_code.startswith('es') else 'en'

def get_keyboard(task_id, status):
    keyboard = []
    if status in ['starting', 'downloading', 'processing', 'uploading']: keyboard.append([InlineKeyboardButton(T('btn_cancel'), callback_data=f"cancel_{task_id}")])
    elif status == 'failed_dl': keyboard.append([InlineKeyboardButton(T('btn_retry'), callback_data=f"retry_dl_{task_id}"), InlineKeyboardButton(T('btn_delete'), callback_data=f"delete_{task_id}"), InlineKeyboardButton(T('btn_log'), callback_data=f"log_{task_id}")])
    elif status == 'failed_ul': keyboard.append([InlineKeyboardButton(T('btn_retry_ul'), callback_data=f"retry_ul_{task_id}"), InlineKeyboardButton(T('btn_delete'), callback_data=f"delete_{task_id}")])
    elif status == 'success': keyboard.append([InlineKeyboardButton(T('btn_upload_now'), callback_data=f"retry_ul_{task_id}"), InlineKeyboardButton(T('btn_delete'), callback_data=f"delete_{task_id}")])
    elif status == 'completed': keyboard.append([InlineKeyboardButton(T('btn_delete_server'), callback_data=f"delete_{task_id}")])
    return InlineKeyboardMarkup(keyboard)

def get_quality_keyboard(task_id):
    keyboard = [[InlineKeyboardButton("🎥 1080p", callback_data=f"qual_1080_{task_id}"), InlineKeyboardButton("📱 720p", callback_data=f"qual_720_{task_id}")],
                [InlineKeyboardButton("⚡ 480p", callback_data=f"qual_480_{task_id}"), InlineKeyboardButton("🎵 Audio MP3", callback_data=f"qual_audio_{task_id}")],
                [InlineKeyboardButton("🌟 Max (4K)", callback_data=f"qual_best_{task_id}"), InlineKeyboardButton(T('btn_cancel'), callback_data=f"delete_{task_id}")]]
    return InlineKeyboardMarkup(keyboard)

# Commands
async def language_command(update, context): 
    if update.effective_user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('lang_select'), reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🇺🇸 EN", callback_data="lang_en"), InlineKeyboardButton("🇪🇸 ES", callback_data="lang_es")]]), parse_mode='Markdown')
async def start(update, context): 
    if update.effective_user.id != ALLOWED_USER_ID: return
    detect_language(update.effective_user); await update.message.reply_text(T('start')); await post_init(context.application)
async def help_command(update, context): 
    if update.effective_user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('help'))
async def quality_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('quality_menu', DEFAULT_QUALITY.upper()), reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("❓ Ask", callback_data="setqual_ask"), InlineKeyboardButton("📱 720p", callback_data="setqual_720")], [InlineKeyboardButton("🎥 1080p", callback_data="setqual_1080"), InlineKeyboardButton("🎵 Audio", callback_data="setqual_audio")]]), parse_mode='Markdown')
async def status_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    tasks = manager.get_active_tasks()
    await update.message.reply_text(T('status_header') + "".join([f"🆔 `{t['id']}` | {t['status']} | {t['progress']}\n" for t in tasks]) if tasks else T('status_empty'))
async def clean_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    manager.tasks.clear(); await update.message.reply_text(T('clean_done'))
async def clean_uploaded_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('confirm_clean_ul'), reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton(T('btn_confirm_clean'), callback_data="confirm_clean_ul"), InlineKeyboardButton(T('btn_cancel_clean'), callback_data="cancel_clean_ul")]]), parse_mode='Markdown')
async def files_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    files = manager.get_local_files(); global FILE_CACHE; FILE_CACHE = files
    if not files: await update.message.reply_text(T('files_empty')); return
    msg = T('files_header'); k = []
    for idx, f in enumerate(files):
        msg += f"📄 `{f}` ({os.path.getsize(os.path.join(manager.base_dir, f))/(1024*1024):.1f} MB)\n"
        k.append([InlineKeyboardButton(T('btn_upload'), callback_data=f"uploc_{idx}"), InlineKeyboardButton(T('btn_delete'), callback_data=f"deloc_{idx}")])
    await update.message.reply_text(msg, reply_markup=InlineKeyboardMarkup(k), parse_mode='Markdown')
async def speedtest_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    msg = await update.message.reply_text(T('speedtest_start')); loop = asyncio.get_running_loop()
    try:
        res = await loop.run_in_executor(None, lambda: (speedtest.Speedtest().get_best_server(), speedtest.Speedtest().download()/1e6, speedtest.Speedtest().upload()/1e6))
        await msg.edit_text(f"🚀 **Results**\n⬇️ `{res[1]:.2f} Mbps`\n⬆️ `{res[2]:.2f} Mbps`", parse_mode='Markdown')
    except Exception as e: await msg.edit_text(T('speedtest_error', e))

async def lib_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    msg = await update.message.reply_text(T('lib_check'), parse_mode='Markdown')
    def check():
        cur = yt_dlp.version.__version__
        try:
            with urllib.request.urlopen("https://pypi.org/pypi/yt-dlp/json", timeout=5) as u: lat = json.loads(u.read().decode())['info']['version']
        except:
            lat = "Unknown"
        return cur, lat
    cur, lat = await asyncio.get_running_loop().run_in_executor(None, check)
    stat = T('lib_uptodate'); k = None
    if lat != "Unknown" and cur != lat: stat = T('lib_outdated'); k = InlineKeyboardMarkup([[InlineKeyboardButton(T('btn_update_lib'), callback_data="update_lib")]])
    await msg.edit_text(T('lib_info', cur, lat, stat), reply_markup=k, parse_mode='Markdown')

def restart_process(): os.execle(sys.executable, sys.executable, os.path.abspath(__file__), os.environ)
async def restart_command(update, context): 
    if update.effective_user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('restart_msg')); restart_process()
async def update_command(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('update_check'))
    try: subprocess.check_output(['git', 'pull']); await update.message.reply_text(T('update_downloaded', 'Git OK')); restart_process()
    except Exception as e: await update.message.reply_text(T('update_error', e))
async def refresh_menu_command(update, context): await post_init(context.application); await update.message.reply_text(T('menu_updated'))

async def post_init(application):
    desc_en = {'start': "Start", 'lib': "Check Lib", 'language': "Language", 'files': "Files", 'status': "Status", 'quality': "Quality", 'clean_uploaded': "Cleanup", 'speedtest': "Speedtest", 'update': "Update", 'restart': "Restart", 'help': "Help"}
    desc_es = {'start': "Iniciar", 'lib': "Ver Librería", 'language': "Idioma", 'files': "Archivos", 'status': "Estado", 'quality': "Calidad", 'clean_uploaded': "Limpiar", 'speedtest': "Velocidad", 'update': "Actualizar", 'restart': "Reiniciar", 'help': "Ayuda"}
    desc = desc_es if CURRENT_LANG == 'es' else desc_en
    cmds = [BotCommand("start",desc['start']), BotCommand("files",desc['files']), BotCommand("lib",desc['lib']), BotCommand("status",desc['status']), BotCommand("clean_uploaded",desc['clean_uploaded']), BotCommand("speedtest",desc['speedtest']), BotCommand("update",desc['update']), BotCommand("restart",desc['restart']), BotCommand("help",desc['help']), BotCommand("quality",desc['quality']), BotCommand("language",desc['language'])]
    await application.bot.set_my_commands(cmds)

# Logic
async def upload_with_userbot(file_path, filename, target, task_id):
    async def cb(cur, tot): 
        if tot: manager.set_upload_progress(task_id, int(cur*100/tot))
    async with TelegramClient(SESSION_PATH, API_ID, API_HASH) as c:
        await c.send_file(target, file_path, caption=f"✅ {filename}", supports_streaming=True, force_document=False, progress_callback=cb)

async def upload_file(task_id, bot, chat_id, msg_id):
    task = manager.get_task(task_id)
    if not task or not task['file_path'] or not os.path.exists(task['file_path']): return
    try:
        f = task['file_path']; sz = os.path.getsize(f)/(1024*1024)
        if sz > 50:
            await bot.edit_message_text(T('upload_userbot', task['filename'], sz), chat_id=chat_id, message_id=msg_id, parse_mode='Markdown')
            await upload_with_userbot(f, task['filename'], (await bot.get_me()).username, task_id)
            await bot.send_message(chat_id, T('upload_userbot_success'), parse_mode='Markdown'); await bot.delete_message(chat_id, msg_id)
        else:
            await bot.edit_message_text(T('upload_bot', task['filename'], sz), chat_id=chat_id, message_id=msg_id, parse_mode='Markdown')
            try: await bot.send_video(chat_id, open(f,'rb'), caption=f"✅ {task['filename']}", supports_streaming=True, read_timeout=3600, write_timeout=3600)
            except: await bot.send_document(chat_id, open(f,'rb'), caption=f"✅ {task['filename']}", read_timeout=3600, write_timeout=3600)
            await bot.edit_message_text(T('upload_success', task['filename']), chat_id=chat_id, message_id=msg_id, reply_markup=get_keyboard(task_id, 'completed'), parse_mode='Markdown')
        manager.update_status(task_id, 'completed'); manager.archive_task_file(task_id)
    except Exception as e:
        manager.update_status(task_id, 'failed_ul', str(e)); await bot.edit_message_text(T('upload_error', str(e)[:50]), chat_id=chat_id, message_id=msg_id, reply_markup=get_keyboard(task_id, 'failed_ul'))

async def download_phase(task_id, chat_id, msg_id, bot, quality):
    await bot.edit_message_text(T('downloading', quality, task_id), chat_id=chat_id, message_id=msg_id, reply_markup=get_keyboard(task_id, 'downloading'), parse_mode='Markdown')
    def run():
        task = manager.get_task(task_id)
        try:
            mode = 'audio' if quality == 'audio' else 'video'
            res = task['downloader'].download(task['url'], mode, quality, progress_hook=lambda d: manager.tasks[task_id].update({'progress':d.get('_percent_str','0%')}) if d['status']=='downloading' else None, check_cancel=lambda: manager.tasks[task_id]['cancel_flag'])
            if res['status'] == 'success': 
                with manager.lock: manager.tasks[task_id].update({'file_path': res['path'], 'filename': os.path.basename(res['path']), 'status': 'success'})
            return res
        except Exception as e: return {'status': 'error', 'message': str(e)}
    res = await asyncio.get_running_loop().run_in_executor(download_executor, run)
    if res['status'] == 'success': await upload_file(task_id, bot, chat_id, msg_id)
    elif res['status'] == 'cancelled': await bot.edit_message_text(T('cancel_ok'), chat_id=chat_id, message_id=msg_id); manager.delete_task_data(task_id)
    else: await bot.edit_message_text(T('error_generic', res['message'][:50]), chat_id=chat_id, message_id=msg_id, reply_markup=get_keyboard(task_id, 'failed_dl'))

async def analyze_phase(url, update, context):
    task_id = manager.create_task(url); msg = await update.message.reply_text(T('analyzing'), parse_mode='Markdown')
    info = await asyncio.get_running_loop().run_in_executor(download_executor, manager.tasks[task_id]['downloader'].get_video_info, url)
    if info['status'] == 'error': await msg.edit_text(T('error_generic', info['message'])); manager.delete_task_data(task_id); return
    safe_title = info['title'].replace('_', '\\_').replace('*', '\\*').replace('`', '\\`').replace('[', '\\[')
    await msg.edit_text(T('quality_select', safe_title, info['duration']), reply_markup=get_quality_keyboard(task_id), parse_mode='Markdown')

async def handle_msg(update, context):
    if update.effective_user.id != ALLOWED_USER_ID: return
    url = update.message.text.strip()
    if not url.startswith("http"): await update.message.reply_text(T('invalid_link')); return
    if DEFAULT_QUALITY != 'ask':
        tid = manager.create_task(url); msg = await update.message.reply_text(T('task_init', tid), parse_mode='Markdown')
        asyncio.create_task(download_phase(tid, update.effective_user.id, msg.message_id, context.bot, DEFAULT_QUALITY))
    else: await analyze_phase(url, update, context)

async def btn_handler(update, context):
    q = update.callback_query; await q.answer(); d = q.data; global CURRENT_LANG, DEFAULT_QUALITY
    if d in ["lang_es","lang_en"]: CURRENT_LANG = d.split("_")[1]; await q.edit_message_text(T('lang_set'), parse_mode='Markdown'); await refresh_menu_command(None, context); return
    if d == "confirm_clean_ul": s, i = manager.clear_uploaded_dir(); await q.edit_message_text(T('clean_ul_success', i) if s else T('error_generic', i), parse_mode='Markdown'); return
    if d == "cancel_clean_ul": await q.edit_message_text(T('clean_ul_cancel')); return
    if d == "update_lib":
        await q.edit_message_text(T('lib_updating'), parse_mode='Markdown')
        try: subprocess.check_output([sys.executable, "-m", "pip", "install", "--upgrade", "yt-dlp"]); await context.bot.send_message(q.message.chat_id, T('lib_updated'), parse_mode='Markdown'); restart_process()
        except Exception as e: await q.edit_message_text(T('lib_error', e))
        return
    if d.startswith("setqual_"): DEFAULT_QUALITY = d.split("_")[1]; await q.edit_message_text(T('quality_set', DEFAULT_QUALITY.upper()), parse_mode='Markdown'); return
    if d.startswith("qual_"): p = d.split("_"); await q.edit_message_text(T('quality_selected', p[1].upper()), parse_mode='Markdown'); asyncio.create_task(download_phase(p[2], q.message.chat_id, q.message.message_id, context.bot, p[1])); return
    if d.startswith("uploc_"):
        idx = int(d.split("_")[1])
        if idx < len(FILE_CACHE): t = manager.create_task_from_file(FILE_CACHE[idx]); 
        if t: await q.edit_message_text(f"🚀 {FILE_CACHE[idx]}..."); asyncio.create_task(upload_file(t, context.bot, q.message.chat_id, q.message.message_id))
        return
    if d.startswith("deloc_"): 
        try: os.remove(os.path.join(manager.base_dir, FILE_CACHE[int(d.split("_")[1])])); await q.edit_message_text(T('delete_ok'))
        except: pass; return
    act, tid = d.split('_', 1)
    if act == "delete": manager.delete_task_data(tid); await q.edit_message_text(T('delete_ok'))
    elif act == "cancel": 
        if manager.cancel_task(tid): await q.edit_message_text(T('cancel_ok'))
    elif act == "retry_dl": 
        if manager.reset_task_for_retry(tid): await q.edit_message_text(T('retry_dl'), reply_markup=get_keyboard(tid, 'downloading')); asyncio.create_task(download_phase(tid, q.message.chat_id, q.message.message_id, context.bot, '720'))
    elif act == "retry_ul": await q.edit_message_text(T('retry_ul')); asyncio.create_task(upload_file(tid, context.bot, q.message.chat_id, q.message.message_id))

if __name__ == '__main__':
    req = HTTPXRequest(connection_pool_size=8, read_timeout=3600.0, write_timeout=3600.0, connect_timeout=60.0, pool_timeout=60.0)
    app = ApplicationBuilder().token(TOKEN).request(req).post_init(post_init).build()
    app.add_handler(CommandHandler('start', start)); app.add_handler(CommandHandler('files', files_command))
    app.add_handler(CommandHandler('help', help_command)); app.add_handler(CommandHandler('status', status_command))
    app.add_handler(CommandHandler('clean', clean_command)); app.add_handler(CommandHandler('clean_uploaded', clean_uploaded_command))
    app.add_handler(CommandHandler('speedtest', speedtest_command)); app.add_handler(CommandHandler('update', update_command))
    app.add_handler(CommandHandler('restart', restart_command)); app.add_handler(CommandHandler('quality', quality_command))
    app.add_handler(CommandHandler('language', language_command)); app.add_handler(CommandHandler('refresh_menu', refresh_menu_command))
    app.add_handler(CommandHandler('lib', lib_command))
    app.add_handler(MessageHandler(filters.TEXT & (~filters.COMMAND), handle_msg)); app.add_handler(CallbackQueryHandler(btn_handler))
    print(f"🤖 Bot Ultimate v1.1 (Stable + Lib Check) Active...")
    app.run_polling()
