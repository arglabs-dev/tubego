import os
import sys
import subprocess
import logging
import asyncio
import traceback
import concurrent.futures
import speedtest
from dotenv import load_dotenv
from telegram import Update, BotCommand, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.request import HTTPXRequest
from telegram.ext import ApplicationBuilder, ContextTypes, CommandHandler, MessageHandler, CallbackQueryHandler, filters
from src.manager import DownloadManager

# --- TELETHON (Userbot for large files) ---
from telethon import TelegramClient
from telethon.tl.types import DocumentAttributeVideo

# Load environment variables
load_dotenv()

TOKEN = os.getenv("TELEGRAM_TOKEN")
ALLOWED_USER_ID = os.getenv("ALLOWED_USER_ID")
API_ID = os.getenv("API_ID")
API_HASH = os.getenv("API_HASH")

# Configure Logging
if not os.path.exists('logs'):
    os.makedirs('logs')

logging.basicConfig(
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    level=logging.INFO,
    handlers=[
        logging.FileHandler("logs/bot.log", encoding='utf-8'),
        logging.StreamHandler()
    ]
)

logger = logging.getLogger(__name__)

# Global Instances
manager = DownloadManager()
download_executor = concurrent.futures.ThreadPoolExecutor(max_workers=4)
FILE_CACHE = []

# Global Configuration
DEFAULT_QUALITY = 'ask' 
CURRENT_LANG = 'en'
SESSION_PATH = os.path.join('data', 'user_session')

if not TOKEN or not ALLOWED_USER_ID:
    logger.error("Error: Missing credentials in .env")
    exit(1)

ALLOWED_USER_ID = int(ALLOWED_USER_ID)

