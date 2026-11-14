package com.ccui.workbench;

import org.junit.*;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class PortIntegrationTest {
    int count(Csb.Node n){int total=1;for(Csb.Node c:n.children)total+=count(c);return total;}
    Csb.Node editable(Csb.Node n,String property){if(n.bindings.containsKey(property))return n;for(Csb.Node c:n.children){Csb.Node r=editable(c,property);if(r!=null)return r;}return null;}
    Csb.Node textNode(Csb.Node n){if(n.textSlot!=0)return n;for(Csb.Node c:n.children){Csb.Node r=textNode(c);if(r!=null)return r;}return null;}
    Csb.Node imageNode(Csb.Node n){if(n.imageSlot!=0)return n;for(Csb.Node c:n.children){Csb.Node r=imageNode(c);if(r!=null)return r;}return null;}
    void flatten(Csb.Node n,List<Csb.Node> nodes){nodes.add(n);for(Csb.Node child:n.children)flatten(child,nodes);}
    void visibilityRoundTrip(byte[] raw){
        Csb c=new Csb(raw);List<Csb.Node> nodes=new ArrayList<>();flatten(c.root(),nodes);
        for(Csb.Node n:nodes){
            Map<String,Double> before=new LinkedHashMap<>(n.props);c.set(n,"visible",0);
            Csb check=new Csb(c.bytes);int w=n.widgetSlot+check.i(n.widgetSlot);assertEquals(0,check.bytes[check.field(w,4)]);
            c.set(n,"visible",1);
            for(String key:before.keySet())if(!key.equals("visible"))assertEquals(before.get(key),n.props.get(key));
        }
        List<Csb.Node> reread=new ArrayList<>();flatten(new Csb(c.bytes).root(),reread);assertEquals(nodes.size(),reread.size());
        for(int k=0;k<nodes.size();k++){Csb.Node a=nodes.get(k),b=reread.get(k);assertEquals(a.id,b.id);assertEquals(a.name,b.name);assertEquals(a.text,b.text);assertEquals(a.imagePath,b.imagePath);assertEquals(a.plistPath,b.plistPath);assertEquals(a.props,b.props);}
        EditHistory history=new EditHistory();history.record(raw,c.bytes);byte[] edited=c.bytes.clone();assertArrayEquals(raw,history.undo(c.bytes));assertArrayEquals(edited,history.redo(raw));
    }
    @Test public void actualApkRoundTrip()throws Exception{
        String apk=System.getenv("CCUI_TEST_APK");Assume.assumeTrue("Set CCUI_TEST_APK to run actual APK integration",apk!=null);
        File output=Files.createTempDirectory("ccui-android-port-").toFile();
        int csbs=0,plists=0,nodes=0,recovered=0,textEdits=0;long coldMillis=0;String modifiedPath=null;byte[] expected=null;
        try(Workspace w=new Workspace(new File(apk),output,s->{})){
            coldMillis=w.scanMillis;assertFalse(w.indexRestored);
            try(Workspace cached=new Workspace(new File(apk),output,s->{})){assertTrue(cached.indexRestored);assertEquals(w.entries.size(),cached.entries.size());for(int i=0;i<w.entries.size();i++){assertEquals(w.entries.get(i).path,cached.entries.get(i).path);assertEquals(w.entries.get(i).searchText,cached.entries.get(i).searchText);}}
            for(Workspace.Entry e:w.entries){if(e.recovered)recovered++;byte[] raw=w.read(e.path);
                if(e.type.equals("CSB")){
                    csbs++;try{visibilityRoundTrip(raw);}catch(RuntimeException error){throw new IllegalArgumentException(e.path,error);}Csb c=new Csb(raw);Csb.Node root=c.root();nodes+=count(root);assertArrayEquals(raw,c.bytes);
                    Csb.Node n=editable(root,"x");if(n!=null){double old=n.get("x");c.set(n,"x",old+7.25);assertEquals(old+7.25,editable(c.root(),"x").get("x"),.01);}
                     Csb.Node visible=c.root();int visibilityId=visible.id;c.set(visible,"visible",0);assertEquals(0,c.root().get("visible"),0);c.set(visible,"visible",1);assertEquals(1,c.root().get("visible"),0);assertEquals(visibilityId,c.root().id);
                     Csb.Node t=textNode(c.root());if(t!=null){String changed="安卓文字回读验证 · "+"扩展内容".repeat(40);c.setText(t,changed);assertEquals(changed,textNode(c.root()).text);textEdits++;}
                    c.root();if(modifiedPath==null&&n!=null){modifiedPath=e.path;expected=c.bytes.clone();w.save(e.path,expected);}
                }else if(e.type.equals("PLIST")){
                    plists++;String decoded=Plists.decode(raw);byte[] encoded=Plists.encode(decoded);assertEquals(ValidationTest.canonical(new JSONObject(decoded)),ValidationTest.canonical(new JSONObject(Plists.decode(encoded))));
                }
            }
            assertTrue(csbs>0);assertEquals(0,plists);assertTrue(recovered>0);assertTrue(nodes>0);assertTrue(textEdits>0);assertNotNull(modifiedPath);
            int deletions=0;
            for(Workspace.Entry e:w.entries){Csb c=new Csb(w.read(e.path));Csb.Node tree=c.root();if(tree.children.isEmpty())continue;byte[] original=c.bytes.clone();int total=count(tree),removed=count(tree.children.get(0));List<Integer> remaining=new ArrayList<>();for(int k=1;k<tree.children.size();k++)remaining.add(tree.children.get(k).id);EditHistory history=new EditHistory();c.delete(tree.children.get(0));history.record(original,c.bytes);Csb.Node changed=c.root();assertEquals(total-removed,count(changed));for(int k=0;k<remaining.size();k++)assertEquals((int)remaining.get(k),changed.children.get(k).id);byte[] deleted=c.bytes.clone();c.bytes=history.undo(c.bytes);assertArrayEquals(original,c.bytes);assertEquals(total,count(c.root()));c.bytes=history.redo(c.bytes);assertArrayEquals(deleted,c.bytes);try{c.delete(c.root());fail("Root deletion must fail");}catch(IllegalArgumentException expectedError){}deletions++;}
            assertTrue(deletions>0);
            assertArrayEquals(expected,w.read(modifiedPath));
            if("1".equals(System.getenv("CCUI_TEST_BUILD"))){File built=w.build(modifiedPath,s->{});try(ZipFile changed=new ZipFile(built)){
                assertArrayEquals(expected,changed.getInputStream(changed.getEntry(modifiedPath)).readAllBytes());assertEquals(1,changed.size());
            }
                for(Workspace.Entry e:w.entries){Csb c=new Csb(w.read(e.path));Csb.Node image=imageNode(c.root());if(image==null)continue;
                    byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6mQAAAAASUVORK5CYII=");
                     w.addImage("custom/new.png",png);w.addImage("custom/unused.png",png);c.setImage(image,"custom/new.png");w.save(e.path,c.bytes);if(e.path.equals(modifiedPath))expected=c.bytes.clone();
                      assertEquals("UITextures/custom/new.png",imageNode(c.root()).imagePath);assertNull(w.findLogical("custom/new.png",".2"));
                       try(ZipFile bundle=new ZipFile(w.build(e.path,s->{}))){assertEquals(2,bundle.size());assertArrayEquals(c.bytes,bundle.getInputStream(bundle.getEntry(e.path)).readAllBytes());assertArrayEquals(png,bundle.getInputStream(bundle.getEntry(w.findLogical("custom/new.png",".1").path)).readAllBytes());}
                      assertEquals(0,w.importLogical(w.logicalBundle(e.path)));
                      byte[] scaled=TextureCodec.encode(png,2,4);java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(scaled).order(java.nio.ByteOrder.LITTLE_ENDIAN);assertEquals(2,header.getInt(36));assertEquals(4,header.getInt(40));assertEquals(84,scaled.length);assertEquals(0x93b4,header.getInt(28));assertEquals(0,header.getInt(16));break;
                }
            }
            JSONObject report=new JSONObject().put("csb",csbs).put("nodes",nodes).put("visibilityRoundTripNodes",nodes).put("recoveredNames",recovered).put("textEdits",textEdits).put("deletionUndoRedoScenes",deletions).put("coldIndexMillis",coldMillis).put("bundleTested","1".equals(System.getenv("CCUI_TEST_BUILD")));
            File reportFile=new File("build/reports/port-integration.json");reportFile.getParentFile().mkdirs();Files.writeString(reportFile.toPath(),report.toString(2));System.out.println(report.toString(2));
        }
        try(Workspace restored=new Workspace(new File(apk),output,s->{})){assertFalse("Saved changes must invalidate the disk index",restored.indexRestored);assertArrayEquals(expected,restored.read(modifiedPath));}
        try(Workspace restored=new Workspace(new File(apk),output,s->{})){assertTrue(restored.indexRestored);System.out.println("Warm index milliseconds: "+restored.scanMillis);}
    }
}
