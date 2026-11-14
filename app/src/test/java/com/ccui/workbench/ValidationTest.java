package com.ccui.workbench;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;

public class ValidationTest {
    static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject o){java.util.TreeMap<String,String> sorted=new java.util.TreeMap<>();java.util.Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();sorted.put(k,canonical(o.get(k)));}return sorted.toString();}
        if(value instanceof org.json.JSONArray a){java.util.List<String> values=new java.util.ArrayList<>();for(int i=0;i<a.length();i++)values.add(canonical(a.get(i)));return values.toString();}
        return String.valueOf(value);
    }
    @Test public void plistKeepsIntegerBooleanAndUnicode()throws Exception {
        String json="{\"integer\":9007199254740993,\"bool\":true,\"text\":\"中文长文字\",\"array\":[1,2.5,false],\"data\":{\"$plistData\":\"AAECAw==\"}}";
        JSONObject result=new JSONObject(Plists.decode(Plists.encode(json)));
        assertEquals(canonical(new JSONObject(json)),canonical(result));
        assertEquals(9007199254740993L,result.getLong("integer"));
        assertTrue(result.getBoolean("bool"));
    }
    @Test public void malformedResourcesStayUnclassified(){
        assertEquals("OTHER",Workspace.kind(new byte[64]));
        assertEquals("OTHER",Workspace.kind(new byte[]{1,2,3}));
        assertEquals("JSON",Workspace.kind("{\"name\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
