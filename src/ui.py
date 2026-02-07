import flet as ft
from src.core import Downloader
import threading
import os
import subprocess
import sys

def main(page: ft.Page):
    page.title = "TubeGo v0.3"
    page.theme_mode = ft.ThemeMode.LIGHT
    page.window_width = 450
    page.window_height = 700
    page.padding = 20

    downloader = Downloader()

    # --- UI Components: Download Tab ---
    url_input = ft.TextField(label="Pegar Link (YouTube, X.com...)", width=400, prefix_icon=ft.icons.LINK)
    
    quality_dropdown = ft.Dropdown(
        width=200,
        label="Calidad / Tipo",
        value="480",
        options=[
            ft.dropdown.Option("best", "La Mejor Disponible"),
            ft.dropdown.Option("1080", "Full HD (1080p)"),
            ft.dropdown.Option("720", "HD (720p)"),
            ft.dropdown.Option("480", "Datos (480p)"),
            ft.dropdown.Option("audio", "Solo Audio (MP3)"),
        ],
    )

    progress_bar = ft.ProgressBar(width=400, visible=False, value=0)
    progress_text = ft.Text("", size=12, color=ft.colors.GREY)
    status_text = ft.Text("Listo para descargar", size=16)
    
    # --- UI Components: History Tab ---
    history_list = ft.ListView(expand=True, spacing=10)

    def refresh_history():
        history_list.controls.clear()
        files = downloader.list_downloads()
        if not files:
            history_list.controls.append(ft.Text("No hay descargas aún.", italic=True))
        else:
            for f in files:
                # Botón simple para cada archivo
                # Nota: En Android abrir archivos requiere plugins extra, aquí usaremos
                # un botón genérico que en escritorio abre la carpeta.
                icon = ft.icons.AUDIO_FILE if f.endswith('.mp3') else ft.icons.VIDEO_FILE
                history_list.controls.append(
                    ft.ListTile(
                        leading=ft.Icon(icon),
                        title=ft.Text(f, max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
                        subtitle=ft.Text("En carpeta local"),
                    )
                )
        page.update()

    # --- Logic ---

    def progress_hook(d):
        if d['status'] == 'downloading':
            try:
                # Calcular porcentaje 0.0 a 1.0
                p = d.get('_percent_str', '0%').replace('%','')
                progress = float(p) / 100
                progress_bar.value = progress
                progress_text.value = f"Descargando... {p}%"
                page.update()
            except:
                pass
        elif d['status'] == 'finished':
            progress_bar.value = 1
            progress_text.value = "Procesando archivo..."
            page.update()

    def on_download_click(e):
        url = url_input.value
        if not url:
            url_input.error_text = "¡Necesitas una URL!"
            page.update()
            return

        url_input.error_text = None
        download_btn.disabled = True
        progress_bar.visible = True
        progress_bar.value = 0
        progress_text.value = "Iniciando..."
        status_text.value = "Descargando..."
        status_text.color = ft.colors.BLUE
        page.update()

        q_val = quality_dropdown.value
        mode = 'audio' if q_val == 'audio' else 'video'
        quality = '720' if q_val == 'audio' else q_val

        def task():
            result = downloader.download(url, mode=mode, quality=quality, progress_hook=progress_hook)
            
            if result['status'] == 'success':
                status_text.value = "¡Descarga Exitosa!"
                status_text.color = ft.colors.GREEN
                url_input.value = ""
                refresh_history()
            else:
                status_text.value = "Error en la descarga"
                status_text.color = ft.colors.RED
                print(result['message']) # Debug en consola

            download_btn.disabled = False
            progress_bar.visible = False
            progress_text.value = ""
            page.update()

        threading.Thread(target=task, daemon=True).start()

    download_btn = ft.ElevatedButton("Descargar Ahora", icon=ft.icons.DOWNLOAD, on_click=on_download_click, width=200)

    def open_folder(e):
        # Intenta abrir la carpeta de descargas en el explorador del sistema
        path = os.path.abspath(downloader.download_dir)
        if sys.platform == 'win32':
            os.startfile(path)
        elif sys.platform == 'darwin':
            subprocess.Popen(['open', path])
        else:
            subprocess.Popen(['xdg-open', path])

    open_folder_btn = ft.TextButton("Abrir Carpeta de Descargas", icon=ft.icons.FOLDER_OPEN, on_click=open_folder)

    # --- Layout Assembly ---
    
    download_view = ft.Column([
        ft.Container(height=20),
        ft.Icon(ft.icons.CLOUD_DOWNLOAD, size=80, color=ft.colors.BLUE),
        ft.Text("TubeGo", size=24, weight=ft.FontWeight.BOLD),
        ft.Container(height=20),
        url_input,
        quality_dropdown,
        ft.Container(height=20),
        download_btn,
        ft.Container(height=10),
        progress_bar,
        progress_text,
        status_text,
    ], horizontal_alignment=ft.CrossAxisAlignment.CENTER)

    history_view = ft.Column([
        ft.Container(height=10),
        open_folder_btn,
        ft.Divider(),
        history_list
    ])

    t = ft.Tabs(
        selected_index=0,
        animation_duration=300,
        tabs=[
            ft.Tab(text="Descargar", icon=ft.icons.DOWNLOAD, content=download_view),
            ft.Tab(text="Mis Archivos", icon=ft.icons.HISTORY, content=history_view),
        ],
        expand=1,
    )

    page.add(t)
    refresh_history()

if __name__ == "__main__":
    ft.app(target=main)