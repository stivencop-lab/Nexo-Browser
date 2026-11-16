package com.nexo.cefriumprototype;
import android.app.Activity;
import android.os.Bundle;
public final class MainActivity extends Activity {
 private CefriumSession session;
 @Override protected void onCreate(Bundle state) { super.onCreate(state); session=CefriumSession.create(this,false); setContentView(session.view()); if(state==null) session.loadUrl("https://example.com/"); }
 @Override protected void onDestroy() { if(session!=null) session.close(); super.onDestroy(); }
}
