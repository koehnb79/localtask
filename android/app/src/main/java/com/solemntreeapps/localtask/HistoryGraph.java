package com.solemntreeapps.localtask;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.*;

/** Three honest time axes: absent samples and long sampling gaps break the line. */
public final class HistoryGraph extends View {
    private JSONArray samples=new JSONArray();
    private final int kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public HistoryGraph(Context c,int kind){super(c);this.kind=kind;setMinimumHeight(dp(110));}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    public void update(JSONArray data){samples=data;invalidate();}
    private double value(JSONObject s,boolean second){
        if(kind==0)return s.optDouble("cpu",Double.NaN);
        if(kind==1){double total=s.optDouble("memory_total",0);return total>0?s.optDouble("memory_used")/total*100:Double.NaN;}
        return s.optDouble(second?"upload":"download",Double.NaN)/1024;
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float left=dp(8),right=getWidth()-dp(8),top=dp(28),bottom=getHeight()-dp(22);
        double max=100,start=0,end=1;
        if(samples.length()>0){start=samples.optJSONObject(0).optDouble("time");end=samples.optJSONObject(samples.length()-1).optDouble("time");}
        if(kind==2){max=1;for(int i=0;i<samples.length();i++)for(boolean second:new boolean[]{false,true}){double v=value(samples.optJSONObject(i),second);if(Double.isFinite(v))max=Math.max(max,v);}}
        paint.setTextSize(dp(12));paint.setColor(Color.DKGRAY);
        c.drawText(kind==0?"CPU · 0–100%":kind==1?"Memory · 0–100%":String.format(java.util.Locale.ROOT,"Network · ↓ blue ↑ purple · 0–%.0f KiB/s",max),left,dp(18),paint);
        paint.setStrokeWidth(dp(1));paint.setColor(Color.LTGRAY);c.drawLine(left,bottom,right,bottom,paint);
        for(int series=0;series<(kind==2?2:1);series++){
            paint.setColor(series==0?Color.rgb(25,100,170):Color.rgb(135,60,160));paint.setStrokeWidth(dp(2));
            boolean prior=false;float px=0,py=0;double pt=0;
            for(int i=0;i<samples.length();i++){
                JSONObject s=samples.optJSONObject(i);double t=s.optDouble("time"),v=value(s,series==1);
                if(!Double.isFinite(v)){prior=false;continue;}
                float x=left+(float)((t-start)/Math.max(1,end-start))*(right-left),y=bottom-(float)(Math.min(max,Math.max(0,v))/max)*(bottom-top);
                if(prior && t>pt && t-pt<=15)c.drawLine(px,py,x,y,paint);else c.drawCircle(x,y,dp(2),paint);
                prior=true;px=x;py=y;pt=t;
            }
        }
        paint.setColor(Color.DKGRAY);paint.setTextSize(dp(11));
        c.drawText(samples.length()<2?"Collecting samples…":String.format(java.util.Locale.ROOT,"Last %.1f minutes · latest sample at right",Math.max(0,end-start)/60),left,getHeight()-dp(5),paint);
    }
}
