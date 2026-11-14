package com.ccui.workbench;

import com.dd.plist.*;
import org.json.*;
import java.util.*;

final class Plists {
    static Object json(NSObject value) throws Exception {
        if(value instanceof NSDictionary d){JSONObject o=new JSONObject();for(String k:d.allKeys())o.put(k,json(d.objectForKey(k)));return o;}
        if(value instanceof NSArray a){JSONArray o=new JSONArray();for(NSObject v:a.getArray())o.put(json(v));return o;}
        if(value instanceof NSString s)return s.getContent();
        if(value instanceof NSNumber n){if(n.isBoolean())return n.boolValue();if(n.isInteger())return n.longValue();return n.doubleValue();}
        if(value instanceof NSData d)return new JSONObject().put("$plistData",java.util.Base64.getEncoder().encodeToString(d.bytes()));
        if(value instanceof NSDate d)return new JSONObject().put("$plistDate",d.getDate().getTime());
        throw new IllegalArgumentException("不支持的 plist 类型："+value.getClass().getSimpleName());
    }
    static NSObject plist(Object v)throws Exception{
        if(v instanceof JSONObject o){
            if(o.length()==1&&o.has("$plistData"))return new NSData(java.util.Base64.getDecoder().decode(o.getString("$plistData")));
            if(o.length()==1&&o.has("$plistDate"))return new NSDate(new Date(o.getLong("$plistDate")));
            NSDictionary d=new NSDictionary();Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();d.put(k,plist(o.get(k)));}return d;
        }
        if(v instanceof JSONArray a){NSArray r=new NSArray(a.length());for(int i=0;i<a.length();i++)r.setValue(i,plist(a.get(i)));return r;}
        if(v instanceof Boolean b)return new NSNumber(b);
        if(v instanceof Integer||v instanceof Long)return new NSNumber(((Number)v).longValue());
        if(v instanceof Number n)return new NSNumber(n.doubleValue());
        if(v instanceof String s)return new NSString(s);
        throw new IllegalArgumentException("plist 不支持 null");
    }
    static String decode(byte[] b)throws Exception{return ((JSONObject)json(PropertyListParser.parse(b))).toString(2);}
    static byte[] encode(String text)throws Exception{return BinaryPropertyListWriter.writeToArray(plist(new JSONTokener(text).nextValue()));}
}
