package com.ccui.workbench;

import android.graphics.*;
import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Resolves the resources referenced by a scene without changing its serialized data. */
final class ResourcePreview {
    private static final Pattern NUMBER=Pattern.compile("-?\\d+(?:\\.\\d+)?");
    static final class Item {
        final Csb.Node node;
        Bitmap bitmap;
        String detail="";
        Item(Csb.Node node){this.node=node;}
    }
    private static final class Texture {
        Bitmap bitmap;
        int width,height;
    }
    static List<Item> load(Workspace workspace,Csb.Node root){
        return load(workspace,root,2048,64L*1024*1024);
    }
    static List<Item> load(Workspace workspace,Csb.Node root,int maxDimension,long limit){
        List<Item> items=new ArrayList<>();collect(root,items);
        Map<String,Texture> textures=new HashMap<>();Map<String,JSONObject> atlases=new HashMap<>();
        Map<String,Bitmap> framesCache=new HashMap<>();Map<String,String> failures=new HashMap<>();
        long used=0;
        for(Item item:items){String resourceKey=item.node.plistPath+"\n"+item.node.imagePath;try{
            if(Thread.currentThread().isInterrupted())break;
            if(failures.containsKey(resourceKey)){item.detail=failures.get(resourceKey);continue;}
            Bitmap cached=framesCache.get(resourceKey);if(cached!=null){item.bitmap=cached;item.detail="已显示 · "+item.node.imagePath;continue;}
            Csb.Node node=item.node;String logical=node.imagePath;JSONObject frame=null;float[] atlasSize=new float[0];
            if(!node.plistPath.isEmpty()){
                JSONObject atlas=atlases.get(node.plistPath);
                if(atlas==null){Workspace.Entry entry=workspace.findLogical(node.plistPath,".3");if(entry==null)throw new Exception("找不到引用的 plist");atlas=new JSONObject(Plists.decode(workspace.read(entry.path)));atlases.put(node.plistPath,atlas);}
                JSONObject frames=atlas.optJSONObject("frames");if(frames!=null){frame=frames.optJSONObject(logical);if(frame==null)frame=frames.optJSONObject(logical.substring(logical.lastIndexOf('/')+1));}
                if(frame==null)throw new Exception("图集中找不到此图片帧");
                JSONObject metadata=atlas.optJSONObject("metadata");String name=metadata==null?"":metadata.optString("textureFileName",metadata.optString("realTextureFileName"));
                atlasSize=numbers(metadata==null?"":metadata.optString("size"));
                if(name.isEmpty())throw new Exception("图集缺少纹理文件名");
                logical=Workspace.textureName(name);
            }
            Workspace.Entry entry=workspace.findLogical(logical,".1");
            if(entry==null){if(workspace.findLogical(logical,".2")!=null)throw new Exception("仅有 KTX 纹理，暂不能预览");throw new Exception("资源包内缺少引用的 PNG："+logical);}
            Texture texture=textures.get(entry.path);
            if(texture==null){
                byte[] data=workspace.read(entry.path);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
                if(options.outWidth<=0||options.outHeight<=0)throw new Exception("纹理不是可解码的图片");
                texture=new Texture();texture.width=options.outWidth;texture.height=options.outHeight;
                Workspace.Entry compressed=workspace.findLogical(logical,".2");if(compressed!=null){byte[] ktx=workspace.readHeader(compressed.path,64);if(ktx.length>=64&&Workspace.kind(ktx).equals("KTX")){java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(ktx).order(java.nio.ByteOrder.LITTLE_ENDIAN);texture.width=header.getInt(36);texture.height=header.getInt(40);}}
                options.inSampleSize=1;
                while(options.outWidth/options.inSampleSize>maxDimension||options.outHeight/options.inSampleSize>maxDimension)options.inSampleSize*=2;
                long estimate=((long)options.outWidth/options.inSampleSize)*((long)options.outHeight/options.inSampleSize)*4;
                if(used+estimate>limit)throw new Exception("预览内存预算已满");
                options.inJustDecodeBounds=false;texture.bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);
                if(texture.bitmap==null)throw new Exception("图片解码失败");used+=texture.bitmap.getAllocationByteCount();textures.put(entry.path,texture);
            }
            Bitmap bitmap=texture.bitmap;
            if(frame!=null){
                float coordinateWidth=atlasSize.length==2&&atlasSize[0]>0?atlasSize[0]:texture.width,coordinateHeight=atlasSize.length==2&&atlasSize[1]>0?atlasSize[1]:texture.height;
                String rect=frame.optString("frame",frame.optString("textureRect"));Matcher matcher=NUMBER.matcher(rect);float[] r=new float[4];int count=0;while(matcher.find()&&count<4)r[count++]=Float.parseFloat(matcher.group());
                boolean rotated=frame.optBoolean("rotated",frame.optBoolean("textureRotated"));if(rotated){float swap=r[2];r[2]=r[3];r[3]=swap;}
                if(count!=4||r[0]<0||r[1]<0||r[2]<=0||r[3]<=0||r[0]+r[2]>coordinateWidth||r[1]+r[3]>coordinateHeight)throw new Exception("图集裁切范围无效："+Arrays.toString(r)+" / "+coordinateWidth+"×"+coordinateHeight);
                float sx=bitmap.getWidth()/coordinateWidth,sy=bitmap.getHeight()/coordinateHeight;
                int x=(int)Math.floor(r[0]*sx),y=(int)Math.floor(r[1]*sy),right=Math.min(bitmap.getWidth(),(int)Math.ceil((r[0]+r[2])*sx)),bottom=Math.min(bitmap.getHeight(),(int)Math.ceil((r[1]+r[3])*sy));
                long estimate=(long)(right-x)*(bottom-y)*4;if(used+estimate*2>limit)throw new Exception("预览内存预算已满");
                bitmap=Bitmap.createBitmap(bitmap,x,y,right-x,bottom-y);used+=estimate;
                if(frame.optBoolean("rotated",frame.optBoolean("textureRotated"))){Matrix rotate=new Matrix();rotate.postRotate(-90);bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),rotate,true);used+=estimate;}
                float[] source=numbers(frame.optString("sourceSize",frame.optString("spriteSourceSize"))),trim=numbers(frame.optString("sourceColorRect"));
                if(source.length==2&&trim.length==4&&source[0]>0&&source[1]>0){
                    int w=Math.max(1,Math.round(source[0]*sx)),h=Math.max(1,Math.round(source[1]*sy));long bytes=(long)w*h*4;if(used+bytes>limit)throw new Exception("预览内存预算已满");
                    Bitmap padded=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);new Canvas(padded).drawBitmap(bitmap,null,new RectF(trim[0]*sx,trim[1]*sy,(trim[0]+trim[2])*sx,(trim[1]+trim[3])*sy),new Paint(Paint.FILTER_BITMAP_FLAG));bitmap=padded;used+=bytes;
                }
            }
            item.bitmap=bitmap;framesCache.put(resourceKey,bitmap);item.detail="已显示 · "+logical;
        }catch(Exception error){item.detail=error.getMessage()==null?"资源读取失败":error.getMessage();failures.put(resourceKey,item.detail);}}
        return items;
    }
    private static void collect(Csb.Node node,List<Item> items){if(!node.imagePath.isEmpty())items.add(new Item(node));for(Csb.Node child:node.children)collect(child,items);}
    private static float[] numbers(String text){Matcher m=NUMBER.matcher(text);List<Float> values=new ArrayList<>();while(m.find())values.add(Float.parseFloat(m.group()));float[] result=new float[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
}
