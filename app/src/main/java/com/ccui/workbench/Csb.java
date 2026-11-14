package com.ccui.workbench;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** FlatBuffers edits preserving existing tables, with visibility expansion when needed. */
public final class Csb {
    public byte[] bytes;
    Runnable beforeEdit=()->{};
    private byte[] bufferBytes;
    private ByteBuffer buffer;
    public Csb(byte[] bytes) { this.bytes = bytes.clone(); }
    private ByteBuffer buf() { if(bufferBytes!=bytes){bufferBytes=bytes;buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}return buffer; }
    int i(int p) { return buf().getInt(p); }
    int u16(int p) { return buf().getShort(p) & 65535; }
    int field(int t, int index) {
        if (t == 0) return 0;
        int v = t - i(t);
        if (v < 0 || v + 4 > bytes.length) throw new IllegalArgumentException("无效的 CSB 表");
        if (4 + index * 2 >= u16(v)) return 0;
        int off = u16(v + 4 + index * 2);
        return off == 0 ? 0 : t + off;
    }
    int ptr(int t, int index) { int p = field(t,index); return p == 0 ? 0 : p + i(p); }
    String string(int p) {
        if (p == 0) return "";
        int n = i(p);
        if (n < 0 || n > bytes.length-p-5 || bytes[p+4+n]!=0) throw new IllegalArgumentException("无效字符串");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,p+4,n)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException(e); }
    }
    public static final class Binding {
        final int offset; final char type;
        Binding(int p,char t) { offset=p; type=t; }
    }
    public static final class Node {
        public int id, textSlot, imageSlot, plistSlot, imageTypeSlot,widgetSlot;
        public String kind, name, text, imagePath="", plistPath="";
        public Node parent;
        public final List<Node> children=new ArrayList<>();
        public final Map<String,Double> props=new LinkedHashMap<>();
        public final Map<String,Binding> bindings=new LinkedHashMap<>();
        public double get(String k) { return props.getOrDefault(k,0d); }
        @Override public String toString() { return (name.isEmpty()?kind:name)+" · "+kind; }
    }
    private void scalar(Node n,String name,int table,int index,char type,double fallback,int component) {
        int p=field(table,index);
        double v=fallback;
        if(p!=0) { p+=component; v=type=='f'?buf().getFloat(p):type=='i'?i(p):bytes[p]&255; n.bindings.put(name,new Binding(p,type)); }
        n.props.put(name,v);
    }
    public Node root() {
        int t=i(0);
        String version=string(ptr(t,0));
        if(!version.startsWith("3.")&&!version.startsWith("2.")) throw new IllegalArgumentException("不支持的 CSB 版本");
        return walk(ptr(t,3),null,0,new HashSet<>());
    }
    private Node walk(int t,Node parent,int depth,Set<Integer> visited) {
        if(t==0||depth>200||!visited.add(t)) throw new IllegalArgumentException("无效节点树");
        Node n=new Node(); n.id=t; n.parent=parent; n.kind=string(ptr(t,0));
        boolean direct=n.kind.equals("Layer")||n.kind.equals("Node");
        int options=ptr(t,2),d=ptr(options,0),w=direct?d:ptr(d,0);
        n.widgetSlot=field(direct?options:d,0);
        String[] names={"x","y","width","height","scaleX","scaleY","anchorX","anchorY","rotation"};
        int[] indices={7,7,11,11,8,8,9,9,2}, components={0,4,0,4,0,4,0,4,0};
        for(int k=0;k<names.length;k++) scalar(n,names[k],w,indices[k],'f',k==4||k==5?1:0,components[k]);
        scalar(n,"visible",w,4,'B',1,0); scalar(n,"alpha",w,5,'B',255,0);
        n.name=string(ptr(w,0));
        int ti=switch(n.kind){case "Text"->4; case "Button"->5; case "TextBMFont","TextAtlas"->2; default->-1;};
        if(ti>=0) { n.textSlot=field(d,ti); if(n.textSlot!=0)n.text=string(n.textSlot+i(n.textSlot)); }
        int fi=switch(n.kind){case "Text"->3; case "Button"->7; default->-1;};
        if(fi>=0) scalar(n,"fontSize",d,fi,'i',20,0);
        if(Set.of("Sprite","ImageView","Button","Panel").contains(n.kind)) {
            int r=ptr(d,1); if(r!=0){ n.imageSlot=field(r,0);n.plistSlot=field(r,1);n.imageTypeSlot=field(r,2);n.imagePath=string(ptr(r,0)); n.plistPath=string(ptr(r,1)); }
        }
        int v=ptr(t,1), count=v==0?0:i(v);
        if(count<0||count>100000) throw new IllegalArgumentException("无效子节点数量");
        for(int k=0;k<count;k++){int p=v+4+k*4;n.children.add(walk(p+i(p),n,depth+1,visited));}
        return n;
    }
    public void set(Node n,String key,double value) {
        Binding b=n.bindings.get(key);
        if(b==null&&key.equals("visible")){if(value!=0&&value!=1)throw new IllegalArgumentException("可见性必须为 0 或 1");addVisibility(n,value);return;}
        if(b==null) throw new IllegalArgumentException("原文件缺少字段："+key);
        if(!Double.isFinite(value)||Math.abs(value)>1e7)throw new IllegalArgumentException("数值超出范围");
        if(Set.of("width","height","fontSize").contains(key)&&value<0)throw new IllegalArgumentException("尺寸不能小于零");
        if(b.type=='B'&&(value<0||value>(key.equals("visible")?1:255)))throw new IllegalArgumentException("数值超出范围");
        beforeEdit.run();if(b.type=='f')buf().putFloat(b.offset,(float)value);else if(b.type=='i')buf().putInt(b.offset,(int)value);else bytes[b.offset]=(byte)(int)value;
        n.props.put(key,value);
    }
    public void setText(Node n,String text) { if(n.textSlot==0)throw new IllegalArgumentException("没有文字字段"); append(text,List.of(n.textSlot)); n.text=text; }
    private int allocate(int length){int p=(bytes.length+3)&~3;bytes=Arrays.copyOf(bytes,p+length);return p;}
    private int cloneString(int source){int size=i(source)+5,p=allocate(size);System.arraycopy(bytes,source,bytes,p,size);return p;}
    private int cloneTable(int source,int extra,int... pointers){
        int vt=source-i(source),vsize=u16(vt),size=u16(vt+2),newSize=extra>0?Math.max(14,vsize):vsize,newV=allocate(newSize),target=allocate(size+extra);
        System.arraycopy(bytes,vt,bytes,newV,vsize);System.arraycopy(bytes,source,bytes,target,size);buf().putInt(target,target-newV);buf().putShort(newV+2,(short)(size+extra));
        buf().putShort(newV,(short)newSize);
        for(int index:pointers){int old=field(source,index);if(old==0)continue;int value=index==20?cloneTable(old+i(old),0,10,11):cloneString(old+i(old));buf().putInt(target+old-source,value-(target+old-source));}
        return target;
    }
    private void addVisibility(Node n,double value){
        if(n.widgetSlot==0)throw new IllegalArgumentException("节点缺少基础属性表");
        int old=n.widgetSlot+i(n.widgetSlot),vt=old-i(old),vsize=u16(vt);
        if(u16(vt+2)<4)throw new IllegalArgumentException("基础属性表尺寸无效："+n.kind+" / "+n.name+" / "+old+" / "+vsize);
        // Preserve extension scalars verbatim; refuse a possible unknown forward reference.
        for(int k=21;k<(vsize-4)/2;k++){int start=field(old,k);if(start==0)continue;int end=old+u16(vt+2);for(int j=0;j<(vsize-4)/2;j++){int next=field(old,j);if(next>start)end=Math.min(end,next);}if(start%4==0&&end-start>=4){long destination=(long)start+i(start);if(i(start)>0&&destination%4==0&&destination+4<=bytes.length)throw new IllegalArgumentException("暂不支持扩展包含未知引用的基础属性表："+n.kind+" / 字段 "+k);}}
        beforeEdit.run();int size=u16(vt+2),target=cloneTable(old,1,0,16,17,18,19,20),newV=target-i(target);
        buf().putShort(newV+12,(short)size);bytes[target+size]=(byte)value;buf().putInt(n.widgetSlot,target-n.widgetSlot);
        for(Map.Entry<String,Binding> entry:n.bindings.entrySet()){Binding binding=entry.getValue();if(binding.offset>=old&&binding.offset<old+size)entry.setValue(new Binding(target+binding.offset-old,binding.type));}
        n.bindings.put("visible",new Binding(target+size,'B'));n.props.put("visible",value);
    }
    public void delete(Node node) {
        if(node.parent==null)throw new IllegalArgumentException("根节点不能删除");
        Node parent=node.parent;int index=parent.children.indexOf(node);
        int vector=ptr(parent.id,1),count=vector==0?0:i(vector);
        if(index<0||count!=parent.children.size())throw new IllegalArgumentException("节点已失效，请重新选择");
        beforeEdit.run();for(int k=index;k<count-1;k++){int destination=vector+4+k*4,source=destination+4;buf().putInt(destination,source+i(source)-destination);}
        buf().putInt(vector,count-1);parent.children.remove(index);
    }
    public void setImage(Node n,String path){if(n.imageSlot==0)throw new IllegalArgumentException("此节点没有图片字段");path=Workspace.textureName(path);append(path,List.of(n.imageSlot));if(n.plistSlot!=0)append("",List.of(n.plistSlot));if(n.imageTypeSlot!=0)buf().putInt(n.imageTypeSlot,0);n.imagePath=path;n.plistPath="";}
    private void append(String text,List<Integer> slots) {
        byte[] raw=text.getBytes(StandardCharsets.UTF_8);
        if(raw.length>1000000)throw new IllegalArgumentException("字符串过长");
        beforeEdit.run();
        int p=(bytes.length+3)&~3;
        bytes=Arrays.copyOf(bytes,p+raw.length+5);buf().putInt(p,raw.length);System.arraycopy(raw,0,bytes,p+4,raw.length);
        for(int slot:slots)buf().putInt(slot,p-slot);
    }
    public static final class TextRef {
        public int offset; public String text; public final List<Integer> slots=new ArrayList<>();
    }
    public List<TextRef> strings() {
        Map<Integer,TextRef> found=new LinkedHashMap<>();
        for(int slot=4;slot<bytes.length-4;slot+=4) {
            int p=slot+i(slot);if(p%4!=0||p<=slot||p+5>bytes.length)continue;
            try {
                String s=string(p);if(s.isEmpty()||s.length()>20000||s.chars().anyMatch(c->c<32&&c!=9&&c!=10&&c!=13))continue;
                TextRef ref=found.get(p);if(ref==null){ref=new TextRef();ref.offset=p;ref.text=s;found.put(p,ref);}ref.slots.add(slot);
            } catch(RuntimeException ignored) { }
        }
        return new ArrayList<>(found.values());
    }
    public void replace(TextRef ref,String value) { append(value,ref.slots); root(); }
}
