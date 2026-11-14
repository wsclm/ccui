package com.ccui.workbench;

import android.graphics.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.*;

public final class Workspace implements Closeable {
    public static final String PREFIX="assets/assets/ccui_packs/";
    public final File source,dir;
    private final ZipFile zip;
    public final List<Entry> entries=new ArrayList<>();
    private final Map<String,Entry> byPath=new HashMap<>();
    private final Map<String,String> added=new TreeMap<>();
    public String sourceName;
    public boolean indexRestored;
    public long scanMillis;
    private static final java.util.regex.Pattern RESOURCE_SUFFIX=java.util.regex.Pattern.compile(".*\\.(csb|plist|png|json)$");
    public static final class Entry {
        public String path,type,name,category,hints="";
        public long size;
        public boolean recovered;
        public String searchText="";
        @Override public String toString(){return name;}
    }
    private static byte[] readBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}
    public Workspace(File source,File dir,Consumer<String> progress)throws Exception {
        long started=System.nanoTime();this.source=source;this.dir=dir;dir.mkdirs();zip=new ZipFile(source);
        File nameFile=new File(dir,"source-name.txt");sourceName=nameFile.exists()?new String(Files.readAllBytes(nameFile.toPath()),StandardCharsets.UTF_8):source.getName();
        File addedFile=new File(dir,"added-images.json");if(addedFile.exists()){JSONObject o=new JSONObject(new String(Files.readAllBytes(addedFile.toPath()),StandardCharsets.UTF_8));Iterator<String> keys=o.keys();while(keys.hasNext()){String key=keys.next();added.put(key,o.getString(key));}}
        if(restoreIndex()){indexRestored=true;scanMillis=(System.nanoTime()-started)/1000000;progress.accept("已恢复 CCUI 索引 "+entries.size());return;}
        Set<String> candidates=new TreeSet<>();
        Enumeration<? extends ZipEntry> it=zip.entries();int count=0;
        while(it.hasMoreElements()){
            ZipEntry z=it.nextElement();if(z.isDirectory()||!z.getName().startsWith(PREFIX))continue;
            Entry e=new Entry();e.path=z.getName();e.size=z.getSize();
            if(!e.path.endsWith(".3")){byPath.put(e.path,e);continue;}
            byte[] raw;try(InputStream in=zip.getInputStream(z)){raw=readBytes(in);}
            e.type=kind(raw);
            if(e.type.equals("CSB")){
                File saved=new File(dir,"overrides/"+e.path);if(saved.exists())raw=Files.readAllBytes(saved.toPath());
                StringBuilder hints=new StringBuilder();for(Csb.TextRef s:new Csb(raw).strings()){
                    if(s.text.contains("/")||RESOURCE_SUFFIX.matcher(s.text).matches())candidates.add(s.text);
                    hints.append(s.text).append(' ');
                }e.hints=hints.toString();
            }else if(e.type.equals("PLIST")){
                JSONObject o=new JSONObject(Plists.decode(raw));JSONObject f=o.optJSONObject("frames");
                if(f!=null){Iterator<String> keys=f.keys();while(keys.hasNext())candidates.add(keys.next());}
                JSONObject meta=o.optJSONObject("metadata");if(meta!=null)candidates.add(meta.optString("textureFileName"));e.hints=o.toString();
            }
            if(e.type.equals("CSB"))entries.add(e);byPath.put(e.path,e);if(++count%100==0)progress.accept("扫描 CCUI "+count);
        }
        for(String logical:candidates){for(String ext:List.of(".1",".2",".3")){
            Entry e=byPath.get(resourcePath(logical,ext));if(e!=null&&!e.recovered){e.name=ext.equals(".3")?logical:textureName(logical);e.recovered=true;}
        }}
        for(Entry e:entries){if(!e.recovered)e.name=e.path.substring(e.path.lastIndexOf('/')+1);int slash=e.name.lastIndexOf('/');e.category=e.recovered?(slash<0?"根目录":e.name.substring(0,slash)):"未还原路径/"+e.type;}
        entries.sort(Comparator.comparing(e->e.name));for(Entry e:entries)updateSearch(e);writeIndex();scanMillis=(System.nanoTime()-started)/1000000;
    }
    private static void updateSearch(Entry e){e.searchText=(e.name+" "+e.path+" "+e.hints).toLowerCase(Locale.ROOT);}
    private File indexFile(){return new File(dir,"ccui-index-v1.bin");}
    private long overrideStamp(){File folder=new File(dir,"overrides");return folder.exists()?stamp(folder):0;}
    private static long stamp(File file){if(file.isFile())return file.lastModified()*31+file.length()+file.getName().hashCode();long result=0;File[] files=file.listFiles();if(files!=null)for(File child:files)result+=stamp(child);return result;}
    private static void writeString(DataOutputStream out,String value)throws IOException{byte[] data=(value==null?"":value).getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);}
    private static String readString(DataInputStream in)throws IOException{int size=in.readInt();if(size<0||size>32*1024*1024)throw new IOException("索引字符串无效");byte[] data=new byte[size];in.readFully(data);return new String(data,StandardCharsets.UTF_8);}
    private boolean restoreIndex(){
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(indexFile())))){
            if(in.readInt()!=0x43435531||in.readLong()!=source.length()||in.readLong()!=source.lastModified()||in.readLong()!=overrideStamp())return false;
            int count=in.readInt();if(count<0||count>200000)throw new IOException("索引数量无效");
            for(int i=0;i<count;i++){Entry e=new Entry();e.path=readString(in);e.type=readString(in);e.name=readString(in);e.category=readString(in);e.hints=readString(in);e.size=in.readLong();e.recovered=in.readBoolean();if(!e.path.startsWith(PREFIX)||zip.getEntry(e.path)==null)throw new IOException("索引路径失效");byPath.put(e.path,e);if("CSB".equals(e.type)){updateSearch(e);entries.add(e);}}
            return true;
        }catch(Exception ignored){entries.clear();byPath.clear();return false;}
    }
    private void writeIndex(){
        File partial=new File(dir,"ccui-index-v1.partial");
        try{try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))){
            out.writeInt(0x43435531);out.writeLong(source.length());out.writeLong(source.lastModified());out.writeLong(overrideStamp());out.writeInt(byPath.size());
            List<Entry> ordered=new ArrayList<>(entries);for(Entry e:byPath.values())if(!"CSB".equals(e.type))ordered.add(e);
            for(Entry e:ordered){writeString(out,e.path);writeString(out,e.type);writeString(out,e.name);writeString(out,e.category);writeString(out,e.hints);out.writeLong(e.size);out.writeBoolean(e.recovered);}
        }Files.move(partial.toPath(),indexFile().toPath(),StandardCopyOption.REPLACE_EXISTING);}catch(IOException ignored){partial.delete();}
    }
    public static String kind(byte[] b){
        if(b.length>=8&&b[0]==(byte)137&&new String(b,1,3,StandardCharsets.US_ASCII).equals("PNG"))return "PNG";
        if(b.length>12&&b[0]==(byte)171&&new String(b,1,6,StandardCharsets.US_ASCII).equals("KTX 11"))return "KTX";
        if(b.length>=8&&new String(b,0,8,StandardCharsets.US_ASCII).equals("bplist00"))return "PLIST";
        String head=new String(b,0,Math.min(300,b.length),StandardCharsets.ISO_8859_1);
        if(head.contains("\nsize:")&&head.contains("\nfilter:"))return "SPINE_ATLAS";
        if(b.length>32)try{new Csb(b).root();return "CSB";}catch(RuntimeException ignored){}
        if(head.substring(0,Math.min(80,head.length())).matches("(?s).*3\\.[6789]\\.\\d+.*"))return "SPINE";
        try{JSONTokener tokener=new JSONTokener(new String(b,StandardCharsets.UTF_8));Object v=tokener.nextValue();if((v instanceof JSONObject||v instanceof JSONArray)&&tokener.nextClean()==0)return "JSON";}catch(Exception ignored){}
        return "OTHER";
    }
    public static String md5(byte[] b)throws Exception{byte[] h=MessageDigest.getInstance("MD5").digest(b);char[] chars=new char[h.length*2];String hex="0123456789abcdef";for(int i=0;i<h.length;i++){chars[i*2]=hex.charAt((h[i]&255)>>>4);chars[i*2+1]=hex.charAt(h[i]&15);}return new String(chars);}
    public File override(String path){if(!byPath.containsKey(path)&&!added.containsKey(path))throw new IllegalArgumentException("未知资源");return new File(dir,"overrides/"+path);}
    public byte[] read(String path)throws Exception{File f=override(path);if(f.exists())return Files.readAllBytes(f.toPath());try(InputStream in=zip.getInputStream(zip.getEntry(path))){return readBytes(in);}}
    public byte[] readHeader(String path,int length)throws Exception{File file=override(path);try(InputStream in=file.exists()?new FileInputStream(file):zip.getInputStream(zip.getEntry(path))){byte[] result=new byte[length];int used=0,n;while(used<length&&(n=in.read(result,used,length-used))!=-1)used+=n;return Arrays.copyOf(result,used);}}
    public void save(String path,byte[] bytes)throws Exception{File f=override(path);f.getParentFile().mkdirs();File temp=new File(f+".partial");Files.write(temp.toPath(),bytes);Files.move(temp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);Entry e=byPath.get(path);if(e!=null&&"CSB".equals(e.type)){StringBuilder hints=new StringBuilder();for(Csb.TextRef ref:new Csb(bytes).strings())hints.append(ref.text).append(' ');e.hints=hints.toString();updateSearch(e);}}
    public int modifiedCount(){int n=0;for(Entry e:entries)if(override(e.path).exists())n++;return n;}
    public void replace(Entry e,byte[] raw)throws Exception{
        byte[] original=read(e.path);if(!kind(raw).equals(e.type))throw new IllegalArgumentException("替换文件格式不一致");
        if(e.type.equals("PNG")){for(int p=16;p<24;p++)if(raw[p]!=original[p])throw new IllegalArgumentException("PNG 必须保持原尺寸");}
        if(e.type.equals("KTX")){if(raw.length<64)throw new IllegalArgumentException("KTX 文件过短");for(int p=12;p<64;p++)if(raw[p]!=original[p])throw new IllegalArgumentException("KTX 头部参数必须一致");}
        if(e.type.equals("CSB"))new Csb(raw).root();if(e.type.equals("PLIST"))Plists.decode(raw);save(e.path,raw);
    }
    public static String textureName(String logical){return logical.startsWith("UITextures/")?logical:"UITextures/"+logical;}
    public static String resourcePath(String logical,String ext)throws Exception {String key=logical;if(!ext.equals(".3")){key=textureName(logical).replaceFirst("(?i)\\.(png|ktx)$","");}String d=md5(key.getBytes(StandardCharsets.UTF_8));return PREFIX+d.substring(0,2)+"/"+d+ext;}
    public Entry findLogical(String logical,String ext)throws Exception {String path=resourcePath(logical,ext);Entry e=byPath.get(path);if(e==null&&added.containsKey(path)){e=new Entry();e.path=path;e.type="PNG";e.name=logical;}return e;}
    public Entry find(String path){return byPath.get(path);}
    public void addImage(String logical,byte[] raw)throws Exception{
        if(!logical.matches("[\\p{L}\\p{N}_./ -]+\\.[pP][nN][gG]")||logical.startsWith("/")||Arrays.stream(logical.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals("..")||p.equals(".")))throw new IllegalArgumentException("请输入相对 PNG 路径，例如 custom/button.png");
        if(raw.length<33||!kind(raw).equals("PNG"))throw new IllegalArgumentException("请选择有效 PNG");
        logical=textureName(logical);String path=resourcePath(logical,".1");
        if(byPath.containsKey(path))throw new IllegalArgumentException("图片路径已存在，请使用新路径");added.put(path,logical);save(path,raw);
        Files.write(new File(dir,"added-images.json").toPath(),new JSONObject(added).toString().getBytes(StandardCharsets.UTF_8));
    }
    private static float[] numbers(String text){Matcher m=Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text);ArrayList<Float> values=new ArrayList<>();while(m.find())values.add(Float.parseFloat(m.group()));float[] result=new float[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
    private JSONObject frameFor(Csb.Node node)throws Exception{
        if(node.plistPath.isEmpty())return null;
        Entry plist=findLogical(node.plistPath,".3");if(plist==null)throw new IOException("找不到原图集："+node.plistPath);
        JSONObject atlas=new JSONObject(Plists.decode(read(plist.path))),frames=atlas.optJSONObject("frames");
        JSONObject frame=frames==null?null:frames.optJSONObject(node.imagePath);if(frame==null&&frames!=null)frame=frames.optJSONObject(node.imagePath.substring(node.imagePath.lastIndexOf('/')+1));
        if(frame==null)throw new IOException("图集中找不到原图片帧："+node.imagePath);return new JSONObject().put("atlas",atlas).put("frame",frame);
    }
    public int[] replacementSize(Csb.Node node)throws Exception{
        JSONObject data=frameFor(node);if(data==null){Entry image=findLogical(node.imagePath,".1");if(image==null)throw new IOException("找不到原 PNG");Bitmap b=BitmapFactory.decodeByteArray(read(image.path),0,(int)image.size);if(b==null)throw new IOException("原 PNG 无法读取");int[] size={b.getWidth(),b.getHeight()};b.recycle();return size;}
        JSONObject atlas=data.getJSONObject("atlas"),frame=data.getJSONObject("frame");float[] r=numbers(frame.optString("frame",frame.optString("textureRect")));if(r.length!=4)throw new IOException("原图片裁切范围无效");boolean rotated=frame.optBoolean("rotated",frame.optBoolean("textureRotated"));return new int[]{Math.max(1,Math.round(rotated?r[3]:r[2])),Math.max(1,Math.round(rotated?r[2]:r[3]))};
    }
    public void replaceImage(Csb.Node node,byte[] replacement,boolean crop)throws Exception{
        if(!kind(replacement).equals("PNG"))throw new IOException("请选择有效 PNG");
        JSONObject data=frameFor(node);String logical=node.imagePath;int left=0,top=0,width=0,height=0;boolean rotated=false;float coordinateWidth=0,coordinateHeight=0;
        if(data==null){Entry image=findLogical(logical,".1");if(image==null)throw new IOException("找不到原 PNG");Bitmap base=decode(read(image.path)),source=decode(replacement);int factor=scaleFactor(base.getWidth(),base.getHeight(),base.getWidth(),base.getHeight(),source.getWidth(),source.getHeight());base=enlarge(base,factor);Bitmap result=compose(base,source,0,0,base.getWidth(),base.getHeight(),crop,false);save(image.path,result,logical,factor);return;}
        JSONObject atlas=data.getJSONObject("atlas"),frame=data.getJSONObject("frame"),metadata=atlas.optJSONObject("metadata");String texture=metadata==null?"":metadata.optString("textureFileName",metadata.optString("realTextureFileName"));if(texture.isEmpty())throw new IOException("图集缺少纹理文件名");logical=textureName(texture);float[] r=numbers(frame.optString("frame",frame.optString("textureRect")));if(r.length!=4)throw new IOException("原图片裁切范围无效");rotated=frame.optBoolean("rotated",frame.optBoolean("textureRotated"));left=Math.round(r[0]);top=Math.round(r[1]);width=Math.max(1,Math.round(r[2]));height=Math.max(1,Math.round(r[3]));if(rotated){int swap=width;width=height;height=swap;}float[] declared=numbers(metadata==null?"":metadata.optString("size"));coordinateWidth=declared.length==2?declared[0]:0;coordinateHeight=declared.length==2?declared[1]:0;
        Entry image=findLogical(logical,".1");if(image==null)throw new IOException("找不到原 PNG："+logical);Bitmap base=decode(read(image.path)),source=decode(replacement);if(coordinateWidth<=0)coordinateWidth=base.getWidth();if(coordinateHeight<=0)coordinateHeight=base.getHeight();int x=Math.round(left*base.getWidth()/coordinateWidth),y=Math.round(top*base.getHeight()/coordinateHeight),w=Math.max(1,Math.round(width*base.getWidth()/coordinateWidth)),h=Math.max(1,Math.round(height*base.getHeight()/coordinateHeight));if(x<0||y<0||x+w>base.getWidth()||y+h>base.getHeight())throw new IOException("原图片裁切范围超出纹理");int factor=scaleFactor(base.getWidth(),base.getHeight(),rotated?h:w,rotated?w:h,source.getWidth(),source.getHeight());base=enlarge(base,factor);Bitmap result=compose(base,source,x*factor,y*factor,w*factor,h*factor,crop,rotated);save(image.path,result,logical,factor);
    }
    static int scaleFactor(int atlasWidth,int atlasHeight,int width,int height,int sourceWidth,int sourceHeight){long wanted=Math.max(1,Math.max(((long)sourceWidth+width-1)/width,((long)sourceHeight+height-1)/height));int limit=1;while(limit<8192/atlasWidth&&limit<8192/atlasHeight&&(long)atlasWidth*atlasHeight*(limit+1)*(limit+1)<=32L*1024*1024)limit++;return (int)Math.min(wanted,limit);}
    private static Bitmap enlarge(Bitmap base,int factor)throws IOException{if(factor==1)return base;Bitmap enlarged=Bitmap.createScaledBitmap(base,base.getWidth()*factor,base.getHeight()*factor,false);base.recycle();Bitmap mutable=enlarged.copy(Bitmap.Config.ARGB_8888,true);enlarged.recycle();if(mutable==null)throw new IOException("图集放大失败");return mutable;}
    private static Bitmap decode(byte[] data)throws IOException{Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length);if(b==null)throw new IOException("PNG 无法读取");return b.copy(Bitmap.Config.ARGB_8888,true);}
    private static Bitmap compose(Bitmap base,Bitmap source,int x,int y,int w,int h,boolean crop,boolean rotated){
        int displayW=rotated?h:w,displayH=rotated?w:h;
        Bitmap patch=Bitmap.createBitmap(displayW,displayH,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(patch);Rect src;
        if(crop){float target=(float)displayW/displayH,actual=(float)source.getWidth()/source.getHeight();if(actual>target){int sw=Math.max(1,Math.round(source.getHeight()*target));src=new Rect((source.getWidth()-sw)/2,0,(source.getWidth()+sw)/2,source.getHeight());}else{int sh=Math.max(1,Math.round(source.getWidth()/target));src=new Rect(0,(source.getHeight()-sh)/2,source.getWidth(),(source.getHeight()+sh)/2);}}
        else src=new Rect(0,0,source.getWidth(),source.getHeight());
        Paint paint=new Paint();paint.setFilterBitmap(false);paint.setAntiAlias(false);canvas.drawBitmap(source,src,new Rect(0,0,displayW,displayH),paint);
        if(rotated){Matrix matrix=new Matrix();matrix.postRotate(90);Bitmap turn=Bitmap.createBitmap(patch,0,0,patch.getWidth(),patch.getHeight(),matrix,false);patch.recycle();patch=turn;}
        Canvas out=new Canvas(base);out.save();out.clipRect(x,y,x+w,y+h);out.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);out.drawBitmap(patch,null,new Rect(x,y,x+w,y+h),paint);out.restore();
        patch.recycle();source.recycle();return base;
    }
    private void save(String path,Bitmap bitmap,String logical,int factor)throws Exception{byte[] png=bitmapToPng(bitmap);Entry compressed=findLogical(logical,".2");byte[] encoded=null;if(compressed!=null){byte[] header=readHeader(compressed.path,64);if(header.length<64)throw new IOException("原 KTX 头部不完整");ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);encoded=TextureCodec.encode(png,Math.multiplyExact(b.getInt(36),factor),Math.multiplyExact(b.getInt(40),factor));}save(path,png);if(compressed!=null)save(compressed.path,encoded);bitmap.recycle();}
    private static byte[] bitmapToPng(Bitmap bitmap)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG 编码失败");return out.toByteArray();}
    private void refs(Csb.Node n,Map<String,String> result){if(!n.imagePath.isEmpty())result.put(n.plistPath+"\n"+n.imagePath,n.plistPath);for(Csb.Node c:n.children)refs(c,result);}
    public File logicalBundle(String path)throws Exception{
        Entry scene=find(path);Map<String,String> paths=new TreeMap<>();paths.put(scene.recovered?scene.name:"unrecovered/"+path.substring(path.lastIndexOf('/')+1),path);
        Map<String,String> references=new TreeMap<>();refs(new Csb(read(path)).root(),references);
        for(Map.Entry<String,String> ref:references.entrySet()){
            String logical=ref.getKey().substring(ref.getKey().indexOf('\n')+1),atlas=ref.getValue();
            if(!atlas.isEmpty()){Entry a=findLogical(atlas,".3");if(a==null)continue;paths.put(atlas,a.path);JSONObject meta=new JSONObject(Plists.decode(read(a.path))).getJSONObject("metadata");logical=textureName(meta.optString("textureFileName",meta.optString("realTextureFileName")));}
            for(String ext:List.of(".1",".2")){Entry image=findLogical(logical,ext);if(image!=null)paths.put(textureName(logical).replaceFirst("(?i)\\.(png|ktx)$","")+(ext.equals(".1")?".png":".ktx"),image.path);}
        }
        File target=new File(dir,sourceName.replaceFirst("(?i)\\.(apk|zip)$","")+"-logical.zip");
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(target))){for(Map.Entry<String,String> p:paths.entrySet()){if(p.getKey().startsWith("/")||Arrays.stream(p.getKey().split("/",-1)).anyMatch(s->s.isEmpty()||s.equals("..")||s.equals(".")))throw new IOException("资源路径无效");out.putNextEntry(new ZipEntry(p.getKey()));out.write(read(p.getValue()));out.closeEntry();}out.putNextEntry(new ZipEntry("ccui-path-map.json"));out.write(new JSONObject().put("scene",path).put("files",new JSONObject(paths)).toString(2).getBytes(StandardCharsets.UTF_8));out.closeEntry();}return target;
    }
    public int importLogical(File file)throws Exception{
        Map<String,byte[]> pending=new TreeMap<>();try(ZipFile archive=new ZipFile(file)){
            JSONObject paths;try(InputStream in=archive.getInputStream(archive.getEntry("ccui-path-map.json"))){paths=new JSONObject(new String(readBytes(in),StandardCharsets.UTF_8)).getJSONObject("files");}
            Iterator<String> keys=paths.keys();while(keys.hasNext()){String logical=keys.next(),packed=paths.getString(logical);byte[] old=read(packed),data;try(InputStream in=archive.getInputStream(archive.getEntry(logical))){data=readBytes(in);}if(Arrays.equals(old,data))continue;
                if(!kind(old).equals(kind(data)))throw new IOException("资源格式不一致："+logical);if(packed.endsWith(".2")&&!Arrays.equals(Arrays.copyOfRange(old,12,64),Arrays.copyOfRange(data,12,64)))throw new IOException("KTX 头部参数必须与原资源一致："+logical);pending.put(packed,data);
                if(packed.endsWith(".1")){if(!Arrays.equals(Arrays.copyOfRange(old,16,24),Arrays.copyOfRange(data,16,24)))throw new IOException("图集 PNG 必须保持原尺寸");}
            }
        }
        for(Map.Entry<String,byte[]> p:new TreeMap<>(pending).entrySet())if(p.getKey().endsWith(".1")){String compressed=p.getKey().substring(0,p.getKey().length()-1)+"2";if(!pending.containsKey(compressed)&&(byPath.containsKey(compressed)||added.containsKey(compressed))){byte[] original=readHeader(compressed,64);if(original.length<64)throw new IOException("KTX 头部不完整");pending.put(compressed,TextureCodec.encode(p.getValue(),ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(36),ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(40)));}}
        for(Map.Entry<String,byte[]> p:pending.entrySet()){if(!byPath.containsKey(p.getKey())&&!added.containsKey(p.getKey()))added.put(p.getKey(),p.getKey());save(p.getKey(),p.getValue());}
        Files.write(new File(dir,"added-images.json").toPath(),new JSONObject(added).toString().getBytes(StandardCharsets.UTF_8));return pending.size();
    }
    public File build(String path,Consumer<String> progress)throws Exception{
        Entry current=find(path);if(current==null||!current.type.equals("CSB"))throw new IllegalArgumentException("请先选择 CCUI 界面");
        Map<String,byte[]> changes=new TreeMap<>();byte[] raw=read(path);changes.put(path,raw);
        Map<String,String> original=new HashMap<>(),now=new HashMap<>();try(InputStream in=zip.getInputStream(zip.getEntry(path))){refs(new Csb(readBytes(in)).root(),original);}refs(new Csb(raw).root(),now);
        for(Map.Entry<String,String> ref:now.entrySet()){
            boolean fresh=!original.containsKey(ref.getKey());String logical=ref.getKey().substring(ref.getKey().indexOf('\n')+1),atlas=ref.getValue();
            if(!atlas.isEmpty()){Entry a=findLogical(atlas,".3");if(a==null){if(fresh)throw new IllegalArgumentException("缺少图集："+atlas);continue;}byte[] data=read(a.path);JSONObject o=new JSONObject(Plists.decode(data));String tex=o.getJSONObject("metadata").getString("textureFileName");logical=textureName(tex);if(fresh||override(a.path).exists())changes.put(a.path,data);}
            Entry compressedOnly=findLogical(logical,".2");if(compressedOnly!=null&&override(compressedOnly.path).exists())changes.put(compressedOnly.path,read(compressedOnly.path));
            Entry png=findLogical(logical,".1");if(png==null){if(fresh)throw new IllegalArgumentException("缺少新引用图片："+logical);continue;}
            if(fresh||added.containsKey(png.path)||override(png.path).exists()){
                changes.put(png.path,read(png.path));Entry compressed=findLogical(logical,".2");if(compressed!=null)changes.put(compressed.path,read(compressed.path));
            }
        }
        String filename=sourceName.replaceFirst("(?i)\\.(apk|zip)$","")+".zip";File target=new File(dir,filename),partial=new File(dir,filename+".partial");
        try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))){for(Map.Entry<String,byte[]> c:changes.entrySet()){out.putNextEntry(new ZipEntry(c.getKey()));out.write(c.getValue());out.closeEntry();progress.accept("打包 "+c.getKey());}}catch(Exception e){partial.delete();throw e;}
        Files.move(partial.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;
    }
    @Override public void close()throws IOException{zip.close();}
}
