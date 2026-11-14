package com.ccui.workbench;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
import java.util.function.Consumer;

/** Cocos coordinates: parent anchor affects drawing, children use bottom-left origin. */
final class SceneView extends View {
    final Csb csb;
    Csb.Node root;
    Csb.Node selected;
    Consumer<Csb.Node> onSelect=n->{};
    Runnable onChange=()->{};
    Runnable onEditStart=()->{},onEditEnd=()->{};
    boolean editingGesture;
    final Map<Integer,Bitmap> images=new HashMap<>();
    List<ResourcePreview.Item> resources=new ArrayList<>();
    boolean resourcesLoading=true;
    final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    final Map<Csb.Node,Matrix> transforms=new LinkedHashMap<>();
    float zoom=1,panX,panY,lastX,lastY;
    boolean fit=true, dragging=false,resizing=false,gestureMoved=false,multiTouch=false;
    int mode=0,scrollAxis=0;
    float downX,downY;
    final RectF content=new RectF(),horizontalThumb=new RectF(),verticalThumb=new RectF();
    final int touchSlop;
    final ScaleGestureDetector pinch;
    boolean geometryDirty=true;
    void geometryChanged(){geometryDirty=true;invalidate();}
    SceneView(Context c,Csb csb,Csb.Node root){
        super(c);this.csb=csb;this.root=root;
        touchSlop=ViewConfiguration.get(c).getScaledTouchSlop();
        pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){float old=zoom;zoom=Math.max(.001f,Math.min(8,zoom*d.getScaleFactor()));float ratio=zoom/old;panX=d.getFocusX()-(d.getFocusX()-panX)*ratio;panY=d.getFocusY()-(d.getFocusY()-panY)*ratio;fit=false;invalidate();return true;}});
        setContentDescription("CCUI 画布，默认点击选择；切换模式移动节点或平移，双指缩放，边缘滚动条可拖动");
    }
    void reset(){fit=true;geometryChanged();}
    String cycleMode(){mode=(mode+1)%3;dragging=false;resizing=false;invalidate();return new String[]{"模式：选择","模式：移动节点","模式：平移画布"}[mode];}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(!fit&&oldw>0&&oldh>0){panX+=(w-oldw)/2f;panY+=(h-oldh)/2f;}}
    private Matrix local(Csb.Node n){
        double radians=Math.toRadians(-n.get("rotation"));float sx=(float)n.get("scaleX"),sy=(float)n.get("scaleY"),cos=(float)Math.cos(radians),sin=(float)Math.sin(radians);
        float ax=(float)(n.get("anchorX")*n.get("width")),ay=(float)(n.get("anchorY")*n.get("height"));
        float a=cos*sx,b=sin*sx,c=-sin*sy,d=cos*sy;
        Matrix m=new Matrix();m.setValues(new float[]{a,c,(float)n.get("x")-a*ax-c*ay,b,d,(float)n.get("y")-b*ax-d*ay,0,0,1});return m;
    }
    private void measureContent(Csb.Node n,Matrix parent){
        if(n.get("visible")==0)return;
        Matrix world=new Matrix();world.setConcat(parent,local(n));RectF r=new RectF(0,0,(float)Math.max(n.get("width"),8),(float)Math.max(n.get("height"),8));world.mapRect(r);content.union(r);
        for(Csb.Node child:n.children)measureContent(child,world);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(Color.rgb(29,34,29));
        if(geometryDirty){content.setEmpty();measureContent(root,new Matrix());if(content.isEmpty())content.set(0,0,640,480);geometryDirty=false;}
        if(fit&&getWidth()>0&&getHeight()>0){float padding=24*getResources().getDisplayMetrics().density;zoom=Math.max(.001f,Math.min(Math.max(1,getWidth()-padding*2)/content.width(),Math.max(1,getHeight()-padding*2)/content.height()));panX=getWidth()/2f-content.centerX()*zoom;panY=getHeight()/2f+content.centerY()*zoom;fit=false;}
        paint.setColor(0xff343c34);paint.setStrokeWidth(1);for(int x=0;x<getWidth();x+=24)canvas.drawLine(x,0,x,getHeight(),paint);for(int y=0;y<getHeight();y+=24)canvas.drawLine(0,y,getWidth(),y,paint);
        Matrix world=new Matrix();world.setValues(new float[]{zoom,0,panX,0,-zoom,panY,0,0,1});transforms.clear();drawNode(canvas,root,world,1f,0);
        drawScrollbars(canvas);
    }
    private float extentX(){return Math.max(getWidth(),content.width()*zoom+getWidth());}
    private float extentY(){return Math.max(getHeight(),content.height()*zoom+getHeight());}
    private void drawScrollbars(Canvas c){
        float unit=getResources().getDisplayMetrics().density,t=8*unit,inset=4*unit,w=getWidth()-20*unit,h=getHeight()-20*unit;
        if(w<=0||h<=0)return;
        float x=Math.max(0,Math.min(1,(getWidth()/2f-content.left*zoom-panX)/(extentX()-getWidth())));
        float y=Math.max(0,Math.min(1,(getHeight()/2f+content.bottom*zoom-panY)/(extentY()-getHeight())));
        float tw=Math.min(w,Math.max(32*unit,w*getWidth()/extentX())),th=Math.min(h,Math.max(32*unit,h*getHeight()/extentY()));
        horizontalThumb.set(inset+x*(w-tw),getHeight()-inset-t,inset+x*(w-tw)+tw,getHeight()-inset);
        verticalThumb.set(getWidth()-inset-t,inset+y*(h-th),getWidth()-inset,inset+y*(h-th)+th);
        paint.setColor(0xff455045);c.drawRoundRect(inset,horizontalThumb.top,inset+w,horizontalThumb.bottom,t,t,paint);c.drawRoundRect(verticalThumb.left,inset,verticalThumb.right,inset+h,t,t,paint);
        paint.setColor(0xffb6ed9d);c.drawRoundRect(horizontalThumb,t,t,paint);c.drawRoundRect(verticalThumb,t,t,paint);
    }
    private void drawNode(Canvas c,Csb.Node n,Matrix parent,float inherited,int depth){
        if(n.get("visible")==0)return;
        double radians=Math.toRadians(-n.get("rotation"));float sx=(float)n.get("scaleX"),sy=(float)n.get("scaleY"),cos=(float)Math.cos(radians),sin=(float)Math.sin(radians);
        float w=(float)n.get("width"),h=(float)n.get("height"),ax=(float)n.get("anchorX")*w,ay=(float)n.get("anchorY")*h;
        float a=cos*sx,b=sin*sx,cc=-sin*sy,d=cos*sy;
        Matrix local=new Matrix();local.setValues(new float[]{a,cc,(float)n.get("x")-a*ax-cc*ay,b,d,(float)n.get("y")-b*ax-d*ay,0,0,1});
        Matrix world=new Matrix();world.setConcat(parent,local);transforms.put(n,world);
        float alpha=inherited*(float)n.get("alpha")/255f;
        c.save();c.concat(world);
        Bitmap image=images.get(n.id);
        if(image!=null){c.save();c.translate(0,h);c.scale(1,-1);paint.setAlpha((int)(255*alpha));c.drawBitmap(image,null,new RectF(0,0,w,h),paint);paint.setAlpha(255);c.restore();}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((n==selected?2.5f:1)/zoom);paint.setColor(n==selected?0xffb6ed9d:0x806e9772);c.drawRect(0,0,Math.max(w,8),Math.max(h,8),paint);paint.setStyle(Paint.Style.FILL);
        if(n.text!=null&&!n.text.isEmpty()){c.save();c.translate(0,h);c.scale(1,-1);paint.setColor(Color.WHITE);paint.setAlpha((int)(alpha*255));paint.setTextSize((float)n.props.getOrDefault("fontSize",20d).doubleValue());int i=1;for(String line:n.text.split("\n"))c.drawText(line,0,paint.getTextSize()*i++,paint);paint.setAlpha(255);c.restore();}
        if(n==selected&&mode==1){paint.setColor(0xffb6ed9d);float r=4/zoom;c.drawRect(w-r,h-r,w+r,h+r,paint);}
        c.restore();for(Csb.Node child:n.children)drawNode(c,child,world,alpha,depth+1);
    }
    private Csb.Node hit(float x,float y){List<Csb.Node> nodes=new ArrayList<>(transforms.keySet());Collections.reverse(nodes);for(Csb.Node n:nodes){Matrix inverse=new Matrix();if(!transforms.get(n).invert(inverse))continue;float[] pt={x,y};inverse.mapPoints(pt);if(pt[0]>=0&&pt[1]>=0&&pt[0]<=Math.max(n.get("width"),12)&&pt[1]<=Math.max(n.get("height"),12))return n;}return null;}
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        pinch.onTouchEvent(e);
        if(e.getPointerCount()>1){multiTouch=true;dragging=false;resizing=false;scrollAxis=0;return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            getParent().requestDisallowInterceptTouchEvent(true);multiTouch=false;gestureMoved=false;dragging=false;resizing=false;scrollAxis=0;
            downX=lastX=e.getX();downY=lastY=e.getY();
            float edge=24*getResources().getDisplayMetrics().density;
            if(e.getY()>getHeight()-edge)scrollAxis=1;else if(e.getX()>getWidth()-edge)scrollAxis=2;
            if(scrollAxis==0&&mode==1&&selected!=null&&transforms.containsKey(selected)){
                if(selected.bindings.containsKey("width")&&selected.bindings.containsKey("height")){float[] corner={(float)selected.get("width"),(float)selected.get("height")};transforms.get(selected).mapPoints(corner);resizing=Math.hypot(corner[0]-e.getX(),corner[1]-e.getY())<20*getResources().getDisplayMetrics().density;}
                dragging=!resizing&&hit(e.getX(),e.getY())==selected;
            }return true;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
            if(editingGesture){editingGesture=false;onEditEnd.run();}
            if(e.getActionMasked()==MotionEvent.ACTION_UP&&!gestureMoved&&!multiTouch&&scrollAxis==0&&mode!=2){Csb.Node target=hit(e.getX(),e.getY());if(target!=null){selected=target;onSelect.accept(target);}performClick();}
            dragging=false;resizing=false;scrollAxis=0;getParent().requestDisallowInterceptTouchEvent(false);invalidate();return true;
        }
        if(multiTouch)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinch.isInProgress()){
            if(!gestureMoved){if(Math.hypot(e.getX()-downX,e.getY()-downY)<=touchSlop)return true;gestureMoved=true;lastX=e.getX();lastY=e.getY();return true;}
            float dx=e.getX()-lastX,dy=e.getY()-lastY;
            if((dragging||resizing)&&!editingGesture){onEditStart.run();editingGesture=true;}
            if(scrollAxis==1){float travel=getWidth()-20*getResources().getDisplayMetrics().density-horizontalThumb.width();panX-=dx*(extentX()-getWidth())/Math.max(1,travel);}
            else if(scrollAxis==2){float travel=getHeight()-20*getResources().getDisplayMetrics().density-verticalThumb.height();panY-=dy*(extentY()-getHeight())/Math.max(1,travel);}
            else if(resizing&&selected!=null){float[] delta={dx,dy};Matrix inverse=new Matrix();if(transforms.get(selected).invert(inverse)){inverse.mapVectors(delta);csb.set(selected,"width",Math.max(0,selected.get("width")+delta[0]));csb.set(selected,"height",Math.max(0,selected.get("height")+delta[1]));onChange.run();}}
            else if(dragging&&selected!=null){Matrix parent=selected.parent==null?null:transforms.get(selected.parent);float[] delta={dx,dy};if(parent!=null){Matrix inverse=new Matrix();if(parent.invert(inverse))inverse.mapVectors(delta);}else{delta[0]/=zoom;delta[1]/=-zoom;}
                if(selected.bindings.containsKey("x"))csb.set(selected,"x",selected.get("x")+delta[0]);if(selected.bindings.containsKey("y"))csb.set(selected,"y",selected.get("y")+delta[1]);onChange.run();
            }else if(mode!=1){panX+=dx;panY+=dy;}
            if(resizing||dragging)geometryDirty=true;
            lastX=e.getX();lastY=e.getY();invalidate();return true;
        }return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
