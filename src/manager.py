import uuid
import threading
import os
import shutil
from src.core import Downloader

class DownloadManager:
    def __init__(self):
        # Configurar directorios
        self.base_dir = "downloads"
        self.uploaded_dir = os.path.join(self.base_dir, "uploaded")
        
        if not os.path.exists(self.base_dir): os.makedirs(self.base_dir)
        if not os.path.exists(self.uploaded_dir): os.makedirs(self.uploaded_dir)

        # Diccionario para guardar tareas: { 'id': { data... } }
        self.tasks = {}
        self.lock = threading.Lock()

    def create_task(self, url):
        """Crea una nueva tarea y devuelve su ID"""
        task_id = str(uuid.uuid4())[:4] # ID corto
        with self.lock:
            self.tasks[task_id] = {
                'id': task_id,
                'url': url,
                'status': 'starting', 
                'progress': '0%',
                'filename': None,
                'file_path': None,
                'cancel_flag': False,
                'last_error': None,
                'downloader': Downloader(self.base_dir)
            }
        return task_id

    def create_task_from_file(self, filename):
        """Crea una tarea ficticia a partir de un archivo existente en disco"""
        file_path = os.path.join(self.base_dir, filename)
        if not os.path.exists(file_path):
            return None
        
        task_id = str(uuid.uuid4())[:4]
        with self.lock:
            self.tasks[task_id] = {
                'id': task_id,
                'url': 'Local File',
                'status': 'success', # Listo para subir
                'progress': '100%',
                'filename': filename,
                'file_path': file_path,
                'cancel_flag': False,
                'last_error': None,
                'downloader': None
            }
        return task_id

    def archive_task_file(self, task_id):
        """Mueve el archivo de la tarea a la carpeta 'uploaded'"""
        with self.lock:
            task = self.tasks.get(task_id)
            if not task or not task['file_path']: return False

            try:
                src = task['file_path']
                filename = os.path.basename(src)
                dst = os.path.join(self.uploaded_dir, filename)
                
                shutil.move(src, dst)
                
                # Actualizar la ruta en la tarea por si se consulta después
                self.tasks[task_id]['file_path'] = dst
                return True
            except Exception as e:
                print(f"Error moviendo archivo: {e}")
                return False

    def get_local_files(self):
        """Devuelve lista de archivos en 'downloads' (excluyendo 'uploaded')"""
        try:
            files = [f for f in os.listdir(self.base_dir) 
                     if os.path.isfile(os.path.join(self.base_dir, f))]
            return sorted(files)
        except:
            return []

    def clear_uploaded_dir(self):
        """Borra todos los archivos de la carpeta uploaded"""
        count = 0
        try:
            for f in os.listdir(self.uploaded_dir):
                file_path = os.path.join(self.uploaded_dir, f)
                if os.path.isfile(file_path):
                    os.remove(file_path)
                    count += 1
            return True, count
        except Exception as e:
            return False, str(e)

    # --- MÉTODOS ESTÁNDAR (Mantener compatibilidad) ---
    def get_task(self, task_id):
        with self.lock:
            return self.tasks.get(task_id)

    def get_active_tasks(self):
        with self.lock:
            return [t for t in self.tasks.values() if t['status'] in ['starting', 'downloading', 'processing']]

    def cancel_task(self, task_id):
        with self.lock:
            if task_id in self.tasks:
                self.tasks[task_id]['cancel_flag'] = True
                self.tasks[task_id]['status'] = 'cancelling'
                return True
        return False

    def delete_task_data(self, task_id):
        with self.lock:
            if task_id not in self.tasks: return False
            task = self.tasks[task_id]
            # Borrar solo si existe y NO ha sido movido a uploaded (o si queremos borrar todo)
            # Por seguridad, si está en uploaded no lo borramos con este comando simple, o sí?
            # Asumiremos que delete borra el archivo donde sea que esté apuntando file_path
            if task['file_path'] and os.path.exists(task['file_path']):
                try:
                    os.remove(task['file_path'])
                except: pass
            del self.tasks[task_id]
            return True

    def reset_task_for_retry(self, task_id):
        with self.lock:
            if task_id in self.tasks:
                self.tasks[task_id]['status'] = 'starting'
                self.tasks[task_id]['progress'] = '0%'
                self.tasks[task_id]['cancel_flag'] = False
                self.tasks[task_id]['last_error'] = None
                return True
        return False

    def update_status(self, task_id, status, error=None):
        with self.lock:
            if task_id in self.tasks:
                self.tasks[task_id]['status'] = status
                if error: self.tasks[task_id]['last_error'] = str(error)

    def run_download(self, task_id):
        task = self.get_task(task_id)
        if not task: return {'status': 'error', 'message': 'Task not found'}

        def check_cancel(): return self.tasks[task_id]['cancel_flag']

        def progress_hook(d):
            if d['status'] == 'downloading':
                try:
                    p = d.get('_percent_str', '0%').replace('%','')
                    self.tasks[task_id]['progress'] = f"{p}%"
                    self.tasks[task_id]['status'] = 'downloading'
                except: pass
            elif d['status'] == 'finished':
                self.tasks[task_id]['status'] = 'processing'

        try:
            # Aseguramos que downloader use el dir correcto
            result = task['downloader'].download(
                task['url'], 
                quality='best', 
                progress_hook=progress_hook,
                check_cancel=check_cancel
            )
            
            if result['status'] == 'success':
                with self.lock:
                    self.tasks[task_id]['file_path'] = result['path']
                    self.tasks[task_id]['filename'] = os.path.basename(result['path'])
                    self.tasks[task_id]['status'] = 'success'
                return result
            else:
                self.update_status(task_id, 'failed_dl', result['message'])
                return result

        except Exception as e:
            msg = str(e)
            if msg == "CANCELLED_BY_USER":
                self.update_status(task_id, 'cancelled')
                return {'status': 'cancelled', 'message': 'Cancelado por usuario'}
            self.update_status(task_id, 'failed_dl', msg)
            return {'status': 'error', 'message': msg}
