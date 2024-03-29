package rabbit.proxy;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import rabbit.filter.IPAccessFilter;
import rabbit.util.Config;

/** An access controller based on socket channels. 
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class SocketAccessController {
    /** the filters, a List of classes (in given order) */
    private List<IPAccessFilter> accessfilters = 
	new ArrayList<IPAccessFilter> ();
    private final Logger logger = Logger.getLogger (getClass ().getName ());

    public SocketAccessController (String filters, Config config) {
	accessfilters = new ArrayList<IPAccessFilter> ();
	loadAccessFilters (filters, accessfilters, config);
    }

    private void loadAccessFilters (String filters, 
				    List<IPAccessFilter> accessfilters, 
				    Config config) {
	StringTokenizer st = new StringTokenizer (filters, ",");
	String classname = "";
	while (st.hasMoreElements ()) {
	    try {
		classname = st.nextToken ().trim ();
		Class<? extends IPAccessFilter> cls = 
		    Class.forName (classname).asSubclass (IPAccessFilter.class);
		IPAccessFilter ipf = cls.newInstance ();
		ipf.setup (config.getProperties (classname));
		accessfilters.add (ipf);
	    } catch (ClassNotFoundException ex) {
		logger.log (Level.WARNING, 
			    "Could not load class: '" + classname + "'", ex);
	    } catch (InstantiationException ex) {
		logger.log (Level.WARNING, 
			    "Could not instansiate: '" + classname + "'", ex);
	    } catch (IllegalAccessException ex) {
		logger.log (Level.WARNING,
			    "Could not instansiate: '" + classname + "'", ex);
	    }
	}
    }
    
    public List<IPAccessFilter> getAccessFilters () {
	return Collections.unmodifiableList (accessfilters);
    }

    public boolean checkAccess (SocketChannel sc) {
	for (IPAccessFilter filter : getAccessFilters ()) {
	    if (filter.doIPFiltering (sc))
		return true;
	}
	return false;
    }
}
