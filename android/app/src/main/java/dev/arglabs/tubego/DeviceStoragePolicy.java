package dev.arglabs.tubego;

/** Pure byte arithmetic; remaining bytes must fit without crossing the reserve. */
public final class DeviceStoragePolicy {
    private DeviceStoragePolicy(){}
    public static final int DEFAULT_PERCENT=10;
    public static int validPercent(int value){if(value<1||value>90)throw new IllegalArgumentException("El umbral debe estar entre 1 y 90 %.");return value;}
    public static final class Decision {
        public final long total,available,reserve,remaining;public final boolean paused;public final String reason;
        Decision(long total,long available,long reserve,long remaining,boolean paused,String reason){this.total=total;this.available=available;this.reserve=reserve;this.remaining=remaining;this.paused=paused;this.reason=reason;}
    }
    public static Decision evaluate(long total,long available,long remaining,int percent){
        validPercent(percent);remaining=Math.max(0,remaining);
        if(total<=0||available<0||available>total)return new Decision(total,available,0,remaining,true,"storage_unavailable");
        long reserve=(total/100)*percent+((total%100)*percent+99)/100;
        if(available<reserve)return new Decision(total,available,reserve,remaining,true,"below_threshold");
        if(remaining>available-reserve)return new Decision(total,available,reserve,remaining,true,"insufficient_space");
        return new Decision(total,available,reserve,remaining,false,"");
    }
}