# --- INTERNATIONALIZATION (i18n) ---
STRINGS = {
    'en': {
        'start': "👋 **TubeGo Bot Ultimate v1.0**\n\n**Commands:**\n/language - Switch Language (ES/EN)\n/quality - Default Quality Settings\n/files - Pending Files\n/status - Active Downloads\n/speedtest - Network Speed\n/help - Help & Guide",
        'help': "📚 **Help Guide**\n\n1. **Downloads:** Send any link.\n2. **Smart Uploads:**\n   - Files < 50MB: Fast upload via Bot.\n   - Files > 50MB: Uploaded via Userbot (up to 2GB).\n3. **Management:** Use `/files` to check disk.",
        'status_empty': "📭 No active downloads.",
        'status_header': "📊 **Current Status:**\n",
        'clean_done': "🧹 Memory cleaned.",
        'files_empty': "📂 No pending files on disk.",
        'files_header': "📂 **Files on Disk (Pending):**\nSelect one to manage:\n\n",
        'speedtest_start': "🚀 **Starting Speedtest...**\nFinding best server (~30s)...",
        'speedtest_error': "❌ Speedtest failed: {}",
        'update_check': "📡 **Checking for updates...**",
        'update_done': "✅ Already up to date:\n`{}`",
        'update_downloaded': "⬇️ **Update downloaded:**\n`{}`\n\n🔄 **Restarting...**",
        'update_error': "❌ Update error: {}",
        'restart_msg': "🔄 **Restarting system...**",
        'menu_updated': "✅ Command menu updated.",
        'upload_userbot': "🚀 **Userbot Mode Activated**\n`{}` ({:.1f} MB)\nUploading with your personal account...",
        'upload_bot': "📤 **Uploading (Bot API)...**\n`{}` ({:.1f} MB)",
        'upload_success': "✅ **Upload Complete**\n`{}`\n(Archived in 'uploaded')",
        'upload_userbot_success': "✅ **Upload Complete**\nFile uploaded via Userbot.\n(Archived)",
        'upload_error': "❌ Upload Error: {}...\nTry again.",
        'downloading': "⬇️ **Downloading ({}) ...**\nTask `{}`",
        'analyzing': "🔍 **Analyzing link...**",
        'quality_select': "📹 **{}**\n⏱ Duration: {}\n\n👇 **Select Quality:**",
        'invalid_link': "⚠️ Invalid link.",
        'task_init': "⏳ Starting `{}`...",
        'error_generic': "❌ Error: {}",
        'cancel_ok': "🛑 Cancelled.",
        'cancel_fail': "⚠️ Could not cancel.",
        'delete_ok': "🗑️ Deleted.",
        'log_header': "📋 Error Log:\n{}",
        'retry_dl': "🔄 Retrying Download...",
        'retry_ul': "📤 Retrying Upload...",
        'file_not_found': "❌ File not found.",
        'confirm_clean_ul': "⚠️ **Are you sure?**\nThis will permanently delete ALL files in the `uploaded` folder.",
        'clean_ul_success': "🗑️ **Cleanup Complete.** Deleted `{}` files.",
        'clean_ul_cancel': "❌ Action cancelled.",
        'lang_select': "🌐 **Select Language / Selecciona Idioma:**",
        'lang_set': "✅ Language set to **English**.",
        'quality_menu': "⚙️ **Quality Settings**\nCurrent: `{}`\n\nSelect preference:",
        'quality_set': "✅ Preference saved: **{}**",
        'quality_selected': "👌 **Quality {} selected.**\nStarting...",
        'btn_cancel': "❌ Cancel",
        'btn_retry': "🔄 Retry",
        'btn_delete': "🗑️ Delete",
        'btn_log': "📄 View Log",
        'btn_retry_ul': "📤 Retry Upload",
        'btn_upload_now': "📤 Upload Now",
        'btn_delete_server': "🗑️ Delete from Server",
        'btn_upload': "📤 Upload",
        'btn_confirm_clean': "✅ YES, delete all",
        'btn_cancel_clean': "❌ NO, cancel",
    },
    'es': {
        'start': "👋 **TubeGo Bot Ultimate v1.0**\n\n**Comandos:**\n/language - Cambiar Idioma (ES/EN)\n/quality - Configurar Calidad\n/files - Archivos Pendientes\n/status - Descargas Activas\n/speedtest - Velocidad de Red\n/help - Ayuda y Guía",
        'help': "📚 **Guía de Ayuda**\n\n1. **Descargas:** Envía cualquier enlace.\n2. **Subidas Inteligentes:**\n   - Archivos < 50MB: Subida rápida vía Bot.\n   - Archivos > 50MB: Subida vía Userbot (hasta 2GB).\n3. **Gestión:** Usa `/files` para revisar el disco.",
        'status_empty': "📭 No hay descargas activas.",
        'status_header': "📊 **Estado Actual:**\n",
        'clean_done': "🧹 Memoria limpiada.",
        'files_empty': "📂 No hay archivos pendientes en disco.",
        'files_header': "📂 **Archivos en Disco (Pendientes):**\nSelecciona uno para gestionar:\n\n",
        'speedtest_start': "🚀 **Iniciando Speedtest...**\nBuscando mejor servidor (~30s)...",
        'speedtest_error': "❌ Falló el test: {}",
        'update_check': "📡 **Buscando actualizaciones...**",
        'update_done': "✅ Ya tienes la última versión:\n`{}`",
        'update_downloaded': "⬇️ **Actualización descargada:**\n`{}`\n\n🔄 **Reiniciando...**",
        'update_error': "❌ Error al actualizar: {}",
        'restart_msg': "🔄 **Reiniciando sistema...**",
        'menu_updated': "✅ Menú de comandos actualizado.",
        'upload_userbot': "🚀 **Modo Userbot Activado**\n`{}` ({:.1f} MB)\nSubiendo con tu cuenta personal...",
        'upload_bot': "📤 **Subiendo (Bot API)...**\n`{}` ({:.1f} MB)",
        'upload_success': "✅ **Subida Completada**\n`{}`\n(Archivado en 'uploaded')",
        'upload_userbot_success': "✅ **Subida Completada**\nArchivo subido vía Userbot.\n(Archivado)",
        'upload_error': "❌ Error al Subir: {}...\nReintenta.",
        'downloading': "⬇️ **Descargando ({}) ...**\nTarea `{}`",
        'analyzing': "🔍 **Analizando enlace...**",
        'quality_select': "📹 **{}**\n⏱ Duración: {}\n\n👇 **Selecciona Calidad:**",
        'invalid_link': "⚠️ Enlace inválido.",
        'task_init': "⏳ Iniciando `{}`...",
        'error_generic': "❌ Error: {}",
        'cancel_ok': "🛑 Cancelado.",
        'cancel_fail': "⚠️ No se pudo cancelar.",
        'delete_ok': "🗑️ Eliminado.",
        'log_header': "📋 Log de Error:\n{}",
        'retry_dl': "🔄 Reintentando Descarga...",
        'retry_ul': "📤 Reintentando Subida...",
        'file_not_found': "❌ Archivo no encontrado.",
        'confirm_clean_ul': "⚠️ **¿Estás seguro?**\nEsto borrará permanentemente TODOS los archivos de la carpeta `uploaded`.",
        'clean_ul_success': "🗑️ **Limpieza completada.** Se borraron `{}` archivos.",
        'clean_ul_cancel': "❌ Acción cancelada.",
        'lang_select': "🌐 **Select Language / Selecciona Idioma:**",
        'lang_set': "✅ Idioma cambiado a **Español**.",
        'quality_menu': "⚙️ **Configuración de Calidad**\nActual: `{}`\n\nSelecciona preferencia:",
        'quality_set': "✅ Preferencia guardada: **{}**",
        'quality_selected': "👌 **Calidad {} seleccionada.**\nIniciando...",
        'btn_cancel': "❌ Cancelar",
        'btn_retry': "🔄 Reintentar",
        'btn_delete': "🗑️ Borrar",
        'btn_log': "📄 Ver Error",
        'btn_retry_ul': "📤 Reintentar Subida",
        'btn_upload_now': "📤 Subir Ahora",
        'btn_delete_server': "🗑️ Borrar del Servidor",
        'btn_upload': "📤 Subir",
        'btn_confirm_clean': "✅ SÍ, borrar todo",
        'btn_cancel_clean': "❌ NO, cancelar",
    }
}

