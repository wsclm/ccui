package com.ccui.workbench;

import android.graphics.*;
import java.util.*;

/** Small static overview for the APK resource library; never creates an editor View. */
final class SceneThumbnail {
    private final RectF bounds=new RectF();
    private final Map<Csb.Node,Matrix> transforms=new LinkedHashMap<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private void measure(Csb.Node n,Matrix parent){
        if(n.get("visible")==0)return;
        float w=(float)n.get("width"),h=(float)n.get("height");
        Matrix local=new Matrix();local.postTranslate(-(float)n.get("anchorX")*w,-(float)n.get("anchorY")*h);
        local.postScale((float)n.get("scaleX"),(float)n.get("scaleY"));local.postRotate(-(float)n.get("rotation"));local.postTranslate((float)n.get("x"),(float)n.get("y"));
        Matrix world=new Matrix();world.setConcat(parent,local);transforms.put(n,world);
        RectF r=new RectF(0,0,Math.max(1,w),Math.max(1,h));world.mapRect(r);bounds.union(r);
        for(Csb.Node child:n.children)measure(child,world);
    }
    static Bitmap render(Workspace workspace,String path)throws Exception{
        SceneThumbnail renderer=new SceneThumbnail();Csb.Node root=new Csb(workspace.read(path)).root();renderer.measure(root,new Matrix());
        Map<Integer,Bitmap> images=new HashMap<>();for(ResourcePreview.Item item:ResourcePreview.load(workspace,root,512,12L*1024*1024))if(item.bitmap!=null)images.put(item.node.id,item.bitmap);
        Bitmap result=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);canvas.drawColor(0xff211f26);
        RectF b=renderer.bounds;if(b.isEmpty())b.set(0,0,640,480);float scale=Math.min(180/b.width(),116/b.height());
        canvas.translate(96-b.centerX()*scale,64+b.centerY()*scale);canvas.scale(scale,-scale);
        for(Map.Entry<Csb.Node,Matrix> entry:renderer.transforms.entrySet()){
            Csb.Node n=entry.getKey();float w=(float)n.get("width"),h=(float)n.get("height"),alpha=(float)n.get("alpha")/255;
            for(Csb.Node p=n.parent;p!=null;p=p.parent)alpha*=p.get("alpha")/255;
            canvas.save();canvas.concat(entry.getValue());renderer.paint.setColor(Color.WHITE);renderer.paint.setAlpha(Math.max(0,Math.min(255,(int)(alpha*255))));
            Bitmap image=images.get(n.id);if(image!=null&&w>0&&h>0){canvas.save();canvas.translate(0,h);canvas.scale(1,-1);canvas.drawBitmap(image,null,new RectF(0,0,w,h),renderer.paint);canvas.restore();}
            else if(w>0&&h>0){renderer.paint.setColor(0xff6750a4);renderer.paint.setAlpha(Math.max(0,Math.min(136,(int)(alpha*136))));renderer.paint.setStyle(Paint.Style.STROKE);renderer.paint.setStrokeWidth(1/scale);canvas.drawRect(0,0,w,h,renderer.paint);renderer.paint.setStyle(Paint.Style.FILL);}
            if(n.text!=null&&!n.text.isEmpty()){canvas.save();canvas.translate(0,h);canvas.scale(1,-1);renderer.paint.setColor(Color.WHITE);renderer.paint.setAlpha(Math.max(0,Math.min(255,(int)(alpha*255))));renderer.paint.setTextSize((float)n.props.getOrDefault("fontSize",20d).doubleValue());int line=1;for(String text:n.text.split("\n"))canvas.drawText(text,0,renderer.paint.getTextSize()*line++,renderer.paint);canvas.restore();}
            canvas.restore();
        }
        return result;
    }
}
