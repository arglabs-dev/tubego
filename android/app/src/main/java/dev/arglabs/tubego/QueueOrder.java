package dev.arglabs.tubego;
import java.util.*;
/** Only selects from waiting items: the active transfer never enters this list. */
public final class QueueOrder {
 public static TransferRecord next(List<TransferRecord> waiting,Map<String,Long> priorities) {
  waiting.sort(Comparator.comparingLong((TransferRecord r)->priorities.getOrDefault(r.id,0L)).reversed().thenComparing(r->r.createdAt).thenComparing(r->r.id));
  return waiting.remove(0);
 }
}
