import smtplib
import ssl
from email.message import EmailMessage
from urllib.parse import urlsplit

class SmtpMailer:
    def __init__(self, settings):
        self.settings = settings

    def send_verification(self, email, token):
        s = self.settings
        if not s.smtp_host or not s.smtp_sender or urlsplit(s.public_url).scheme != "https":
            raise RuntimeError("Configure SMTP and an HTTPS public URL")
        message = EmailMessage()
        message["From"] = s.smtp_sender
        message["To"] = email
        message["Subject"] = "Verifica tu correo en Tubego"
        message.set_content("Verifica tu correo (enlace válido durante una hora):\n" +
            s.public_url + "/api/v1/auth/verify#token=" + token)
        with smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15) as smtp:
            if s.smtp_tls:
                smtp.starttls(context=ssl.create_default_context())
            if s.smtp_username:
                smtp.login(s.smtp_username, s.smtp_password)
            smtp.send_message(message)
