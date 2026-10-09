package dev.arglabs.tubego.spike;

public final class ResumePositionTest {
    public static void main(String[] args) {
        check(ResumePosition.startMillis(932000L, 10) == 922000L);
        check(ResumePosition.startMillis(932000L, 20) == 912000L);
        check(ResumePosition.startMillis(5000L, 10) == 0L);
        check(ResumePosition.startMillis(-1L, 10) == 0L);
        check(ResumePosition.startMillis(Long.MAX_VALUE, Integer.MAX_VALUE) > 0L);
        check(ResumePosition.validResult(0L, 1000L));
        check(ResumePosition.validResult(1000L, 1000L));
        check(!ResumePosition.validResult(null, 1000L));
        check(!ResumePosition.validResult(-1L, 1000L));
        check(!ResumePosition.validResult(1100L, 1000L));
        check(!ResumePosition.validResult(0L, null));
        check(!ResumePosition.validResult(0L, 0L));
        try {
            ResumePosition.startMillis(1000L, -1);
            throw new AssertionError("Negative rewind accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("ResumePosition: 13 checks passed");
    }
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}
