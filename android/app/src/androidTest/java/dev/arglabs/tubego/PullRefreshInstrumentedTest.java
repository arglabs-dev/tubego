package dev.arglabs.tubego;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.core.widget.NestedScrollView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.json.JSONObject;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class PullRefreshInstrumentedTest extends InstrumentationTestCase {
    private final String origin="https://pull-refresh.example.invalid";
    private Context context;
    private JSONObject session;
    private Activity activity;
    private String previousOrigin;
    @Override protected void setUp()throws Exception{
        super.setUp();context=getInstrumentation().getTargetContext();
        previousOrigin=context.getSharedPreferences("server_connection",0).getString("server_url",null);
        context.getSharedPreferences("server_connection",0).edit().putString("server_url",origin).commit();
        session=new JSONObject().put("token","pull-refresh-fixture").put("status","approved").put("user_id",UUID.randomUUID().toString()).put("device_id",UUID.randomUUID().toString());
        new SessionStore(context,origin).save(session);LanguagePreferences.saveLocal(context,origin,"es");
        File root=LocalLibraryStorage.root(context,origin,session.getString("user_id"),session.getString("device_id"));root.mkdirs();
        Map<String,String> rows=new LinkedHashMap<>();
        for(int n=0;n<20;n++){String id=UUID.randomUUID().toString();rows.put(id,new JSONObject().put("id",id).put("title","Lesson "+n).put("source_url","https://example.invalid/lesson").put("server_available",false).put("created_at","2026-11-15T12:00:00Z").toString());}
        new HistoryCache(root).merge(rows);
        activity=getInstrumentation().startActivitySync(new Intent(context,LibraryActivity.class).putExtra("server_url",origin).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        long until=System.currentTimeMillis()+5000;while(!contains(activity.getWindow().getDecorView(),"Lesson")&&System.currentTimeMillis()<until)Thread.sleep(20);
        assertTrue(contains(activity.getWindow().getDecorView(),"Lesson"));
    }
    @Override protected void tearDown()throws Exception{
        if(activity!=null){getInstrumentation().runOnMainSync(activity::finish);getInstrumentation().waitForIdleSync();}
        TransferJobs.stop(context,origin);new SessionStore(context,origin).clear();
        LocalLibraryStorage.wipe(context,origin,session.getString("user_id"),session.getString("device_id"));
        var edit=context.getSharedPreferences("server_connection",0).edit();if(previousOrigin==null)edit.remove("server_url");else edit.putString("server_url",previousOrigin);edit.commit();super.tearDown();
    }
    public void testGestureRefreshesAtTopButPreservesNormalScrolling()throws Exception{
        SwipeRefreshLayout refresh=(SwipeRefreshLayout)activity.getWindow().getDecorView().findViewWithTag("library_refresh");
        NestedScrollView scroll=(NestedScrollView)activity.getWindow().getDecorView().findViewWithTag("library_scroll");
        AtomicInteger requests=new AtomicInteger();
        getInstrumentation().runOnMainSync(()->{refresh.setOnRefreshListener(requests::incrementAndGet);scroll.scrollTo(0,Ds.dp(context,700));});
        getInstrumentation().waitForIdleSync();assertTrue(scroll.getScrollY()>0);swipe(refresh,0.65f);
        assertEquals("Ordinary downward scrolling must not refresh from the middle",0,requests.get());
        getInstrumentation().runOnMainSync(()->scroll.scrollTo(0,0));getInstrumentation().waitForIdleSync();
        swipe(refresh,0.08f);assertEquals("Small movements must not refresh",0,requests.get());
        swipe(refresh,0.9f);long until=System.currentTimeMillis()+2000;while(requests.get()==0&&System.currentTimeMillis()<until)Thread.sleep(20);
        assertEquals(1,requests.get());assertTrue(refresh.isRefreshing());
        swipe(refresh,0.9f);assertEquals("No duplicate request while refreshing",1,requests.get());
        getInstrumentation().runOnMainSync(()->refresh.setRefreshing(false));
    }
    public void testAccessibleRefreshFinishesOfflineAndPreservesSearch()throws Exception{
        SwipeRefreshLayout refresh=(SwipeRefreshLayout)activity.getWindow().getDecorView().findViewWithTag("library_refresh");
        EditText search=findSearch(activity.getWindow().getDecorView());assertNotNull(search);
        View button=findDescription(activity.getWindow().getDecorView(),"Actualizar biblioteca");assertNotNull(button);
        getInstrumentation().runOnMainSync(()->{search.setText("Lesson");button.performClick();assertTrue(refresh.isRefreshing());});
        long until=System.currentTimeMillis()+20000;while(refresh.isRefreshing()&&System.currentTimeMillis()<until)Thread.sleep(50);
        assertFalse("Indicator must finish after offline refresh",refresh.isRefreshing());assertEquals("Lesson",search.getText().toString());assertTrue(contains(activity.getWindow().getDecorView(),"Lesson"));
    }
    private void swipe(View target,float distance)throws Exception{
        int[] pos=new int[2];getInstrumentation().runOnMainSync(()->target.getLocationOnScreen(pos));
        float x=pos[0]+target.getWidth()*0.5f,start=pos[1]+8,end=start+(target.getHeight()-24)*distance;
        long down=SystemClock.uptimeMillis();
        send(down,down,MotionEvent.ACTION_DOWN,x,start);
        for(int n=1;n<=12;n++){Thread.sleep(12);send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x,start+(end-start)*n/12);}
        send(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,end);getInstrumentation().waitForIdleSync();
    }
    private void send(long down,long at,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,at,action,x,y,0);try{getInstrumentation().sendPointerSync(event);}finally{event.recycle();}}
    private EditText findSearch(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){EditText found=findSearch(g.getChildAt(n));if(found!=null)return found;}}return null;}
    private View findDescription(View v,String text){if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){View found=findDescription(g.getChildAt(n),text);if(found!=null)return found;}}return null;}
    private boolean contains(View v,String text){if(v instanceof android.widget.TextView&&((android.widget.TextView)v).getText().toString().contains(text))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++)if(contains(g.getChildAt(n),text))return true;}return false;}
}
