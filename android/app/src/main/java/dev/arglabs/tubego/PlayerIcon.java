package dev.arglabs.tubego;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Original vector-like drawings for the standard video controls; no VLC artwork copied. */
final class PlayerIcon extends Drawable {
    private final String kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    PlayerIcon(String kind){this.kind=kind;}
    private void line(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,paint);}
    private void triangle(Canvas c,float a,float b,float d,float e,float f,float g){Path p=new Path();p.moveTo(a,b);p.lineTo(d,e);p.lineTo(f,g);p.close();c.drawPath(p,paint);}
    @Override public void draw(Canvas canvas){
        canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        paint.setColor(Color.WHITE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        switch(kind){
            case "play":case "pause":
                paint.setStrokeWidth(1.35f);canvas.drawCircle(12,12,10,paint);paint.setStyle(Paint.Style.FILL);
                if(kind.equals("play"))triangle(canvas,10,7,17,12,10,17);
                else {canvas.drawRect(8,7,10.5f,17,paint);canvas.drawRect(13.5f,7,16,17,paint);}break;
            case "back":line(canvas,14,5,7,12,14,19);line(canvas,7,12,21,12);break;
            case "more":paint.setStyle(Paint.Style.FILL);for(int x:new int[]{5,12,19})canvas.drawCircle(x,12,1.7f,paint);break;
            case "tracks":
                paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(1,3,23,21,2,2,paint);paint.setColor(Color.BLACK);
                canvas.drawRoundRect(4,13,9,15,1,1,paint);canvas.drawRoundRect(11,13,20,15,1,1,paint);canvas.drawRoundRect(4,17,14,19,1,1,paint);canvas.drawRoundRect(16,17,20,19,1,1,paint);break;
            case "rotate":
                canvas.save();canvas.rotate(-45,12,12);canvas.drawRoundRect(7,4,17,20,1,1,paint);canvas.restore();
                line(canvas,2,8,2,4,6,4);line(canvas,22,16,22,20,18,20);break;
            case "aspect":
                canvas.drawRoundRect(2,5,22,19,1,1,paint);line(canvas,5,11,5,8,9,8);line(canvas,19,13,19,16,15,16);break;
            case "lock":case "unlock":
                canvas.drawRoundRect(5,10,19,22,2,2,paint);canvas.drawArc(new RectF(8,2,16,15),180,kind.equals("lock")?180:145,false,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,15,1.3f,paint);canvas.drawRect(11.4f,15,12.6f,18,paint);break;
            case "speed":
                canvas.drawArc(new RectF(2,3,22,23),170,200,false,paint);line(canvas,2,15,2,20,22,20,22,15);paint.setStyle(Paint.Style.FILL);triangle(canvas,10,14,18,6,14,16);canvas.drawCircle(12,14,2,paint);break;
            case "timer":
                canvas.drawCircle(12,14,8,paint);line(canvas,12,6,12,3,9,3,15,3);line(canvas,12,9,12,14,16,14);line(canvas,2,5,5,2);line(canvas,19,2,22,5);break;
            case "equalizer":
                line(canvas,5,2,5,22);line(canvas,12,2,12,22);line(canvas,19,2,19,22);paint.setStrokeWidth(3.5f);line(canvas,2,16,8,16);line(canvas,9,8,15,8);line(canvas,16,13,22,13);break;
            case "repeat":
                line(canvas,4,8,4,5,20,5,20,9);line(canvas,16,5,20,9,23,5);line(canvas,20,16,20,19,4,19,4,15);line(canvas,1,19,4,15,8,19);break;
            case "info":
                canvas.drawCircle(12,12,9,paint);line(canvas,12,11,12,17);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,7,1,paint);break;
            case "check":line(canvas,4,12,9,17,21,5);break;
            case "cast":
                line(canvas,2,7,2,4,22,4,22,20,16,20);canvas.drawArc(new RectF(-7,12,13,32),270,90,false,paint);canvas.drawArc(new RectF(-3,16,9,28),270,90,false,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(3,22,1.3f,paint);break;
            case "brightness":
                canvas.drawCircle(12,12,4,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;line(canvas,(float)(12+7*Math.cos(a)),(float)(12+7*Math.sin(a)),(float)(12+10*Math.cos(a)),(float)(12+10*Math.sin(a)));}break;
            case "volume":
                line(canvas,3,9,7,9,12,4,12,20,7,15,3,15,3,9);canvas.drawArc(new RectF(8,6,20,18),-60,120,false,paint);canvas.drawArc(new RectF(6,2,26,22),-55,110,false,paint);break;
            case "forward":case "rewind":
                if(kind.equals("rewind")){canvas.translate(24,0);canvas.scale(-1,1);}paint.setStyle(Paint.Style.FILL);triangle(canvas,3,5,12,12,3,19);triangle(canvas,12,5,21,12,12,19);break;
            case "seek_back":case "seek_forward":
                if(kind.equals("seek_back")){canvas.translate(24,0);canvas.scale(-1,1);}canvas.drawArc(new RectF(3,4,21,22),-65,300,false,paint);line(canvas,16,1,16,6,21,6);
                if(kind.equals("seek_back")){canvas.translate(24,0);canvas.scale(-1,1);}paint.setStyle(Paint.Style.FILL);paint.setTextSize(8);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("10",12,16,paint);break;
            default:canvas.drawCircle(12,12,8,paint);line(canvas,12,7,12,12,16,15);
        }
        canvas.restore();
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