def T(key, *args):
    text = STRINGS[CURRENT_LANG].get(key, STRINGS['en'].get(key, key))
    if args: return text.format(*args)
    return text

def detect_language(user):
    global CURRENT_LANG
    if user and user.language_code and user.language_code.startswith('es'): CURRENT_LANG = 'es'
    else: CURRENT_LANG = 'en'

# --- HELPER KEYBOARDS ---
def get_keyboard(task_id, status):
    keyboard = []
    if status in ['starting', 'downloading', 'processing', 'uploading']:
        keyboard.append([InlineKeyboardButton(T('btn_cancel'), callback_data=f"cancel_{task_id}")])
    elif status == 'failed_dl':
        keyboard.append([InlineKeyboardButton(T('btn_retry'), callback_data=f"retry_dl_{task_id}")])
        keyboard.append([InlineKeyboardButton(T('btn_delete'), callback_data=f"delete_{task_id}")])
        keyboard.append([InlineKeyboardButton(T('btn_log'), callback_data=f"log_{task_id}")])
    elif status == 'failed_ul':
        keyboard.append([InlineKeyboardButton(T('btn_retry_ul'), callback_data=f"retry_ul_{task_id}")])
        keyboard.append([InlineKeyboardButton(T('btn_delete'), callback_data=f"delete_{task_id}")])
    elif status == 'success':
        keyboard.append([InlineKeyboardButton(T('btn_upload_now'), callback_data=f"retry_ul_{task_id}")])
        keyboard.append([InlineKeyboardButton(T('btn_delete'), callback_data=f"delete_{task_id}")])
    elif status == 'completed':
        keyboard.append([InlineKeyboardButton(T('btn_delete_server'), callback_data=f"delete_{task_id}")])
    return InlineKeyboardMarkup(keyboard)

def get_quality_keyboard(task_id):
    keyboard = [
        [InlineKeyboardButton("🎥 1080p", callback_data=f"qual_1080_{task_id}"),
         InlineKeyboardButton("📱 720p", callback_data=f"qual_720_{task_id}")],
        [InlineKeyboardButton("⚡ 480p", callback_data=f"qual_480_{task_id}"),
         InlineKeyboardButton("🎵 Audio MP3", callback_data=f"qual_audio_{task_id}")],
        [InlineKeyboardButton("🌟 Max (4K)", callback_data=f"qual_best_{task_id}"),
         InlineKeyboardButton(T('btn_cancel'), callback_data=f"delete_{task_id}")]
    ]
    return InlineKeyboardMarkup(keyboard)

