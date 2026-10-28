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
    public JSONObject processes() throws Exception {return request("/v1/processes",null);}
    public JSONObject command(JSONObject body)throws Exception{return request("/v1/commands",body);}
    private JSONObject request(String path,JSONObject body)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(base+path).openConnection();
        c.setSSLSocketFactory(sockets);
        // The exact certificate pin authenticates this computer, independent of DHCP hostname/IP.
        c.setHostnameVerifier((host,session)->true);
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(5000); c.setReadTimeout(8000);
        c.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body!=null){
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                try(java.io.OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            }
            int status=c.getResponseCode();
            if(status!=200) throw new java.io.IOException("Agent returned HTTP "+status+". Check access key, permissions, or refresh the process list.");
            try(java.io.InputStream in=c.getInputStream(); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[8192]; int n;
                while((n=in.read(buffer))!=-1){out.write(buffer,0,n); if(out.size()>8*1024*1024)throw new java.io.IOException("Response too large");}
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {c.disconnect();}
    }
}
