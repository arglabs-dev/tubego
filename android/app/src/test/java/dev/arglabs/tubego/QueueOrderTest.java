package dev.arglabs.tubego;
import org.junit.Test;import static org.junit.Assert.*;import java.io.File;import java.util.*;
public final class QueueOrderTest {
 private TransferRecord record(int n,String created){return new TransferRecord(new File("unused"),new UUID(0,n).toString(),"0".repeat(64),1,"Title",created);}
 @Test public void defaultsFifoAndNewPriorityOnlyChangesNextWaitingItem(){TransferRecord a=record(1,"a"),b=record(2,"b"),c=record(3,"c");List<TransferRecord> waiting=new ArrayList<>(Arrays.asList(c,b,a));Map<String,Long> priorities=new HashMap<>();TransferRecord active=QueueOrder.next(waiting,priorities);assertSame(a,active);priorities.put(c.id,3L);assertSame(c,QueueOrder.next(waiting,priorities));assertSame(a,active);assertSame(b,QueueOrder.next(waiting,priorities));}
 @Test public void latestSelectionWinsAndTieRemainsStable(){TransferRecord a=record(1,"a"),b=record(2,"b"),c=record(3,"c");List<TransferRecord> waiting=new ArrayList<>(Arrays.asList(c,b,a));Map<String,Long> priorities=new HashMap<>();priorities.put(a.id,1L);priorities.put(b.id,2L);assertSame(b,QueueOrder.next(waiting,priorities));assertSame(a,QueueOrder.next(waiting,priorities));assertSame(c,QueueOrder.next(waiting,priorities));}
}
