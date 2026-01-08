import sys
from src.cli import run_cli
import flet as ft
from src.ui import main as ui_main

def main():
    # Si hay argumentos (más allá del nombre del script), usamos CLI
    if len(sys.argv) > 1:
        run_cli()
    else:
        print("Iniciando modo gráfico...")
        ft.app(target=ui_main)

if __name__ == "__main__":
    main()