# --- COMMANDS ---
async def language_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != ALLOWED_USER_ID: return
    keyboard = [[InlineKeyboardButton("🇺🇸 English", callback_data="lang_en"), InlineKeyboardButton("🇪🇸 Español", callback_data="lang_es")]]
    await update.message.reply_text(T('lang_select'), reply_markup=InlineKeyboardMarkup(keyboard), parse_mode='Markdown')

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    logger.info(f"CMD /start by {user.id}")
    if user.id != ALLOWED_USER_ID: return
    detect_language(user)
    await update.message.reply_text(T('start'))
    await post_init(context.application)

async def help_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    if user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('help'))

async def quality_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != ALLOWED_USER_ID: return
    keyboard = [[InlineKeyboardButton("❓ Ask Always", callback_data="setqual_ask")], [InlineKeyboardButton("📱 720p", callback_data="setqual_720")], [InlineKeyboardButton("🎥 1080p", callback_data="setqual_1080")], [InlineKeyboardButton("🎵 Audio", callback_data="setqual_audio")]]
    await update.message.reply_text(T('quality_menu', DEFAULT_QUALITY.upper()), reply_markup=InlineKeyboardMarkup(keyboard), parse_mode='Markdown')

async def status_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    logger.info(f"CMD /status by {user.id}")
    if user.id != ALLOWED_USER_ID: return
    tasks = manager.get_active_tasks()
    if not tasks:
        await update.message.reply_text(T('status_empty'))
        return
    msg = T('status_header')
    for t in tasks: msg += f"🆔 `{t['id']}` | {t['status']} | {t['progress']}\n🔗 {t['url']}\n\n" 
    await update.message.reply_text(msg)

async def clean_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    logger.info(f"CMD /clean by {user.id}")
    if user.id != ALLOWED_USER_ID: return
    manager.tasks.clear()
    await update.message.reply_text(T('clean_done'))

async def clean_uploaded_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != ALLOWED_USER_ID: return
    keyboard = [[InlineKeyboardButton(T('btn_confirm_clean'), callback_data="confirm_clean_ul"), InlineKeyboardButton(T('btn_cancel_clean'), callback_data="cancel_clean_ul")]]
    await update.message.reply_text(T('confirm_clean_ul'), reply_markup=InlineKeyboardMarkup(keyboard), parse_mode='Markdown')

async def files_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    logger.info(f"CMD /files by {user.id}")
    if user.id != ALLOWED_USER_ID: return
    files = manager.get_local_files()
    if not files:
        await update.message.reply_text(T('files_empty'))
        return
    global FILE_CACHE
    FILE_CACHE = files 
    msg = T('files_header')
    keyboard = []
    for idx, f in enumerate(files):
        size_mb = os.path.getsize(os.path.join(manager.base_dir, f)) / (1024*1024)
        msg += f"📄 `{f}` ({size_mb:.1f} MB)\n"
        keyboard.append([InlineKeyboardButton(T('btn_upload'), callback_data=f"uploc_{idx}"), InlineKeyboardButton(T('btn_delete'), callback_data=f"deloc_{idx}")])
    await update.message.reply_text(msg, reply_markup=InlineKeyboardMarkup(keyboard), parse_mode='Markdown')

async def speedtest_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    logger.info(f"CMD /speedtest by {user.id}")
    if user.id != ALLOWED_USER_ID: return
    msg = await update.message.reply_text(T('speedtest_start'), parse_mode='Markdown')
    def run_speedtest_sync():
        st = speedtest.Speedtest(); st.get_best_server()
        return st.results.dict(), st.download()/1e6, st.upload()/1e6
    loop = asyncio.get_running_loop()
    try:
        results, dl, ul = await loop.run_in_executor(None, run_speedtest_sync)
        await msg.edit_text(f"🚀 **Results**\n⬇️ `{dl:.2f} Mbps`\n⬆️ `{ul:.2f} Mbps`\n📶 `{results['ping']} ms`", parse_mode='Markdown')
    except Exception as e: await msg.edit_text(T('speedtest_error', e))

# --- SYSTEM COMMANDS ---
def restart_process():
    python = sys.executable; script_path = os.path.abspath(__file__)
    project_root = os.path.dirname(os.path.dirname(script_path))
    env = os.environ.copy(); env["PYTHONPATH"] = f"{project_root}:{env.get('PYTHONPATH', '')}"
    logger.info(f"Restarting from: {project_root}")
    os.execle(python, python, script_path, env)

async def restart_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    logger.info(f"CMD /restart by {user.id}")
    if user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('restart_msg')); restart_process()

