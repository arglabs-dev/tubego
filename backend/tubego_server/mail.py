import smtplib
import ssl
from email.message import EmailMessage
from urllib.parse import urlsplit

class SmtpMailer:
    def __init__(self, settings):
        self.settings = settings

    def send_verification(self, email, token):
        self._send(email, "Verifica tu correo en Tubego", "/auth/verify", token)

    def send_reset(self, email, token):
        self._send(email, "Restablece tu contraseña en Tubego", "/auth/password/reset", token)

    def _send(self, email, subject, path, token):
        s = self.settings
        if not s.smtp_host or not s.smtp_sender or urlsplit(s.public_url).scheme != "https":
            raise RuntimeError("Configure SMTP and an HTTPS public URL")
        message = EmailMessage()
        message["From"] = s.smtp_sender
        message["To"] = email
        message["Subject"] = subject
        message.set_content("Abre este enlace (válido durante una hora):\n" +
            s.public_url + "/api/v1" + path + "#token=" + token)
        with smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15) as smtp:
            if s.smtp_tls:
                smtp.starttls(context=ssl.create_default_context())
            if s.smtp_username:
                smtp.login(s.smtp_username, s.smtp_password)
            smtp.send_message(message)
