package com.example.thinkv2.calendar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Standalone test APK UID; no Kotlin/app runtime needed. Protected by shell DUMP permission. */
public final class CalendarFixtureProvider extends ContentProvider {
    private static final String ACCOUNT="thinkV2-calendar-synthetic";
    private Uri sync(Uri uri) { return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true")
        .appendQueryParameter("account_name",ACCOUNT).appendQueryParameter("account_type",CalendarContract.ACCOUNT_TYPE_LOCAL).build(); }
    @Override public boolean onCreate() { return true; }
    private JSONArray rows(Uri uri,String where,String[] args) throws Exception {
        Cursor cursor=getContext().getContentResolver().query(uri,null,where,args,"_id");
        if(cursor==null) throw new IllegalStateException("fixture_provider_null");
        try(Cursor c=cursor) {
            JSONArray result=new JSONArray();
            while(c.moveToNext()) { JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++) row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));result.put(row); }
            return result;
        }
    }
    private JSONArray calendars() throws Exception { return rows(CalendarContract.Calendars.CONTENT_URI,"account_name=? AND account_type=?",new String[]{ACCOUNT,CalendarContract.ACCOUNT_TYPE_LOCAL}); }
    private JSONObject snapshot() throws Exception {
        JSONArray cs=calendars(),all=new JSONArray();
        for(int i=0;i<cs.length();i++) {
            JSONObject cal=cs.getJSONObject(i);JSONArray events=rows(CalendarContract.Events.CONTENT_URI,"calendar_id=?",new String[]{cal.getString("_id")}),reminders=new JSONArray();
            for(int j=0;j<events.length();j++) { JSONArray rs=rows(CalendarContract.Reminders.CONTENT_URI,"event_id=?",new String[]{events.getJSONObject(j).getString("_id")});for(int k=0;k<rs.length();k++) reminders.put(rs.get(k)); }
            all.put(new JSONObject().put("calendar",cal).put("events",events).put("reminders",reminders));
        }
        return new JSONObject().put("sources",all);
    }
    private long event(long calendar,JSONObject ids,String label,String title,String description,long start,Long end,Consumer<ContentValues> extra) throws Exception {
        ContentValues v=new ContentValues();v.put("calendar_id",calendar);v.put("title",title);v.put("description",description);v.put("dtstart",start);if(end!=null) v.put("dtend",end);
        v.put("eventTimezone","Asia/Shanghai");v.put("eventEndTimezone","Asia/Shanghai");v.put("eventLocation","合成地点");v.put("eventStatus",CalendarContract.Events.STATUS_CONFIRMED);
        if(extra!=null) extra.accept(v);
        long id=Long.parseLong(getContext().getContentResolver().insert(sync(CalendarContract.Events.CONTENT_URI),v).getLastPathSegment());ids.put(label,id);return id;
    }
    private JSONObject seed() throws Exception {
        JSONArray old=calendars();for(int i=0;i<old.length();i++) getContext().getContentResolver().delete(sync(CalendarContract.Calendars.CONTENT_URI),"_id=?",new String[]{old.getJSONObject(i).getString("_id")});
        ContentValues c=new ContentValues();c.put("account_name",ACCOUNT);c.put("account_type",CalendarContract.ACCOUNT_TYPE_LOCAL);c.put("name","ThinkV2Synthetic");c.put("calendar_displayName","合成测试日历");c.put("ownerAccount",ACCOUNT);
        c.put("calendar_access_level",CalendarContract.Calendars.CAL_ACCESS_OWNER);c.put("visible",1);c.put("sync_events",1);c.put("calendar_timezone","Asia/Shanghai");c.put("calendar_color",0xff446688);c.put("maxReminders",5);c.put("allowedReminders","0,1");
        long cal=Long.parseLong(getContext().getContentResolver().insert(sync(CalendarContract.Calendars.CONTENT_URI),c).getLastPathSegment());JSONObject ids=new JSONObject();long day=1788220800000L;
        long chinese=event(cal,ids,"chinese","合成中文事件","原始中文描述\n第二行不能丢失",day,day+3600000,null);
        ContentValues r=new ContentValues();r.put("event_id",chinese);r.put("minutes",15);r.put("method",CalendarContract.Reminders.METHOD_ALERT);getContext().getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI,r);
        event(cal,ids,"titleOnly","只有标题也能导入","",day,day+3600000,null);
        event(cal,ids,"allDay","合成全天事件","全天日期",day+86400000,day+2*86400000,v->{v.put("allDay",1);v.put("eventTimezone","UTC");v.put("eventEndTimezone","UTC");});
        event(cal,ids,"zones","合成跨时区行程","起止时区分别保留",day,day+3600000,v->v.put("eventEndTimezone","America/Los_Angeles"));
        long master=event(cal,ids,"master","范围前开始的每日系列","按主事件只导入一条",day-31*86400000L,null,v->{v.put("rrule","FREQ=DAILY;COUNT=60");v.put("duration","PT3600S");});
        event(cal,ids,"moved","合成移动例外","从9月3日移到9月5日",day+4*86400000,day+4*86400000+3600000,v->{v.put("original_id",master);v.put("originalInstanceTime",day+2*86400000);v.put("originalAllDay",0);});
        event(cal,ids,"cancelled","合成取消例外","取消状态保留",day+3*86400000,day+3*86400000+3600000,v->{v.put("original_id",master);v.put("originalInstanceTime",day+3*86400000);v.put("originalAllDay",0);v.put("eventStatus",CalendarContract.Events.STATUS_CANCELED);});
        event(cal,ids,"similar1","相似标题","相同日期不同身份",day,day+3600000,null);event(cal,ids,"similar2","相似标题","相同日期不同身份",day,day+3600000,null);event(cal,ids,"empty","","",day,day+3600000,null);
        Uri instances=CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(Long.toString(day)).appendPath(Long.toString(day+7*86400000)).build();
        try(Cursor ignored=getContext().getContentResolver().query(instances,new String[]{"event_id"},"calendar_id=?",new String[]{Long.toString(cal)},null)) { }
        getContext().getSharedPreferences("fixture",0).edit().putString("ids",ids.toString()).commit();
        return new JSONObject().put("fixtureUid",android.os.Process.myUid()).put("calendarId",cal).put("ids",ids).put("snapshot",snapshot());
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        try {
            String callId=extras==null?null:extras.getString("call_id");
            if(callId==null || !callId.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("missing_fixture_call_id");
            JSONObject result;
            if(method.equals("seed")) result=seed();
            else if(method.equals("snapshot")) result=snapshot();
            else if(method.equals("edit") || method.equals("delete") || method.equals("large") || method.equals("within")) {
                JSONObject ids=new JSONObject(getContext().getSharedPreferences("fixture",0).getString("ids","{}"));
                long id=ids.getLong(arg==null?(!method.equals("delete")?"chinese":"titleOnly"):arg);
                if(!method.equals("delete")) { ContentValues v=new ContentValues();
                    v.put("description",method.equals("large")?"大".repeat(300000):method.equals("within")?"界".repeat(30000):"合成来源已修改");
                    if(getContext().getContentResolver().update(sync(CalendarContract.Events.CONTENT_URI),v,"_id=?",new String[]{Long.toString(id)})!=1) throw new IllegalStateException("fixture_update_count"); }
                else getContext().getContentResolver().delete(sync(CalendarContract.Events.CONTENT_URI),"_id=?",new String[]{Long.toString(id)});
                result=snapshot();
            } else throw new IllegalArgumentException("unknown_fixture_method");
            try(FileOutputStream out=new FileOutputStream(getContext().getFileStreamPath("calendar-fixture-result.json"))) { out.write(new JSONObject().put("callId",callId).put("method",method).put("result",result).toString(2).getBytes(StandardCharsets.UTF_8)); }
            Bundle bundle=new Bundle();bundle.putString("result","synthetic result saved "+callId);return bundle;
        } catch(Exception e) { throw new IllegalStateException("synthetic_fixture_failed",e); }
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
