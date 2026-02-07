from telethon.sync import TelegramClient
from telethon.errors import SessionPasswordNeededError
import os
from dotenv import load_dotenv

# Cargar variables
load_dotenv()

api_id = os.getenv('API_ID')
api_hash = os.getenv('API_HASH')

if not os.path.exists('data'):
    os.makedirs('data')
session_file = os.path.join('data', 'user_session')

if not api_id or not api_hash:
    print("Error: Faltan API_ID o API_HASH en el archivo .env")
    exit(1)

print("--- Generador de Sesión de Userbot ---")
phone_number = input("Introduce tu número de teléfono (ej: +521...): ")

client = TelegramClient(session_file, api_id, api_hash)

client.connect()

if not client.is_user_authorized():
    print(f"Solicitando código para {phone_number}...")
    try:
        client.send_code_request(phone_number)
    except Exception as e:
        print(f"Error solicitando código: {e}")
        exit(1)

    try:
        code = input('Introduce el código que te llegó a Telegram: ')
        client.sign_in(phone_number, code)
    except SessionPasswordNeededError:
        # Aquí manejamos explícitamente el 2FA
        print("\n🔐 Tu cuenta tiene verificación de dos pasos (2FA).")
        password = input('Por favor, introduce tu contraseña de la nube: ')
        try:
            client.sign_in(password=password)
        except Exception as e:
            print(f"Contraseña incorrecta o error: {e}")
            exit(1)
    except Exception as e:
        print(f"Error al iniciar sesión: {e}")
        exit(1)

print("\n✅ ¡Éxito! Sesión guardada correctamente.")
print(f"Archivo generado: {session_file}.session")
client.disconnect()