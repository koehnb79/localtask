"""PyInstaller entry point; imports only packaged, administrator-protected code."""
from windows_boot.host import main

if __name__ == '__main__':
    main()
