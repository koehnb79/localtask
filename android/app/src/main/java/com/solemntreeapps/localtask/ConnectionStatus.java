package com.solemntreeapps.localtask;

/** Human-readable reachability failures; never implies that a computer is powered off. */
public final class ConnectionStatus {
    private ConnectionStatus() {}
    public static String message(Exception error) {
        if(error instanceof javax.net.ssl.SSLException)
            return "⚠ Secure connection failed — verify fingerprint / certificate";
        if(error instanceof AgentClient.AgentException){
            int code=((AgentClient.AgentException)error).status;
            if(code==401 || code==403)return "⚠ Access denied — check saved token";
            return "⚠ Agent error (HTTP "+code+")";
        }
        if(error instanceof IllegalArgumentException || error instanceof org.json.JSONException)
            return "⚠ Check saved connection details / agent response";
        return "○ Unreachable";
    }
}
