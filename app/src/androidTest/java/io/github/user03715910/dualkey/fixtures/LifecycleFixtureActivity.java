package io.github.user03715910.dualkey.fixtures;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.util.Rational;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.widget.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/** A separate, test-only app process: actual local video, fullscreen, PiP and optional text focus.
 * Java/boot APIs only: the standalone test APK doesn't include the target app's Kotlin runtime. */
public class LifecycleFixtureActivity extends Activity {
    private static WeakReference<LifecycleFixtureActivity> current=new WeakReference<>(null);
    private FrameLayout root;
    private LinearLayout header;
    private VideoView video;
    private EditText editor;
    private TextView state;
    private boolean playing;
    private int imeInset;
    private volatile String received="none";
    private volatile byte[] expectedMedia;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); current=new WeakReference<>(this);
        // Use one inset strategy. adjustResize plus IME padding would hide the test editor twice.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        root=new FrameLayout(this);
        root.setFocusableInTouchMode(true); root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        header=new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout buttons=new LinearLayout(this);
        button(buttons,"横","landscape",()->setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        button(buttons,"縦","portrait",()->setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        button(buttons,"全","fullscreen",()->bars(false)); button(buttons,"戻","windowed",()->bars(true));
        button(buttons,"小","pip",()->enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build()));
        button(buttons,"終了","end-input",this::endInput);
        button(buttons,"入力","edit",this::beginInput);
        button(buttons,"閉","close",this::finishAndRemoveTask);
        header.addView(buttons,new LinearLayout.LayoutParams(-1,dp(36)));
        state=new TextView(this); state.setTextSize(11); state.setContentDescription("fixture-state");
        header.addView(state,new LinearLayout.LayoutParams(-1,dp(20)));
        video=new VideoView(this); video.setContentDescription("fixture-video"); video.setFocusable(false);
        root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        root.addView(header,new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP));
        editor=new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo info) {
                InputConnection base=super.onCreateInputConnection(info);
                String mode=getIntent().getStringExtra("media");
                if (base==null || mode==null) return base;
                info.contentMimeTypes=mode.equals("images")?new String[]{"image/*"}:new String[]{"image/*","video/mp4"};
                return new InputConnectionWrapper(base,false) {
                    @Override public boolean commitContent(InputContentInfo value,int flags,Bundle opts) {
                        if ((flags&InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION)==0) { received="missing-grant"; return false; }
                        try { value.requestPermission(); } catch (Exception e) { received="grant-error"; return false; }
                        byte[] expected=expectedMedia;
                        new Thread(()->{
                            try (InputStream input=getContentResolver().openInputStream(value.getContentUri())) {
                                byte[] bytes=readBytes(input);
                                received=value.getDescription().getMimeType(0)+"; match="+java.util.Arrays.equals(expected,bytes);
                            } catch (Exception e) { received="read-error"; }
                            finally { value.releasePermission(); }
                        },"test-receive-media").start();
                        return true;
                    }
                };
            }
        }; editor.setSingleLine(false); editor.setTextSize(16);
        editor.setId(View.generateViewId()); editor.setFocusableInTouchMode(true);
        editor.setContentDescription("fixture-editor");
        editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setVisibility(View.GONE); root.addView(editor,new FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM));
        setContentView(root); root.requestFocus();
        if (Build.VERSION.SDK_INT>=30) root.setOnApplyWindowInsetsListener((v,insets)->{
            Insets system=insets.getInsets(WindowInsets.Type.systemBars());
            imeInset=Math.max(0,insets.getInsets(WindowInsets.Type.ime()).bottom-system.bottom);
            root.setPadding(system.left,system.top,system.right,system.bottom+imeInset); updateState(); return insets;
        });
        video.setOnPreparedListener(player->{ player.setLooping(true); video.start(); playing=true; updateState(); });
        video.setOnErrorListener((player,what,extra)->{ playing=false; state.setText("video=error:"+what+":"+extra); return true; });
        new Thread(()->{
            try {
                File clip=clip(getCacheDir());
                runOnUiThread(()->{ if (!isDestroyed()) video.setVideoURI(Uri.fromFile(clip)); });
            } catch (Exception e) { runOnUiThread(()->state.setText("video=error:"+e.getClass().getSimpleName())); }
        },"test-video").start();
        updateState();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private void button(LinearLayout row,String label,String name,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setTextSize(11); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(0,0,0,0); b.setContentDescription("fixture-"+name); b.setOnClickListener(v->action.run());
        row.addView(b,new LinearLayout.LayoutParams(0,-1,1));
    }
    private void endInput() {
        root.setFocusableInTouchMode(true); root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        root.requestFocus(); editor.setVisibility(View.GONE);
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(),0);
        updateState();
    }
    private void beginInput() {
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS); root.setFocusable(false);
        editor.setVisibility(View.VISIBLE);
        editor.post(()->{ editor.requestFocus(); getSystemService(InputMethodManager.class).showSoftInput(editor,0); updateState(); });
    }
    private void command(String value) {
        if (value.startsWith("copy-")) { copyMedia(value.substring(5)); return; }
        if (value.equals("reset-media")) { received="none"; return; }
        switch(value) {
            case "edit": beginInput(); break;
            case "save-screenshot": saveScreenshot(); break;
            case "delete-screenshot": deleteScreenshot(); break;
            case "end-input": endInput(); break;
            case "landscape": setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); break;
            case "portrait": setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); break;
            case "fullscreen": bars(false); break;
            case "windowed": bars(true); break;
            case "pip": enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build()); break;
            default: finishAndRemoveTask();
        }
    }
    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192]; int n;
        while ((n=input.read(buffer))!=-1) { data.write(buffer,0,n); if (data.size()>2*1024*1024) throw new IOException("fixture size"); }
        return data.toByteArray();
    }
    private Uri testScreenshot;
    private void deleteScreenshot() {
        if (testScreenshot != null) {
            getContentResolver().delete(testScreenshot, null, null);
            testScreenshot = null;
        }
    }
    private void saveScreenshot() {
        if (Build.VERSION.SDK_INT < 29) return;
        deleteScreenshot();
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(android.graphics.Color.BLUE);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            expectedMedia = png.toByteArray();
            received = "none";
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("test", "unchanged text"));
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("relative_path", "Pictures/Screenshots");
            values.put("_display_name", "DualKeyScreenshotFixture.png");
            values.put("mime_type", "image/png");
            values.put("is_pending", 1);
            testScreenshot = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            try (OutputStream output = getContentResolver().openOutputStream(testScreenshot)) {
                output.write(expectedMedia);
            }
            values.clear();
            values.put("is_pending", 0);
            getContentResolver().update(testScreenshot, values, null, null);
        } catch (Exception e) { received = "screenshot-error"; }
    }
    private void copyMedia(String name) {
        try {
            if (TestMediaProvider.mime(name)==null) return;
            File file=new File(getCacheDir(),name);
            if (!name.equals("lifecycle-video.mp4")) {
                try (OutputStream output=new FileOutputStream(file)) {
                    if (name.endsWith(".gif")) output.write(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",0));
                    else {
                        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(160,90,android.graphics.Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
                        canvas.scale(160f/Math.max(1,root.getWidth()),90f/Math.max(1,root.getHeight())); root.draw(canvas);
                        android.graphics.Bitmap.CompressFormat format=name.endsWith(".jpeg")?android.graphics.Bitmap.CompressFormat.JPEG:
                            name.endsWith(".webp")?android.graphics.Bitmap.CompressFormat.WEBP:android.graphics.Bitmap.CompressFormat.PNG;
                        bitmap.compress(format,90,output); bitmap.recycle();
                    }
                }
            }
            try (InputStream input=new FileInputStream(file)) { expectedMedia=readBytes(input); }
            received="none";
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newUri(getContentResolver(),"test media",
                Uri.parse("content://"+getPackageName()+".test-media/"+name)));
        } catch (Exception e) { received="copy-error"; }
    }
    private void bars(boolean show) {
        if (Build.VERSION.SDK_INT>=30 && getWindow().getInsetsController()!=null) {
            if (show) getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
            else getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        }
    }
    private void updateState() {
        state.setText("video="+(playing?"playing":"loading")+"; ime="+imeInset+"; pip="+isInPictureInPictureMode()+
            "; landscape="+(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE)+
            "; focus="+editor.hasFocus()+"; windowFocus="+hasWindowFocus());
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); updateState(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (state!=null) updateState(); }
    @Override public void onPictureInPictureModeChanged(boolean pip,Configuration config) {
        super.onPictureInPictureModeChanged(pip,config); header.setVisibility(pip?View.GONE:View.VISIBLE);
        if (pip) editor.setVisibility(View.GONE); updateState();
    }
    @Override public void dump(String prefix,FileDescriptor fd,PrintWriter out,String[] args) {
        super.dump(prefix,fd,out,args); out.println("Fixture: "+state.getText());
        // This synthetic editor lives only in the test APK, not in the production IME.
        out.println("FixtureText64: "+android.util.Base64.encodeToString(editor.getText().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.NO_WRAP));
        out.println("FixtureFocus: "+editor.hasFocus());
        out.println("FixtureMedia: "+received);
    }
    @Override public void onDestroy() {
        video.stopPlayback(); if (current.get()==this) current.clear(); super.onDestroy();
    }
    public static class FinishReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent) {
            LifecycleFixtureActivity a=current.get(); if (a!=null) a.command(intent.getStringExtra("command")==null?"close":intent.getStringExtra("command"));
        }
    }
    public static class TestMediaProvider extends ContentProvider {
        static String mime(String name) {
            if (name==null) return null;
            switch(name) {
                case "test.png": return "image/png";
                case "test.jpeg": return "image/jpeg";
                case "test.webp": return "image/webp";
                case "test.gif": return "image/gif";
                case "lifecycle-video.mp4": return "video/mp4";
                default: return null;
            }
        }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return mime(uri.getLastPathSegment()); }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
            if (!mode.equals("r") || getType(uri)==null) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),uri.getLastPathSegment()),ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public android.database.Cursor query(Uri u,String[] p,String s,String[] a,String o) { return null; }
        @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    }
    /** Generate our own two-second moving grayscale video; no network or copyrighted media. */
    private static synchronized File clip(File directory) throws Exception {
        File file=new File(directory,"lifecycle-video.mp4"); if (file.length()>0) return file;
        File temporary=new File(directory,"lifecycle-video.tmp.mp4");
        MediaCodec codec=MediaCodec.createEncoderByType("video/avc");
        MediaMuxer muxer=new MediaMuxer(temporary.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started=false,ended=false; int frame=0,track=-1;
        try {
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",160,96);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE,64000); format.setInteger(MediaFormat.KEY_FRAME_RATE,10);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1); codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); long deadline=SystemClock.uptimeMillis()+15000;
            byte[] pixels=new byte[160*96*3/2]; java.util.Arrays.fill(pixels,160*96,pixels.length,(byte)128);
            while (!ended && SystemClock.uptimeMillis()<deadline) {
                if (frame<=20) {
                    int input=codec.dequeueInputBuffer(10000);
                    if (input>=0) {
                        if (frame==20) codec.queueInputBuffer(input,0,0,frame*100000L,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        else {
                            for (int y=0;y<96;y++) for (int x=0;x<160;x++) pixels[y*160+x]=(byte)(32+((x/16+frame)%8)*24);
                            ByteBuffer buffer=codec.getInputBuffer(input); buffer.clear(); buffer.put(pixels);
                            codec.queueInputBuffer(input,0,pixels.length,frame*100000L,0);
                        }
                        frame++;
                    }
                }
                int output=codec.dequeueOutputBuffer(info,10000);
                if (output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track=muxer.addTrack(codec.getOutputFormat()); muxer.start(); started=true; }
                else if (output>=0) {
                    if (info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        ByteBuffer buffer=codec.getOutputBuffer(output); buffer.position(info.offset); buffer.limit(info.offset+info.size);
                        muxer.writeSampleData(track,buffer,info);
                    }
                    ended=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0; codec.releaseOutputBuffer(output,false);
                }
            }
            if (!ended) throw new IOException("Video encoding timeout");
        } finally { codec.stop(); codec.release(); if (started) muxer.stop(); muxer.release(); }
        if (!temporary.renameTo(file)) throw new IOException("Video commit failed"); return file;
    }
}
