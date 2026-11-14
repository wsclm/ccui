package com.ccui.workbench;

import org.junit.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;
import java.nio.*;
import java.util.regex.*;

public class ResourceAuditTest {
    @Test public void audit()throws Exception{
        String apk=System.getenv("CCUI_TEST_APK");Assume.assumeNotNull(apk);
        Map<String,Integer> types=new TreeMap<>();Set<String> atlasFrames=new HashSet<>();int examples=0,atlases=0,frames=0,reduced=0,missing=0,invalid=0;List<String> unresolved=new ArrayList<>();
        try(ZipFile zip=new ZipFile(apk)){for(ZipEntry entry:Collections.list(zip.entries())){
            if(!entry.getName().startsWith(Workspace.PREFIX)||entry.isDirectory())continue;
            byte[] raw;try(InputStream in=zip.getInputStream(entry)){raw=in.readAllBytes();}
            String kind=Workspace.kind(raw);types.merge(entry.getName().substring(entry.getName().lastIndexOf('.'))+":"+kind,1,Integer::sum);
            if(kind.equals("PLIST")){JSONObject all=new JSONObject(Plists.decode(raw)).getJSONObject("frames");for(Iterator<String> keys=all.keys();keys.hasNext();)atlasFrames.add(keys.next());}
            if(kind.equals("PLIST")&&examples++<3){JSONObject atlas=new JSONObject(Plists.decode(raw));String texture=atlas.getJSONObject("metadata").getString("textureFileName");ZipEntry png=zip.getEntry(Workspace.resourcePath(texture,".1"));byte[] image;try(InputStream in=zip.getInputStream(png)){image=in.readNBytes(24);}java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(image).order(java.nio.ByteOrder.BIG_ENDIAN);System.out.println(texture+" metadata="+atlas.getJSONObject("metadata")+" PNG="+header.getInt(16)+"x"+header.getInt(20));}
            if(kind.equals("PLIST")){atlases++;JSONObject atlas=new JSONObject(Plists.decode(raw)),meta=atlas.getJSONObject("metadata"),all=atlas.getJSONObject("frames");String logical=meta.getString("textureFileName");ZipEntry png=zip.getEntry(Workspace.resourcePath(logical,".1")),ktx=zip.getEntry(Workspace.resourcePath(logical,".2"));if(png==null){missing++;continue;}byte[] image;try(InputStream in=zip.getInputStream(png)){image=in.readNBytes(24);}ByteBuffer header=ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN);float[] size=numbers(meta.optString("size"));if(size.length!=2)size=new float[]{header.getInt(16),header.getInt(20)};if(size[0]!=header.getInt(16)||size[1]!=header.getInt(20))reduced++;for(Iterator<String> keys=all.keys();keys.hasNext();){String key=keys.next();frames++;JSONObject frame=all.getJSONObject(key);float[] rect=numbers(frame.optString("frame",frame.optString("textureRect")));if(rect.length!=4){invalid++;continue;}if(frame.optBoolean("rotated",frame.optBoolean("textureRotated"))){float swap=rect[2];rect[2]=rect[3];rect[3]=swap;}if(rect[0]<0||rect[1]<0||rect[2]<=0||rect[3]<=0||rect[0]+rect[2]>size[0]||rect[1]+rect[3]>size[1]){invalid++;if(invalid<8)System.out.println("Invalid frame "+logical+" "+key+" "+frame+" size="+Arrays.toString(size));}}}
            if(kind.equals("CSB"))auditNodes(new Csb(raw).root(),zip,unresolved);
        }}System.out.println(types);System.out.println("Atlases="+atlases+", frames="+frames+", reduced PNG="+reduced+", missing textures="+missing+", invalid rectangles="+invalid+", missing references="+unresolved.size());for(String reference:new TreeSet<>(unresolved))System.out.println("Missing "+reference+" atlasFrame="+atlasFrames.contains(reference));assertEquals(0,invalid);
    }
    private static float[] numbers(String value){Matcher m=Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(value);List<Float> result=new ArrayList<>();while(m.find())result.add(Float.parseFloat(m.group()));float[] out=new float[result.size()];for(int k=0;k<out.length;k++)out[k]=result.get(k);return out;}
    private static void auditNodes(Csb.Node node,ZipFile zip,List<String> missing)throws Exception{
        if(!node.imagePath.isEmpty()){
            String path=node.plistPath.isEmpty()?node.imagePath:node.plistPath;
            boolean found=node.plistPath.isEmpty()?(zip.getEntry(Workspace.resourcePath(path,".1"))!=null||zip.getEntry(Workspace.resourcePath(path,".2"))!=null):zip.getEntry(Workspace.resourcePath(path,".3"))!=null;
            if(!found)missing.add(path);
            else if(!node.plistPath.isEmpty()){
                byte[] data;try(InputStream in=zip.getInputStream(zip.getEntry(Workspace.resourcePath(path,".3")))){data=in.readAllBytes();}
                JSONObject frames=new JSONObject(Plists.decode(data)).getJSONObject("frames");
                if(!frames.has(node.imagePath)&&!frames.has(node.imagePath.substring(node.imagePath.lastIndexOf('/')+1)))System.out.println("Missing frame: "+path+" / "+node.imagePath);
            }
        }
        for(Csb.Node child:node.children)auditNodes(child,zip,missing);
    }
}
