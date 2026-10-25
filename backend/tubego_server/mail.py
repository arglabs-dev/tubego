import smtplib
import ssl
from email.message import EmailMessage
from datetime import datetime, timezone
from urllib.parse import urlsplit


class MailTestError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.safe_message = message


class SmtpMailer:
    def __init__(self, settings):
        self.settings = settings

    def send_verification(self, email, token):
        self._send(email, "Verifica tu correo en Tubego", "/auth/verify", token)

    def send_reset(self, email, token):
        self._send(email, "Restablece tu contraseña en Tubego", "/auth/password/reset", token)

    def configuration_status(self):
        s = self.settings
        transport = "SSL/TLS" if s.smtp_ssl else "STARTTLS" if s.smtp_tls else "sin cifrado"
        checks = {
            "host": bool(s.smtp_host),
            "sender": bool(s.smtp_sender),
            "username": bool(s.smtp_username),
            "password": bool(s.smtp_password),
            "encrypted_transport": bool(s.smtp_ssl) != bool(s.smtp_tls),
            "public_https": urlsplit(s.public_url).scheme == "https",
        }
        return {
            "host": s.smtp_host,
            "port": s.smtp_port,
            "sender": s.smtp_sender,
            "username": s.smtp_username,
            "password_configured": checks["password"],
            "transport": transport,
            "public_url_https": checks["public_https"],
            "ready": all(checks.values()),
            "checks": checks,
        }

    def check_connection(self):
        smtp = self._authenticated_session()
        try:
            code, _ = smtp.noop()
            if code != 250:
                raise MailTestError("smtp_check_failed", "El servidor SMTP no confirmó la conexión.")
        except MailTestError:
            raise
        except (OSError, smtplib.SMTPException, ssl.SSLError):
            raise MailTestError("smtp_check_failed", "No se pudo completar la comprobación SMTP.") from None
        finally:
            try:
                smtp.quit()
            except (OSError, smtplib.SMTPException):
                pass
        return {"ok": True, "message": "Conexión segura y autenticación SMTP correctas."}

    def send_test(self, recipient):
        smtp = self._authenticated_session()
        message = EmailMessage()
        message["From"] = self.settings.smtp_sender
        message["To"] = recipient
        message["Subject"] = "Prueba de correo de TuBeGo"
        message.set_content(
            "Este es un correo de prueba solicitado desde el Centro de administración de TuBeGo.\n"
            f"Enviado: {datetime.now(timezone.utc).isoformat()}\n"
        )
        try:
            refused = smtp.send_message(message)
            if refused:
                raise MailTestError("smtp_recipient_rejected", "El servidor SMTP rechazó el destinatario de prueba.")
        except MailTestError:
            raise
        except (OSError, smtplib.SMTPException, ssl.SSLError):
            raise MailTestError("smtp_send_failed", "El servidor SMTP no aceptó el correo de prueba.") from None
        finally:
            try:
                smtp.quit()
            except (OSError, smtplib.SMTPException):
                pass
        return {"ok": True, "recipient": recipient,
                "message": "El servidor SMTP aceptó el correo; esto no confirma su entrega en la bandeja."}

    def _authenticated_session(self):
        s = self.settings
        if (not s.smtp_host or not s.smtp_sender or not s.smtp_username or not s.smtp_password
                or s.smtp_port < 1 or s.smtp_port > 65535):
            raise MailTestError("smtp_config_incomplete", "Faltan datos SMTP requeridos en la configuración del servidor.")
        if bool(s.smtp_ssl) == bool(s.smtp_tls):
            raise MailTestError("smtp_transport_invalid", "Configura solo un modo seguro: SSL/TLS o STARTTLS.")
        try:
            if s.smtp_ssl:
                smtp = smtplib.SMTP_SSL(s.smtp_host, s.smtp_port, timeout=15,
                                       context=ssl.create_default_context())
            else:
                smtp = smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15)
        except (OSError, smtplib.SMTPException, ssl.SSLError):
            raise MailTestError("smtp_connection_failed", "No se pudo conectar al servidor SMTP desde la API.") from None
        try:
            smtp.ehlo()
            if s.smtp_tls and not s.smtp_ssl:
                smtp.starttls(context=ssl.create_default_context())
                smtp.ehlo()
        except (OSError, smtplib.SMTPException, ssl.SSLError):
            try:
                smtp.quit()
            except (OSError, smtplib.SMTPException):
                pass
            raise MailTestError("smtp_tls_failed", "No se pudo negociar el cifrado TLS con el servidor SMTP.") from None
        try:
            smtp.login(s.smtp_username, s.smtp_password)
        except (OSError, smtplib.SMTPException, ssl.SSLError):
            try:
                smtp.quit()
            except (OSError, smtplib.SMTPException):
                pass
            raise MailTestError("smtp_auth_failed", "El servidor SMTP rechazó la autenticación.") from None
        return smtp

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
        if s.smtp_ssl:
            smtp_connection = smtplib.SMTP_SSL(
                s.smtp_host, s.smtp_port, timeout=15,
                context=ssl.create_default_context())
        else:
            smtp_connection = smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15)
        with smtp_connection as smtp:
            if s.smtp_tls and not s.smtp_ssl:
                smtp.starttls(context=ssl.create_default_context())
            if s.smtp_username:
                smtp.login(s.smtp_username, s.smtp_password)
            smtp.send_message(message)