async def update_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    logger.info(f"CMD /update by {user.id}")
    if user.id != ALLOWED_USER_ID: return
    await update.message.reply_text(T('update_check'))
    try:
        subprocess.check_output(['git', 'pull'], stderr=subprocess.STDOUT)
        await update.message.reply_text(T('update_downloaded', "Git pull success")); restart_process()
    except Exception as e: await update.message.reply_text(T('update_error', e))

async def refresh_menu_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await post_init(context.application)
    if update: await update.message.reply_text(T('menu_updated'))

async def post_init(application):
    desc_en = {'start': "Start", 'language': "Language", 'files': "Files", 'status': "Status", 'quality': "Quality", 'clean_uploaded': "Cleanup", 'speedtest': "Speedtest", 'update': "Update", 'restart': "Restart", 'help': "Help"}
    desc_es = {'start': "Iniciar", 'language': "Idioma", 'files': "Archivos", 'status': "Estado", 'quality': "Calidad", 'clean_uploaded': "Limpiar", 'speedtest': "Velocidad", 'update': "Actualizar", 'restart': "Reiniciar", 'help': "Ayuda"}
    desc = desc_es if CURRENT_LANG == 'es' else desc_en
    commands = [BotCommand("start", desc['start']), BotCommand("language", desc['language']), BotCommand("files", desc['files']), BotCommand("status", desc['status']), BotCommand("quality", desc['quality']), BotCommand("clean_uploaded", desc['clean_uploaded']), BotCommand("speedtest", desc['speedtest']), BotCommand("update", desc['update']), BotCommand("restart", desc['restart']), BotCommand("help", desc['help'])]
    await application.bot.set_my_commands(commands)

