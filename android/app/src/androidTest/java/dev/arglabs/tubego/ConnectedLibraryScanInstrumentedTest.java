package dev.arglabs.tubego;

import android.test.InstrumentationTestCase;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

/** Read-only regression check against the installed library, including any queued actions. */
@SuppressWarnings("deprecation")
public final class ConnectedLibraryScanInstrumentedTest extends InstrumentationTestCase {
 public void testScanInstalledLibraryWithoutReadingOutboxes()throws Exception{
  List<TransferRecord> records=new ArrayList<>();Method scan=SharedMedia.class.getDeclaredMethod("collect",File.class,List.class);scan.setAccessible(true);
  scan.invoke(null,new File(getInstrumentation().getTargetContext().getFilesDir(),"tubego-media"),records);
  for(TransferRecord record:records)assertFalse(record.root.getAbsolutePath().contains("outbox"));
 }
}
