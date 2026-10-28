package ru.evolenta.pgs.worker;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
public final class Util {
    private Util(){}
    public static Map<String,Object> map(Object v){return v instanceof Map?(Map<String,Object>)v:new LinkedHashMap<String,Object>();}
    public static List<Object> list(Object v){return v instanceof List?(List<Object>)v:new ArrayList<Object>();}
    public static List<Map<String,Object>> maps(Object v){List<Map<String,Object>> a=new ArrayList<Map<String,Object>>();for(Object x:list(v))if(x instanceof Map)a.add(map(x));return a;}
    public static Map<String,Object> obj(Object... kv){Map<String,Object> m=new LinkedHashMap<String,Object>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    public static String text(Object v){return v==null?"":String.valueOf(v);}
    public static String clean(Object v){return text(v).trim();}
    public static String key(Object v){return clean(v).toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");}
    public static boolean present(Object v){return !clean(v).isEmpty();}
    public static String id(Object v){Map<String,Object> m=map(v);return clean(first(m.get("_id"),m.get("id")));}
    public static Object first(Object... vs){for(Object v:vs)if(v!=null&&(!(v instanceof String)||present(v))&&(!(v instanceof Map)||!map(v).isEmpty()))return v;return null;}
    public static Object at(Object v,String p){for(String part:p.replace("[",".").replace("]","").split("\\.")){if(v instanceof Map)v=map(v).get(part);else if(v instanceof List&&part.matches("[0-9]+")){int n=Integer.parseInt(part);v=n<list(v).size()?list(v).get(n):null;}else return null;}return v;}
    public static Map<String,Object> copy(Map<String,Object> v){return map(Json.parse(Json.write(v)));}
    public static BigDecimal number(Object v){try{return new BigDecimal(clean(v).replace(',','.'));}catch(Exception e){return null;}}
    public static int integer(Object v){BigDecimal n=number(v);return n==null?0:n.intValue();}
    public static boolean bool(Object v){return Boolean.TRUE.equals(v)||"true".equalsIgnoreCase(clean(v));}
    public static String sha(byte[] b){try{StringBuilder s=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format("%02x",x&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    public static String hash(Object v){return sha(Json.write(sorted(v)).getBytes(StandardCharsets.UTF_8));}
    private static Object sorted(Object v){if(v instanceof Map){Map<String,Object> m=new TreeMap<String,Object>();for(Map.Entry<String,Object> e:map(v).entrySet())m.put(e.getKey(),sorted(e.getValue()));return m;}if(v instanceof List){List<Object>a=new ArrayList<Object>();for(Object x:list(v))a.add(sorted(x));return a;}return v;}
    public static String now(){return Instant.now().toString();}
    public static byte[] readBounded(InputStream in,long limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];long n=0;int count;while((count=in.read(b))!=-1){n+=count;if(n>limit)throw new IOException("Exceeded input limit: "+limit);out.write(b,0,count);}return out.toByteArray();}
    public static LocalDate date(Object v){String t=clean(v);try{return LocalDate.parse(t,DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT));}catch(Exception e){return null;}}
    public static String baseName(Object v){String t=clean(v).replace('\\','/');return t.substring(t.lastIndexOf('/')+1);}
    public static int columnOrder(Object v){int n=0;for(char c:clean(v).toUpperCase(Locale.ROOT).replaceAll("[^A-Z]","").toCharArray())n=n*26+c-'A'+1;return n;}
    public static String columnName(int n){String s="";for(n++;n>0;n=(n-1)/26)s=(char)('A'+(n-1)%26)+s;return s;}
    public static String require(Map<String,Object> m,String k){String t=clean(m.get(k));if(t.isEmpty())throw new IllegalArgumentException("Не заполнено: "+k);return t;}
    public static void put(Map<String,Object> target,String path,Object value){String[]parts=path.split("\\.");Map<String,Object>node=target;for(int i=0;i<parts.length-1;i++){Object next=node.get(parts[i]);if(!(next instanceof Map)){next=obj();node.put(parts[i],next);}node=map(next);}node.put(parts[parts.length-1],value);}
}
