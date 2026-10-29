package com.solemntreeapps.localtask;

import org.json.JSONObject;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

/** Transport boundary: a future VPN/relay client can implement the same API. */
public final class AgentClient {
    private final String base, token;
    private final SSLSocketFactory sockets;
    public AgentClient(String host, String port, String token, String fingerprint) throws Exception {
        String[] octets = host.split("\\.");
        if (octets.length != 4) throw new IllegalArgumentException("Enter a private IPv4 address");
        int[] ip = new int[4];
        for (int i=0;i<4;i++) { ip[i]=Integer.parseInt(octets[i]); if(ip[i]<0 || ip[i]>255) throw new IllegalArgumentException("Invalid IP"); }
        if (!(ip[0]==10 || (ip[0]==172 && ip[1]>=16 && ip[1]<=31) || (ip[0]==192 && ip[1]==168)))
            throw new IllegalArgumentException("Use a private LAN address");
        int p = Integer.parseInt(port);
        if (p<1 || p>65535) throw new IllegalArgumentException("Invalid port");
        String pin = fingerprint.replace(":", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!pin.matches("[0-9a-f]{64}") || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Copy the full fingerprint and access key from the computer");
        byte[] expected = new byte[32];
        for(int i=0;i<32;i++) expected[i]=(byte)Integer.parseInt(pin.substring(i*2,i*2+2),16);
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("Unsupported");}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
                try {
                    if(c.length==0) throw new CertificateException("Missing certificate");
                    c[0].checkValidity();
                    if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded())))
                        throw new CertificateException("Computer fingerprint does not match");
                } catch(CertificateException e){throw e;} catch(Exception e){throw new CertificateException(e);}
            }
        };
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(null,new TrustManager[]{trust},null);
        sockets=context.getSocketFactory(); this.token=token;
        base="https://"+host+":"+p;
    }
    public JSONObject metrics() throws Exception {return request("/v1/metrics",null);}
    public JSONObject processes() throws Exception {return request("/v1/processes",null);}
    /** Uses the same pinned, authenticated transport as process control. */
    public void checkOnline() throws Exception {
        try {
            if (!"ok".equals(request("/v1/status",null,3000).optString("status")))
                throw new java.io.IOException("Unexpected status response");
        } catch (AgentException e) {
            if(e.status!=404)throw e;
            // Compatibility with agents released before the status endpoint.
            if(request("/v1/processes",null,3000).optJSONArray("processes")==null)
                throw new java.io.IOException("Unexpected process response");
        }
    }
    public JSONObject command(JSONObject body)throws Exception{return request("/v1/commands",body);}
    public JSONObject computerActions()throws Exception{return request("/v1/computer-actions",null);}
    public JSONObject prepareComputerAction(String action)throws Exception{
        return request("/v1/computer-actions/prepare",new JSONObject().put("action",action));
    }
    public JSONObject confirmComputerAction(String confirmation)throws Exception{
        return request("/v1/computer-actions/confirm",new JSONObject().put("confirmation",confirmation));
    }
    public static final class AgentException extends java.io.IOException {
        private static final long serialVersionUID=1L;
        public final int status;
        AgentException(int status,String message){super(message);this.status=status;}
    }
    private JSONObject request(String path,JSONObject body)throws Exception{
        return request(path,body,0);
    }
    private JSONObject request(String path,JSONObject body,int timeout)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(base+path).openConnection();
        c.setSSLSocketFactory(sockets);
        // The exact certificate pin authenticates this computer, independent of DHCP hostname/IP.
        c.setHostnameVerifier((host,session)->true);
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(timeout>0?timeout:5000);
        c.setReadTimeout(timeout>0?timeout:path.startsWith("/v1/computer-actions")?15000:8000);
        c.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body!=null){
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(payload.length);
                try(java.io.OutputStream out=c.getOutputStream()){out.write(payload);}
            }
            int status=c.getResponseCode();
            if(status!=200){
                String message="Agent returned HTTP "+status+". Check access key, permissions, or refresh the process list.";
                try(java.io.InputStream in=c.getErrorStream(); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                    if(in!=null){
                        byte[] buffer=new byte[1024];int n;
                        while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>8192)break;}
                        if(out.size()<=8192){
                            Object detail=new JSONObject(out.toString("UTF-8")).opt("detail");
                            if(detail instanceof String && ((String)detail).length()<=2048)message=(String)detail;
                        }
                    }
                }catch(Exception ignored){}
                throw new AgentException(status,message);
            }
            try(java.io.InputStream in=c.getInputStream(); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[8192]; int n;
                while((n=in.read(buffer))!=-1){out.write(buffer,0,n); if(out.size()>8*1024*1024)throw new java.io.IOException("Response too large");}
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {c.disconnect();}
    }
}
