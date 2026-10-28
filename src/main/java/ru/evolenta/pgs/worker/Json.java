package ru.evolenta.pgs.worker;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small dependency-free JSON codec; rejects duplicate keys and trailing data. */
public final class Json {
    private Json() {}
    public static Object parse(String s) { Parser p=new Parser(s); Object v=p.value(0);p.ws();if(p.i!=p.s.length())throw p.error("Trailing JSON");return v; }
    public static Object read(InputStream in) throws IOException { return parse(new String(Util.readBounded(in, 32*1024*1024),StandardCharsets.UTF_8)); }
    public static String write(Object v) { StringBuilder b=new StringBuilder();append(b,v);return b.toString(); }
    private static void append(StringBuilder b,Object v) {
        if(v==null){b.append("null");return;}
        if(v instanceof Boolean || v instanceof Number){String n=v.toString();if(n.equals("NaN")||n.contains("Infinity"))throw new IllegalArgumentException("Nonfinite JSON");b.append(n);return;}
        if(v instanceof Map){b.append('{');boolean first=true;for(Map.Entry<?,?> e:((Map<?,?>)v).entrySet()){if(!first)b.append(',');first=false;append(b,String.valueOf(e.getKey()));b.append(':');append(b,e.getValue());}b.append('}');return;}
        if(v instanceof Iterable){b.append('[');boolean first=true;for(Object x:(Iterable<?>)v){if(!first)b.append(',');first=false;append(b,x);}b.append(']');return;}
        b.append('"');String s=String.valueOf(v);for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}b.append('"');
    }
    private static final class Parser {
        final String s;int i;Parser(String s){this.s=s.startsWith("\uFEFF")?s.substring(1):s;}
        void ws(){while(i<s.length()&&" \r\n\t".indexOf(s.charAt(i))>=0)i++;}
        IllegalArgumentException error(String text){return new IllegalArgumentException(text+" at "+i);}
        Object value(int depth){if(depth>100)throw error("JSON nesting limit");ws();if(i>=s.length())throw error("Unexpected end");char c=s.charAt(i);
            if(c=='"')return string();
            if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<String,Object>();ws();if(take('}'))return m;do{ws();if(i>=s.length()||s.charAt(i)!='"')throw error("Object key");String k=string();ws();need(':');if(m.containsKey(k))throw error("Duplicate key");m.put(k,value(depth+1));ws();if(take('}'))return m;need(',');}while(true);}
            if(c=='['){i++;List<Object> a=new ArrayList<Object>();ws();if(take(']'))return a;do{a.add(value(depth+1));ws();if(take(']'))return a;need(',');}while(true);}
            for(String literal:Arrays.asList("true","false","null"))if(s.startsWith(literal,i)){i+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}
            int start=i;while(i<s.length()&&"-+0123456789.eE".indexOf(s.charAt(i))>=0)i++;String n=s.substring(start,i);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw error("Invalid number");return new BigDecimal(n);
        }
        boolean take(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        void need(char c){if(!take(c))throw error("Expected "+c);}
        String string(){need('"');StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c<32)throw error("Control in string");if(c!='\\'){b.append(c);continue;}if(i>=s.length())throw error("Escape");c=s.charAt(i++);switch(c){case '"':case '\\':case '/':b.append(c);break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;case 'u':if(i+4>s.length())throw error("Unicode");b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;break;default:throw error("Escape");}}throw error("Unclosed string");}
    }
}
