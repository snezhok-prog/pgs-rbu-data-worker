package ru.evolenta.pgs.worker;
import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** No redirects: never forwards service credentials to a different host. */
public final class HttpJson {
    public static final class HttpError extends IOException {public final int status;HttpError(int code,String method,String route){super("HTTP "+code+" "+method+" "+route.split("[?#]",2)[0]);status=code;}}
    private final String base,token;private final String tokenFile,cookieFile;private final boolean tokenHeader;private final int timeout;private final boolean allowHttp;
    public HttpJson(String base,String token,int timeout,boolean allowHttp){this(base,token,timeout,allowHttp,"","",false);}
    public HttpJson(String base,String token,int timeout,boolean allowHttp,String tokenFile,String cookieFile,boolean tokenHeader){this.tokenFile=tokenFile;this.cookieFile=cookieFile;this.tokenHeader=tokenHeader;this.base=base.endsWith("/")?base:base+"/";this.token=token;this.timeout=timeout;this.allowHttp=allowHttp;}
    public byte[] bytes(String method,String route,Object body,long limit)throws Exception{
        if(route.startsWith("/")||route.contains("://")||route.contains("..")||route.indexOf('\r')>=0||route.indexOf('\n')>=0)throw new IllegalArgumentException("Unsafe API route");
        URL url=new URL(base+route);if(!url.getProtocol().equals("https")&&!(allowHttp&&url.getProtocol().equals("http")))throw new IllegalArgumentException("HTTPS required; allowHttp only for approved internal network");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(timeout);c.setReadTimeout(timeout);c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");
        String currentToken=secret(tokenFile,token).replaceFirst("(?i)^Bearer\\s+","").trim();if(!currentToken.isEmpty()){c.setRequestProperty("Authorization","Bearer "+currentToken);if(tokenHeader)c.setRequestProperty("token",currentToken);}String cookie=secret(cookieFile,"").replaceFirst("(?i)^Cookie:\\s*","");if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
        try{if(body!=null){byte[] data=Json.write(body).getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
            int status=c.getResponseCode();if(status<200||status>=300)throw new HttpError(status,method,route);
            try(InputStream in=c.getInputStream()){return Util.readBounded(in,limit);}
        }finally{c.disconnect();}
    }
    private static String secret(String file,String fallback)throws Exception{String value=fallback==null?"":fallback;if(file!=null&&!file.trim().isEmpty()){java.nio.file.Path p=java.nio.file.Paths.get(file);if(java.nio.file.Files.size(p)>16384)throw new IllegalArgumentException("Credential file is too large");value=new String(java.nio.file.Files.readAllBytes(p),StandardCharsets.UTF_8).replace("\uFEFF","").trim();}if(value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IllegalArgumentException("Credential must contain one header value");return value;}
    public Object call(String method,String route,Object body)throws Exception{byte[] b=bytes(method,route,body,32*1024*1024);return b.length==0?null:Json.parse(new String(b,StandardCharsets.UTF_8));}
    public static String enc(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalArgumentException(e);}}
}
