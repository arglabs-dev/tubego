# Despliegue de TubeGo en Docker

Esta guía levanta el bot de Telegram con Docker Compose y configura la sesión de usuario que permite subir archivos de más de 50 MB.

## Requisitos

- Docker Engine y Docker Compose.
- El archivo `.env` en la raíz del proyecto, con `TELEGRAM_TOKEN`, `ALLOWED_USER_ID`, `API_ID` y `API_HASH` configurados. Consulta la [guía del bot de Telegram](telegram_bot.md) para obtener estas credenciales.

## Levantar el contenedor

Desde la carpeta `deploy/`:

```bash
docker compose up -d --build
```

También puedes ejecutar el comando desde la raíz del proyecto:

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

## Autorizar la cuenta de Telegram

Haz este paso una vez por cada sesión de usuario que necesites configurar. Entra al contenedor y ejecuta el asistente:

```bash
docker exec -it tubego_bot bash
python setup_session.py
```

El contenedor ya inicia dentro de `/app`, donde está `setup_session.py`. El asistente pedirá tu número de teléfono, el código que Telegram envíe y, si está activada, la contraseña de verificación en dos pasos.

La sesión se guarda en `data/user_session.session` en la raíz del proyecto. Docker Compose monta esa carpeta dentro del contenedor, así que la autorización persiste al recrear o reiniciar el contenedor. No compartas ese archivo: permite acceder a la sesión autorizada de Telegram.

## Comprobar el servicio

```bash
docker compose ps
docker compose logs -f tubego-bot
```

Si ejecutas estos comandos desde la raíz del proyecto, agrega `-f deploy/docker-compose.yml` a cada uno.
