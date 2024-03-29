package rabbit.proxy;

import java.io.IOException;
import java.util.Date;
import java.util.logging.Logger;
import org.khelekore.rnio.impl.Closer;
import rabbit.http.HttpDateParser;
import rabbit.http.HttpHeader;
import rabbit.httpio.HttpHeaderListener;
import rabbit.httpio.HttpHeaderReader;
import rabbit.httpio.HttpHeaderSender;
import rabbit.httpio.HttpHeaderSentListener;
import rabbit.httpio.WebConnectionResourceSource;
import rabbit.httpio.request.ClientResourceHandler;
import rabbit.io.BufferHandle;
import rabbit.io.ConnectionHandler;
import rabbit.io.Resolver;
import rabbit.io.WebConnection;
import rabbit.io.WebConnectionListener;

/** A class that tries to establish a connection to the real server
 *  or the next proxy in the chain.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class SWC implements HttpHeaderSentListener,
                            HttpHeaderListener, WebConnectionListener {
    private final Connection con;
    private final Resolver resolver;
    private final HttpHeader header;
    private final TrafficLoggerHandler tlh;
    private final ClientResourceHandler crh;
    private final RequestHandler rh;

    private int attempts = 0;
    private final String method;
    private boolean safe = true;

    private char status = '0';

    private Exception lastException;
    private final Logger logger = Logger.getLogger (getClass ().getName ());
    
    private boolean wasReadingFromWeb = false;
    private boolean wasTimeout = true;

    public SWC (Connection con, Resolver resolver, HttpHeader header,
		TrafficLoggerHandler tlh, ClientResourceHandler crh,
		RequestHandler rh) {
	this.con = con;
	this.resolver = resolver;
	this.header = header;
	this.tlh = tlh;
	this.crh = crh;
	this.rh = rh;
	method = header.getMethod ().trim ();
    }

    public void establish () {
	attempts++;
	con.getCounter ().inc ("Trying to establish a WebConnection: " +
			       attempts);

	// if we cant get a connection in five cancel..
	if (!safe || attempts > 5) {
		if (wasTimeout) {
			if (wasReadingFromWeb)
		   		con.getProxy().getAdaptiveEngine().getEventsHandler().logResponseReadTimeout(con);
		   	else
		   		con.getProxy().getAdaptiveEngine().getEventsHandler().logRequestDeliveryTimeout(con);
		} else {
			if (wasReadingFromWeb)
				con.getProxy().getAdaptiveEngine().getEventsHandler().logResponseReadFailed(con);
			else
				con.getProxy().getAdaptiveEngine().getEventsHandler().logRequestDeliveryFailed(con);
		}
	    con.webConnectionSetupFailed (rh, lastException);
	} else {
		wasReadingFromWeb = false;
	    con.getProxy ().getWebConnection (header, this);
	}
    }

    public void connectionEstablished (WebConnection wc) {
	con.getCounter ().inc ("WebConnection established: " +
			       attempts);
	rh.setWebConnection (wc);
	/* TODO: handle this
 	if (header.getContentStream () != null)
	    header.setHeader ("Transfer-Encoding", "chunked");
	*/

	// we cant retry if we sent the header...
	safe = wc.getReleasedAt () > 0
	    || (method != null
		&& (method.equals ("GET") || method.equals ("HEAD")));

	try {
	    if (crh != null)
		crh.modifyRequest (header);

	    HttpHeaderSender hhs =
		new HttpHeaderSender (wc.getChannel (), con.getNioHandler (),
				      tlh.getNetwork (), header,
				      useFullURI (), this);
	    hhs.sendHeader ();
	} catch (IOException e) {
	    failed (e);
	}
    }

    public boolean useFullURI () {
	return resolver.isProxyConnected ();
    }

    public void httpHeaderSent () {
	if (crh != null)
	    crh.transfer (rh.getWebConnection (), new ResourceTransferListener());
	else
	    httpHeaderSentTransferDone ();
    }

    class ResourceTransferListener implements ClientResourceTransferredListener {

    	public void clientResourceTransferred () {
    		httpHeaderSentTransferDone ();
    	}

    	public void clientResourceAborted (HttpHeader reason) {
    		if (rh != null && rh.getWebConnection () != null) {
    			rh.getWebConnection ().setKeepalive (false);
    			con.getProxy ().releaseWebConnection (rh.getWebConnection ());
    		}
    		con.sendAndClose (reason);
    	}

		@Override
		public void sendingFailed(Exception cause) {
			SWC.this.failed(cause);
		}

		@Override
		public void sendingTimeout() {
			SWC.this.timeout();
		}

		public void timeout () {
	   		con.getProxy().getAdaptiveEngine().getEventsHandler().logRequestReadTimeout(con);
	   		closeDownWebConnection ();
	    }
	    
	    public void failed (Exception e) {
	    	con.getProxy().getAdaptiveEngine().getEventsHandler().logRequestReadFailed(con);
	    	closeDownWebConnection ();
	    }
    }
    
    private void httpHeaderSentTransferDone () {
	if (!header.isDot9Request ()) {
	    readResponse ();
	} else {
	    // HTTP/0.9 close after resource..
	    rh.getWebConnection ().setKeepalive (false);
	    setupResource (rh.getWebHandle (), false, -1);
	    con.webConnectionEstablished (rh);
	}
    }
    
    // Redeemer: Changed from "readRequest" to "readResponse"
    private void readResponse () {
    wasReadingFromWeb = true;
	con.getCounter ().inc ("Trying read response from WebConnection: " + 
			       attempts);
	try {
	    HttpHeaderReader hhr =
		new HttpHeaderReader (rh.getWebConnection ().getChannel (),
				      rh.getWebHandle (), con.getNioHandler (),
				      tlh.getNetwork (), false,
				      con.getProxy ().getStrictHttp (), this);
	    hhr.readHeader ();
	} catch (IOException e) {
	    failed (e);
	}
    }

    public void httpHeaderRead (HttpHeader header, BufferHandle wbh,
				boolean keepalive, boolean isChunked,
				long dataSize) {
	con.getCounter ().inc ("Read response from WebConnection: " +
			       attempts);
	rh.setWebHeader (header);
	rh.setWebHandle (wbh);
	rh.getWebConnection ().setKeepalive (keepalive);

	String sc = rh.getWebHeader ().getStatusCode ();
	//if client is using http/1.1
	if (sc.length () > 0 && (status = sc.charAt (0)) == '1' &&
	    con.getRequestVersion ().endsWith ("1.1")) {
	    // tell client
	    Looper l = new Looper ();
	    con.getCounter ().inc ("WebConnection got 1xx reply " +
				   attempts);
	    try {
		HttpHeaderSender hhs =
		    new HttpHeaderSender (con.getChannel (),
					  con.getNioHandler (),
					  tlh.getClient (), header, false, l);
		hhs.sendHeader ();
		return;
	    } catch (IOException e) {
		failed (e);
	    }
	}

	// since we have posted the full request we
	// loop while we get 100 (continue) response.
	if (status == '1') {
	    readResponse ();
	} else {
	    String responseVersion = rh.getWebHeader ().getResponseHTTPVersion ();
	    setAge (rh);
	    WarningsHandler wh = new WarningsHandler ();
	    wh.removeWarnings (rh.getWebHeader (), false);
	    HttpHeader webHeader = rh.getWebHeader ();
	    String proxyChain = header.getHeader("Via");
		webHeader.setHeader ("Via", (proxyChain != null)? proxyChain+", " : "" + con.getProxy().getProxyIdentity());
	    //original code: rh.getWebHeader ().addHeader ("Via", responseVersion + " RabbIT");
	    HttpProxy proxy = con.getProxy ();
	    rh.setSize (dataSize);
	    setupResource (wbh, isChunked, dataSize);
	    con.webConnectionEstablished (rh);
	}
    }

    private void setupResource (BufferHandle wbh, boolean isChunked, long dataSize) {
	HttpProxy proxy = con.getProxy ();
	ConnectionHandler ch = con.getProxy ().getConnectionHandler ();
	WebConnectionResourceSource rs =
	    new WebConnectionResourceSource (ch, con.getNioHandler (),
					     rh.getWebConnection (),
					     wbh, tlh.getNetwork (),
					     isChunked, dataSize,
					     proxy.getStrictHttp ());
	rh.setContent (rs);
    }

    public void closed () {
	if (rh.getWebConnection () != null) {
	    closeDownWebConnection ();
	    lastException = new IOException ("closed");
	    establish ();
	}
    }

    /** Calculate the age of the resource, needs ntp to be accurate.
     */
    private void setAge (RequestHandler rh) {
	long now = System.currentTimeMillis ();
	String age = rh.getWebHeader ().getHeader ("Age");
	String date = rh.getWebHeader ().getHeader ("Date");
	Date dd = HttpDateParser.getDate (date);
	long ddt = now;
	if (dd != null)
	    ddt = dd.getTime ();
	long lage = 0;
	try {
	    if (age != null)
		lage = Long.parseLong (age);
	    long dt = Math.max ((now - ddt) / 1000, 0);
	    // correct_age is found in spec, but is not actually used
	    //long correct_age = lage + dt;
	    long correct_recieved_age = Math.max (dt, lage);
	    long corrected_initial_age = correct_recieved_age + dt;
	    if (corrected_initial_age > 0) {
		rh.getWebHeader ().setHeader ("Age",
					      "" + corrected_initial_age);
	    }
	} catch (NumberFormatException e) {
	    // if we cant parse it, we leave the Age header..
	    logger.warning ("Bad age: " + age);
	}
    }

    private class Looper implements HttpHeaderSentListener {

	public void httpHeaderSent () {
	    // read the next request...
	    readResponse ();
	}

	public void timeout () {
	    SWC.this.timeout ();
	}

	public void failed (Exception e) {
	    SWC.this.failed (e);
	}
    }

    private void closeDownWebConnection () {
	WebConnection wc = rh.getWebConnection ();
	rh.setWebConnection (null);
	if (wc != null) {
	    con.getNioHandler ().close (wc.getChannel ());
	    Closer.close (wc, logger);
	}
    }

    public void timeout () {
   	wasTimeout = true;
   	// retry
	lastException = new IOException ("timeout");
	closeDownWebConnection ();
	establish ();
    }

    public void failed (Exception e) {
    wasTimeout = false;
	lastException = e;
	con.getCounter ().inc ("WebConnections failed: " +
			       attempts + ": " + e);
	closeDownWebConnection ();
	// retry
	establish ();
    }
}
