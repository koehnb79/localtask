package com.solemntreeapps.localtask;

/** Offline setup copy, shared by the in-app platform pages. */
public final class AgentGuide {
    public static final String REPOSITORY = "https://github.com/koehnb79/localtask";
    public static final String README = "https://github.com/koehnb79/localtask/blob/main/README.md";
    public static final String PYTHON = "https://www.python.org/downloads/";
    private AgentGuide() {}
    public static String download() {
        return "On the COMPUTER you want to manage, open github.com/koehnb79/localtask. Sign in to a GitHub account with access, choose Code → Download ZIP, and extract it. Open the agent folder inside the extracted localtask-main folder.\n\n"
            + "The repository is private. Receiving a computer QR does not give someone GitHub access. If the page says 404, ask the repository owner for access or for the agent source files.\n\n"
            + "Agents currently run from Python source; there are no packaged .exe, .dmg or Linux installers. Install Python 3.12 from python.org or your Linux distribution first. Downloads belong on the managed computer, not this phone.";
    }
    public static String commands(String os) {
        if ("Windows".equals(os)) return "py -3.12 -m venv .venv\n"
            + ".\\.venv\\Scripts\\python.exe -m pip install -r requirements.txt\n"
            + ".\\.venv\\Scripts\\python.exe agent.py init\n"
            + ".\\.venv\\Scripts\\python.exe agent.py pair\n"
            + ".\\.venv\\Scripts\\python.exe agent.py serve --host YOUR_LAN_IP";
        return "python3.12 -m venv .venv\n"
            + ".venv/bin/python -m pip install -r requirements.txt\n"
            + ".venv/bin/python agent.py init\n"
            + ".venv/bin/python agent.py pair\n"
            + ".venv/bin/python agent.py serve --host YOUR_LAN_IP";
    }
    public static String address(String os) {
        if ("Mac".equals(os)) return "System Settings → Network → your active Wi-Fi or Ethernet connection → Details → TCP/IP. Use its local IPv4 address.";
        if ("Windows".equals(os)) return "Run ipconfig in PowerShell. Use the IPv4 Address under your active Wi-Fi or Ethernet adapter.";
        return "Open your network settings, or run hostname -I in a terminal. Use the IPv4 address of the adapter on the same network as your phone.";
    }
    public static String restart(String os) {
        return ("Windows".equals(os) ? ".\\.venv\\Scripts\\python.exe" : ".venv/bin/python")
            + " agent.py serve --host YOUR_LAN_IP";
    }
}
