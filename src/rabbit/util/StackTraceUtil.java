package rabbit.util;

import java.io.StringWriter;
import java.io.PrintWriter;

/** Utility functions when dealing with stack traces.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class StackTraceUtil {
    public static String getStackTrace (Throwable t) {
	StringWriter sw = new StringWriter ();
	PrintWriter sos = new PrintWriter (sw);
	t.printStackTrace (sos);
	return sw.toString ();
    }
}
