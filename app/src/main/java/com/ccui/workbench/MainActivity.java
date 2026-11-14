package com.ccui.workbench;

import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.*;
import com.google.android.material.color.DynamicColors;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity {
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final Handler uiHandler=new Handler(Looper.getMainLooper());
    final Runnable searchRefresh=()->{if(!this.disposed&&this.entry==null)refresh();};
    final Runnable scrollSave=()->{if(!this.disposed)rememberView();};
    final Runnable thumbnailLoad=this::loadVisibleThumbnails;
    void scheduleScrollSave(){uiHandler.removeCallbacks(scrollSave);uiHandler.postDelayed(scrollSave,250);}
    final android.util.LruCache<String,Bitmap> thumbnails=new android.util.LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}};
    final Map<ImageView,String> thumbnailViews=new LinkedHashMap<>();
    final Set<String> failedThumbnails=new HashSet<>();
    boolean thumbnailPending=false,disposed=false;int thumbnailRevision=0;
    Workspace thumbnailWorkspace;
    void invalidateThumbnails(){thumbnailRevision++;thumbnails.evictAll();failedThumbnails.clear();}
    @Override protected void onDestroy(){disposed=true;uiHandler.removeCallbacksAndMessages(null);thumbnailViews.clear();thumbnails.evictAll();worker.shutdown();super.onDestroy();}
    void loadVisibleThumbnails(){
        if(disposed||thumbnailPending||busy||entry!=null||workspace==null||libraryScroll==null)return;
        if(thumbnailWorkspace!=workspace){thumbnailWorkspace=workspace;invalidateThumbnails();}
        Rect visible=new Rect();
        for(Map.Entry<ImageView,String> item:thumbnailViews.entrySet()){
            ImageView view=item.getKey();String path=item.getValue();
            if(!view.getLocalVisibleRect(visible))continue;
            Bitmap cached=thumbnails.get(path);if(cached!=null){if(view.getTag()!=cached){view.setImageBitmap(cached);view.setTag(cached);}continue;}
            if(failedThumbnails.contains(path))continue;
            thumbnailPending=true;Workspace source=workspace;int revision=thumbnailRevision;
            worker.execute(()->{Bitmap bitmap=null;try{bitmap=SceneThumbnail.render(source,path);}catch(Exception ignored){/* Keep a placeholder for unsupported .3 files. */}final Bitmap result=bitmap;
                runOnUiThread(()->{thumbnailPending=false;if(disposed)return;
                    if(source==workspace&&revision==thumbnailRevision){if(result==null)failedThumbnails.add(path);else{thumbnails.put(path,result);for(Map.Entry<ImageView,String> target:thumbnailViews.entrySet())if(path.equals(target.getValue())){target.getKey().setImageBitmap(result);target.getKey().setTag(result);}}}
                    loadVisibleThumbnails();
                });
            });return;
        }
    }
    void addLibraryCard(Workspace.Entry e){
        MaterialCardView card=new MaterialCardView(this);LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(8),dp(12),dp(8));
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setImageResource(android.R.drawable.ic_menu_gallery);preview.setBackgroundColor(0xff211f26);preview.setContentDescription(e.name+" · 整个界面预览");
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(96),dp(64));imageParams.setMarginEnd(dp(12));row.addView(preview,imageParams);
        if(e.type.equals("CSB"))thumbnailViews.put(preview,e.path);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);TextView name=label(e.name,15);name.setMaxLines(3);content.addView(name);content.addView(label(e.type+" · "+e.size+" B"+(e.recovered?"":" · MD5")+(workspace.override(e.path).exists()?" · 已修改":""),12));row.addView(content,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(row);card.setOnClickListener(v->{if(!busy)load(e);});resourceList.addView(card);LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)card.getLayoutParams();p.width=-1;p.bottomMargin=dp(8);
    }
    Workspace workspace;
    Workspace.Entry entry;
    LinearLayout base,body,resourceList;
    MaterialToolbar toolbar;
    TextView status,summary,selectedLabel;
    LinearProgressIndicator progress;
    EditText search,editor;
    Csb csb; Csb.Node root,selected;
    final EditHistory history=new EditHistory();
    byte[] pendingEdit;boolean gestureEdit;
    final Runnable finishEdit=()->{if(pendingEdit!=null&&csb!=null){history.record(pendingEdit,csb.bytes);pendingEdit=null;}};
    void trackEdit(){if(pendingEdit==null)pendingEdit=csb.bytes.clone();if(!gestureEdit){uiHandler.removeCallbacks(finishEdit);uiHandler.post(finishEdit);}}
    void rebuildScene(int selectedId)throws Exception{rememberView();byte[] raw=csb.bytes;showResource(raw);selected=findNode(root,selectedId);scene.selected=selected;if(selected!=null)selectedLabel.setText(selected.toString());dirty=true;}
    void undoEdit(boolean redo){if(csb==null)return;finishEdit.run();if(redo?!history.canRedo():!history.canUndo()){toast(redo?"没有可重做的操作":"没有可撤销的操作");return;}int id=selected==null?root.id:selected.id;try{csb.bytes=redo?history.redo(csb.bytes):history.undo(csb.bytes);rebuildScene(id);toast(redo?"已重做":"已撤销");}catch(Exception e){error(e);}}
    void deleteSelected(){if(selected==null){toast("请先选择节点");return;}try{Csb.Node parent=selected.parent;csb.delete(selected);finishEdit.run();rebuildScene(parent.id);toast("已删除节点及其子节点，可撤销恢复");}catch(Exception e){error(e);}}
    void toggleSelected(){if(selected==null){toast("请先选择节点");return;}try{csb.set(selected,"visible",selected.get("visible")==0?1:0);dirty=true;scene.geometryChanged();selectedLabel.setText(selected.toString()+(selected.get("visible")==0?" · 已隐藏":" · 已显示"));}catch(Exception e){error(e);}}
    SceneView scene;
    boolean busy=false,dirty=false;
    int page=0,generation=0;
    String format="全部格式",category="全部目录",query="";
    boolean onlyThree=true;
    File exportFile;
    byte[] exportBytes;
    Csb.Node imageTarget;
    ScrollView libraryScroll,nodeScroll;String libraryKey="";LinearLayout nodeRail;final Map<Integer,ImageView> nodeIcons=new HashMap<>();
    String viewKey(){return workspace.dir.getName()+"/"+(entry==null?"library":entry.path);}
    void rememberView(){if(workspace==null)return;if(entry!=null&&scene!=null&&scene.getWidth()>0){getPreferences(0).edit().putFloat(viewKey()+"/zoom",scene.zoom).putFloat(viewKey()+"/cx",(scene.getWidth()/2f-scene.panX)/scene.zoom).putFloat(viewKey()+"/cy",(scene.panY-scene.getHeight()/2f)/scene.zoom).apply();}else if(libraryScroll!=null&&!libraryKey.isEmpty())getPreferences(0).edit().putInt(libraryKey,libraryScroll.getScrollY()).putInt(workspace.dir.getName()+"/page",page).putString(workspace.dir.getName()+"/query",query).putString(workspace.dir.getName()+"/category",category).apply();}
    @Override protected void onPause(){rememberView();if(entry!=null&&nodeScroll!=null)getPreferences(0).edit().putInt(viewKey()+"/nodes",nodeScroll.getScrollY()).apply();super.onPause();}
    final ActivityResultLauncher<String[]> imagePicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null&&imageTarget!=null)requestImageReplacement(uri,imageTarget);});
    void requestImageReplacement(Uri uri,Csb.Node node){runTask("读取替换图片",()->{byte[] raw;try(InputStream in=getContentResolver().openInputStream(uri)){raw=readBytes(in);}BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IllegalArgumentException("请选择有效 PNG");return new Object[]{raw,workspace.replacementSize(node),new int[]{bounds.outWidth,bounds.outHeight}};},data->{byte[] raw=(byte[])data[0];int[] target=(int[])data[1],incoming=(int[])data[2];boolean same=(long)target[0]*incoming[1]==(long)target[1]*incoming[0];if(same)applyImageReplacement(node,raw,false);else new MaterialAlertDialogBuilder(this).setTitle("图片比例不一致").setItems(new String[]{"拉伸填充","按比例裁切"},(dialog,choice)->applyImageReplacement(node,raw,choice==1)).setNegativeButton("取消",null).show();});}
    void applyImageReplacement(Csb.Node node,byte[] raw,boolean crop){runTask("替换图片裁切区域",()->{workspace.replaceImage(node,raw,crop);return true;},done->{dirty=false;scene.images.remove(node.id);loadReferencedImages(scene);toast("已替换原图片区域");});}
    byte[] readBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}
    final ActivityResultLauncher<String[]> open=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null)importApk(uri);});
    final ActivityResultLauncher<String[]> replace=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null&&entry!=null){Workspace.Entry target=entry;runTask("校验并导入资源",()->{byte[] b;try(InputStream in=getContentResolver().openInputStream(uri)){b=readBytes(in);}workspace.replace(target,b);return b;},r->{dirty=false;load(target);});}});
    final ActivityResultLauncher<String> create=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"),uri->{if(uri!=null){File f=exportFile;byte[] b=exportBytes;runTask("导出文件",()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(f!=null)Files.copy(f.toPath(),out);else out.write(b);}return true;},r->toast("已导出"));}});
    interface Job<T>{T run()throws Exception;}
    interface Done<T>{void accept(T result)throws Exception;}
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle state){super.onCreate(state);DynamicColors.applyToActivityIfAvailable(this);
        base=new LinearLayout(this);base.setOrientation(LinearLayout.VERTICAL);base.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface,0xfffafaf5));setContentView(base);
        ViewCompat.setOnApplyWindowInsetsListener(base,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});
        toolbar=new MaterialToolbar(this);toolbar.setTitle("NekoCCUI");base.addView(toolbar);
        toolbar.setNavigationOnClickListener(v->leaveDetail());
        progress=new LinearProgressIndicator(this);progress.setIndeterminate(true);progress.setVisibility(View.GONE);base.addView(progress);
        status=label("本地处理 · 原 APK 保持不变",12);base.addView(status);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(8),dp(16),0);base.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){if(busy)return;if(entry!=null)leaveDetail();else{setEnabled(false);getOnBackPressedDispatcher().onBackPressed();}}});
        home();String active=getPreferences(0).getString("workspace",null);if(active!=null){File d=new File(getFilesDir(),active),apk=new File(d,"source.apk");if(apk.exists())openWorkspace(apk,d);}
    }
    TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setPadding(dp(4),dp(6),dp(4),dp(6));return v;}
    MaterialButton button(String title,Runnable click){MaterialButton b=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);b.setText(title);b.setOnClickListener(v->{if(!busy)click.run();});return b;}
    void actions(LinearLayout host,MaterialButton...buttons){HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(this);for(MaterialButton b:buttons){row.addView(b);LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)b.getLayoutParams();p.setMarginEnd(dp(8));}scroll.addView(row);host.addView(scroll);}
    ScrollView scroll(LinearLayout host){ScrollView sc=new ScrollView(this);host.addView(sc,new LinearLayout.LayoutParams(-1,0,1));return sc;}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void error(Exception e){new MaterialAlertDialogBuilder(this).setTitle("操作未完成").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("知道了",null).show();}
    <T> void runTask(String title,Job<T> job,Done<T> done){if(busy)return;busy=true;progress.setVisibility(View.VISIBLE);status.setText(title);worker.execute(()->{try{T r=job.run();runOnUiThread(()->{if(disposed)return;busy=false;progress.setVisibility(View.GONE);status.setText("本地处理 · 原 APK 保持不变");if(title.contains("导入")||title.contains("保存")||title.equals("按原路径解包"))invalidateThumbnails();try{done.accept(r);}catch(Exception e){error(e);}loadVisibleThumbnails();});}catch(Exception e){runOnUiThread(()->{if(disposed)return;busy=false;progress.setVisibility(View.GONE);status.setText("操作失败，可重试");error(e);loadVisibleThumbnails();});}});}
    void updateProgress(String text){runOnUiThread(()->status.setText(text));}
    void restoreNode(Csb.Node target,Csb.Node source){target.props.clear();target.props.putAll(source.props);target.text=source.text;for(int i=0;i<target.children.size();i++)restoreNode(target.children.get(i),source.children.get(i));}
    Csb.Node findNode(Csb.Node node,int id){if(node.id==id)return node;for(Csb.Node child:node.children){Csb.Node found=findNode(child,id);if(found!=null)return found;}return null;}
     void home(){entry=null;body.removeAllViews();toolbar.setTitle("NekoCCUI");toolbar.setSubtitle(null);toolbar.setNavigationIcon(null);actions(body,button("选择 APK",()->open.launch(new String[]{"application/vnd.android.package-archive","application/zip","application/octet-stream"})),button("选择已安装应用",this::chooseInstalledApp));}
     void chooseInstalledApp(){runTask("读取已安装应用",()->{android.content.Intent query=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);List<android.content.pm.ResolveInfo> resolved=getPackageManager().queryIntentActivities(query,0);Map<String,android.content.pm.ApplicationInfo> unique=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);for(android.content.pm.ResolveInfo item:resolved){android.content.pm.ApplicationInfo app=item.activityInfo.applicationInfo;if(!app.packageName.equals(getPackageName())&&app.sourceDir!=null)unique.putIfAbsent(app.packageName,app);}return new ArrayList<>(unique.values());},apps->{if(apps.isEmpty()){toast("没有可读取的已安装应用");return;}String[] labels=new String[apps.size()];for(int i=0;i<labels.length;i++)labels[i]=getPackageManager().getApplicationLabel(apps.get(i))+" · "+apps.get(i).packageName;new MaterialAlertDialogBuilder(this).setTitle("选择已安装应用").setItems(labels,(dialog,index)->importInstalledApp(apps.get(index))).setNegativeButton("取消",null).show();});}
     void importInstalledApp(android.content.pm.ApplicationInfo app){runTask("导入已安装应用",()->{File d=new File(getFilesDir(),"workspace-"+System.currentTimeMillis());d.mkdirs();String name=getPackageManager().getApplicationLabel(app)+".apk";Files.write(new File(d,"source-name.txt").toPath(),name.getBytes(StandardCharsets.UTF_8));File apk=new File(d,"source.apk");Files.copy(new File(app.sourceDir).toPath(),apk.toPath());return new Workspace(apk,d,this::updateProgress);},w->{if(workspace!=null)workspace.close();workspace=w;getPreferences(0).edit().putString("workspace",w.dir.getName()).apply();browser();});}
     void importApk(Uri uri){runTask("导入 APK",()->{File d=new File(getFilesDir(),"workspace-"+System.currentTimeMillis());d.mkdirs();String name="source.apk";try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}name=name.replaceAll("[\\\\/:*?\"<>|]","_");Files.write(new File(d,"source-name.txt").toPath(),name.getBytes(StandardCharsets.UTF_8));File apk=new File(d,"source.apk");try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(apk)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return new Workspace(apk,d,this::updateProgress);},w->{if(workspace!=null)workspace.close();workspace=w;getPreferences(0).edit().putString("workspace",w.dir.getName()).apply();browser();});}
    void openWorkspace(File apk,File d){runTask("恢复上次工作区",()->new Workspace(apk,d,this::updateProgress),w->{workspace=w;browser();});}
    void browser(){rememberView();entry=null;scene=null;dirty=false;page=getPreferences(0).getInt(workspace.dir.getName()+"/page",page);query=getPreferences(0).getString(workspace.dir.getName()+"/query",query);category=getPreferences(0).getString(workspace.dir.getName()+"/category",category);body.removeAllViews();toolbar.setTitle("资源库");toolbar.setSubtitle(workspace.entries.size()+" 项界面 · 点击卡片开始编辑");toolbar.setNavigationIcon(null);
        actions(body,button("打开 APK",()->open.launch(new String[]{"*/*"})),button("使用说明",this::help));
        TextInputLayout input=new TextInputLayout(this,null,com.google.android.material.R.attr.textInputOutlinedStyle);input.setHint("搜索昵称、文本、变量名或路径");search=new TextInputEditText(input.getContext());search.setSingleLine(true);search.setText(query);input.addView(search);body.addView(input);
        actions(body,button("界面目录",this::chooseDirectory));
        summary=label("",12);body.addView(summary);ScrollView sc=scroll(body);libraryScroll=sc;sc.setFillViewport(true);sc.setSmoothScrollingEnabled(true);sc.setVerticalScrollBarEnabled(true);resourceList=new LinearLayout(this);resourceList.setOrientation(LinearLayout.VERTICAL);sc.addView(resourceList);
        actions(body,button("上一页",()->{if(page>0){page--;refresh();sc.scrollTo(0,0);}}),button("下一页",()->{page++;refresh();sc.scrollTo(0,0);}));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();page=0;uiHandler.removeCallbacks(searchRefresh);uiHandler.postDelayed(searchRefresh,220);}public void afterTextChanged(Editable e){}});refresh();
    }
    interface Choice{void accept(String s);}
    void choose(String title,String[] values,Choice done){new MaterialAlertDialogBuilder(this).setTitle(title).setItems(values,(d,i)->done.accept(values[i])).setNegativeButton("取消",null).show();}
    void chooseDirectory(){TreeSet<String> dirs=new TreeSet<>();for(Workspace.Entry e:workspace.entries){String p=e.category;dirs.add(p);int slash=p.indexOf('/');while(slash>=0){dirs.add(p.substring(0,slash));slash=p.indexOf('/',slash+1);}}ArrayList<String> list=new ArrayList<>();list.add("全部目录");list.addAll(dirs);choose("原始目录",list.toArray(new String[0]),s->{category=s;page=0;refresh();});}
    void refresh(){
        if(resourceList==null||entry!=null)return;
        ArrayList<Workspace.Entry> matches=new ArrayList<>();String q=query.toLowerCase(Locale.ROOT);
        for(Workspace.Entry e:workspace.entries){if(onlyThree&&!e.path.endsWith(".3"))continue;if(!format.equals("全部格式")&&!e.type.equals(format))continue;if(!category.equals("全部目录")&&!e.category.equals(category)&&!e.category.startsWith(category+"/"))continue;if(!q.isEmpty()&&!e.searchText.contains(q))continue;matches.add(e);}
        int pages=Math.max(1,(matches.size()+79)/80);page=Math.min(page,pages-1);summary.setText(category+" · "+format+"\n"+matches.size()+" 项 · 第 "+(page+1)+" / "+pages+" 页");libraryKey=workspace.dir.getName()+"/library/"+category+"/"+query+"/"+page;
        String restoreKey=libraryKey;ScrollView restoreScroll=libraryScroll;
        restoreScroll.post(()->{if(restoreScroll==libraryScroll&&restoreKey.equals(libraryKey)){restoreScroll.scrollTo(0,getPreferences(0).getInt(restoreKey,0));loadVisibleThumbnails();}});
        libraryScroll.setOnScrollChangeListener((v,x,y,ox,oy)->{if(restoreKey.equals(libraryKey))scheduleScrollSave();uiHandler.removeCallbacks(thumbnailLoad);uiHandler.postDelayed(thumbnailLoad,100);});
        thumbnailViews.clear();resourceList.removeAllViews();for(int i=page*80;i<Math.min(matches.size(),(page+1)*80);i++)addLibraryCard(matches.get(i));
    }
    void load(Workspace.Entry target){rememberView();uiHandler.removeCallbacks(searchRefresh);int request=++generation;runTask("载入资源",()->{byte[] raw=workspace.read(target.path);Csb parsed="CSB".equals(target.type)?new Csb(raw):null;Csb.Node parsedRoot=parsed==null?null:parsed.root();return new Object[]{raw,parsed,parsedRoot};},result->{if(request!=generation)return;entry=target;dirty=false;history.clear();pendingEdit=null;preparedCsb=(Csb)result[1];preparedRoot=(Csb.Node)result[2];showResource((byte[])result[0]);});}
    Csb preparedCsb;Csb.Node preparedRoot;
    void showResource(byte[] raw)throws Exception{body.removeAllViews();toolbar.setTitle(entry.type+" 编辑器");toolbar.setSubtitle(entry.name);toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);editor=null;csb=null;scene=null;selected=null;
        if(entry.type.equals("CSB")){csb=preparedCsb!=null?preparedCsb:new Csb(raw);root=preparedRoot!=null?preparedRoot:csb.root();preparedCsb=null;preparedRoot=null;scene=new SceneView(this,csb,root);csb.beforeEdit=this::trackEdit;scene.onEditStart=()->gestureEdit=true;scene.onEditEnd=()->{gestureEdit=false;finishEdit.run();};
            actions(body,button("保存修改",()->save(false)),button("编译 ZIP",()->save(true)),button("文件与资源…",()->choose("文件与资源",new String[]{"查看引用资源","按原路径解包","导入路径 ZIP","导出 .3","导入替换 .3","高级字符串","放弃未保存"},s->{switch(s){case "查看引用资源"->referencedResources();case "按原路径解包"->exportLogical();case "导入路径 ZIP"->confirmDiscard(()->logicalPicker.launch(new String[]{"application/zip","application/octet-stream"}));case "导出 .3"->exportSaved();case "导入替换 .3"->confirmDiscard(()->replace.launch(new String[]{"*/*"}));case "高级字符串"->strings();default->confirmDiscard(()->load(entry));}})));
            LinearLayout canvasRow=new LinearLayout(this);body.addView(canvasRow,new LinearLayout.LayoutParams(-1,0,1));ScrollView railScroll=new ScrollView(this);nodeScroll=railScroll;String railKey=viewKey()+"/nodes";railScroll.setOnScrollChangeListener((v,x,y,ox,oy)->getPreferences(0).edit().putInt(railKey,y).apply());railScroll.post(()->railScroll.scrollTo(0,getPreferences(0).getInt(railKey,0)));railScroll.setSmoothScrollingEnabled(true);canvasRow.addView(railScroll,new LinearLayout.LayoutParams(dp(getResources().getConfiguration().screenWidthDp>=700?180:76),-1));nodeRail=new LinearLayout(this);nodeRail.setOrientation(LinearLayout.VERTICAL);railScroll.addView(nodeRail);nodeIcons.clear();ArrayList<Csb.Node> railNodes=new ArrayList<>();collect(root,railNodes,new ArrayList<>(),0);nodeRail.addView(label("节点预览",12));for(Csb.Node n:railNodes){LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(4),dp(6),dp(4),dp(6));ImageView icon=new ImageView(this);icon.setImageResource(android.R.drawable.ic_menu_gallery);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setContentDescription(n.toString());tile.addView(icon,new LinearLayout.LayoutParams(-1,dp(44)));TextView caption=label((n.name.isEmpty()?n.kind:n.name)+(n.get("visible")==0?" · 隐藏":""),11);caption.setMaxLines(2);tile.addView(caption);nodeIcons.put(n.id,icon);tile.setOnClickListener(v->{selected=n;scene.selected=n;selectedLabel.setText(n.toString());scene.invalidate();});nodeRail.addView(tile);}canvasRow.addView(scene,new LinearLayout.LayoutParams(0,-1,1));
            String key=viewKey();SceneView currentScene=scene;scene.post(()->{if(scene!=currentScene)return;float z=getPreferences(0).getFloat(key+"/zoom",0);if(z>0){currentScene.zoom=z;currentScene.panX=currentScene.getWidth()/2f-getPreferences(0).getFloat(key+"/cx",0)*z;currentScene.panY=currentScene.getHeight()/2f+getPreferences(0).getFloat(key+"/cy",0)*z;currentScene.fit=false;currentScene.invalidate();}});
            selectedLabel=label("① 左侧选节点  ② 编辑属性  ③ 保存或编译",12);selectedLabel.setMaxLines(2);body.addView(selectedLabel);scene.onSelect=n->{selected=n;selectedLabel.setText(n.toString());};scene.onChange=()->dirty=true;
            MaterialButton modeButton=button("模式：选择",()->{});modeButton.setOnClickListener(v->{modeButton.setText(scene.cycleMode());toast(scene.mode==1?"先选中节点，再拖动移动；角点调整尺寸":scene.mode==2?"拖动平移画布，不修改节点":"点击选择节点，滑动浏览画布");});actions(body,modeButton,button("适应全部",()->scene.reset()));
            actions(body,button("删除选中节点",this::deleteSelected),button("撤销",()->undoEdit(false)),button("重做",()->undoEdit(true)),button("隐藏 / 显示",this::toggleSelected));actions(body,button("搜索节点",this::nodes),button("编辑属性",this::properties),button("更换图片",()->{if(selected==null||selected.imageSlot==0){toast("请先在左侧或画布选择图片节点");return;}imageTarget=selected;imagePicker.launch(new String[]{"image/png"});}));loadReferencedImages(scene);
        }else if(entry.type.equals("JSON")||entry.type.equals("PLIST")){
            body.addView(label(entry.type.equals("PLIST")?"图集索引不包含界面节点。以 JSON 编辑，保存回二进制 plist。":"JSON 数据不包含 CSB 界面节点。",14));editor=new EditText(this);editor.setGravity(Gravity.TOP|Gravity.START);editor.setTextSize(13);editor.setTypeface(Typeface.MONOSPACE);editor.setInputType( android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);editor.setText(entry.type.equals("PLIST")?Plists.decode(raw):prettyJson(new String(raw,StandardCharsets.UTF_8)));body.addView(editor,new LinearLayout.LayoutParams(-1,0,1));editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){dirty=true;}public void afterTextChanged(Editable e){}});actions(body,button("保存编辑",()->save(false)),button("保存并导出",()->save(true)),button("放弃未保存",()->confirmDiscard(()->load(entry))));
        }else{
            ScrollView sc=scroll(body);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);sc.addView(content);
            if(entry.type.equals("PNG")||entry.type.equals("KTX")){byte[] png=raw;if(entry.type.equals("KTX")){ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);content.addView(label("KTX · "+b.getInt(36)+" × "+b.getInt(40)+"\n内部格式 0x"+Integer.toHexString(b.getInt(28))+" · mip "+b.getInt(56)+"\n下方预览为同名 PNG。替换需保持 KTX 头参数一致。",14));Workspace.Entry sibling=workspace.find(entry.path.substring(0,entry.path.length()-1)+"1");png=sibling==null?null:workspace.read(sibling.path);}if(png!=null){Bitmap bitmap=decodeBitmap(png);ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setImageBitmap(bitmap);content.addView(image,new LinearLayout.LayoutParams(-1,-2));}}
            else content.addView(label(switch(entry.type){case "SPINE"->"Spine 骨骼动画，需要配套图集和运行时。";case "SPINE_ATLAS"->"Spine 图集描述，当前画布不支持 Spine 动画。";default->"未识别为 CSB，可导出或导入相同格式资源。";},15));
            StringBuilder hex=new StringBuilder();for(int i=0;i<Math.min(raw.length,256);i++){if(i%16==0)hex.append('\n');hex.append(String.format(Locale.ROOT,"%02x ",raw[i]&255));}TextView code=label(hex.toString(),12);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);content.addView(code);
        }
    }
    static String prettyJson(String text)throws Exception{JSONTokener t=new JSONTokener(text);Object v=t.nextValue();if(!(v instanceof JSONObject||v instanceof JSONArray)||t.nextClean()!=0)throw new IllegalArgumentException("请输入完整的 JSON 对象或数组");return v instanceof JSONObject o?o.toString(2):((JSONArray)v).toString(2);}
    void save(boolean export){if(entry==null)return;try{byte[] bytes;if(csb!=null){csb.root();bytes=csb.bytes.clone();}else if(editor!=null){String text=editor.getText().toString();bytes=entry.type.equals("PLIST")?Plists.encode(prettyJson(text)):prettyJson(text).getBytes(StandardCharsets.UTF_8);}else return;Workspace.Entry target=entry;runTask("保存工作副本",()->{workspace.save(target.path,bytes);return bytes;},b->{dirty=false;toast("已保存");if(export)build();});}catch(Exception e){error(e);}}
    void exportSaved(){Workspace.Entry target=entry;runTask("读取已保存资源",()->workspace.read(target.path),b->offerBytes(target,b));}
    void offerBytes(Workspace.Entry e,byte[] b){exportFile=null;exportBytes=b;create.launch(e.path.substring(e.path.lastIndexOf('/')+1));}
    void confirmDiscard(Runnable action){if(!dirty){action.run();return;}new MaterialAlertDialogBuilder(this).setTitle("有未保存的修改").setMessage("继续将放弃当前未保存的编辑。").setNegativeButton("继续编辑",null).setPositiveButton("放弃修改",(d,w)->{dirty=false;action.run();}).show();}
    void leaveDetail(){if(busy)return;confirmDiscard(this::browser);}
    void collect(Csb.Node n,List<Csb.Node> nodes,List<String> labels,int depth){nodes.add(n);StringBuilder indent=new StringBuilder();for(int i=0;i<depth;i++)indent.append("  ");labels.add(indent+n.toString()+(n.text==null?"":" · "+n.text));for(Csb.Node c:n.children)collect(c,nodes,labels,depth+1);}
    void nodes(){List<Csb.Node> nodes=new ArrayList<>();List<String> labels=new ArrayList<>();collect(root,nodes,labels,0);LinearLayout box=new LinearLayout(this);box.setPadding(dp(16),0,dp(16),0);box.setOrientation(LinearLayout.VERTICAL);EditText filter=new EditText(this);filter.setHint("搜索节点名称或文字");box.addView(filter);ListView list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,dp(360)));ArrayList<Csb.Node> shown=new ArrayList<>(nodes);ArrayList<String> names=new ArrayList<>(labels);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,names);list.setAdapter(adapter);androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("节点层级").setView(box).setNegativeButton("关闭",null).create();filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){shown.clear();names.clear();for(int i=0;i<nodes.size();i++)if(labels.get(i).toLowerCase(Locale.ROOT).contains(s.toString().toLowerCase(Locale.ROOT))){shown.add(nodes.get(i));names.add(labels.get(i));}adapter.notifyDataSetChanged();}});list.setOnItemClickListener((p,v,i,id)->{selected=shown.get(i);scene.selected=selected;selectedLabel.setText(selected.toString());scene.invalidate();dialog.dismiss();});dialog.show();}
    void properties(){if(selected==null){toast("请先选择一个节点");return;}Csb.Node n=selected;ScrollView sc=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setPadding(dp(20),dp(8),dp(20),dp(8));box.setOrientation(LinearLayout.VERTICAL);sc.addView(box);Map<String,EditText> fields=new LinkedHashMap<>();Map<String,String> names=Map.ofEntries(Map.entry("x","X 位置"),Map.entry("y","Y 位置"),Map.entry("width","宽度"),Map.entry("height","高度"),Map.entry("scaleX","X 缩放"),Map.entry("scaleY","Y 缩放"),Map.entry("anchorX","X 锚点"),Map.entry("anchorY","Y 锚点"),Map.entry("rotation","旋转"),Map.entry("visible","可见 0 / 1"),Map.entry("alpha","透明度 0–255"),Map.entry("fontSize","字号"));for(String key:n.bindings.keySet()){TextInputLayout input=new TextInputLayout(this);input.setHint(names.getOrDefault(key,key));EditText edit=new TextInputEditText(input.getContext());edit.setSingleLine(true);edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);edit.setText(String.format(Locale.ROOT,"%.4f",n.get(key)).replaceAll("\\.?0+$",""));input.addView(edit);box.addView(input);fields.put(key,edit);}EditText text=new EditText(this);if(n.textSlot!=0){text.setHint("文字");text.setText(n.text);box.addView(text);}box.addView(label("仅显示原文件已有字段。宽高可在这里调整。",12));androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle(n.toString()).setView(sc).setNegativeButton("取消",null).setPositiveButton("应用",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{byte[] before=csb.bytes.clone();try{for(Map.Entry<String,EditText> f:fields.entrySet())csb.set(n,f.getKey(),Double.parseDouble(f.getValue().getText().toString()));if(n.textSlot!=0&&!Objects.equals(n.text,text.getText().toString()))csb.setText(n,text.getText().toString());dirty=true;scene.geometryChanged();dialog.dismiss();}catch(Exception e){csb.bytes=before;root=csb.root();scene.csb.bytes=csb.bytes;scene.root=root;scene.selected=findNode(root,n.id);selected=scene.selected;scene.invalidate();error(e);}}));dialog.show();}
    void strings(){EditText filter=new EditText(this);filter.setHint("搜索字符串");LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),0,dp(20),0);box.addView(filter);ListView list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,dp(360)));List<Csb.TextRef> all=csb.strings(),shown=new ArrayList<>(all);ArrayList<String> values=new ArrayList<>();for(Csb.TextRef r:shown)values.add(r.text);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,values);list.setAdapter(adapter);androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(this).setTitle("高级字符串编辑").setView(box).setNegativeButton("关闭",null).create();filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){shown.clear();values.clear();for(Csb.TextRef ref:all)if(ref.text.contains(s)){shown.add(ref);values.add(ref.text);}adapter.notifyDataSetChanged();}});list.setOnItemClickListener((p,v,i,id)->{Csb.TextRef ref=shown.get(i);EditText edit=new EditText(this);edit.setText(ref.text);new MaterialAlertDialogBuilder(this).setTitle("编辑字符串").setView(edit).setNegativeButton("取消",null).setPositiveButton("应用",(d,w)->{byte[] before=csb.bytes.clone();try{csb.replace(ref,edit.getText().toString());byte[] changed=csb.bytes.clone();showResource(changed);dirty=true;dialog.dismiss();}catch(Exception e){csb.bytes=before;error(e);}}).show();});dialog.show();}
    static Bitmap decodeBitmap(byte[] raw){BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,o);o.inSampleSize=1;while(o.outWidth/o.inSampleSize>2048||o.outHeight/o.inSampleSize>2048)o.inSampleSize*=2;o.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(raw,0,raw.length,o);}
    final ActivityResultLauncher<String[]> logicalPicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri==null)return;runTask("导入路径 ZIP",()->{File file=new File(getCacheDir(),"logical-import.zip");try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}try{return workspace.importLogical(file);}finally{file.delete();}},count->{toast("已导入 "+count+" 个修改资源");load(entry);});});
    void exportLogical(){if(entry==null)return;saveSceneForLogical();}
    void saveSceneForLogical(){runTask("按原路径解包",()->{if(csb!=null)workspace.save(entry.path,csb.bytes);return workspace.logicalBundle(entry.path);},file->{dirty=false;exportFile=file;exportBytes=null;create.launch(file.getName());});}
    int previewGeneration;
    void loadReferencedImages(SceneView target){target.resourcesLoading=true;Workspace source=workspace;int request=++previewGeneration;byte[] snapshot=target.csb.bytes.clone();worker.execute(()->{List<ResourcePreview.Item> items=ResourcePreview.load(source,new Csb(snapshot).root());runOnUiThread(()->{if(disposed||scene!=target||request!=previewGeneration)return;target.resources=items;target.resourcesLoading=false;target.images.clear();int loaded=0;for(ResourcePreview.Item item:items)if(item.bitmap!=null){target.images.put(item.node.id,item.bitmap);ImageView icon=nodeIcons.get(item.node.id);if(icon!=null)icon.setImageBitmap(item.bitmap);loaded++;}target.invalidate();toast("引用图片已显示 "+loaded+" / "+items.size()+"，详情见引用资源");});});}
    void referencedResources(){
        if(scene==null)return;if(scene.resourcesLoading){toast("正在加载引用资源，请稍后");return;}
        ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(8),dp(16),dp(8));scroll.addView(content);
        if(scene.resources.isEmpty())content.addView(label("当前 .3 没有图片节点引用。",14));
        for(ResourcePreview.Item item:scene.resources){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(8),0,dp(16));
            row.addView(label(item.node.toString(),16));TextView path=label(item.node.imagePath+(item.node.plistPath.isEmpty()?"":"\n图集："+item.node.plistPath)+"\n"+item.detail,12);path.setTextIsSelectable(true);row.addView(path);
            if(item.bitmap!=null){ImageView image=new ImageView(this);image.setImageBitmap(item.bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(item.node.imagePath);row.addView(image,new LinearLayout.LayoutParams(-1,dp(120)));}
            content.addView(row);
        }
        new MaterialAlertDialogBuilder(this).setTitle("当前 .3 的引用资源（"+scene.resources.size()+"）").setView(scroll).setPositiveButton("关闭",null).show();
    }
    void build(){if(entry==null)return;String path=entry.path;runTask("编译当前界面 ZIP",()->workspace.build(path,this::updateProgress),file->{exportFile=file;exportBytes=null;create.launch(file.getName());});}
    void help(){new MaterialAlertDialogBuilder(this).setTitle("使用说明").setMessage("1. 导入 APK，浏览 CCUI 界面。\n2. 选择节点，编辑布局、尺寸与文字；可一键删除、隐藏 / 显示，支持撤销和重做。\n3. 在图片节点中引用新 PNG，填写相对路径。\n4. 编译 ZIP 会先保存当前画布，再打包当前 .3 和新引用图片；新图集引用会附带必要的 plist。\n\nZIP 与原安装包同名，保留包内路径。工作区自动恢复。画布为静态近似，动画、九宫格及百分比布局需在游戏中复核。").setPositiveButton("知道了",null).show();}
}
