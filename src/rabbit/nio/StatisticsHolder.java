package rabbit.nio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rabbit.nio.statistics.CompletionEntry;
import rabbit.nio.statistics.TotalTimeSpent;

/** A class that executes one task and gathers information about
 *  the time spent and the success status of the task. 
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class StatisticsHolder {
    private Map<String, List<TaskIdentifier>> pendingTasks = 
	new HashMap<String, List<TaskIdentifier>> ();
    private Map<String, List<TaskIdentifier>> runningTasks =
	new HashMap<String, List<TaskIdentifier>> ();

    private int maxLatest = 10;
    private Map<String, List<CompletionEntry>> latest = 
	new HashMap<String, List<CompletionEntry>> ();
    
    private int maxLongest = 10;
    private Map<String, List<CompletionEntry>> longest = 
	new HashMap<String, List<CompletionEntry>> ();

    private Map<String, TotalTimeSpent> total =
	new HashMap<String, TotalTimeSpent> ();

    private <T> List<T> getList (String id, 
				 Map<String, List<T>> tasks) {
	List<T> ls = tasks.get (id);
	if (ls == null) {
	    ls = new ArrayList<T> ();
	    tasks.put (id, ls);
	}
	return ls;
    }

    private void addTask (TaskIdentifier ti, 
			  Map<String, List<TaskIdentifier>> tasks) {
	getList (ti.getGroupId (), tasks).add (ti);
    }

    private void removeTask (TaskIdentifier ti, 
			     Map<String, List<TaskIdentifier>> tasks) {
	List<TaskIdentifier> ls = tasks.get (ti.getGroupId ());
	if (ls == null)
	    throw new NullPointerException ("No pending taks for group: " + 
					    ti.getGroupId ());
	if (!ls.remove (ti))
	    throw new IllegalArgumentException ("Given task was not pending: " +
						ti);
    }

    public synchronized void addPendingTask (TaskIdentifier ti) {
	addTask (ti, pendingTasks);
    }

    public synchronized void changeTaskStatusToRunning (TaskIdentifier ti) {
	removeTask (ti, pendingTasks);
	addTask (ti, runningTasks);
    }

    public synchronized void changeTaskStatusToFinished (TaskIdentifier ti, 
							 boolean wasOk, 
							 long timeSpent) {
	removeTask (ti, runningTasks);
	CompletionEntry ce = new CompletionEntry (ti, wasOk, timeSpent);
	addToLatest (ce);
	addToLongest (ce);
	addToTotal (ce);
    }

    private void addToLatest (CompletionEntry ce) {
	List<CompletionEntry> ls = getList (ce.ti.getGroupId (), latest);
	ls.add (ce);
	if (ls.size () > maxLatest)
	    ls.remove (0);
    }

    private void addToLongest (CompletionEntry ce) {
	List<CompletionEntry> ls = getList (ce.ti.getGroupId (), longest);
	if (ls.isEmpty ()) {
	    ls.add (ce);
	} else if (addSorted (ce, ls)) {
	    if (ls.size () > maxLongest)
		ls.remove (ls.size () - 1);
	}
    }

    private boolean addSorted (CompletionEntry ce,
			       List<CompletionEntry> ls) {
	int s = ls.size ();
	for (int i = 0; i < s; i++) {
	    if (ce.timeSpent > ls.get (i).timeSpent) {
		ls.add (i, ce);
		return true;
	    }
	}
	if (s < maxLongest) {
	    ls.add (ce);
	    return true;
	}
	return false;
    }
    
    private void addToTotal (CompletionEntry ce) {
	TotalTimeSpent tts = total.get (ce.ti.getGroupId ());
	if (tts == null) {
	    tts = new TotalTimeSpent ();
	    total.put (ce.ti.getGroupId (), tts);
	}
	tts.update (ce);
    }

    private <K, V> Map<K, List<V>> copy (Map<K, List<V>> m) {
	Map<K, List<V>> ret = new HashMap<K, List<V>> ();
	for (Map.Entry<K, List<V>> me : m.entrySet ())
	    ret.put (me.getKey (), new ArrayList<V> (me.getValue ()));
	return ret;
    }

    public synchronized Map<String, List<TaskIdentifier>> getPendingTasks () {
	return copy (pendingTasks);
    }

    public synchronized Map<String, List<TaskIdentifier>> getRunningTasks () {
	return copy (runningTasks);
    }

    public synchronized Map<String, List<CompletionEntry>> getLatest () {
	return copy (latest);
    }

    public synchronized Map<String, List<CompletionEntry>> getLongest () {
	return copy (longest);
    }

    public synchronized Map<String, TotalTimeSpent> getTotalTimeSpent () {
	return Collections.unmodifiableMap (total);
    }
}