# --- CORE LOGIC ---
async def upload_with_userbot(file_path, filename, target_username, status_msg, task_id):
    last_logged_step = -1
    async def callback(current, total):
        nonlocal last_logged_step
        if total == 0: return
        percent = int(current * 100 / total); manager.set_upload_progress(task_id, percent)
        step = (percent // 25) * 25
        if step > last_logged_step: logger.info(f"Task {task_id}: Uploading... {step}%"); last_logged_step = step

    async with TelegramClient(SESSION_PATH, API_ID, API_HASH) as client:
        await client.send_file(target_username, file_path, caption=f"✅ **{filename}**\n_(Userbot Video)_", force_document=False, supports_streaming=True, progress_callback=callback)

async def upload_file(task_id, bot, chat_id, message_id):
    task = manager.get_task(task_id)
    if not task or not task['file_path']: return
    try:
        file_path = task['file_path']
        if not os.path.exists(file_path):
            await bot.edit_message_text(T('file_not_found'), chat_id=chat_id, message_id=message_id)
            return
        size_mb = os.path.getsize(file_path) / (1024 * 1024)
        is_video = file_path.lower().endswith(('.mp4', '.mkv', '.mov', '.avi'))
        if size_mb > 50:
            await bot.edit_message_text(T('upload_userbot', task['filename'], size_mb), chat_id=chat_id, message_id=message_id, parse_mode='Markdown')
            bot_info = await bot.get_me(); logger.info(f"Task {task_id}: Starting Userbot Upload ({size_mb:.2f} MB)")
            await upload_with_userbot(file_path, task['filename'], bot_info.username, message_id, task_id)
            manager.update_status(task_id, 'completed'); manager.archive_task_file(task_id)
            await bot.send_message(chat_id=chat_id, text=T('upload_userbot_success'), parse_mode='Markdown')
            await bot.delete_message(chat_id=chat_id, message_id=message_id)
        else:
            await bot.edit_message_text(T('upload_bot', task['filename'], size_mb), chat_id=chat_id, message_id=message_id, parse_mode='Markdown')
            manager.set_upload_progress(task_id, 0); logger.info(f"Task {task_id}: Starting Bot API Upload")
            uploaded = False
            if is_video:
                try:
                    await bot.send_video(chat_id=chat_id, video=open(file_path, 'rb'), read_timeout=3600, write_timeout=3600, connect_timeout=60, pool_timeout=3600, caption=f"✅ {task['filename']}", supports_streaming=True)
                    uploaded = True
                except: pass
            if not uploaded:
                await bot.send_document(chat_id=chat_id, document=open(file_path, 'rb'), read_timeout=3600, write_timeout=3600, connect_timeout=60, pool_timeout=3600, caption=f"✅ {task['filename']}")
            manager.update_status(task_id, 'completed'); manager.archive_task_file(task_id)
            await bot.edit_message_text(T('upload_success', task['filename']), chat_id=chat_id, message_id=message_id, reply_markup=get_keyboard(task_id, 'completed'), parse_mode='Markdown')
    except Exception as e:
        logger.error(f"Upload fail: {traceback.format_exc()}"); manager.update_status(task_id, 'failed_ul', str(e))
        await bot.edit_message_text(T('upload_error', str(e)[:50]), chat_id=chat_id, message_id=message_id, reply_markup=get_keyboard(task_id, 'failed_ul'))

async def download_phase(task_id, chat_id, message_id, bot, quality):
    loop = asyncio.get_running_loop()
    await bot.edit_message_text(T('downloading', quality, task_id), chat_id=chat_id, message_id=message_id, reply_markup=get_keyboard(task_id, 'downloading'), parse_mode='Markdown')
    def run_dl_wrapper():
        task = manager.get_task(task_id)
        if not task: return {'status': 'error', 'message': 'Task lost'}
        def check_cancel(): return manager.tasks[task_id]['cancel_flag']
        def progress(d):
            if d['status'] == 'downloading':
                try: manager.tasks[task_id]['progress'] = d.get('_percent_str', '0%')
                except: pass
        try:
            mode = 'audio' if quality == 'audio' else 'video'
            qual_val = 'best' if quality == 'best' else quality
            if quality == 'audio': qual_val = '192' 
            res = task['downloader'].download(task['url'], mode=mode, quality=qual_val, progress_hook=progress, check_cancel=check_cancel)
            if res['status'] == 'success':
                with manager.lock:
                    manager.tasks[task_id]['file_path'] = res['path']; manager.tasks[task_id]['filename'] = os.path.basename(res['path']); manager.tasks[task_id]['status'] = 'success'
            return res
        except Exception as e: return {'status': 'error', 'message': str(e)}
    result = await loop.run_in_executor(download_executor, run_dl_wrapper)
    if result['status'] == 'success': logger.info(f"Download completed: {task_id}"); await upload_file(task_id, bot, chat_id, message_id)
    elif result['status'] == 'cancelled': await bot.edit_message_text(T('cancel_ok'), chat_id=chat_id, message_id=message_id); manager.delete_task_data(task_id)
    else: await bot.edit_message_text(T('error_generic', result['message'][:50]), chat_id=chat_id, message_id=message_id, reply_markup=get_keyboard(task_id, 'failed_dl'))

async def analyze_phase(url, update, context):
    task_id = manager.create_task(url); msg = await update.message.reply_text(T('analyzing'), parse_mode='Markdown')
    loop = asyncio.get_running_loop(); info = await loop.run_in_executor(download_executor, manager.tasks[task_id]['downloader'].get_video_info, url)
    if info['status'] == 'error': await msg.edit_text(T('error_generic', info['message'])); manager.delete_task_data(task_id); return
    safe_title = info['title'].replace('_', '\\_').replace('*', '\\*').replace('`', '\\`').replace('[', '\\[')
    await msg.edit_text(T('quality_select', safe_title, info['duration']), reply_markup=get_quality_keyboard(task_id), parse_mode='Markdown')

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user = update.effective_user
    if user.id != ALLOWED_USER_ID: return
    if CURRENT_LANG == 'en' and user.language_code and user.language_code.startswith('es'): detect_language(user)
    logger.info(f"Msg from {user.first_name}: {update.message.text}")
    url = update.message.text.strip()
    if not url.startswith("http"): await update.message.reply_text(T('invalid_link')); return
    if DEFAULT_QUALITY != 'ask':
        task_id = manager.create_task(url); msg = await update.message.reply_text(T('task_init', task_id), parse_mode='Markdown')
        asyncio.create_task(download_phase(task_id, user.id, msg.message_id, context.bot, DEFAULT_QUALITY))
    else: await analyze_phase(url, update, context)

async def button_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query; await query.answer(); data = query.data; global CURRENT_LANG, DEFAULT_QUALITY
    if data == "lang_es": CURRENT_LANG = 'es'; await query.edit_message_text(T('lang_set'), parse_mode='Markdown'); await refresh_menu_command(None, context); return
    if data == "lang_en": CURRENT_LANG = 'en'; await query.edit_message_text(T('lang_set'), parse_mode='Markdown'); await refresh_menu_command(None, context); return
    if data == "confirm_clean_ul":
        success, info = manager.clear_uploaded_dir()
        if success: await query.edit_message_text(T('clean_ul_success', info), parse_mode='Markdown')
        else: await query.edit_message_text(T('error_generic', info)); return
    if data == "cancel_clean_ul": await query.edit_message_text(T('clean_ul_cancel')); return
    if data.startswith("setqual_"):
        new_q = data.split("_")[1]; DEFAULT_QUALITY = new_q; await query.edit_message_text(T('quality_set', new_q.upper()), parse_mode='Markdown'); return
    if data.startswith("qual_"):
        parts = data.split("_"); quality = parts[1]; task_id = parts[2]
        await query.edit_message_text(T('quality_selected', quality.upper()), parse_mode='Markdown')
        asyncio.create_task(download_phase(task_id, query.message.chat_id, query.message.message_id, context.bot, quality)); return
    if data.startswith("uploc_"):
        idx = int(data.split("_")[1])
        if idx < len(FILE_CACHE):
            fname = FILE_CACHE[idx]; tid = manager.create_task_from_file(fname)
            if tid: await query.edit_message_text(f"🚀 {fname}..."); asyncio.create_task(upload_file(tid, context.bot, query.message.chat_id, query.message.message_id))
        return
    if data.startswith("deloc_"):
        try:
            idx = int(data.split("_")[1])
            if idx < len(FILE_CACHE):
                filename = FILE_CACHE[idx]; file_path = os.path.join(manager.base_dir, filename)
                if os.path.exists(file_path): os.remove(file_path); await query.edit_message_text(T('delete_ok'))
                else: await query.edit_message_text(T('file_not_found'))
        except: pass
        return
    action, task_id = data.split('_', 1)
    if action == "retry" and "dl" in task_id: action = "retry_dl"; task_id = task_id.replace("dl_", "")
    elif action == "retry" and "ul" in task_id: action = "retry_ul"; task_id = task_id.replace("ul_", "")
    task = manager.get_task(task_id)
    if action == "delete": manager.delete_task_data(task_id); await query.edit_message_text(T('delete_ok'))
    elif action == "cancel":
        if manager.cancel_task(task_id): await query.edit_message_text(T('cancel_ok'))
    elif action == "log": await context.bot.send_message(query.message.chat_id, T('log_header', task.get('last_error', '-')))
    elif action == "retry_dl":
        if manager.reset_task_for_retry(task_id):
            await query.edit_message_text(T('retry_dl'), reply_markup=get_keyboard(task_id, 'downloading'))
            asyncio.create_task(download_phase(task_id, query.message.chat_id, query.message.message_id, context.bot, '720'))
    elif action == "retry_ul": await query.edit_message_text(T('retry_ul')); asyncio.create_task(upload_file(task_id, context.bot, query.message.chat_id, query.message.message_id))

if __name__ == '__main__':
    request = HTTPXRequest(connection_pool_size=8, read_timeout=3600.0, write_timeout=3600.0, connect_timeout=60.0, pool_timeout=60.0)
    application = ApplicationBuilder().token(TOKEN).request(request).post_init(post_init).build()
    application.add_handler(CommandHandler('start', start))
    application.add_handler(CommandHandler('files', files_command))
    application.add_handler(CommandHandler('help', help_command))
    application.add_handler(CommandHandler('status', status_command))
    application.add_handler(CommandHandler('clean', clean_command))
    application.add_handler(CommandHandler('clean_uploaded', clean_uploaded_command))
    application.add_handler(CommandHandler('speedtest', speedtest_command))
    application.add_handler(CommandHandler('update', update_command))
    application.add_handler(CommandHandler('restart', restart_command))
    application.add_handler(CommandHandler('quality', quality_command))
    application.add_handler(CommandHandler('language', language_command))
    application.add_handler(CommandHandler('refresh_menu', refresh_menu_command))
    application.add_handler(MessageHandler(filters.TEXT & (~filters.COMMAND), handle_message))
    application.add_handler(CallbackQueryHandler(button_handler))
    print(f"🤖 Bot Ultimate v1.0 (Stable) Active...")
    application.run_polling()